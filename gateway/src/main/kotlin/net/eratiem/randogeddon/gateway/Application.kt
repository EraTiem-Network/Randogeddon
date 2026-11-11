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
import io.ktor.server.routing.port
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.WebSocketDeflateExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.DatabaseConfig
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.SessionTimeStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.GameServerStatus
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Application")

/** The `gateway` section of `application.yaml`; every value can be set by an environment variable. */
data class GatewayConfig(
  val port: Int,
  val upstream: String,
  val roomJson: Path,
  val database: DatabaseConfig,
  val internalPort: Int,
  val internalToken: String,
  /** `null`: MultiServer runs elsewhere (development against `upstream`), the gateway only relays. */
  val gameServer: MultiServerOptions?,
) {
  companion object {
    fun from(config: ApplicationConfig): GatewayConfig {
      fun value(key: String) = config.property("gateway.$key").getString()
      fun server(key: String) = value("gameServer.$key")
      return GatewayConfig(
        port = value("port").toInt(),
        upstream = value("upstream"),
        roomJson = Path.of(value("roomJson")),
        database = DatabaseConfig(value("database.url"), value("database.user"), value("database.password")),
        internalPort = value("internal.port").toInt(),
        internalToken = value("internal.token"),
        gameServer = if (server("enabled").trim().lowercase() in setOf("0", "false", "no", "off")) null else MultiServerOptions(
          apHome = Path.of(server("apHome")),
          python = server("python"),
          dataDir = Path.of(server("dataDir")),
          port = server("port").toInt(),
          hintCost = server("hintCost"),
          locationCheckPoints = server("locationCheckPoints"),
          releaseMode = server("releaseMode"),
          collectMode = server("collectMode"),
          remainingMode = server("remainingMode"),
          countdownMode = server("countdownMode"),
          adminPassword = server("adminPassword"),
        ),
      )
    }
  }
}

/** MultiServer's options, as the archipelago container's entrypoint passed them before. */
data class MultiServerOptions(
  val apHome: Path,
  val python: String,
  val dataDir: Path,
  val port: Int,
  val hintCost: String,
  val locationCheckPoints: String,
  val releaseMode: String,
  val collectMode: String,
  val remainingMode: String,
  val countdownMode: String,
  /** For `!admin` remote commands; empty: disabled. */
  val adminPassword: String,
) {
  /** serve.py = MultiServer plus the hint switches (AP_POINT_HINTS, AP_AUTO_HINTS, read from the environment). */
  fun command(seed: Path): List<String> = buildList {
    addAll(listOf(python, "serve.py", seed.absolutePathString(), "--host", "0.0.0.0", "--port", port.toString()))
    addAll(listOf("--hint_cost", hintCost, "--location_check_points", locationCheckPoints))
    addAll(listOf("--release_mode", releaseMode, "--collect_mode", collectMode))
    addAll(listOf("--remaining_mode", remainingMode, "--countdown_mode", countdownMode))
    addAll(listOf("--auto_shutdown", "0", "--logtime"))
    if (adminPassword.isNotBlank()) addAll(listOf("--server_password", adminPassword))
  }

  /** The slot list for the tracker and the slot passwords (room_info.py, next to serve.py). */
  fun writeRoomJson(seed: Path, roomJson: Path) {
    val proc = ProcessBuilder(python, "room_info.py", seed.absolutePathString(), roomJson.absolutePathString())
      .directory(apHome.toFile()).redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader().readText()
    check(proc.waitFor() == 0) { "room_info.py failed: ${output.trim().lines().lastOrNull()}" }
  }
}

/**
 * Accepts game clients on [GatewayConfig.port] and relays each one to MultiServer, which it runs itself; the tracker
 * controls that on [GatewayConfig.internalPort].
 */
fun main() {
  val config = GatewayConfig.from(ApplicationConfig("application.yaml"))
  // Only reads; the tracker migrates
  val database = RandogeddonDatabase.connect(config.database, migrate = false, poolSize = 3)
  val passwords = SlotPasswordCache(SlotPasswordStore(database), config.roomJson)
  val http = HttpClient(CIO) {
    install(ClientWebSockets) {
      maxFrameSize = Long.MAX_VALUE // the data package can be several MB
      pingIntervalMillis = 30_000
      extensions { install(WebSocketDeflateExtension) }
    }
  }
  val options = config.gameServer
  // The session time: how long the game server ran for each seed, without the times it was stopped
  val times = SessionTimeStore(database)
  val background = CoroutineScope(SupervisorJob())
  // Separate, so the shutdown can wait for the last counts
  val counts = CoroutineScope(SupervisorJob())
  fun count(block: suspend () -> Unit) = counts.launch {
    runCatching { block() }.onFailure { logger.warn("Could not count the session time: {}", it.message) }
  }
  val gameServer = options?.let {
    GameServer(
      GameServerConfig(
        dataDir = it.dataDir,
        command = it::command,
        prepare = { seed -> it.writeRoomJson(seed, config.roomJson) },
        workDir = it.apHome,
        // Counted under the seed's name from room.json (written right before the start), like the tracker reads it
        onRunning = { _ -> readSeedName(config.roomJson)?.let { seed -> count { times.started(seed) } } },
        onEnded = { _ -> readSeedName(config.roomJson)?.let { seed -> count { times.stopped(seed) } } },
      ),
      background,
    )
  }
  if (gameServer != null) {
    // Counted before a kill of the gateway stays; the stretch after the last count is unknown and dropped
    runBlocking { runCatching { times.dropOpenCounts() } }
    background.launch {
      while (true) {
        delay(30.seconds)
        val state = gameServer.state
        if (state.status == GameServerStatus.RUNNING) readSeedName(config.roomJson)?.let { seed -> count { times.tick(seed) } }
      }
    }
  }
  if (config.internalToken.isBlank()) logger.warn("No INTERNAL_TOKEN: the admin panel can't control the game server")
  logger.info(
    "Gateway on port {}, relaying to {}; {}", config.port, config.upstream,
    if (gameServer != null) "running MultiServer from ${options.apHome}" else "MultiServer runs elsewhere",
  )

  embeddedServer(
    factory = Netty,
    environment = applicationEnvironment { },
    configure = {
      connector { port = config.port }
      connector { port = config.internalPort }
    },
  ) {
    // Behind Traefik: the players' addresses (for the log) come from X-Forwarded-For
    install(XForwardedHeaders) {
      // The last address is the one Traefik saw; earlier ones in the header come from the client and can be made up
      useLastProxy()
    }
    install(WebSockets) {
      maxFrameSize = Long.MAX_VALUE
      pingPeriod = 30.seconds
      extensions { install(WebSocketDeflateExtension) }
    }
    install(ContentNegotiation) { json(ApiJson) }
    routing {
      port(config.port) {
        // Clients connect to the root ("wss://host:443"); any path is accepted, MultiServer ignores it too
        webSocket("/{path...}") { relay(http, config.upstream, passwords) }
      }
      // Never published or routed by Traefik: only the tracker reaches it, inside the compose network
      if (gameServer != null) port(config.internalPort) { internalApi(config.internalToken, gameServer) }
    }
    monitor.subscribe(ApplicationStopping) {
      // MultiServer saves on /exit; a container stop gives the gateway the time for that. Then the last count.
      runBlocking {
        gameServer?.stop()
        counts.coroutineContext[Job]?.children?.forEach { it.join() }
      }
      http.close()
      database.close()
    }
  }.also { runBlocking { gameServer?.start() } }.start(wait = true)
}
