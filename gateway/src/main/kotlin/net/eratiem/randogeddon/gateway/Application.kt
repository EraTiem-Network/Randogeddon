package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.database.DEFAULT_GAME_SERVERS
import net.eratiem.randogeddon.database.DEFAULT_IDLE_MINUTES
import net.eratiem.randogeddon.database.DatabaseConfig
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.SessionEventStore
import net.eratiem.randogeddon.database.SessionFinishStore
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.SessionStore
import net.eratiem.randogeddon.database.SessionTimeStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.legacySessionId
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.GameServerStatus
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import kotlin.time.Duration.Companion.minutes

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Application")

/** The `gateway` section of `application.yaml`; every value can be set by an environment variable. */
data class GatewayConfig(
  val port: Int,
  /** Game clients connect to `<session-id>.<baseDomain>`; empty: only by path (`ws://host:port/<session-id>`). */
  val baseDomain: String,
  val database: DatabaseConfig,
  val internalPort: Int,
  val internalToken: String,
  val gameServer: GameServerSettings,
) {
  companion object {
    fun from(config: ApplicationConfig): GatewayConfig {
      fun value(key: String) = config.property("gateway.$key").getString()
      fun server(key: String) = value("gameServer.$key")
      return GatewayConfig(
        port = value("port").toInt(),
        baseDomain = value("baseDomain").trim().trim('.'),
        database = DatabaseConfig(value("database.url"), value("database.user"), value("database.password")),
        internalPort = value("internal.port").toInt(),
        internalToken = value("internal.token"),
        gameServer = GameServerSettings(
          apRoot = Path.of(server("apRoot")),
          python = server("python"),
          dataDir = Path.of(server("dataDir")),
          customWorlds = Path.of(server("customWorlds")),
          adminPassword = server("adminPassword"),
          generationMinutes = server("generationMinutes").toInt(),
          generationsAtOnce = generationsAtOnce(server("generationsAtOnce").toIntOrNull() ?: 0),
          minecraftApi = server("minecraftApi").trim().trimEnd('/'),
          minecraftEula = server("minecraftEula").trim().equals("true", ignoreCase = true),
        ),
      )
    }
  }
}

/** Where Archipelago is (one directory per release under [apRoot]) and how MultiServer runs. */
data class GameServerSettings(
  val apRoot: Path,
  val python: String,
  val dataDir: Path,
  /** The apworlds put on the server by hand, for every release (read-only). */
  val customWorlds: Path,
  val adminPassword: String,
  /** How long a generation may take before it is stopped. */
  val generationMinutes: Int,
  /** How many Generate.py run at once (seeds and spoiler logs' later levels together). */
  val generationsAtOnce: Int,
  /** The minecraft service's API (`http://minecraft:8002`); empty: no Minecraft Dig server is hosted. */
  val minecraftApi: String = "",
  /** The operator accepted Mojang's EULA for every hosted Minecraft server (`MINECRAFT_EULA=true`); else none runs. */
  val minecraftEula: Boolean = false,
)

/**
 * Runs one MultiServer per session and accepts game clients on [GatewayConfig.port], relaying each one to its
 * session's; the tracker controls them on [GatewayConfig.internalPort].
 */
/** How long the gateway waits at its start for its database login and the tables (the tracker makes both). */
private val DATABASE_WAIT = 2.minutes

fun main() {
  val config = GatewayConfig.from(ApplicationConfig("application.yaml"))
  val options = config.gameServer
  // Only reads (and counts the session time); the tracker migrates and makes this login. On a new database that
  // happens a moment after both started: wait for it instead of failing and starting again
  val database = RandogeddonDatabase.connect(config.database, migrate = false, poolSize = 3, waitFor = DATABASE_WAIT)
  val passwordStore = SlotPasswordStore(database)
  val http = HttpClient(CIO) {
    install(ClientWebSockets) {
      maxFrameSize = Long.MAX_VALUE // the data package can be several MB
      pingIntervalMillis = 30_000
      extensions { install(WebSocketDeflateExtension) }
    }
  }
  // The session time: how long the game server ran for each seed, without the times it was stopped
  val times = SessionTimeStore(database)
  val background = CoroutineScope(SupervisorJob())
  // Separate, so the shutdown can wait for the last counts
  val counts = CoroutineScope(SupervisorJob())
  fun count(block: suspend () -> Unit) = counts.launch {
    runCatching { block() }.onFailure { logger.warn("Could not count the session time: {}", it.message) }
  }
  val archipelago = Archipelago(options.apRoot, options.python)
  // The apworld library: uploads per release in the data directory, plus the hand-placed ones
  val apworlds = Apworlds(archipelago, options.dataDir.resolve("apworlds"), options.customWorlds)
  val finishes = SessionFinishStore(database)
  val sessionStore = SessionStore(database)
  val dirs = SessionDirs(options.dataDir)
  // The files of one session at a time, from before, into sessions/<id>/
  dirs.migrate { seed -> runBlocking { runCatching { sessionStore.idOf(seed) }.getOrNull() } ?: legacySessionId(seed) }
  val setups = SessionSetupStore(database)
  val launcher = Launcher(archipelago, setups, options.adminPassword)
  // How many game servers may run at once and when one sleeps (the Administrators' settings), read at most every 30 s
  val settings = AppSettingsStore(database)
  val gameServers = Cached(30.seconds) { runCatching { settings.gameServers() }.getOrDefault(DEFAULT_GAME_SERVERS) }
  val idleMinutes = Cached(30.seconds) { runCatching { settings.idleMinutes() }.getOrDefault(DEFAULT_IDLE_MINUTES) }
  // A session's: kept running, its hosted slots. Unknown (the database didn't answer): it keeps running
  suspend fun rulesOf(session: SessionGameServer): SleepRules =
    readSeedName(session.roomJson)?.let { seed -> runCatching { setups.idleRules(seed) }.getOrElse { return SleepRules(keepRunning = true) } }
      ?.let { SleepRules(it.keepRunning, it.hosted) } ?: SleepRules()
  // What happens on the game servers, heard here and kept for the tracker
  val events = SessionEvents(SessionEventStore(database), http, background)
  // Each session's logic (the available checks), running with its game server and a while after
  val logic = Logic(archipelago, dirs, apworlds, background)
  val sessions = Sessions(
    dirs, gameServers::get,
    create = { id, dir, port ->
      val roomJson = dir.resolve("room.json")
      GameServer(
        GameServerConfig(
          dataDir = dir,
          launch = { seed -> launcher.launch(seed, roomJson, port()) },
          prepare = { seed -> launcher.prepare(seed, roomJson) },
          // Counted under the seed's name from room.json (written right before the start), like the tracker reads it
          onRunning = { _ ->
            readSeedName(roomJson)?.let { seed -> count { times.started(seed) } }
            events.started(id, port(), roomJson)
            logic.serverRunning(id)
          },
          onEnded = { _ ->
            readSeedName(roomJson)?.let { seed -> count { times.stopped(seed) } }
            events.ended(id)
            logic.serverEnded(id)
          },
          // A finished session stays stopped, also after a gateway restart, until it is reopened
          refuse = { seed ->
            runBlocking { finishes.of(seedNameOf(seed)) }?.finishedAt?.let { "its session is finished (reopen it in the admin panel)" }
          },
        ),
        background,
      )
    },
    passwords = { roomJson -> SlotPasswordCache(passwordStore, roomJson) },
  )
  // MultiServer and Generate.py load the apworlds from each release's custom_worlds/
  runBlocking { apworlds.syncAll() }
  // The games of each release, listed ahead (it loads every world), so the admin panel doesn't wait for it
  background.launch { archipelago.versions().forEach { runCatching { apworlds.release(it) } } }
  // Counted before a kill of the gateway stays; the stretch after the last count is unknown and dropped
  runBlocking { runCatching { times.dropOpenCounts() } }
  background.launch {
    while (true) {
      delay(30.seconds)
      sessions.all().filter { it.server.state.status == GameServerStatus.RUNNING }.forEach { session ->
        readSeedName(session.roomJson)?.let { seed -> count { times.tick(seed) } }
      }
    }
  }
  // Game servers nobody played on for a while go to sleep; a player's connection starts them again
  background.launch {
    while (true) {
      delay(1.minutes)
      runCatching { sessions.sweep(idleMinutes.get(), ::rulesOf) }
        .onSuccess { if (it.isNotEmpty()) logger.info("Asleep, nobody played: {}", it.joinToString()) }
        .onFailure { logger.warn("Could not put idle game servers to sleep: {}", it.message) }
      // A logic stays as long again after its game server ended
      runCatching { logic.sweep(idleMinutes.get()) }
        .onSuccess { if (it.isNotEmpty()) logger.info("Logic ended, its game server sleeps: {}", it.joinToString()) }
    }
  }
  // The Minecraft Dig slots' servers, run by the minecraft service
  val minecraft = Minecraft(
    http, options.minecraftApi, config.internalToken, dirs, eula = { options.minecraftEula },
    passwords = { id -> sessions.get(id)?.passwords?.current().orEmpty() }, idleMinutes = { idleMinutes.get() }, scope = background,
  )
  // Seeds are generated with a part of their spoiler log; the rest is completed in the background, after them
  val generator = Generator(archipelago, dirs, apworlds, options.generationMinutes, options.generationsAtOnce)
  val uploadedSeeds = UploadedSeeds(archipelago, dirs, options.dataDir)
  apworlds.beforeChange = generator::yieldBackground
  background.launch { generator.completeSpoilers() }
  val connections = ConnectionLimits()
  if (config.internalToken.isBlank()) logger.warn("No INTERNAL_TOKEN: the admin panel can't control the game servers")
  logger.info(
    "Gateway on port {} ({}), Archipelago {}, up to {} game servers at once (0: no limit), asleep after {} min without players " +
      "(0: never), {} generations at once",
    config.port, if (config.baseDomain.isNotBlank()) "<session>.${config.baseDomain}" else "ws://<host>:${config.port}/<session>",
    archipelago.versions().joinToString(), runBlocking { gameServers.get() }, runBlocking { idleMinutes.get() },
    options.generationsAtOnce,
  )

  embeddedServer(
    factory = Netty,
    environment = applicationEnvironment { },
    configure = {
      connector { port = config.port }
      connector { port = config.internalPort }
    },
  ) {
    // Behind Traefik: the host the player connected to (X-Forwarded-Host) and their address (for the log)
    install(XForwardedHeaders) {
      // The last address is the one Traefik saw; earlier ones in the header come from the client and can be made up
      useLastProxy()
    }
    gameClientWebSockets()
    install(ContentNegotiation) { json(ApiJson) }
    routing {
      // The port the connection came in on: port() would take the one the client asked for (Host header,
      // X-Forwarded-Port), which is 443 behind Traefik, and could name the internal port
      localPort(config.port) {
        // wss://<session>.<domain>:443 behind Traefik, ws://host:38281/<session> without; with one session running,
        // a client that names none gets that one
        webSocket("/{path...}") {
          val path = call.parameters.getAll("path").orEmpty()
          // Randogeddon Bridge's tunnel to a Minecraft Dig slot's server: it starts no game server (the Minecraft
          // server's own connection does), only the slot's password and an open slot let it in
          minecraftPath(path)?.let { (sessionPath, slot) ->
            val tunnelId = sessionIdOf(call.request.origin.serverHost, sessionPath, config.baseDomain)
            val tunnelSession = tunnelId?.let { sessions.get(it) }
            if (tunnelSession == null || !minecraft.enabled) {
              close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "No Minecraft server here"))
            } else if (!tunnelAllowed(call.request.headers[SLOT_PASSWORD_HEADER], slot, tunnelSession.passwords::current, tunnelSession.passwords::locked)) {
              logger.info("Refused a Minecraft tunnel from {} to {}'s {}", call.request.origin.remoteHost, tunnelSession.id, printable(slot))
              close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Wrong password or a locked slot"))
            } else {
              minecraftTunnel(this, minecraft, tunnelSession.id, slot)
            }
            return@webSocket
          }
          val id = sessionIdOf(call.request.origin.serverHost, path, config.baseDomain)
          val session = if (id != null) sessions.get(id) else sessions.onlyActive()
          // A sleeping game server starts for its player (if there's room), who waits meanwhile
          if (session != null && session.server.state.status in setOf(GameServerStatus.IDLE, GameServerStatus.STARTING)) {
            val woke = try {
              sessions.wake(session.id)
            } catch (_: ServersBusy) {
              close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Every game server is in use: try again later"))
              return@webSocket
            }
            if (!woke) {
              close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "The game server didn't start: try again in a moment"))
              return@webSocket
            }
          }
          val port = session?.port?.takeIf { session.active }
          if (session == null || port == null) {
            val why = if (id == null) "Which session? Connect to <session>.<domain>" else "Session $id isn't running"
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, why))
            return@webSocket
          }
          // The gateway listens before a player is in, so the tracker hears of this one too
          events.awaitListening(session.id)
          // A player keeps it awake; a hosted slot's connection doesn't
          serve(http, "ws://localhost:$port", session.passwords::current, session.passwords::locked, connections) { slot ->
            if (slot in rulesOf(session).hosted) null else session.joined()
          }
        }
      }
      // Never published or routed by Traefik: only the tracker reaches it, inside the compose network
      localPort(config.internalPort) {
        internalApi(config.internalToken, sessions, archipelago, generator, apworlds, dirs, uploadedSeeds, logic, minecraft)
      }
    }
    monitor.subscribe(ApplicationStopping) {
      // MultiServer saves on /exit; a container stop gives the gateway the time for that. Then the last counts.
      logic.stopAll()
      runBlocking {
        sessions.stopAll()
        counts.coroutineContext[Job]?.children?.forEach { it.join() }
      }
      http.close()
      database.close()
    }
  }.also { runBlocking { sessions.sleepAll() } }.start(wait = true)
}

/**
 * Routes for connections that came in on [port] (the server's own port). Ktor's `port()` takes the port the client
 * asked for instead (Host header, X-Forwarded-Port): 443 behind Traefik, or whatever a client writes there.
 */
private fun Route.localPort(port: Int, build: Route.() -> Unit): Route = createChild(LocalPortSelector(port)).apply(build)

private class LocalPortSelector(private val port: Int) : RouteSelector() {
  override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
    if (context.call.request.local.localPort == port) RouteSelectorEvaluation.Constant else RouteSelectorEvaluation.Failed

  override fun toString() = "(local port $port)"
}
