package net.eratiem.randogeddon.server.config

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.http.ContentType
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sse.SSE
import net.eratiem.randogeddon.server.RandogeddonTracker
import net.eratiem.randogeddon.server.ap.TrackerService
import net.eratiem.randogeddon.shared.ApiJson
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.koin.plugin.module.dsl.withConfiguration

fun Application.installs(trackerConfig: TrackerConfig) {
  install(Koin) {
    slf4jLogger()
    modules(module {
      single { trackerConfig }
    })
    withConfiguration<RandogeddonTracker>()
  }

  install(ContentNegotiation) { json(ApiJson) }
  install(SSE)
  // ETag/Last-Modified for the static files, so the browser revalidates the web app instead of downloading it again
  install(ConditionalHeaders)
  // The web app is ~11 MB uncompressed (mostly Compose's Skia engine); gzip brings it to roughly a third. The live
  // stream must not be compressed, or the events are held back in the compressor's buffer.
  install(Compression) {
    gzip {
      excludeContentType(ContentType.Text.EventStream)
    }
  }

  // Follows the room for as long as the application runs
  val tracker by inject<TrackerService>()
  tracker.start(this)
  monitor.subscribe(ApplicationStopping) { tracker.close() }
}
