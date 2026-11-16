package net.eratiem.randogeddon.server.config

import net.eratiem.randogeddon.shared.FontRequest
import net.eratiem.randogeddon.shared.ThemeRequest
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.server.config.refuse
import net.eratiem.randogeddon.shared.i18n.Language
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.AccountProblem
import net.eratiem.randogeddon.server.account.AccountService
import net.eratiem.randogeddon.server.account.BridgeKeys
import net.eratiem.randogeddon.server.account.LOGIN_DURATION
import net.eratiem.randogeddon.server.account.TwoFactor
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.AutoDeleteRequest
import net.eratiem.randogeddon.shared.LanguageRequest
import net.eratiem.randogeddon.shared.CodeRequest
import net.eratiem.randogeddon.shared.EmailRequest
import net.eratiem.randogeddon.shared.InviteRequest
import net.eratiem.randogeddon.shared.NameRequest
import net.eratiem.randogeddon.shared.PasswordChangeRequest
import net.eratiem.randogeddon.shared.RecoveryCodeList
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
      call.respond(accounts.invitation(call.parameters["token"].orEmpty(), call.language()))
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
fun Route.accountRoutes(accounts: AccountService, twoFactor: TwoFactor, bridgeKeys: BridgeKeys, secure: Boolean = true) {
  route("/account") {
    // Randogeddon Bridge's keys: made here (with the cookie login), listed and ended
    route("/bridge-keys") {
      get { call.respond(bridgeKeys.list(call.loggedIn.user)) }
      post { accountCall { call.respond(bridgeKeys.make(call.loggedIn.user)) } }
      delete("/{key}") {
        if (bridgeKeys.end(call.loggedIn.user, call.parameters["key"].orEmpty())) call.respond(HttpStatusCode.NoContent)
        else call.refuse(HttpStatusCode.NotFound, Refusal(Problem.BRIDGE_KEY_GONE))
      }
    }
    // Two-factor login; rate-limited, since each takes a code
    rateLimit(LOGIN_RATE_LIMIT) {
      route("/2fa") {
        post("/setup") { accountCall { call.respond(twoFactor.setUp(call.loggedIn.user)) } }
        post("/enable") {
          accountCall { call.respond(RecoveryCodeList(twoFactor.enable(call.loggedIn.user, call.receive<CodeRequest>().code))) }
        }
        post("/disable") {
          accountCall {
            twoFactor.disable(call.loggedIn.user, call.receive<CodeRequest>().code)
            call.respond(HttpStatusCode.NoContent)
          }
        }
        post("/codes") {
          accountCall { call.respond(RecoveryCodeList(twoFactor.newCodes(call.loggedIn.user, call.receive<CodeRequest>().code))) }
        }
      }
    }
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
        accounts.requestEmail(call.loggedIn.user, call.receive<EmailRequest>().email, call.language())
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/language") {
      accountCall {
        accounts.setLanguage(call.loggedIn.user, call.receive<LanguageRequest>().language)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/theme") {
      accountCall {
        val request = call.receive<ThemeRequest>()
        accounts.setTheme(call.loggedIn.user, request.theme, request.mode)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/font") {
      accountCall {
        val request = call.receive<FontRequest>()
        accounts.setFont(call.loggedIn.user, request.font, request.everywhere)
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
fun Route.adminUserRoutes(accounts: AccountService, logins: LoginCheck) {
  route("/admin/users") {
    install(RequireAdministrator) { this.logins = logins }
    get { call.respond(accounts.list()) }
    post {
      accountCall {
        val request = call.receive<InviteRequest>()
        val account = accounts.invite(request.email, request.suggestedName, call.loggedIn.user.name, call.language())
        call.respond(HttpStatusCode.Created, accounts.list().first { it.id == account.id.toString() })
      }
    }
    put("/{user}/email") {
      accountCall {
        accounts.changeEmail(userId(), call.receive<EmailRequest>().email, call.language())
        call.respond(HttpStatusCode.NoContent)
      }
    }
    post("/{user}/invite") {
      accountCall {
        accounts.resend(userId(), call.language())
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}

private fun RoutingContext.userId(): Uuid =
  call.parameters["user"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)

/** Answers what's wrong with an account request (400, 403, 404, 409, 410, 502) instead of failing it. */
internal suspend fun RoutingContext.accountCall(block: suspend () -> Unit) {
  try {
    block()
  } catch (e: AccountProblem) {
    call.refuse(e.status, e.refusal)
  }
}
