package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.WebSocketDeflateExtension
import net.eratiem.randogeddon.database.DatabaseConfig
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.SlotPasswordStore
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Application")

/** The `gateway` section of `application.yaml`; every value can be set by an environment variable. */
data class GatewayConfig(val port: Int, val upstream: String, val roomJson: Path, val database: DatabaseConfig) {
  companion object {
    fun from(config: ApplicationConfig): GatewayConfig {
      fun value(key: String) = config.property("gateway.$key").getString()
      return GatewayConfig(
        port = value("port").toInt(),
        upstream = value("upstream"),
        roomJson = Path.of(value("roomJson")),
        database = DatabaseConfig(value("database.url"), value("database.user"), value("database.password")),
      )
    }
  }
}

/** Accepts game clients on [GatewayConfig.port] and relays each one to MultiServer. */
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
  logger.info("Gateway on port {}, relaying to {}", config.port, config.upstream)

  embeddedServer(
    factory = Netty,
    environment = applicationEnvironment { },
    configure = { connector { port = config.port } },
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
    routing {
      // Clients connect to the root ("wss://host:443"); any path is accepted, MultiServer ignores it too
      webSocket("/{path...}") { relay(http, config.upstream, passwords) }
    }
    monitor.subscribe(io.ktor.server.application.ApplicationStopping) {
      http.close()
      database.close()
    }
  }.start(wait = true)
}
