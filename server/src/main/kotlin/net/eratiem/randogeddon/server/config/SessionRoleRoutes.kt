package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.eratiem.randogeddon.server.account.AccountProblem
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.admin.SessionPeopleService
import net.eratiem.randogeddon.shared.AddPersonRequest
import net.eratiem.randogeddon.shared.CreateSessionRequest
import net.eratiem.randogeddon.shared.InspectRequest
import net.eratiem.randogeddon.shared.InviteRequest
import net.eratiem.randogeddon.shared.SessionRole
import kotlin.uuid.Uuid

/**
 * Under the protected `/api` branch, for every user: the sessions they manage (`/manage`), what a new session can use
 * (`/sessions/new`), its YAMLs checked, someone new invited for it, and creating it (`POST /sessions`); the creator
 * becomes its Session-Manager. How many running sessions a user may manage at once is the Administrators' setting.
 */
fun Route.newSessionRoutes(admin: SessionAdmin, people: SessionPeopleService) {
  // The panel: the sessions the user manages (an Administrator: every session), and what a new one can use
  get("/manage") { gatewayCall { call.respond(admin.overview(call.loggedIn.user)) } }
  route("/sessions/new") {
    get { gatewayCall { call.respond(admin.newSession(call.loggedIn.user)) } }
    // The slots of the uploaded YAMLs, before the players are assigned; games the release lacks are problems
    post("/inspect") { call.respond(admin.inspect(call.receive<InspectRequest>())) }
    // Someone without an account, to play in it: an invitation (only for an address that has no account)
    post("/invite") {
      accountCall {
        val request = call.receive<InviteRequest>()
        call.respond(people.inviteNew(request.email, request.suggestedName, call.loggedIn.user))
      }
    }
  }
  post("/sessions") {
    gatewayCall { call.respond(admin.create(call.receive<CreateSessionRequest>(), call.loggedIn.user)) }
  }
}

/**
 * Under the protected `/api` branch: a session's people (`/sessions/{id}/people`), for its Session-Managers and
 * Administrators: everyone with a part in it, who can still be given one, and giving or taking Session-Manager,
 * support or watcher.
 */
fun Route.peopleRoutes(people: SessionPeopleService, logins: LoginCheck) {
  route("/sessions/{id}/people") {
    install(RequireSessionRole) {
      this.logins = logins
      roles = setOf(SessionRole.MANAGER)
    }
    get { call.respond(people.list(id())) }
    get("/choices") { call.respond(people.choices(id())) }
    post {
      accountCall { call.respond(HttpStatusCode.Created, people.add(id(), call.receive<AddPersonRequest>(), call.loggedIn.user)) }
    }
    delete("/{user}") {
      accountCall {
        val user = call.parameters["user"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
          ?: throw AccountProblem(HttpStatusCode.NotFound, "No such account")
        people.remove(id(), user, call.loggedIn.user)
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}
