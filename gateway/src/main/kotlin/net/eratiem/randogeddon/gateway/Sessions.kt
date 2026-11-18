package net.eratiem.randogeddon.gateway

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

/** One session's game server, its directory, and the port it listens on while it runs. */
class SessionGameServer(val id: String, val dir: Path, val server: GameServer, passwordCache: () -> SlotPasswordCache) {
  /** Its slot passwords, made when the first client connects. */
  val passwords: SlotPasswordCache by lazy(passwordCache)

  @Volatile
  var port: Int? = null
    internal set

  val roomJson: Path get() = dir.resolve("room.json")

  /** Started and not stopped: running, about to, or waiting to start again after a crash. */
  val active: Boolean
    get() = server.state.status in setOf(GameServerStatus.STARTING, GameServerStatus.RUNNING, GameServerStatus.CRASHED)
}

/** Why a game server can't start: as many run as the Administrators allow; for the admin panel. */
class ServersBusy(message: String) : Exception(message)

/**
 * The game servers of every session with a seed, one MultiServer each, on a free port. Only so many run at once as
 * [limit] says (the Administrators' setting; 0: no limit). [create] makes a session's game server, [passwords] its
 * slot password cache (from its room.json), [port] picks its port ([freePort]).
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
   * Takes every session directory that has a seed, and starts them: a finished session's server refuses and stays
   * stopped. Beyond the [limit], the rest stay stopped too.
   */
  suspend fun startAll() {
    dirs.ids().filter { dirs.seed(it) != null }.forEach { id ->
      register(id)
      runCatching { start(id) }.onFailure { logger.warn("Not starting {}: {}", id, it.message) }
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
