package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.eratiem.randogeddon.database.SessionEventStore
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.SessionEvents")

/** The links between games whose `Bounced` packets are kept (the feed shows them): the listener has these tags. */
val LINK_TAGS = listOf("DeathLink", "TrapLink")

/** The `PrintJSON` messages kept: what the tracker makes feed events and the slots' presence of. */
private val KEPT = setOf("ItemSend", "ItemCheat", "Hint", "Goal", "Release", "Collect", "Join", "Part", "Chat", "ServerChat", "Countdown")

/** Whether [packet] of a game server is one to keep: a `PrintJSON` of [KEPT], or a link's `Bounced`. */
internal fun kept(packet: JsonObject): Boolean = when ((packet["cmd"] as? JsonPrimitive)?.content) {
  "PrintJSON" -> (packet["type"] as? JsonPrimitive)?.content in KEPT
  "Bounced" -> (packet["tags"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.content in LINK_TAGS }
  else -> false
}

/** The first player's slot of a session's room.json (written before each start): the listener connects as it. */
internal fun firstSlot(roomJson: Path): String? = runCatching {
  if (!roomJson.exists()) return null
  ((Json.parseToJsonElement(roomJson.readText()) as JsonObject)["slots"] as? JsonArray).orEmpty().map { it as JsonObject }
    .filter { (it["type"] as? JsonPrimitive)?.content == "1" }
    .minByOrNull { (it["slot"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Int.MAX_VALUE }
    ?.let { (it["name"] as? JsonPrimitive)?.content }
}.getOrNull()

/**
 * What happens on each running game server, heard by the gateway and written into the database
 * ([SessionEventStore]): the tracker reads it from there, so nothing is lost while the tracker isn't connected (it
 * restarts, or the player who wakes a sleeping session is in before it). The gateway starts every game server and is
 * there whenever one runs: one connection each (a `Tracker` client, like the tracker's own), from the moment the
 * server accepts clients ([started]) until it ends ([ended]); both are written too, so the tracker knows that
 * nobody is connected any more. Players are let in once it listens ([awaitListening]).
 */
class SessionEvents(
  private val store: SessionEventStore,
  private val http: HttpClient,
  private val scope: CoroutineScope,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private class Listener(val job: Job, val listening: CompletableDeferred<Unit>)

  private val listeners = ConcurrentHashMap<String, Listener>()

  /** Session [id]'s game server accepts clients on [port] now. */
  fun started(id: String, port: Int, roomJson: Path) {
    listeners.remove(id)?.job?.cancel()
    val listening = CompletableDeferred<Unit>()
    val job = scope.launch {
      write(id, SessionEventStore.START, "")
      var pause = 1
      while (isActive) {
        try {
          val slot = firstSlot(roomJson) ?: error("no slot in ${roomJson.fileName}")
          listen(id, "ws://localhost:$port", slot, listening)
          pause = 1
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger.info("Listening to {}: {}", id, e.message ?: e::class.simpleName)
        }
        delay(pause.seconds)
        pause = (pause * 2).coerceAtMost(5)
      }
    }
    listeners[id] = Listener(job, listening)
  }

  /** Session [id]'s game server ended: nobody is connected to it any more. */
  fun ended(id: String) {
    listeners.remove(id)?.job?.cancel() ?: return
    scope.launch { write(id, SessionEventStore.STOP, "") }
  }

  /**
   * Waits until the gateway listens to session [id]'s game server, [within] at most (it goes on without then: a
   * player isn't kept out for it).
   */
  suspend fun awaitListening(id: String, within: Duration = 3.seconds) {
    val listener = listeners[id] ?: return
    withTimeoutOrNull(within) { listener.listening.await() }
  }

  private suspend fun listen(id: String, url: String, slot: String, listening: CompletableDeferred<Unit>) = http.webSocket(url) {
    val pending = ArrayDeque<JsonObject>()
    suspend fun next(): JsonObject {
      while (pending.isEmpty()) {
        val frame = incoming.receive()
        if (frame is Frame.Text) (Json.parseToJsonElement(frame.readText()) as JsonArray).mapTo(pending) { it as JsonObject }
      }
      return pending.removeFirst()
    }
    var roomInfo: JsonObject
    do roomInfo = next() while ((roomInfo["cmd"] as? JsonPrimitive)?.content != "RoomInfo")
    send(Frame.Text(JsonArray(listOf(buildJsonObject {
      put("cmd", "Connect")
      // MultiServer has no password of its own: only the gateway reaches it
      put("password", "")
      put("game", "")
      put("name", slot)
      put("uuid", "randogeddon-events")
      put("version", roomInfo["version"] ?: buildJsonObject { put("major", 0); put("minor", 6); put("build", 0); put("class", "Version") })
      // No items: only what is said to everyone
      put("items_handling", 0)
      putJsonArray("tags") { add("Tracker"); LINK_TAGS.forEach { add(it) } }
      put("slot_data", false)
    })).toString()))
    while (true) {
      val packet = next()
      when ((packet["cmd"] as? JsonPrimitive)?.content) {
        "Connected" -> {
          if (listening.complete(Unit)) logger.info("Listening to {} as {}", id, printable(slot))
        }
        "ConnectionRefused" -> error("refused (${packet["errors"]})")
        else -> if (kept(packet)) write(id, SessionEventStore.PACKET, packet.toString())
      }
    }
  }

  private suspend fun write(id: String, kind: String, packet: String) {
    runCatching { store.append(id, clock(), kind, packet) }
      .onFailure { logger.warn("Could not write {}'s event ({}): {}", id, kind, it.message) }
  }
}
