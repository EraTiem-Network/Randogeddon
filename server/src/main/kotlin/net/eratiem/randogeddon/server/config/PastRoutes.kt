package net.eratiem.randogeddon.server.config

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.admin.GameServerUnavailable
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.Connection
import net.eratiem.randogeddon.shared.SessionRole
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val POLL_INTERVAL = 750.milliseconds
private val KEEPALIVE = 15.seconds

/**
 * Under the protected `/api` branch: the sessions a user may see (theirs, all for admins), and each one's summary
 * (once and as a live stream), the details of one slot, where and with which passwords to connect, and its spoiler
 * log (for its players once it is finished, any time for admins). A finished session answers as it was when it
 * finished (its archive).
 */
fun Route.sessionRoutes(
  config: TrackerConfig,
  logins: LoginCheck,
  sessions: LiveSessions,
  list: SessionList,
  archives: Archives,
  slotPasswords: SlotPasswords,
  spoilers: Spoilers,
) {
  get("/sessions") { call.respond(list.visibleTo(call.loggedIn.user)) }
  route("/sessions/{id}") {
    install(RequireSessionMember) { this.logins = logins }
    get("/state") { session(sessions) { call.respondText(it.json(), ContentType.Application.Json) } }
    get("/player/{slot}") { session(sessions) { playerDetail(it, archives) } }
    // Where the person's game clients connect, with their slots' passwords
    get("/connection") {
      session(sessions) { live ->
        val person = live.personOf(call.loggedIn.user)
        call.respond(Connection(config.addressOf(live.id), person?.let { slotPasswords.of(live, it) }.orEmpty()))
      }
    }
    // A finished session as it was when it finished, with every slot's details
    get("/archive") {
      session(sessions) { live ->
        val archived = archives.read(live.id) ?: return@session call.respond(HttpStatusCode.NotFound, ApiError("${live.id} isn't finished"))
        call.respond(archived)
      }
    }
    // A full spoiler: before a session is finished only Administrators, its Session-Managers and support see it
    get("/spoiler") {
      session(sessions) { live ->
        val user = call.loggedIn.user
        val helps = user.administrator || live.roleOf(user) in setOf(SessionRole.MANAGER, SessionRole.SUPPORT)
        if (!helps && !live.finished) {
          return@session call.respond(HttpStatusCode.Forbidden, ApiError("The spoiler log is shown once the session is finished"))
        }
        try {
          val spoiler = spoilers.of(live.id, live.seedName)
            ?: return@session call.respond(HttpStatusCode.NotFound, ApiError("No spoiler log for ${live.id}"))
          call.respond(spoiler)
        } catch (e: GameServerUnavailable) {
          call.respond(e.status, ApiError(e.message ?: "The gateway can't be reached"))
        }
      }
    }
    // Pushes the whole summary whenever the state changes. Polled and therefore debounced: a burst of item sends (e.g.
    // a release) becomes one update.
    sse("/stream") {
      val live = sessions.get(call.parameters["id"].orEmpty()) ?: return@sse
      follow(live) { live.json() }
    }
  }
}

/** The details of the path's `{slot}` of [live]; a finished session's from its archive. */
internal suspend fun RoutingContext.playerDetail(live: LiveSession, archives: Archives) {
  val slot = call.parameters["slot"]?.toIntOrNull() ?: return call.respond(HttpStatusCode.BadRequest)
  val archived = if (live.finished) archives.read(live.id) else null
  val detail = (if (archived != null) archived.details.firstOrNull { it.slot == slot } else live.tracker.read { playerDetail(slot) })
    ?: return call.respond(HttpStatusCode.NotFound)
  call.respond(detail)
}

/** Sends [live]'s summary ([json]) whenever it changes, with a keepalive in between, until the client goes. */
internal suspend fun ServerSSESession.follow(live: LiveSession, json: suspend () -> String) {
  var sentVersion = -1L
  var idle = 0.milliseconds
  while (true) {
    // A finished session's summary no longer changes, but finishing (and reopening) changes the version
    val version = live.version()
    if (version != sentVersion) {
      sentVersion = version
      send(ServerSentEvent(data = json(), event = "state"))
      idle = 0.milliseconds
    } else if (idle >= KEEPALIVE) {
      send(ServerSentEvent(comments = "keepalive"))
      idle = 0.milliseconds
    }
    delay(POLL_INTERVAL)
    idle += POLL_INTERVAL
  }
}

/** The session of the path's `{id}`, or 404. */
internal suspend fun RoutingContext.session(sessions: LiveSessions, block: suspend (LiveSession) -> Unit) {
  val id = call.parameters["id"].orEmpty()
  val live = sessions.get(id) ?: return call.respond(HttpStatusCode.NotFound, ApiError("No session $id"))
  block(live)
}
