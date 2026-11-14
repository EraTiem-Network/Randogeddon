package net.eratiem.randogeddon.server.config

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.server.config.refuse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import net.eratiem.randogeddon.server.account.AccountProblem
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.admin.SessionPeopleService
import net.eratiem.randogeddon.server.admin.ShareLinks
import net.eratiem.randogeddon.server.admin.Support
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.CheatRequest
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.WatcherRequest
import kotlin.uuid.Uuid

/**
 * Under the protected `/api` branch, `/sessions/{id}/support` for the session's support (and Administrators): the slots
 * to help, a slot's items and open locations, and giving an item, checking a location or a free hint.
 */
fun Route.supportRoutes(support: Support, logins: LoginCheck) {
  route("/sessions/{id}/support") {
    install(RequireSessionRole) {
      this.logins = logins
      roles = setOf(SessionRole.SUPPORT)
    }
    get { gatewayCall { call.respond(support.view(id())) } }
    get("/names") {
      val slot = call.request.queryParameters["slot"] ?: return@get call.respond(HttpStatusCode.BadRequest)
      gatewayCall { call.respond(support.names(id(), slot)) }
    }
    post {
      val request = call.receive<CheatRequest>()
      gatewayCall {
        support.run(id(), request, call.loggedIn.user.name)
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}

/**
 * Under the protected `/api` branch, for the session's players and Session-Managers (and Administrators): its watchers
 * (`/sessions/{id}/watchers`: who, who can still watch, adding and taking them) and its share link
 * (`/sessions/{id}/share`: on since when, a new one, off).
 */
fun Route.watcherRoutes(people: SessionPeopleService, links: ShareLinks, logins: LoginCheck) {
  // On each branch: on `/sessions/{id}` it would also hold the dashboard's routes below
  fun Route.playersAndManagers() = install(RequireSessionRole) {
    this.logins = logins
    roles = setOf(SessionRole.PLAYER, SessionRole.MANAGER)
  }
  route("/sessions/{id}") {
    route("/watchers") {
      playersAndManagers()
      get { accountCall { call.respond(people.watchers(id())) } }
      get("/choices") { accountCall { call.respond(people.choices(id())) } }
      post {
        accountCall { call.respond(HttpStatusCode.Created, people.addWatcher(id(), call.receive<WatcherRequest>(), call.loggedIn.user, call.language())) }
      }
      delete("/{user}") {
        accountCall {
          val user = call.parameters["user"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)
          people.removeWatcher(id(), user, call.loggedIn.user)
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
    route("/share") {
      playersAndManagers()
      get { accountCall { call.respond(links.view(id())) } }
      post { accountCall { call.respond(HttpStatusCode.Created, links.create(id(), call.loggedIn.user.name)) } }
      delete {
        accountCall {
          links.remove(id(), call.loggedIn.user.name)
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
  }
}

/**
 * `/api/watch/{token}/...`, without a login (rate-limited): a session by its share link, read-only: its summary (once
 * and as a live stream) and one slot's details. No passwords, no spoiler, no connection, no accounts. A link that is
 * off (or replaced) answers 404.
 */
fun Route.publicWatchRoutes(links: ShareLinks, archives: Archives) {
  rateLimit(WATCH_RATE_LIMIT) {
    route("/api/watch/{token}") {
      get("/state") { shared(links) { call.respondText(it.publicJson(), ContentType.Application.Json) } }
      get("/player/{slot}") { shared(links) { playerDetail(it, archives) } }
      sse("/stream") {
        val live = links.sessionOf(call.parameters["token"].orEmpty()) ?: return@sse
        follow(live) { live.publicJson() }
      }
    }
  }
}

/** The session of the path's share link `{token}`, or 404. */
private suspend fun RoutingContext.shared(links: ShareLinks, block: suspend (LiveSession) -> Unit) {
  val live = links.sessionOf(call.parameters["token"].orEmpty())
    ?: return call.refuse(HttpStatusCode.NotFound, Refusal(Problem.SHARE_LINK_GONE))
  block(live)
}
