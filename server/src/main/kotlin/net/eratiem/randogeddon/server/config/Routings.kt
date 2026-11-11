package net.eratiem.randogeddon.server.config

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.eratiem.randogeddon.server.account.LoginService
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.ap.TrackerService
import net.eratiem.randogeddon.server.session.CurrentSession
import net.eratiem.randogeddon.database.SessionTime
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.Summary
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val POLL_INTERVAL = 750.milliseconds
private val KEEPALIVE = 15.seconds
private val HASHED = Regex("[0-9a-f]{16,}\\.wasm")

fun Application.routings(trackerConfig: TrackerConfig) {
  val tracker by inject<TrackerService>()
  val logins by inject<LoginService>()
  val slotPasswords by inject<SlotPasswords>()
  val gameServer by inject<GameServerClient>()
  val sessionAdmin by inject<SessionAdmin>()
  val currentSession by inject<CurrentSession>()
  val summaries = SummaryCache(tracker, currentSession, trackerConfig)

  routing {
    loginRoutes(logins)

    // Everything about the session needs a login (login and logout above are outside this branch)
    route("/api") {
      install(RequireLogin) { this.logins = logins }
      sessionRoutes(tracker, summaries)
      meRoute(trackerConfig, slotPasswords)
      adminRoutes(gameServer, sessionAdmin)
    }

    // Nothing about the session: open for the container's healthcheck
    get("/healthz") {
      call.respondText("""{"ok":true}""", ContentType.Application.Json)
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

/** Under `/api`: the session's summary (once and as a live stream) and the details of one world. */
private fun Route.sessionRoutes(tracker: TrackerService, summaries: SummaryCache) {
  get("/state") {
    call.respondText(summaries.json(), ContentType.Application.Json)
  }
  get("/player/{slot}") {
    val slot = call.parameters["slot"]?.toIntOrNull()
      ?: return@get call.respond(HttpStatusCode.BadRequest)
    val detail = tracker.read { playerDetail(slot) }
      ?: return@get call.respond(HttpStatusCode.NotFound)
    call.respond(detail)
  }
  // Pushes the whole summary whenever the state changes. Polled and therefore debounced: a burst of item sends (e.g.
  // a release) becomes one update.
  sse("/stream") {
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
}

/** The serialized summary, built at most once per state version however many dashboards are open. */
private class SummaryCache(
  private val tracker: TrackerService,
  private val session: CurrentSession,
  private val config: TrackerConfig,
) {
  private val mutex = Mutex()
  private var version = -1L
  private var people: Map<String, List<String>> = emptyMap()
  private var time: SessionTime? = null
  private var options: SessionOptions? = null
  private var json = ""

  /** Changes with the session state, with its people and options, and with the session time's count. */
  suspend fun version(): Long = tracker.read { version } + session.people().hashCode().toLong().shl(32) +
    (session.time to session.options).hashCode().toLong().shl(16)

  suspend fun json(): String = mutex.withLock {
    val currentPeople = session.people()
    val currentTime = session.time
    // A session from the admin panel brings its hint switches; a CLI-deployed seed uses the environment's
    val options = session.options
    val summary: Summary? = tracker.read {
      if (version == this@SummaryCache.version && currentPeople == people && currentTime == time && options == this@SummaryCache.options) null
      else summary(
        config.title, tracker.lastError, currentPeople,
        options?.pointHints ?: config.pointHints, options?.autoHints ?: config.autoHints,
      ).copy(
        sessionTimeMs = currentTime?.runTimeMs ?: 0,
        sessionRunningSince = currentTime?.countedUntil?.toEpochMilli(),
      )
    }
    people = currentPeople
    time = currentTime
    this.options = options
    if (summary != null) {
      version = summary.version
      json = ApiJson.encodeToString(Summary.serializer(), summary)
    }
    json
  }
}
