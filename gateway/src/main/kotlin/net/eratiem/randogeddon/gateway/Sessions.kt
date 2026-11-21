package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.SessionIds
import net.eratiem.randogeddon.shared.SessionServer
import net.eratiem.randogeddon.shared.SessionServers
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Sessions")

/**
 * A port for a game server to listen on: [last] (the one it had) if nothing else listens there, else one the system
 * gives out; never one of [taken] (those of the others running). The ports are the gateway's own: game clients come
 * through its relay, and only the tracker reaches MultiServer directly, inside the compose network.
 */
fun freePort(last: Int?, taken: Set<Int>): Int {
  if (last != null && last !in taken && runCatching { ServerSocket(last).close() }.isSuccess) return last
  repeat(20) {
    val port = ServerSocket(0).use { it.localPort }
    if (port !in taken) return port
  }
  error("No free port for a game server")
}

/**
 * The session a game client wants: the first label of the host it connected to (`<id>.<baseDomain>`, behind Traefik),
 * or the first part of the path (`ws://localhost:38281/<id>`, locally). `null`: neither names one.
 */
fun sessionIdOf(host: String?, path: List<String>, baseDomain: String): String? {
  val name = host?.substringBefore(':')?.lowercase()
  if (baseDomain.isNotBlank() && name != null && name.endsWith(".${baseDomain.lowercase()}")) {
    name.removeSuffix(".${baseDomain.lowercase()}").takeIf { '.' !in it && SessionIds.valid(it) }?.let { return it }
  }
  return path.firstOrNull { it.isNotEmpty() }?.lowercase()?.takeIf(SessionIds::valid)
}

/**
 * Whether a game server goes to sleep: it runs, nobody plays ([players]), it has for [idleFor] at least
 * [idleMinutes] (0: never), and an Administrator doesn't keep it running ([keepRunning]).
 */
fun sleepsNow(status: GameServerStatus, players: Int, idleFor: Duration, idleMinutes: Int, keepRunning: Boolean): Boolean =
  status == GameServerStatus.RUNNING && players == 0 && idleMinutes > 0 && !keepRunning && idleFor >= idleMinutes.minutes

/**
 * What decides a session's sleep, from the database: kept running, and its hosted slots (they aren't players); of
 * them, the [direct] ones, whose Minecraft server the gateway runs.
 */
data class SleepRules(val keepRunning: Boolean = false, val hosted: Set<String> = emptySet(), val direct: Set<String> = emptySet())

/** One session's game server, its directory, and the port it listens on while it runs. */
class SessionGameServer(val id: String, val dir: Path, val server: GameServer, passwordCache: () -> SlotPasswordCache) {
  /** Its slot passwords, made when the first client connects. */
  val passwords: SlotPasswordCache by lazy(passwordCache)

  @Volatile
  var port: Int? = null
    internal set

  val roomJson: Path get() = dir.resolve("room.json")

  private val playing = AtomicInteger()

  /** Game clients connected to one of its slots that isn't hosted, through the relay (not the tracker). */
  val players: Int get() = playing.get()

  /** Since when nobody plays: its start, or the last player leaving. */
  @Volatile
  var idleSince: TimeMark = TimeSource.Monotonic.markNow()
    internal set

  /** A player connected to a slot; the answer is called when they leave. */
  fun joined(): () -> Unit {
    playing.incrementAndGet()
    var left = false
    return {
      if (!left) {
        left = true
        if (playing.decrementAndGet() == 0) idleSince = TimeSource.Monotonic.markNow()
      }
    }
  }

  /** Started and not stopped: running, about to, or waiting to start again after a crash. */
  val active: Boolean
    get() = server.state.status in setOf(GameServerStatus.STARTING, GameServerStatus.RUNNING, GameServerStatus.CRASHED)
}

/** Why a game server can't start: as many run as the Administrators allow; for the admin panel. */
class ServersBusy(message: String) : Exception(message)

/**
 * The game servers of every session with a seed, one MultiServer each, on a free port. Only so many run at once as
 * [limit] says (the Administrators' setting; 0: no limit). [create] makes a session's game server, [passwords] its
 * slot password cache (from its room.json), [port] picks its port ([freePort]). One nobody plays on goes to sleep
 * ([sweep]) and starts again when a player connects ([wake]).
 */
class Sessions(
  private val dirs: SessionDirs,
  private val limit: suspend () -> Int,
  private val create: (id: String, dir: Path, port: () -> Int) -> GameServer,
  private val passwords: (roomJson: Path) -> SlotPasswordCache,
  private val port: (last: Int?, taken: Set<Int>) -> Int = ::freePort,
) {
  private val mutex = Mutex()
  private val sessions = ConcurrentHashMap<String, SessionGameServer>()

  fun get(id: String): SessionGameServer? = sessions[id]

  fun all(): List<SessionGameServer> = sessions.values.sortedBy { it.id }

  fun list(): SessionServers = SessionServers(all().map { SessionServer(it.id, it.server.state, it.port.takeIf { _ -> it.active }) })

  /** The only session that runs, for a client that names none (a local test with one session). */
  fun onlyActive(): SessionGameServer? = all().filter { it.active }.singleOrNull()

  /**
   * Takes every session directory that has a seed; none starts: each sleeps until its first player connects ([wake]),
   * a finished session's stays stopped.
   */
  suspend fun sleepAll() {
    dirs.ids().filter { dirs.seed(it) != null }.forEach { id ->
      val session = mutex.withLock { register(id) }
      runCatching { session.server.sleep("Asleep until a player connects (the gateway started)") }
        .onFailure { logger.warn("Could not put {} to sleep: {}", id, it.message) }
    }
  }

  /**
   * Puts the game servers nobody played on for [idleMinutes] to sleep ([sleepsNow]), but those [rules] keep running;
   * the IDs of those it did.
   */
  suspend fun sweep(idleMinutes: Int, rules: suspend (SessionGameServer) -> SleepRules): List<String> =
    all().filter { sleepsNow(it.server.state.status, it.players, it.idleSince.elapsedNow(), idleMinutes, false) }
      .filter { !rules(it).keepRunning }
      .onEach { it.server.sleep("Asleep: nobody played for $idleMinutes minutes; it starts when a player connects") }
      .map { it.id }

  /**
   * Starts a sleeping game server for a player who connects ([start]: throws [ServersBusy] when there's no room) and
   * waits up to [within] until it accepts clients; `false`: it didn't (it failed, or takes longer). One that doesn't
   * sleep stays as it is (`true` when it runs).
   */
  suspend fun wake(id: String, within: Duration = 60.seconds): Boolean {
    val session = sessions[id] ?: return false
    if (session.server.state.status == GameServerStatus.IDLE) {
      logger.info("Waking {}: a player connects", id)
      start(id)
    }
    val until = TimeSource.Monotonic.markNow() + within
    while (true) {
      when (session.server.state.status) {
        GameServerStatus.RUNNING -> return true
        GameServerStatus.STARTING, GameServerStatus.CRASHED -> if (until.hasPassedNow()) return false
        else -> return false
      }
      delay(250.milliseconds)
    }
  }

  /** A session got its seed (generated): its game server, stopped until [start]. */
  suspend fun added(id: String): SessionGameServer = mutex.withLock { register(id) }

  /** Starts the session's game server on a free port; throws [ServersBusy] when as many run as [limit] allows. */
  suspend fun start(id: String): GameServerState = mutex.withLock {
    val session = sessions[id] ?: throw NoSuchElementException("No session $id")
    if (!session.active) {
      val others = sessions.values.filter { it !== session && it.active }
      val max = limit()
      if (max > 0 && others.size >= max) throw ServersBusy("All $max game servers are in use; finish or stop a session first")
      session.port = port(session.port, others.mapNotNull { it.port }.toSet())
      session.idleSince = TimeSource.Monotonic.markNow()
      session.server.start()
    }
    session.server.state
  }

  suspend fun stop(id: String): GameServerState {
    val session = sessions[id] ?: throw NoSuchElementException("No session $id")
    session.server.stop()
    return session.server.state
  }

  suspend fun restart(id: String): GameServerState {
    stop(id)
    return start(id)
  }

  /** Stops the session's game server and removes its files ([SessionDirs.remove]); `false` if it has none. */
  suspend fun remove(id: String, purge: Boolean): Boolean {
    sessions.remove(id)?.server?.stop()
    return dirs.remove(id, purge)
  }

  /** Every game server saves and ends (the gateway shuts down). */
  suspend fun stopAll() {
    sessions.values.forEach { runCatching { it.server.stop() } }
  }

  private fun register(id: String): SessionGameServer = sessions.getOrPut(id) {
    val dir = dirs.dir(id)
    lateinit var session: SessionGameServer
    val server = create(id, dir) { session.port ?: error("$id has no port") }
    session = SessionGameServer(id, dir, server) { passwords(dir.resolve("room.json")) }
    session
  }
}
