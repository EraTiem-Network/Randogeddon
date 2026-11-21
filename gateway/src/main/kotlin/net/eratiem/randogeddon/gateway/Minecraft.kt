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
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Minecraft")

private val HostJson = Json { ignoreUnknownKeys = true }

/** A seed's Minecraft Dig patch: `AP_<seed>_P<n>_<slot>.apmcdig`. */
private val DIG_PATCH = Regex("""^AP_[^_]+_P(\d+)_(.+)\.apmcdig$""")

/** One Minecraft Dig slot of a seed: its number, its name, its patch's `data.json`. */
internal data class DigSlot(val number: Int, val name: String, val data: String)

/** The Minecraft Dig slots of the seed zip [seed], from their patches (each a zip with its `data.json`). */
internal fun digSlots(seed: Path): List<DigSlot> = buildList {
  ZipInputStream(seed.inputStream()).use { zip ->
    while (true) {
      val entry = zip.nextEntry ?: break
      val match = DIG_PATCH.matchEntire(entry.name.substringAfterLast('/')) ?: continue
      val data = ZipInputStream(zip.readBytes().inputStream()).use { patch ->
        generateSequence { patch.nextEntry }.firstOrNull { it.name == "data.json" }?.let { patch.readBytes().decodeToString() }
      } ?: continue
      add(DigSlot(match.groupValues[1].toInt(), match.groupValues[2], data))
    }
  }
}.sortedBy { it.number }

/** What the Minecraft service says of one server. */
@Serializable
internal data class HostServer(
  val key: String,
  val status: String,
  val since: Long? = null,
  val port: Int = 0,
  val relay: Int = 0,
  val players: List<String> = emptyList(),
  val session: String? = null,
)

@Serializable
internal data class HostServers(val servers: List<HostServer> = emptyList())

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
  private val token: String,
  private val dirs: SessionDirs,
  /** Mojang's EULA accepted (the Administrators' setting). */
  private val eula: suspend () -> Boolean,
  /** The slot passwords of session `id`, by slot name. */
  private val passwords: suspend (String) -> Map<String, String>,
  private val idleMinutes: suspend () -> Int,
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

  init {
    if (api.isNotBlank()) scope.launch {
      while (true) {
        runCatching { look() }.onFailure { logger.debug("The minecraft service didn't answer: {}", it.message) }
        delay(3.seconds)
      }
    }
  }

  val enabled: Boolean get() = api.isNotBlank()

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

  /** Session [id]'s Minecraft Dig slots and their servers. */
  fun servers(id: String): MinecraftServers = MinecraftServers(slotsOf(id).map { slot ->
    val key = key(id, slot)
    val host = known[key]
    val status = when (host?.status) {
      "starting" -> MinecraftStatus.STARTING
      "running" -> MinecraftStatus.RUNNING
      "crashed" -> MinecraftStatus.CRASHED
      else -> MinecraftStatus.ASLEEP
    }
    MinecraftServerView(slot.name, status, host?.since, host?.players.orEmpty(), problems[key])
  })

  /** Where session [id]'s slot [slot]'s Minecraft server listens (host, port) while it runs; `null`: it doesn't. */
  fun address(id: String, slot: String): Pair<String, Int>? {
    val digSlot = slotsOf(id).firstOrNull { it.name == slot } ?: return null
    val host = known[key(id, digSlot)]?.takeIf { it.status == "running" } ?: return null
    return api.substringAfter("://").substringBefore(':') to host.port
  }

  /** Starts session [id]'s slot [slot]'s server (its files for the seed first); `null`, or why it can't. */
  suspend fun start(id: String, slot: String): String? {
    if (!enabled) return "No Minecraft is hosted here"
    val digSlot = slotsOf(id).firstOrNull { it.name == slot } ?: return "$slot isn't a Minecraft Dig slot of this session"
    val key = key(id, digSlot)
    if (known[key]?.status in setOf("starting", "running")) return null
    if (!eula()) return "Mojang's EULA isn't accepted (an Administrator's setting)".also { problems[key] = it }
    val seed = dirs.seed(id)?.name ?: return "it has no seed"
    val files = buildJsonText(id, digSlot, seed)
    val put = http.put("$api/servers/$key") { auth(); contentType(ContentType.Application.Json); setBody(files) }
    if (!put.status.isSuccess()) return "The minecraft service answered ${put.status}".also { problems[key] = it }
    val started = http.post("$api/servers/$key/start") { auth() }
    if (!started.status.isSuccess()) {
      val why = runCatching { HostJson.parseToJsonElement(started.bodyAsText()) as JsonObject }.getOrNull()
        ?.get("message")?.jsonPrimitive?.content ?: "the minecraft service answered ${started.status}"
      problems[key] = why
      return why
    }
    problems.remove(key)
    lastPlayer[key] = System.currentTimeMillis()
    logger.info("Minecraft server of {}'s {} starting", id, slot)
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
    val digSlot = slotsOf(id).firstOrNull { it.name == slot } ?: return false
    return http.post("$api/servers/${key(id, digSlot)}/stop") { auth() }.status.isSuccess()
  }

  /** Session [id]'s Minecraft Dig slots' server logs, their lines numbered above [since], by slot. */
  suspend fun log(id: String, since: Long): MinecraftLogs = MinecraftLogs(slotsOf(id).associate { slot ->
    val text = runCatching { http.get("$api/servers/${key(id, slot)}/log?since=$since") { auth() } }.getOrNull()
      ?.takeIf { it.status.isSuccess() }?.bodyAsText()
    slot.name to (text?.let { HostJson.decodeFromString(LogLines.serializer(), it) } ?: LogLines(emptyList(), since))
  })

  /** Session [id] is deleted: its servers and their worlds too. */
  suspend fun remove(id: String) {
    if (!enabled) return
    known.keys.filter { it.startsWith("$id-p") }.forEach { key ->
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
        if (before?.players.isNullOrEmpty()) connect(server)
      }
      val since = lastPlayer.getOrPut(server.key) { now }
      if (server.status == "running" && server.players.isEmpty() && idle > 0 && now - since >= idle.minutes.inWholeMilliseconds) {
        logger.info("Minecraft server {} sleeps: no player for {} min", server.key, idle)
        http.post("$api/servers/${server.key}/stop") { auth() }
      }
    }
  }

  /** Its first player is on it: it connects to Archipelago, through the relay that leads to its session's game server. */
  private suspend fun connect(server: HostServer) {
    val id = server.session ?: return
    val slot = slotsOf(id).firstOrNull { key(id, it) == server.key } ?: return
    val password = passwords(id)[slot.name] ?: return logger.warn("No password for {}'s {}: not connected", id, slot.name)
    // The password in double quotes (the mod's argument); the passwords made here have no quotes or backslashes
    val line = "connect 127.0.0.1 ${server.relay} \"$password\""
    val sent = http.post("$api/servers/${server.key}/command") {
      auth()
      contentType(ContentType.Application.Json)
      setBody(HostJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("line" to JsonPrimitive(line)))))
    }
    logger.info("Minecraft server {} connects to Archipelago as {}: {}", server.key, slot.name, sent.status)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.auth() = bearerAuth(token)
}
