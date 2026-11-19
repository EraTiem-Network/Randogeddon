package net.eratiem.randogeddon.server.config

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlin.uuid.Uuid
import net.eratiem.randogeddon.server.account.AccountProblem
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.admin.SessionPeopleService
import net.eratiem.randogeddon.shared.AddPersonRequest
import net.eratiem.randogeddon.shared.CreateSessionRequest
import net.eratiem.randogeddon.shared.InspectRequest
import net.eratiem.randogeddon.shared.InviteRequest
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.RoleRequest
import net.eratiem.randogeddon.shared.SessionRole

/**
 * Under the protected `/api` branch, for every user: the sessions they manage (`/manage`), what a new session can use
 * (`/sessions/new`), its ID and its YAMLs checked, someone new invited for it, and creating it (`POST /sessions`); the creator
 * becomes its Session-Manager. How many running sessions a user may manage at once is the Administrators' setting.
 */
fun Route.newSessionRoutes(admin: SessionAdmin, people: SessionPeopleService) {
  // The panel: the sessions the user manages (an Administrator: every session), and what a new one can use
  get("/manage") { gatewayCall { call.respond(admin.overview(call.actor)) } }
  route("/sessions/new") {
    get { gatewayCall { call.respond(admin.newSession(call.actor)) } }
    // Whether an ID can be a new session's (its form, unwanted words, taken): no content, or why not
    get("/id") {
      gatewayCall {
        admin.checkId(call.request.queryParameters["id"].orEmpty().trim())
        call.respond(HttpStatusCode.NoContent)
      }
    }
    // The slots of the uploaded YAMLs, before the players are assigned; games the release lacks are problems
    post("/inspect") { call.respond(admin.inspect(call.receive<InspectRequest>())) }
    // A new session being created (its creator's, or for an Administrator anyone's): how far, how it ended, its log.
    // Without an ID: the caller's newest, for their page after a reload (no content: none)
    route("/creation") {
      get { gatewayCall { admin.ownCreation(call.actor)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NoContent) } }
      route("/{id}") {
        get {
          gatewayCall {
            admin.creation(call.parameters["id"].orEmpty(), call.actor)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NoContent)
          }
        }
        // Cancels it while it generates; one that ended is forgotten
        delete {
          gatewayCall {
            admin.cancel(call.parameters["id"].orEmpty(), call.actor)
            call.respond(HttpStatusCode.NoContent)
          }
        }
        // Generate.py's output after ?since= (a part at a time), or all of it with the command that ran, as a file
        get("/log") {
          val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
          gatewayCall {
            admin.creationLog(call.parameters["id"].orEmpty(), since, call.actor)?.let { call.respond(it) }
              ?: call.respond(HttpStatusCode.NotFound)
          }
        }
        get("/log.txt") {
          val id = call.parameters["id"].orEmpty()
          gatewayCall {
            val text = admin.creationLogText(id, call.actor) ?: return@gatewayCall call.respond(HttpStatusCode.NotFound)
            call.response.header(
              HttpHeaders.ContentDisposition,
              ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "generation-$id.log").toString(),
            )
            call.respondText(text, ContentType.Text.Plain)
          }
        }
      }
    }
    // Someone without an account, to play in it: an invitation (only for an address that has no account)
    post("/invite") {
      accountCall {
        val request = call.receive<InviteRequest>()
        call.respond(people.inviteNew(request.email, request.suggestedName, call.loggedIn.user, call.language()))
      }
    }
  }
  // Checks it now; it generates and is set up in the background (see /sessions/new/creation)
  post("/sessions") {
    gatewayCall { call.respond(HttpStatusCode.Accepted, admin.create(call.receive<CreateSessionRequest>(), call.actor)) }
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
    get {
      val user = call.actor
      call.respond(people.list(id()).copy(you = user.id.toString(), mayLeave = user.administrator))
    }
    get("/choices") { call.respond(people.choices(id())) }
    post {
      accountCall { call.respond(HttpStatusCode.Created, people.add(id(), call.receive<AddPersonRequest>(), call.actor, call.language())) }
    }
    put("/{user}") {
      accountCall {
        val user = call.parameters["user"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
          ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)
        call.respond(people.change(id(), user, call.receive<RoleRequest>().role, call.actor))
      }
    }
    delete("/{user}") {
      accountCall {
        val user = call.parameters["user"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
          ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)
        people.remove(id(), user, call.actor)
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}
