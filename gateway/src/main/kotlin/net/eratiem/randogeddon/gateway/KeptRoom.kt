package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.KeptRoom")

/** The file that names the seed, holds the `RoomInfo` and lists the games' files; written last. */
private const val INDEX = "room-info.json"

/** A game's data package file, as [KeptRoom.keep] names them (nothing else of the index is read as a path). */
private val GAME_FILE = Regex("game-[0-9]+\\.json")

/**
 * The fields of a `RoomInfo` that can differ between two runs of a seed's game server: the session's options, given
 * to MultiServer at each start (or changed on its console). The rest is the seed's and the release's.
 */
private val SESSION_OPTIONS = listOf("permissions", "hint_cost", "location_check_points")

/** Data package answers read at once: each holds its games' files whole (megabytes for a large multiworld). */
private val answering = Semaphore(4)

/**
 * What a game client needs of a session's game server before it joins, kept in [dir] (`sessions/<id>/kept/`) while the
 * server runs, so the gateway can answer the client itself while the server sleeps and wake it only for a `Connect`
 * with a slot's password: the `RoomInfo` MultiServer sends every new connection, and the data package of every game of
 * the room (what `GetDataPackage` is answered from). Kept per seed (the seed file's name): what was kept for another
 * seed is dropped. Read when a client needs it, never held: a data package is megabytes.
 */
class KeptRoom(private val dir: Path) {
  /**
   * The kept `RoomInfo` (as MultiServer sent it) and where each game's data package is. Read for one client's
   * connection.
   */
  class Kept internal constructor(val roomInfo: JsonObject, private val games: Map<String, Path>) {
    /**
     * The `RoomInfo` frame as the client gets it: asking for the slot's password, like MultiServer's own through the
     * relay ([askingForPassword]), its `time` now (MultiServer sends its clock with it).
     */
    fun roomInfoFrame(): String {
      val now = JsonPrimitive(System.currentTimeMillis() / 1000.0)
      return askingForPassword(JsonArray(listOf(JsonObject(roomInfo + ("time" to now)))).toString())
    }

    /**
     * The answer to a `GetDataPackage` for [asked], as MultiServer gives it: the data packages of the games asked for
     * that are in the room, the others left out. `null`: a file is missing or damaged (the kept room isn't used then).
     */
    suspend fun dataPackage(asked: List<String>): String? = answering.withPermit {
      withContext(Dispatchers.IO) { answer(asked.distinct().filter { it in games }) }
    }

    private fun answer(names: List<String>): String? {
      val answer = StringBuilder("""[{"cmd":"DataPackage","data":{"games":{""")
      for ((i, name) in names.withIndex()) {
        val text = runCatching { games.getValue(name).readText() }.getOrNull()
          // Written whole from a parsed object: a file cut short doesn't end as one
          ?.takeIf { it.startsWith('{') && it.endsWith('}') } ?: return null
        if (i > 0) answer.append(',')
        answer.append(JsonPrimitive(name).toString()).append(':').append(text)
      }
      return answer.append("}}}]").toString()
    }
  }

  /**
   * Keeps [roomInfo] and the data packages of [games] (game name -> its package) for [seed]: written beside the
   * kept files of before, which they then replace at once (a client reading meanwhile reads the one or the other).
   */
  suspend fun keep(seed: String, roomInfo: JsonObject, games: JsonObject): Unit = withContext(Dispatchers.IO) {
    val fresh = dir.resolveSibling("${dir.name}.new")
    fresh.toFile().deleteRecursively()
    fresh.createDirectories()
    val files = games.keys.withIndex().associate { (i, name) -> name to "game-$i.json" }
    files.forEach { (name, file) -> fresh.resolve(file).writeText(games.getValue(name).toString()) }
    fresh.resolve(INDEX).writeText(
      buildJsonObject {
        put("seed", seed)
        put("roomInfo", roomInfo)
        putJsonObject("games") { files.forEach { (name, file) -> put(name, file) } }
      }.toString(),
    )
    dir.toFile().deleteRecursively()
    Files.move(fresh, dir)
    Unit
  }

  /**
   * What is kept for [seed] (the seed file the game server serves when it wakes); `null`: nothing, or not all of it
   * (a game of the room without its data package), or damaged. Kept for another seed is dropped.
   */
  suspend fun read(seed: String?): Kept? {
    if (seed == null) return null
    return withContext(Dispatchers.IO) { readIndex(seed) }
  }

  private fun readIndex(seed: String): Kept? {
    val index = runCatching { Json.parseToJsonElement(dir.resolve(INDEX).readText()) as? JsonObject }.getOrNull()
      ?: return null
    val keptFor = index.text("seed") ?: return null
    if (keptFor != seed) {
      logger.info("Dropping the room kept for {}: the session serves {} now", printable(keptFor), printable(seed))
      runCatching { dir.toFile().deleteRecursively() }
      return null
    }
    val roomInfo = (index["roomInfo"] as? JsonObject)?.takeIf { it.text("cmd") == "RoomInfo" } ?: return null
    val games = (index["games"] as? JsonObject ?: return null).mapValues { (_, file) ->
      val name = (file as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(GAME_FILE::matches) ?: return null
      dir.resolve(name)
    }
    val roomGames = (roomInfo["games"] as? JsonArray ?: return null)
      .map { (it as? JsonPrimitive)?.content ?: return null }
    if (!games.keys.containsAll(roomGames)) return null
    return Kept(roomInfo, games)
  }

  /**
   * Asks the game server at [url] (it runs [seed]) for what is kept, and keeps it ([askRoom]); a few tries, as it may
   * be busy right after its start. What was kept stays when none answers.
   */
  suspend fun capture(http: HttpClient, url: String, seed: String) {
    repeat(3) {
      val room = try {
        askRoom(http, url)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.info("Asking {} for its room: {}", url, e.message ?: e::class.simpleName)
        null
      }
      if (room != null) {
        runCatching { keep(seed, room.first, room.second) }
          .onSuccess { logger.info("Kept the room of {} ({} games)", printable(seed), room.second.size) }
          .onFailure { logger.warn("Could not keep the room of {}: {}", printable(seed), it.message) }
        return
      }
      delay(5.seconds)
    }
  }

  companion object {
    /** A session's, in its directory [sessionDir]. */
    fun of(sessionDir: Path) = KeptRoom(sessionDir.resolve("kept"))
  }
}

/**
 * The `RoomInfo` and the data package of every game of the room (game name -> its package) from the game server at
 * [url], over a connection of the gateway's own: the `RoomInfo` MultiServer sends first, then a `GetDataPackage` for
 * the room's games, which it answers without a `Connect`. `null`: it didn't answer, or not with every game.
 */
suspend fun askRoom(http: HttpClient, url: String, within: Duration = 2.minutes): Pair<JsonObject, JsonObject>? =
  withTimeoutOrNull(within) {
    var room: Pair<JsonObject, JsonObject>? = null
    http.webSocket(url) {
      suspend fun packets(): List<JsonObject> {
        while (true) {
          val frame = incoming.receive()
          if (frame !is Frame.Text) continue
          return (Json.parseToJsonElement(frame.readText()) as JsonArray).filterIsInstance<JsonObject>()
        }
      }
      val roomInfo = packets().firstOrNull { it.text("cmd") == "RoomInfo" } ?: return@webSocket
      val games = (roomInfo["games"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
      val ask = buildJsonObject {
        put("cmd", "GetDataPackage")
        putJsonArray("games") { games.forEach { add(it) } }
      }
      send(Frame.Text(JsonArray(listOf(ask)).toString()))
      while (room == null) {
        val dataPackage = packets().firstOrNull { it.text("cmd") == "DataPackage" } ?: continue
        val kept = (dataPackage["data"] as? JsonObject)?.get("games") as? JsonObject ?: return@webSocket
        if (!kept.keys.containsAll(games)) return@webSocket
        room = roomInfo to kept
      }
    }
    room
  }

/**
 * MultiServer's first frame to a connection the gateway answered from a [KeptRoom] ([server]), whose `RoomInfo` the
 * client got already ([sent]): without that `RoomInfo` (a client takes one per connection), but with a `RoomUpdate`
 * of the session's options that changed since it was kept ([SESSION_OPTIONS]); `null`: nothing left to pass on.
 */
fun replayedRoomInfo(server: String, sent: JsonObject): String? {
  val packets = runCatching { Json.parseToJsonElement(server) }.getOrNull() as? JsonArray ?: return server
  val left = packets.mapNotNull { packet ->
    if (packet !is JsonObject || packet.text("cmd") != "RoomInfo") return@mapNotNull packet
    val changed = SESSION_OPTIONS.filter { packet[it] != null && packet[it] != sent[it] }
    if (changed.isEmpty()) return@mapNotNull null
    val update: Map<String, JsonElement> = mapOf("cmd" to JsonPrimitive("RoomUpdate"))
    JsonObject(update + changed.associateWith { packet.getValue(it) })
  }
  return if (left.isEmpty()) null else JsonArray(left).toString()
}

/** A string field; `null` if missing or not a string. */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
