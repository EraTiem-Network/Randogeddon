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
import net.eratiem.randogeddon.server.account.LoginService
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.ap.TrackerService
import net.eratiem.randogeddon.server.admin.Cheats
import net.eratiem.randogeddon.server.admin.SessionLifecycle
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.CurrentSession
import net.eratiem.randogeddon.server.session.PastSessions
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.server.session.Summaries
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
  val summaries by inject<Summaries>()
  val currentSession by inject<CurrentSession>()
  val archives by inject<Archives>()
  val past by inject<PastSessions>()
  val spoilers by inject<Spoilers>()
  val lifecycle by inject<SessionLifecycle>()
  val cheats by inject<Cheats>()

  routing {
    loginRoutes(logins, trackerConfig.cookieSecure)

    // Everything about the session needs a login (login and logout above are outside this branch)
    route("/api") {
      install(RequireLogin) { this.logins = logins }
      sessionRoutes(tracker, summaries, currentSession, archives)
      meRoute(trackerConfig, slotPasswords)
      pastRoutes(past, archives, spoilers)
      adminRoutes(gameServer, sessionAdmin, lifecycle, cheats, currentSession)
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

/**
 * Under `/api`: the session's summary (once and as a live stream) and the details of one slot; of a finished session
 * as they were when it finished.
 */
private fun Route.sessionRoutes(tracker: TrackerService, summaries: Summaries, session: CurrentSession, archives: Archives) {
  // Only for the session's players and admins
  route("/state") {
    install(RequireSessionMember)
    get { call.respondText(summaries.json(), ContentType.Application.Json) }
  }
  route("/player/{slot}") {
    install(RequireSessionMember)
    get {
      val slot = call.parameters["slot"]?.toIntOrNull()
        ?: return@get call.respond(HttpStatusCode.BadRequest)
      val seed = session.seedName
      val archived = if (session.finish?.finishedAt != null && seed != null) archives.read(seed) else null
      val detail = (if (archived != null) archived.details.firstOrNull { it.slot == slot } else tracker.read { playerDetail(slot) })
        ?: return@get call.respond(HttpStatusCode.NotFound)
      call.respond(detail)
    }
  }
  // Pushes the whole summary whenever the state changes. Polled and therefore debounced: a burst of item sends (e.g.
  // a release) becomes one update.
  route("/stream") {
    install(RequireSessionMember)
    sse {
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
}
