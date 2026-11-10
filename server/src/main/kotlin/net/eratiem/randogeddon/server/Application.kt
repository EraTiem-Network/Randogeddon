package net.eratiem.randogeddon.server

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.config.installs
import net.eratiem.randogeddon.server.config.routings
import net.eratiem.randogeddon.server.util.logger
import org.koin.core.annotation.KoinApplication
import java.lang.invoke.MethodHandles

/** Starts the tracker: the dashboard and its API on [TrackerConfig.port], following the room in the background. */
fun main() {
  val config = ApplicationConfig("application.yaml")
  val trackerConfig = TrackerConfig.from(config)

  logger.info("Dashboard on port {}, following {}", trackerConfig.port, trackerConfig.serverUrl)

  embeddedServer(
    factory = Netty,
    environment = applicationEnvironment { this.config = config },
    configure = { connector { port = trackerConfig.port } },
    module = { module(trackerConfig) },
  ).start(wait = true)
}

/** Named after this file's class (`net.eratiem.randogeddon.server.ApplicationKt`), so it follows moves and renames. */
private val logger = logger(MethodHandles.lookup().lookupClass())

@KoinApplication
object RandogeddonTracker

fun Application.module(trackerConfig: TrackerConfig) {
  installs(trackerConfig)
  routings(trackerConfig)
}
