package net.eratiem.randogeddon.server.ap

import net.eratiem.randogeddon.shared.CheatAction
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
import net.eratiem.randogeddon.database.SessionEventStore
import net.eratiem.randogeddon.database.SessionSnapshotStore
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.server.state.CheatLine
import net.eratiem.randogeddon.server.state.LINK_TAGS
import net.eratiem.randogeddon.server.state.SessionState
import net.eratiem.randogeddon.server.util.array
import net.eratiem.randogeddon.server.util.int
import net.eratiem.randogeddon.server.util.longs
import net.eratiem.randogeddon.server.util.obj
import net.eratiem.randogeddon.server.util.string
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AvailableAnswer
import net.eratiem.randogeddon.shared.AvailableSlot
import java.lang.invoke.MethodHandles
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

private val logger = logger(MethodHandles.lookup().lookupClass())

/** Receive all items: from other worlds, from the own world and the starting inventory. */
private const val ITEMS_ALL = 0b111
private const val MAX_BACKOFF_SECONDS = 5L

class ConnectionRefused(val errors: List<String>) : Exception(errors.joinToString())

/**
 * The WebSocket client of the trackers' connections to the game servers: one for every session's (see
 * `LiveSessions`), closed when the tracker stops.
 */
fun trackerClient(): HttpClient = HttpClient(CIO) {
  install(WebSockets) {
    maxFrameSize = Long.MAX_VALUE // the data package can be several MB
    pingIntervalMillis = 30_000
    extensions { install(WebSocketDeflateExtension) }
  }
}

/**
 * Follows the room through the Archipelago network protocol and feeds [SessionState].
 *
 * One *primary* connection (tag `Tracker`) gets the room-wide information: RoomInfo, the data package and the data
 * storage keys of the hints and client status of all slots. What is said to everyone (every PrintJSON event: the
 * feed, who joins and leaves) it doesn't take from there: the gateway hears that from the game server's start and
 * keeps it in the database, and [follow] reads it in order. It then starts one *secondary* connection per other slot
 * (tags `Tracker`, `NoText`), only to learn that slot's checked locations and received items, which the server sends
 * to the slot's own connections only.
 *
 * Its history is those events: at its start the slots are set up from [roomJson] and every event of the session is
 * replayed (onto the history file of before, [history], kept once in the database as the baseline), so nothing is
 * lost while this tracker isn't running, and nothing it knew waits for a save. A cheat's feed line is written there
 * too.
 *
 * All access to [SessionState] goes through [read] and the private `update`, so the connections and the web layer
 * never see it half-updated. [serverUrl] is where its MultiServer runs (`null` while it doesn't). Its connections go
 * through [http], the client shared by every session's tracker ([trackerClient]), which it doesn't close.
 */
class TrackerService(
  /** The history file of before 0.19.10: imported once as the baseline, then not read any more. */
  private val history: Path,
  private val roomJson: Path,
  /** The session's ID and where its events and its baseline are kept; `null`: heard here only, nothing kept. */
  private val sessionId: String = "",
  private val events: SessionEventStore? = null,
  private val snapshots: SessionSnapshotStore? = null,
  private val http: HttpClient,
  private val serverUrl: () -> String?,
) {
  private val state = SessionState()
  private val mutex = Mutex()

  /**
   * Why the tracker is not connected, for the dashboard (what the connection said last time it failed); `null` while
   * connected.
   */
  @Volatile
  var lastError: String? = null

  /** The game server turned the tracker away ([lastError] says how). */
  @Volatile
  var lastErrorRefused: Boolean = false
    private set

  suspend fun <T> read(block: SessionState.() -> T): T = mutex.withLock { state.block() }

  private suspend fun update(block: SessionState.() -> Unit) = mutex.withLock { state.block() }

  /** The session logic's answer about the slots in [asked] and the maps of [maps] (see [SessionState.onAvailable]). */
  suspend fun onAvailable(asked: List<AvailableSlot>, answer: AvailableAnswer, maps: List<Int> = emptyList()) =
    update { onAvailable(asked, answer, maps) }

  /** Its logic was built again: every slot is asked anew. */
  suspend fun forgetAvailableAsks() = update { forgetAvailableAsks() }

  /** Brings its history back once (the slots, the baseline), follows its events, and stays connected until cancelled. */
  fun start(scope: CoroutineScope): Job = scope.launch {
    if (!prepared) {
      prepare()
      prepared = true
    }
    events?.let { launch { follow(it) } }
    var backoff = 1L
    while (isActive) {
      val name = slotNames().firstOrNull()
      val url = serverUrl()
      if (name == null || url == null) {
        // Its own error (noted for the dashboard), not Kotlin's, which would throw and end this connection's job
        error(if (url == null) "the game server isn't running" else "no slots known yet (waiting for $roomJson)", refused = false)
        delay(5.seconds)
        continue
      }
      try {
        primary(name, url)
        backoff = 1
      } catch (e: CancellationException) {
        throw e
      } catch (e: ConnectionRefused) {
        error(e.message.orEmpty(), refused = true)
      } catch (e: Exception) {
        error(e.message ?: e::class.simpleName.orEmpty(), refused = false)
      }
      update { if (events == null) onConnectionLost() else onTrackerDisconnected() }
      delay(backoff.seconds)
      backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
    }
  }

  /**
   * The slots from `room.json` (the gateway writes it before each start of the game server), then the baseline: the
   * history file of before, imported into the database once.
   */
  private suspend fun prepare() {
    Room.read(roomJson)?.let { room -> update { prepare(room) } }
    val store = snapshots ?: return
    val baseline = runCatching { store.baseline(sessionId) ?: importHistory(store) }
      .onFailure { logger.warn("Could not read {}'s history from before: {}", sessionId, it.message) }.getOrNull()
    if (baseline != null) update {
      loadSaved(baseline)
      applySaved()
    }
  }

  /** The history file of before 0.19.10, into the database (once); it is left where it is. `null`: there is none. */
  private suspend fun importHistory(store: SessionSnapshotStore): String? {
    if (!history.exists()) return null
    val text = history.readText()
    store.importBaseline(sessionId, System.currentTimeMillis(), text)
    logger.info("Imported {}'s history file into the database", sessionId)
    return text
  }

  /**
   * Replays every event of the session, in its order (from the baseline's end on), then reads the new ones about
   * once a second. Slots not known yet (no `room.json` at the start: the game server hadn't run) are taken from it as
   * soon as it is there, before an event needs them.
   */
  private suspend fun follow(events: SessionEventStore) {
    while (true) {
      // The baseline first, then the events after it (none of them twice)
      if (read { players.isEmpty() }) Room.read(roomJson)?.let { room ->
        update {
          prepare(room)
          applySaved()
        }
      }
      val next = runCatching { events.after(sessionId, read { serverCursor }) }
        .onFailure { logger.warn("Could not read {}'s events: {}", sessionId, it.message) }.getOrDefault(emptyList())
      if (next.isNotEmpty()) update { next.forEach { onServerEvent(it.id, it.atMs, it.kind, it.packet) } }
      if (next.size < 1000) delay(1.seconds)
    }
  }

  /** Its slots and history were brought back (by the first [start]). */
  @Volatile
  private var prepared = false

  /** It has been connected to the game server since its start: what it knows is the game server's, not the kept. */
  @Volatile
  var connectedOnce = false
    private set

  /** The session was deleted: its state goes. */
  suspend fun clear() {
    update { clear() }
  }

  /**
   * A cheat for the live feed: [message] in English, and its parts. Kept among the session's events (and taken from
   * there, as every event); without them, or if that fails, straight into the feed.
   */
  suspend fun cheat(message: String, action: CheatAction, by: String, role: String, slot: String, value: String?) {
    val store = events
    val written = store != null && runCatching {
      store.append(sessionId, System.currentTimeMillis(), SessionEventStore.CHEAT,
        ApiJson.encodeToString(CheatLine.serializer(), CheatLine(message, action, by, role, slot, value)))
    }.onFailure { logger.warn("Could not keep {}'s cheat: {}", sessionId, it.message) }.isSuccess
    if (!written) update { onCheat(message, action, by, role, slot, value) }
  }

  private fun error(detail: String, refused: Boolean) {
    if (detail != lastError) logger.warn(if (refused) "server refused the connection: {}" else "connection problem: {}", detail)
    lastError = detail
    lastErrorRefused = refused
  }

  private fun slotNames(): List<String> = Room.read(roomJson)?.players.orEmpty().map { it.name }

  private suspend fun primary(slotName: String, url: String) = http.webSocket(url) {
    val conn = ApConnection(this)
    // The links' tags: MultiServer passes a Bounced packet on to the clients that have one of its tags
    val (roomInfo, connected) = conn.handshake(slotName, listOf("Tracker") + if (events == null) LINK_TAGS else emptyList())
    val games = roomInfo.array("games").orEmpty().map { it.jsonPrimitive.content }.toSet() + "Archipelago"
    conn.send(buildJsonObject {
      put("cmd", "GetDataPackage")
      putJsonArray("games") { games.sorted().forEach { add(it) } }
    })
    val mySlot = connected.int("slot")!!
    val team = connected.int("team")!!
    conn.scout(mySlot, connected)
    val others = mutex.withLock {
      if (state.onRoomInfo(roomInfo)) logger.info("Seed {}", state.seedName)
      state.onConnected(mySlot, connected)
      // Without a baseline before (the seed wasn't known then): now it is
      state.applySaved()
      state.connected = true
      state.players.filterKeys { it != mySlot }.mapValues { it.value.name }
    }
    lastError = null
    connectedOnce = true
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
      val secondaries = others.map { (slot, name) -> launch { secondary(slot, name, url) } }
      try {
        while (true) dispatch(mySlot, conn.next(), primary = true)
      } finally {
        secondaries.forEach { it.cancel() }
      }
    }
  }

  /** Retries on its own; ends when the primary connection ends. */
  private suspend fun secondary(slot: Int, slotName: String, url: String) {
    var backoff = 1L
    while (true) {
      try {
        http.webSocket(url) {
          val conn = ApConnection(this)
          val (_, connected) = conn.handshake(slotName, listOf("Tracker", "NoText"))
          update { onConnected(slot, connected) }
          conn.scout(slot, connected)
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
      "LocationInfo" -> onLocationInfo(slot, packet)
      else -> if (primary) when (packet.string("cmd")) {
        // With the gateway's events: taken from those, not heard a second time here
        "PrintJSON" -> if (events == null) onPrintJson(packet)
        "Bounced" -> if (events == null) onBounced(packet)
        "SetReply" -> onSetReply(packet.string("key")!!, packet["value"])
        "Retrieved" -> packet.obj("keys")?.forEach { (key, value) -> onSetReply(key, value) }
        "DataPackage" -> onDataPackage(packet)
        "RoomInfo" -> onRoomInfo(packet)
      }
    }
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

    /**
     * Asks what lies at every location of [slot] (its `Connected` lists them), without making hints of it: for the
     * progression meant for each slot. The answer (`LocationInfo`) comes like any packet.
     */
    suspend fun scout(slot: Int, connected: JsonObject) {
      val locations = (connected.longs("checked_locations") + connected.longs("missing_locations")).distinct()
      if (locations.isEmpty()) return update { onNothingToScout(slot) }
      send(buildJsonObject {
        put("cmd", "LocationScouts")
        putJsonArray("locations") { locations.forEach { add(it) } }
        put("create_as_hint", 0)
      })
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
