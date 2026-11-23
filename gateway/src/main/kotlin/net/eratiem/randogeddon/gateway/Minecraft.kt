package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.MinecraftLogs
import net.eratiem.randogeddon.shared.MinecraftServerView
import net.eratiem.randogeddon.shared.MinecraftServers
import net.eratiem.randogeddon.shared.MinecraftStatus
import org.slf4j.LoggerFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Minecraft")

private val HostJson = Json { ignoreUnknownKeys = true }

/** A seed's Minecraft Dig patch: `AP_<seed>_P<n>_<slot>.apmcdig`. */
private val DIG_PATCH = Regex("""^AP_[^_]+_P(\d+)_(.+)\.apmcdig$""")

/** One Minecraft Dig slot of a seed: its number, its name, its patch's `data.json`. */
internal data class DigSlot(val number: Int, val name: String, val data: String)

/**
 * The most bytes of a patch's `data.json` that are read: a real one (the slot's settings and its connection) is a few
 * KB; a longer one, which only a made-up seed has, is no slot, and it isn't unpacked whole into the memory.
 */
internal const val MAX_DIG_DATA = 1024 * 1024

/**
 * The most bytes of a patch that are read to find its `data.json`: a real patch (`data.json` and `archipelago.json`) is
 * a few KB; one whose `data.json` isn't within this is no slot.
 */
internal const val MAX_DIG_PATCH = 4L * 1024 * 1024

/**
 * At most [left] bytes of [input], which stays open when this closes (a patch's bytes in the seed zip, whose next
 * entries are still to be read).
 */
private class PatchBytes(input: InputStream, private var left: Long) : FilterInputStream(input) {
  override fun read(): Int {
    if (left <= 0) return -1
    return super.read().also { if (it >= 0) left-- }
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    if (left <= 0) return -1
    return super.read(b, off, minOf(len.toLong(), left).toInt()).also { if (it > 0) left -= it }
  }

  override fun skip(n: Long): Long = super.skip(minOf(n, left)).also { left -= it }

  override fun available(): Int = minOf(super.available().toLong(), left).toInt()

  override fun close() {}
}

/**
 * The Minecraft Dig slots of the seed zip [seed], from their patches (each a zip with its `data.json`, of at most
 * [MAX_DIG_DATA] bytes). Each patch is read as it comes, up to its `data.json` and no further than [MAX_DIG_PATCH]
 * bytes, never whole into the memory.
 */
internal fun digSlots(seed: Path): List<DigSlot> = buildList {
  ZipInputStream(seed.inputStream()).use { zip ->
    while (true) {
      val entry = zip.nextEntry ?: break
      val match = DIG_PATCH.matchEntire(entry.name.substringAfterLast('/')) ?: continue
      // A patch that isn't a zip, or is cut by the limit before its data.json ends, is no slot (the others still are)
      val data = try {
        ZipInputStream(PatchBytes(zip, MAX_DIG_PATCH)).use { patch ->
          generateSequence { patch.nextEntry }.firstOrNull { it.name == "data.json" }
            ?.let { patch.readNBytes(MAX_DIG_DATA + 1) }?.takeIf { it.size <= MAX_DIG_DATA }?.decodeToString()
        }
      } catch (_: IOException) {
        null
      } ?: continue
      add(DigSlot(match.groupValues[1].toInt(), match.groupValues[2], data))
    }
  }
}.sortedBy { it.number }

/** How long a session found not finished counts as not finished, before the database is asked again. */
internal val FINISHED_AGAIN = 30.seconds

/**
 * Whether a session is finished, as [finished] says, asked again only once [again] has passed since the session was
 * last found not finished: the servers are looked at every few seconds, each session's question goes to the database.
 * A finished one is asked every time (its servers are stopped, and it may be reopened).
 */
internal class FinishedSessions(
  private val finished: suspend (String) -> Boolean,
  private val again: Duration = FINISHED_AGAIN,
  private val now: () -> Long = System::currentTimeMillis,
) {
  /** When each session was last found not finished. */
  private val unfinished = ConcurrentHashMap<String, Long>()

  suspend fun of(id: String): Boolean {
    val time = now()
    if (unfinished[id]?.let { time - it < again.inWholeMilliseconds } == true) return false
    return finished(id).also { if (it) unfinished.remove(id) else unfinished[id] = time }
  }

  /** Session [id] is deleted: it is asked anew (for a new session of its ID). */
  fun forget(id: String) {
    unfinished.remove(id)
  }
}

/** What the Minecraft service says of one server. */
@Serializable
internal data class HostServer(
  val key: String,
  val status: String,
  val since: Long? = null,
  val port: Int = 0,
  val relay: Int = 0,
  val players: List<String> = emptyList(),
  /** When a player was last on it (epoch ms; its start counts), as the minecraft service keeps it. */
  val lastPlayer: Long? = null,
)

@Serializable
internal data class HostServers(val servers: List<HostServer> = emptyList())

/** The minecraft service's states of a server, as [HostServer.status] says them. */
private const val HOST_STARTING = "starting"
private const val HOST_RUNNING = "running"
private const val HOST_CRASHED = "crashed"

/**
 * Of the server [keys], those of session [id] (`<id>-p<number>`, as the gateway makes them), and only those: not the
 * ones of a session whose ID starts the same (`<id>-party-p2`, `<id>-p1-p1`).
 */
internal fun serverKeysOf(id: String, keys: Collection<String>): List<String> {
  val own = Regex("^${Regex.escape(id.lowercase())}-p\\d+$")
  return keys.filter { own.matches(it) }
}

/** A server's key as the gateway makes it: `<session>-p<number>`. */
private val SERVER_KEY = Regex("""^(.+)-p\d+$""")

/**
 * The session server [key] belongs to (`abc-p1-p2`: `abc-p1`), from the key the gateway made itself; never what the
 * minecraft service's answer says (code in a hosted server could make it name any session). `null`: no such key.
 */
internal fun sessionOfKey(key: String): String? = SERVER_KEY.matchEntire(key)?.groupValues?.get(1)

/** Why no Minecraft server runs while the minecraft service's token isn't set. */
internal const val NO_MINECRAFT_TOKEN = "The minecraft service has no token of its own (the operator's MINECRAFT_TOKEN)"

/**
 * The hosted Minecraft servers of the sessions' Minecraft Dig slots, run by the minecraft service
 * (`minecraft_host.py`) on the gateway's orders. A server starts when one of its slot's players joins (through the
 * bridge's tunnel, [start]); it connects to Archipelago when its first player is on it (`/connect` in its console,
 * with the slot's password, through the gateway like any client); it sleeps when no player has been on it for as long
 * as the game servers wait ([idleMinutes]); its world stays while it sleeps.
 */
class Minecraft(
  private val http: HttpClient,
  /** The minecraft service's API, e.g. `http://minecraft:8002`; empty: no Minecraft is hosted. */
  private val api: String,
  /**
   * Between the gateway and the minecraft service only (`MINECRAFT_TOKEN`, never the internal API's); empty: nothing is
   * asked of the minecraft service, and no Minecraft is hosted.
   */
  private val token: String,
  private val dirs: SessionDirs,
  /** Mojang's EULA accepted (the operator's `MINECRAFT_EULA`). */
  private val eula: suspend () -> Boolean,
  /** The slot passwords of session `id`, by slot name. */
  private val passwords: suspend (String) -> Map<String, String>,
  private val idleMinutes: suspend () -> Int,
  /** Session `id`'s hosted slots, and of them the ones hosted directly (only those get a server here). */
  private val rules: suspend (String) -> SleepRules = { SleepRules() },
  /** Session `id` is finished (ended or paused): its game server is stopped, its Minecraft servers too. */
  private val finished: suspend (String) -> Boolean = { false },
  scope: CoroutineScope,
) {
  /** The last answer of the minecraft service, by server key. */
  private val known = ConcurrentHashMap<String, HostServer>()
  /** When each server last had a player on it (or started). */
  private val lastPlayer = ConcurrentHashMap<String, Long>()
  /** Each session's seed's Minecraft Dig slots, read once per seed file. */
  private val slots = ConcurrentHashMap<Path, Pair<Long, List<DigSlot>>>()
  /** Why the last start of a server failed, by key (shown until it starts). */
  private val problems = ConcurrentHashMap<String, String>()
  /** Whether the session of a running server is finished, as [look] asks it: once per session and [FINISHED_AGAIN]. */
  private val finishedSessions = FinishedSessions(finished)

  /** The minecraft service is there, but the gateway has no token for it: hosted Minecraft is off, and says why. */
  private val withoutToken = api.isNotBlank() && token.isBlank()

  init {
    if (withoutToken) {
      logger.warn(
        "No MINECRAFT_TOKEN (or it is INTERNAL_TOKEN): no Minecraft server is hosted. Set one, the same for the " +
          "gateway and the minecraft service (openssl rand -hex 32)",
      )
    }
    if (enabled) scope.launch {
      while (true) {
        runCatching { look() }.onFailure { logger.debug("The minecraft service didn't answer: {}", it.message) }
        delay(3.seconds)
      }
    }
  }

  val enabled: Boolean get() = api.isNotBlank() && token.isNotBlank()

  /** Session [id]'s Minecraft Dig slots. */
  internal fun slotsOf(id: String): List<DigSlot> {
    val seed = dirs.seed(id) ?: return emptyList()
    val modified = seed.getLastModifiedTime().toMillis()
    slots[seed]?.takeIf { it.first == modified }?.let { return it.second }
    val found = runCatching { digSlots(seed) }.onFailure { logger.warn("{}'s seed: {}", id, it.message) }.getOrDefault(emptyList())
    slots[seed] = modified to found
    return found
  }

  private fun key(id: String, slot: DigSlot) = "$id-p${slot.number}".lowercase()

  /** Session [id]'s Minecraft Dig slot [slot]; `null`: it has none of that name. */
  private fun digSlot(id: String, slot: String): DigSlot? = slotsOf(id).firstOrNull { it.name == slot }

  /** Asks the minecraft service to stop server [key] (its world stays); `false`: it didn't, or doesn't answer. */
  private suspend fun stopServer(key: String): Boolean =
    runCatching { http.post("$api/servers/$key/stop") { auth() }.status.isSuccess() }.getOrDefault(false)

  /** Session [id]'s hosted Minecraft Dig slots and their servers (a slot played by an account runs its own). */
  suspend fun servers(id: String): MinecraftServers {
    val rules = rules(id)
    return servers(id, rules)
  }

  private suspend fun servers(id: String, rules: SleepRules): MinecraftServers = MinecraftServers(slotsOf(id).filter { it.name in rules.hosted }.map { slot ->
    val key = key(id, slot)
    val host = known[key]
    val status = when (host?.status) {
      HOST_STARTING -> MinecraftStatus.STARTING
      HOST_RUNNING -> MinecraftStatus.RUNNING
      HOST_CRASHED -> MinecraftStatus.CRASHED
      else -> MinecraftStatus.ASLEEP
    }
    val problem = problems[key] ?: NO_MINECRAFT_TOKEN.takeIf { withoutToken }
    MinecraftServerView(slot.name, status, host?.since, host?.players.orEmpty(), problem, direct = slot.name in rules.direct)
  }, hosting = enabled && eula())

  /** Where session [id]'s slot [slot]'s Minecraft server listens (host, port) while it runs; `null`: it doesn't. */
  fun address(id: String, slot: String): Pair<String, Int>? {
    val digSlot = digSlot(id, slot) ?: return null
    val host = known[key(id, digSlot)]?.takeIf { it.status == HOST_RUNNING } ?: return null
    return api.substringAfter("://").substringBefore(':') to host.port
  }

  /** Starts session [id]'s slot [slot]'s server (its files for the seed first); `null`, or why it can't. */
  suspend fun start(id: String, slot: String): String? {
    if (!enabled) return if (withoutToken) NO_MINECRAFT_TOKEN else "No Minecraft is hosted here"
    val digSlot = digSlot(id, slot) ?: return "$slot isn't a Minecraft Dig slot of this session"
    val key = key(id, digSlot)
    if (slot !in rules(id).direct) return "$slot isn't hosted directly (an Administrator's switch)"
    if (finished(id)) return "its session is finished (reopen it first)"
    if (known[key]?.status in setOf(HOST_STARTING, HOST_RUNNING)) return null
    if (!eula()) return "Mojang's EULA isn't accepted (the operator's MINECRAFT_EULA)".also { problems[key] = it }
    val seed = dirs.seed(id)?.name ?: return "it has no seed"
    val files = buildJsonText(id, digSlot, seed)
    val refused = runCatching {
      val put = http.put("$api/servers/$key") { auth(); contentType(ContentType.Application.Json); setBody(files) }
      if (!put.status.isSuccess()) return@runCatching "The minecraft service answered ${put.status}"
      val started = http.post("$api/servers/$key/start") { auth() }
      if (started.status.isSuccess()) null
      else runCatching { HostJson.parseToJsonElement(started.bodyAsText()) as JsonObject }.getOrNull()
        ?.get("message")?.jsonPrimitive?.content ?: "the minecraft service answered ${started.status}"
    }.getOrElse { "the minecraft service doesn't answer" }
    if (refused != null) {
      problems[key] = refused
      return refused
    }
    problems.remove(key)
    lastPlayer[key] = System.currentTimeMillis()
    logger.info("Minecraft server of {}'s {} starting", id, printable(slot))
    runCatching { look() }
    return null
  }

  private fun buildJsonText(id: String, slot: DigSlot, seed: String): String = HostJson.encodeToString(
    JsonObject.serializer(),
    JsonObject(
      mapOf(
        "session" to JsonPrimitive(id),
        "slot" to JsonPrimitive(slot.name),
        "seed" to JsonPrimitive(seed),
        "data" to JsonPrimitive(slot.data),
        "eula" to JsonPrimitive(true),
      ),
    ),
  )

  suspend fun stop(id: String, slot: String): Boolean {
    if (!enabled) return false
    val digSlot = digSlot(id, slot) ?: return false
    return stopServer(key(id, digSlot))
  }

  /** Session [id]'s Minecraft Dig slots' server logs, their lines numbered above [since], by slot. */
  suspend fun log(id: String, since: Long): MinecraftLogs = MinecraftLogs(slotsOf(id).associate { slot ->
    val text = if (!enabled) null
    else runCatching { http.get("$api/servers/${key(id, slot)}/log?since=$since") { auth() } }.getOrNull()
      ?.takeIf { it.status.isSuccess() }?.bodyAsText()
    slot.name to (text?.let { HostJson.decodeFromString(LogLines.serializer(), it) } ?: LogLines(emptyList(), since))
  })

  /**
   * Session [id] is deleted: its servers and their worlds too, and what is kept of them here (for a new session of its
   * ID). Only its own: not those of a session whose ID starts the same.
   */
  suspend fun remove(id: String) {
    val dir = dirs.dir(id)
    slots.keys.removeIf { it.startsWith(dir) }
    finishedSessions.forget(id)
    serverKeysOf(id, problems.keys + lastPlayer.keys).forEach { key ->
      problems.remove(key)
      lastPlayer.remove(key)
    }
    if (!enabled) return
    serverKeysOf(id, known.keys).forEach { key ->
      runCatching { http.delete("$api/servers/$key") { auth() } }
      known.remove(key)
    }
  }

  /** The minecraft service's servers now: a first player connects the server to Archipelago, an empty one sleeps. */
  private suspend fun look() {
    val text = http.get("$api/servers") { auth() }.bodyAsText()
    val now = System.currentTimeMillis()
    val idle = idleMinutes()
    for (server in HostJson.decodeFromString(HostServers.serializer(), text).servers) {
      val before = known.put(server.key, server)
      if (server.players.isNotEmpty()) {
        lastPlayer[server.key] = now
        // One that doesn't answer now is asked again at its next first player
        if (before?.players.isNullOrEmpty()) runCatching { connect(server) }
      }
      // Its session ended or paused: its game server is stopped, so is it (its world stays; FINISHED_AGAIN late)
      val session = sessionOfKey(server.key)
      if (server.status in setOf(HOST_STARTING, HOST_RUNNING) && session != null && finishedSessions.of(session)) {
        logger.info("Minecraft server {} stops: its session is finished", server.key)
        stopServer(server.key)
        continue
      }
      // The minecraft service's time where it has one: it goes on across the gateway's restarts
      val since = server.lastPlayer ?: lastPlayer.getOrPut(server.key) { now }
      if (server.status == HOST_RUNNING && server.players.isEmpty() && idle > 0 && now - since >= idle.minutes.inWholeMilliseconds) {
        logger.info("Minecraft server {} sleeps: no player for {} min", server.key, idle)
        stopServer(server.key)
      }
    }
  }

  /** Session [id]'s slot [slot]'s server, if it runs, connects to Archipelago again (with a new password). */
  suspend fun reconnect(id: String, slot: String) {
    val digSlot = digSlot(id, slot) ?: return
    known[key(id, digSlot)]?.takeIf { it.status == HOST_RUNNING }?.let { server ->
      runCatching { connect(server) }
        .onFailure { logger.info("Minecraft server {} not reconnected: {}", server.key, it.message) }
    }
  }

  /**
   * Its first player is on it: it connects to Archipelago, through the relay that leads to its session's game server.
   * Only a slot hosted directly (one the gateway starts a server for) gets its password sent, whatever server the
   * minecraft service names.
   */
  private suspend fun connect(server: HostServer) {
    val id = sessionOfKey(server.key) ?: return
    val slot = slotsOf(id).firstOrNull { key(id, it) == server.key } ?: return
    if (slot.name !in rules(id).direct) return logger.info("Minecraft server {} isn't hosted directly: not connected", printable(server.key))
    val password = passwords(id)[slot.name]
      ?: return logger.warn("No password for {}'s {}: not connected", id, printable(slot.name))
    // The password in double quotes (the mod's argument); the passwords made here have no quotes or backslashes
    val line = "connect 127.0.0.1 ${server.relay} \"$password\""
    val sent = http.post("$api/servers/${server.key}/command") {
      auth()
      contentType(ContentType.Application.Json)
      setBody(HostJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("line" to JsonPrimitive(line)))))
    }
    logger.info("Minecraft server {} connects to Archipelago as {}: {}", server.key, printable(slot.name), sent.status)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.auth() = bearerAuth(token)
}
