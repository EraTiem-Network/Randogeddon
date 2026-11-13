package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import net.eratiem.randogeddon.server.account.AccountProblem
import net.eratiem.randogeddon.server.account.AccountService
import net.eratiem.randogeddon.server.account.LOGIN_DURATION
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.AutoDeleteRequest
import net.eratiem.randogeddon.shared.EmailRequest
import net.eratiem.randogeddon.shared.InviteRequest
import net.eratiem.randogeddon.shared.NameRequest
import net.eratiem.randogeddon.shared.PasswordChangeRequest
import net.eratiem.randogeddon.shared.SignupRequest
import net.eratiem.randogeddon.shared.TokenRequest
import kotlin.uuid.Uuid

/**
 * Without a login: what an invitation link shows, and signing up with it (which logs the new user in). Rate-limited
 * like the login, since a token could be guessed there too.
 */
fun Route.signupRoutes(accounts: AccountService, secure: Boolean = true) {
  rateLimit(LOGIN_RATE_LIMIT) {
    get("/api/invitations/{token}") {
      call.respond(accounts.invitation(call.parameters["token"].orEmpty()))
    }
    post("/api/signup") {
      accountCall {
        val token = accounts.signUp(call.receive<SignupRequest>())
        call.response.cookies.append(sessionCookie(token, LOGIN_DURATION.inWholeSeconds, secure))
        call.respond(HttpStatusCode.NoContent)
      }
    }
    // The link of the confirmation mail to a new address (the token proves it reached that address)
    post("/api/email/confirm") {
      accountCall { call.respond(EmailRequest(accounts.confirmEmail(call.receive<TokenRequest>().token))) }
    }
  }
}

/** Under the protected `/api` branch: the logged-in user's own account (the settings page). */
fun Route.accountRoutes(accounts: AccountService, secure: Boolean = true) {
  route("/account") {
    get { call.respond(accounts.view(call.loggedIn.user)) }
    put("/name") {
      accountCall {
        accounts.rename(call.loggedIn.user, call.receive<NameRequest>().name)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/password") {
      accountCall {
        val request = call.receive<PasswordChangeRequest>()
        val token = accounts.changePassword(call.loggedIn.user, request.current, request.password)
        call.response.cookies.append(sessionCookie(token, LOGIN_DURATION.inWholeSeconds, secure))
        call.respond(HttpStatusCode.NoContent)
      }
    }
    post("/email") {
      accountCall {
        accounts.requestEmail(call.loggedIn.user, call.receive<EmailRequest>().email)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/auto-delete") {
      accountCall {
        accounts.setAutoDelete(call.loggedIn.user, call.receive<AutoDeleteRequest>().autoDelete)
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}

/** Under the protected `/api` branch: the accounts in the admin panel (invite, send the link again, another address). */
fun Route.adminUserRoutes(accounts: AccountService) {
  route("/admin/users") {
    install(RequireAdmin)
    get { call.respond(accounts.list()) }
    post {
      accountCall {
        val request = call.receive<InviteRequest>()
        val account = accounts.invite(request.email, request.suggestedName, call.loggedIn.user.name)
        call.respond(HttpStatusCode.Created, accounts.list().first { it.id == account.id.toString() })
      }
    }
    put("/{user}/email") {
      accountCall {
        accounts.changeEmail(userId(), call.receive<EmailRequest>().email)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    post("/{user}/invite") {
      accountCall {
        accounts.resend(userId())
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}

private fun RoutingContext.userId(): Uuid =
  call.parameters["user"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    ?: throw AccountProblem(HttpStatusCode.NotFound, "No such account")

/** Answers what's wrong with an account request (400, 404, 409, 410, 502) instead of failing it. */
private suspend fun RoutingContext.accountCall(block: suspend () -> Unit) {
  try {
    block()
  } catch (e: AccountProblem) {
    call.respond(e.status, ApiError(e.message ?: "Not possible"))
  }
}
