package net.eratiem.archipelago.server.config

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.eratiem.archipelago.server.ap.TrackerService
import net.eratiem.archipelago.server.session.SessionFileWatcher
import net.eratiem.archipelago.shared.ApiJson
import net.eratiem.archipelago.shared.Summary
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val POLL_INTERVAL = 750.milliseconds
private val KEEPALIVE = 15.seconds
private val HASHED = Regex("[0-9a-f]{16,}\\.wasm")

fun Application.routings(trackerConfig: TrackerConfig) {
  val tracker by inject<TrackerService>()
  val sessionFile by inject<SessionFileWatcher>()
  val summaries = SummaryCache(tracker, sessionFile, trackerConfig)

  routing {
    get("/api/state") {
      call.respondText(summaries.json(), ContentType.Application.Json)
    }
    get("/api/player/{slot}") {
      val slot = call.parameters["slot"]?.toIntOrNull()
        ?: return@get call.respond(HttpStatusCode.BadRequest)
      val detail = tracker.read { playerDetail(slot) }
        ?: return@get call.respond(HttpStatusCode.NotFound)
      call.respond(detail)
    }
    // Pushes the whole summary whenever the state changes. Polled and therefore debounced: a burst of item sends (e.g.
    // a release) becomes one update.
    sse("/api/stream") {
      var sentVersion = -1L
      var idle = 0.milliseconds
      while (true) {
        val version = summaries.version()
        if (version != sentVersion) {
          sentVersion = version
          send(ServerSentEvent(data = summaries.json(), event = "state"))
          idle = 0.milliseconds
        } else if (idle >= KEEPALIVE) {
          send(ServerSentEvent(comments = "keepalive"))
          idle = 0.milliseconds
        }
        delay(POLL_INTERVAL)
        idle += POLL_INTERVAL
      }
    }
    get("/healthz") {
      call.respondText("""{"ok":true,"connected":${tracker.read { connected }}}""", ContentType.Application.Json)
    }

    // The web app. The .wasm files are named after their content hash and never change; everything else is
    // revalidated (ETag/Last-Modified), so a new build is picked up on the next load
    staticFiles("/", trackerConfig.webDir.toFile(), index = "index.html") {
      cacheControl { file ->
        if (HASHED.matches(file.name)) listOf(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public))
        else listOf(CacheControl.NoCache(null))
      }
      contentType { file ->
        when (file.extension) {
          "wasm" -> ContentType("application", "wasm")
          "webmanifest" -> ContentType("application", "manifest+json")
          else -> null
        }
      }
    }
  }
}

/** The serialized summary, built at most once per state version however many dashboards are open. */
private class SummaryCache(
  private val tracker: TrackerService,
  private val sessionFile: SessionFileWatcher,
  private val config: TrackerConfig,
) {
  private val mutex = Mutex()
  private var version = -1L
  private var people: Map<String, List<String>> = emptyMap()
  private var json = ""

  /** Changes with the session state and with `session.yaml`. */
  suspend fun version(): Long = tracker.read { version } + sessionFile.people().hashCode().toLong().shl(32)

  suspend fun json(): String = mutex.withLock {
    val currentPeople = sessionFile.people()
    val summary: Summary? = tracker.read {
      if (version == this@SummaryCache.version && currentPeople == people) null
      else summary(config.title, tracker.lastError, currentPeople, config.pointHints, config.autoHints)
    }
    people = currentPeople
    if (summary != null) {
      version = summary.version
      json = ApiJson.encodeToString(Summary.serializer(), summary)
    }
    json
  }
}
