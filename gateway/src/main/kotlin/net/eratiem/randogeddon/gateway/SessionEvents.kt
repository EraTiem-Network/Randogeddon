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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.eratiem.randogeddon.database.SessionEventStore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
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

/**
 * The most characters of one text a kept chat line or link event keeps: a real chat line, a death's cause or a trap's
 * name is far shorter (the tracker shows 200 of a cause, 80 of a name), but a client may send up to a frame's
 * megabyte, which would be a row of that size for each.
 */
internal const val KEPT_TEXT = 1000

/** The fields of a link's `Bounced` data that are kept: what the tracker reads, and the time it was sent. */
private val LINK_FIELDS = listOf("time", "source", "cause", "trap_name")

/**
 * [packet] as it is kept ([kept]): what a player writes at will is cut, a chat line's `message` and parts to
 * [KEPT_TEXT] characters each, a link's `Bounced` to its link tags and [LINK_FIELDS] (the rest of it is the sending
 * client's own, of any size), their texts cut the same. Everything else MultiServer makes itself and is kept as it is.
 */
internal fun keptForm(packet: JsonObject): JsonObject = when ((packet["cmd"] as? JsonPrimitive)?.content) {
  "PrintJSON" -> if ((packet["type"] as? JsonPrimitive)?.content in setOf("Chat", "ServerChat")) cutChat(packet) else packet
  "Bounced" -> cutLink(packet)
  else -> packet
}

private fun cut(text: JsonElement): JsonElement =
  if (text is JsonPrimitive && text.isString && text.content.length > KEPT_TEXT) JsonPrimitive(text.content.take(KEPT_TEXT))
  else text

private fun cutChat(packet: JsonObject): JsonObject = JsonObject(
  packet + buildMap<String, JsonElement> {
    packet["message"]?.let { put("message", cut(it)) }
    (packet["data"] as? JsonArray)?.let { parts ->
      val cutParts = parts.map { part ->
        if (part is JsonObject && "text" in part) JsonObject(part + ("text" to cut(part.getValue("text")))) else part
      }
      put("data", JsonArray(cutParts))
    }
  },
)

private fun cutLink(packet: JsonObject): JsonObject = buildJsonObject {
  put("cmd", "Bounced")
  val tags = (packet["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { tag -> tag.isString }?.content }
  putJsonArray("tags") { LINK_TAGS.filter { it in tags }.forEach { add(it) } }
  (packet["data"] as? JsonObject)?.let { data ->
    putJsonObject("data") {
      for (field in LINK_FIELDS) {
        val value = data[field] as? JsonPrimitive ?: continue
        // A number as it came, if it is one of a sensible length
        if (value.isString) put(field, cut(value)) else if (value.content.length <= 32) put(field, value)
      }
    }
  }
}

/**
 * What happens on each running game server, heard by the gateway and written into the database
 * ([SessionEventStore], through [append]): the tracker reads it from there, so nothing is lost while the tracker isn't
 * connected (it restarts, or the player who wakes a sleeping session is in before it). The gateway starts every game
 * server and is there whenever one runs: one connection each (a `Tracker` client, like the tracker's own), from the
 * moment the server accepts clients ([started]) until it ends ([ended]); both are written too, so the tracker knows
 * that nobody is connected any more. Players are let in once it listens ([awaitListening]).
 */
class SessionEvents(
  private val append: suspend (id: String, atMs: Long, kind: String, packet: String) -> Unit,
  private val http: HttpClient,
  private val scope: CoroutineScope,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  constructor(store: SessionEventStore, http: HttpClient, scope: CoroutineScope) :
    this({ id, atMs, kind, packet -> store.append(id, atMs, kind, packet) }, http, scope)

  private class Listener(val job: Job, val listening: CompletableDeferred<Unit>)

  private val listeners = ConcurrentHashMap<String, Listener>()

  /** The `stop` writes [ended] launched and that haven't finished: the shutdown waits for them ([flush]). */
  private val stops = ConcurrentHashMap.newKeySet<Job>()

  /**
   * Session [id]'s game server accepts clients on [port] now; the listener connects as [room]'s first slot, with its
   * room password ([password], asked at each try: a crashed game server starts again with another).
   */
  fun started(id: String, port: Int, room: RoomJson, password: () -> String? = { null }) {
    listeners.remove(id)?.job?.cancel()
    val listening = CompletableDeferred<Unit>()
    val job = scope.launch {
      write(id, SessionEventStore.START, "")
      var pause = 1
      while (isActive) {
        try {
          val slot = room.firstSlot() ?: error("no slot in ${room.path.fileName}")
          listen(id, "ws://localhost:$port", slot, password().orEmpty(), listening)
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
    val job = scope.launch { write(id, SessionEventStore.STOP, "") }
    stops += job
    job.invokeOnCompletion { stops -= job }
  }

  /** Waits for the `stop`s [ended] is still writing (the gateway stops: the database is closed after). */
  suspend fun flush() {
    stops.toList().joinAll()
  }

  /**
   * Nobody is connected to session [id]'s game server (the gateway started, none runs): its `stop`, which a killed
   * gateway didn't write (its players would show as connected until the session's next start).
   */
  suspend fun stopped(id: String) = write(id, SessionEventStore.STOP, "")

  /**
   * [stopped] for each of [ids] (the session folders, at the gateway's start) whose session the database has, asked
   * once ([known]: the IDs it has). A folder whose session is gone from it gets none (the events' foreign key would
   * refuse it), said in one line. [known] failing (the database doesn't answer at the moment): each one's is tried as
   * before, so a real session's isn't lost for it.
   */
  suspend fun stoppedAll(ids: Collection<String>, known: suspend () -> Set<String>) {
    val there = runCatching { known() }
      .onFailure { logger.warn("Could not read which sessions there are, writing every folder's stop: {}", it.message) }
      .getOrNull()
    val gone = if (there == null) emptyList() else ids.filter { it !in there }
    if (gone.isNotEmpty()) logger.info("No stop for session folders the database has no session of: {}", gone.joinToString())
    ids.filter { it !in gone }.forEach { stopped(it) }
  }

  /**
   * Waits until the gateway listens to session [id]'s game server, [within] at most (it goes on without then: a
   * player isn't kept out for it).
   */
  suspend fun awaitListening(id: String, within: Duration = 3.seconds) {
    val listener = listeners[id] ?: return
    withTimeoutOrNull(within) { listener.listening.await() }
  }

  private suspend fun listen(
    id: String, url: String, slot: String, password: String, listening: CompletableDeferred<Unit>,
  ) = http.webSocket(url) {
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
      // The room's password (the gateway's): without it MultiServer lets nobody in
      put("password", password)
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
        else -> if (kept(packet)) write(id, SessionEventStore.PACKET, keptForm(packet).toString())
      }
    }
  }

  private suspend fun write(id: String, kind: String, packet: String) {
    runCatching { append(id, clock(), kind, packet) }
      .onFailure { logger.warn("Could not write {}'s event ({}): {}", id, kind, it.message) }
  }
}
