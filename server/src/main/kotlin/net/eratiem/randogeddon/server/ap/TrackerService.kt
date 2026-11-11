package net.eratiem.randogeddon.server.ap

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.server.state.SessionState
import net.eratiem.randogeddon.server.state.array
import net.eratiem.randogeddon.server.state.int
import net.eratiem.randogeddon.server.state.obj
import net.eratiem.randogeddon.server.state.string
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.ApiJson
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import kotlin.time.Duration.Companion.seconds

private val logger = logger(MethodHandles.lookup().lookupClass())

/** Receive all items: from other worlds, from the own world and the starting inventory. */
private const val ITEMS_ALL = 0b111
private const val MAX_BACKOFF_SECONDS = 5L

class ConnectionRefused(val errors: List<String>) : Exception(errors.joinToString())

/**
 * Follows the room through the Archipelago network protocol and feeds [SessionState].
 *
 * One *primary* connection (tag `Tracker`) gets the room-wide information: RoomInfo, the data package, every PrintJSON
 * event and the data storage keys of the hints and client status of all slots. It then starts one *secondary*
 * connection per other slot (tags `Tracker`, `NoText`), only to learn that slot's checked locations and received items,
 * which the server sends to the slot's own connections only.
 *
 * All access to [SessionState] goes through [read] and the private `update`, so the connections and the web layer
 * never see it half-updated.
 */
@Single
class TrackerService(private val config: TrackerConfig) {
  private val state = SessionState()
  private val mutex = Mutex()

  /** Why the tracker is not connected, for the dashboard; `null` while connected. */
  @Volatile
  var lastError: String? = null
    private set

  private val http = HttpClient(CIO) {
    install(WebSockets) {
      maxFrameSize = Long.MAX_VALUE // the data package can be several MB
      pingIntervalMillis = 30_000
      extensions { install(WebSocketDeflateExtension) }
    }
  }

  suspend fun <T> read(block: SessionState.() -> T): T = mutex.withLock { state.block() }

  private suspend fun update(block: SessionState.() -> Unit) = mutex.withLock { state.block() }

  /** Connects and stays connected until [scope] ends. */
  fun start(scope: CoroutineScope): Job = scope.launch {
    update { load(config.statePath) }
    launch { autosave() }
    var backoff = 1L
    while (isActive) {
      val name = slotNames().firstOrNull()
      if (name == null) {
        error("no slots known yet (waiting for ${config.roomJson})")
        delay(10.seconds)
        continue
      }
      try {
        primary(name)
        backoff = 1
      } catch (e: CancellationException) {
        throw e
      } catch (e: ConnectionRefused) {
        error("server refused the connection: ${e.message}")
      } catch (e: Exception) {
        error("connection problem: ${e.message ?: e::class.simpleName}")
      }
      update { onConnectionLost() }
      delay(backoff.seconds)
      backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
    }
  }

  fun close() {
    http.close()
    save()
  }

  private fun error(message: String) {
    if (message != lastError) logger.warn(message)
    lastError = message
  }

  private fun slotNames(): List<String> {
    config.bootstrapSlot?.let { return listOf(it) }
    return Room.read(config.roomJson)?.players.orEmpty().map { it.name }
  }

  private suspend fun primary(slotName: String) = http.webSocket(config.serverUrl) {
    val conn = ApConnection(this)
    val (roomInfo, connected) = conn.handshake(slotName, listOf("Tracker"))
    val games = roomInfo.array("games").orEmpty().map { it.jsonPrimitive.content }.toSet() + "Archipelago"
    conn.send(buildJsonObject {
      put("cmd", "GetDataPackage")
      putJsonArray("games") { games.sorted().forEach { add(it) } }
    })
    val mySlot = connected.int("slot")!!
    val team = connected.int("team")!!
    val others = mutex.withLock {
      if (state.onRoomInfo(roomInfo)) logger.info("Seed {}", state.seedName)
      state.onConnected(mySlot, connected)
      state.applySaved()
      state.connected = true
      state.players.filterKeys { it != mySlot }.mapValues { it.value.name }
    }
    lastError = null
    logger.info("Connected as tracker for {} ({} players)", slotName, others.size + 1)

    val slots = others.keys + mySlot
    val keys = slots.map { "_read_hints_${team}_$it" } + slots.map { "_read_client_status_${team}_$it" }
    val keyArray = buildJsonArray { keys.forEach { add(it) } }
    // The World Locks slot data maps its keys to the locked worlds; it doesn't change, so no notification
    val lockData = buildJsonArray { read { lockSlots() }.forEach { add("_read_slot_data_$it") } }
    conn.send(
      buildJsonObject { put("cmd", "SetNotify"); put("keys", keyArray) },
      buildJsonObject { put("cmd", "Get"); put("keys", JsonArray(keyArray + lockData)) },
    )

    coroutineScope {
      val secondaries = others.map { (slot, name) -> launch { secondary(slot, name) } }
      try {
        while (true) dispatch(mySlot, conn.next(), primary = true)
      } finally {
        secondaries.forEach { it.cancel() }
      }
    }
  }

  /** Retries on its own; ends when the primary connection ends. */
  private suspend fun secondary(slot: Int, slotName: String) {
    var backoff = 1L
    while (true) {
      try {
        http.webSocket(config.serverUrl) {
          val conn = ApConnection(this)
          val (_, connected) = conn.handshake(slotName, listOf("Tracker", "NoText"))
          update { onConnected(slot, connected) }
          backoff = 1
          while (true) dispatch(slot, conn.next(), primary = false)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.info("Connection for slot {}: {}", slotName, e.message ?: e::class.simpleName)
      }
      delay(backoff.seconds)
      backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
    }
  }

  private suspend fun dispatch(slot: Int, packet: JsonObject, primary: Boolean) = update {
    when (packet.string("cmd")) {
      "ReceivedItems" -> onReceivedItems(slot, packet)
      "RoomUpdate" -> onRoomUpdate(slot, packet)
      "Connected" -> onConnected(slot, packet)
      else -> if (primary) when (packet.string("cmd")) {
        "PrintJSON" -> onPrintJson(packet)
        "SetReply" -> onSetReply(packet.string("key")!!, packet["value"], live = true)
        "Retrieved" -> packet.obj("keys")?.forEach { (key, value) -> onSetReply(key, value) }
        "DataPackage" -> onDataPackage(packet)
        "RoomInfo" -> onRoomInfo(packet)
      }
    }
  }

  // -------------------------------------------------------------------------------------------------- persistence

  private suspend fun autosave() {
    var savedVersion = -1L
    while (true) {
      delay(15.seconds)
      val version = read { version }
      if (version != savedVersion) {
        savedVersion = version
        save()
      }
    }
  }

  private fun save() {
    // Called from close() too, so not through the mutex; a save is a snapshot of plain collections
    runCatching { if (state.seedName != null) state.save(config.statePath) }
      .onFailure { logger.warn("Could not save the tracker state to {}: {}", config.statePath, it.message) }
  }

  // ----------------------------------------------------------------------------------------------------- protocol

  /**
   * One connection to the room. The server sends JSON arrays of packets, often several in one frame (Connected is
   * followed by ReceivedItems), so packets are handed out one by one from a buffer.
   */
  private inner class ApConnection(private val session: DefaultClientWebSocketSession) {
    private val pending = ArrayDeque<JsonObject>()

    /** The next packet. Throws once the connection is closed. */
    suspend fun next(): JsonObject {
      while (pending.isEmpty()) {
        val frame = session.incoming.receive()
        if (frame is Frame.Text) {
          ApiJson.parseToJsonElement(frame.readText()).jsonArray.mapTo(pending) { it.jsonObject }
        }
      }
      return pending.removeFirst()
    }

    suspend fun send(vararg packets: JsonObject) {
      session.send(Frame.Text(JsonArray(packets.toList()).toString()))
    }

    /** Waits for RoomInfo, sends Connect and returns (RoomInfo, Connected). Later packets stay buffered. */
    suspend fun handshake(slotName: String, tags: List<String>): Pair<JsonObject, JsonObject> {
      val roomInfo = expect("RoomInfo")
      send(buildJsonObject {
        put("cmd", "Connect")
        // MultiServer has no password: only the gateway is reachable from outside, and it checks the game clients
        put("password", "")
        put("game", "") // trackers may connect without a game
        put("name", slotName)
        put("uuid", "randogeddon-$slotName")
        put("version", roomInfo["version"] ?: buildJsonObject {
          put("major", 0); put("minor", 6); put("build", 0); put("class", "Version")
        })
        put("items_handling", ITEMS_ALL)
        putJsonArray("tags") { tags.forEach { add(it) } }
        put("slot_data", false)
      })
      return roomInfo to expect("Connected")
    }

    private suspend fun expect(cmd: String): JsonObject {
      while (true) {
        val packet = next()
        when (packet.string("cmd")) {
          cmd -> return packet
          "ConnectionRefused" -> throw ConnectionRefused(
            packet.array("errors").orEmpty().map { it.jsonPrimitive.content }
          )
        }
      }
    }
  }
}
