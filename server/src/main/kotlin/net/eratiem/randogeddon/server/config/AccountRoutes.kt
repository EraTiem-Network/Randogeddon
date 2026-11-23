package net.eratiem.randogeddon.server.config

import net.eratiem.randogeddon.shared.AccountActionRequest
import net.eratiem.randogeddon.shared.FontRequest
import net.eratiem.randogeddon.shared.SessionLimitRequest
import net.eratiem.randogeddon.shared.ThemeRequest
import net.eratiem.randogeddon.shared.TimeZoneRequest
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
import net.eratiem.randogeddon.server.account.LoginAttempts
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
      apiCall {
        val token = accounts.signUp(call.receive<SignupRequest>())
        call.response.cookies.append(sessionCookie(token, LOGIN_DURATION.inWholeSeconds, secure))
        call.respond(HttpStatusCode.NoContent)
      }
    }
    // The link of the confirmation mail to a new address (the token proves it reached that address)
    post("/api/email/confirm") {
      apiCall { call.respond(EmailRequest(accounts.confirmEmail(call.receive<TokenRequest>().token))) }
    }
  }
}

/**
 * Under the protected `/api` branch: the logged-in user's own account (the settings page). Wrong codes and wrong
 * current passwords count towards the account's [attempts] (as at the login).
 */
fun Route.accountRoutes(
  accounts: AccountService,
  twoFactor: TwoFactor,
  bridgeKeys: BridgeKeys,
  attempts: LoginAttempts,
  secure: Boolean = true,
) {
  route("/account") {
    // Randogeddon Bridge's keys: made here (with the cookie login), listed and ended
    route("/bridge-keys") {
      get { call.respond(bridgeKeys.list(call.loggedIn.user)) }
      post { apiCall { call.respond(bridgeKeys.make(call.loggedIn.user)) } }
      delete("/{key}") {
        if (bridgeKeys.end(call.loggedIn.user, call.parameters["key"].orEmpty())) call.respond(HttpStatusCode.NoContent)
        else call.refuse(HttpStatusCode.NotFound, Refusal(Problem.BRIDGE_KEY_GONE))
      }
    }
    // Two-factor login; rate-limited, since each takes a code
    rateLimit(LOGIN_RATE_LIMIT) {
      route("/2fa") {
        post("/setup") { apiCall { call.respond(twoFactor.setUp(call.loggedIn.user)) } }
        post("/enable") {
          apiCall {
            // An Administrator's other logins end; this one, which passed the code, stays
            val keep = call.request.cookies[SESSION_COOKIE]
            call.respond(RecoveryCodeList(twoFactor.enable(call.loggedIn.user, call.receive<CodeRequest>().code, keep = keep)))
          }
        }
        post("/disable") {
          apiCall {
            val code = call.receive<CodeRequest>().code
            countedTry(attempts, listOf(LoginAttempts.code(call.loggedIn.user.id.toString())), Problem.WRONG_CODE) {
              twoFactor.disable(call.loggedIn.user, code)
            }
            call.respond(HttpStatusCode.NoContent)
          }
        }
        post("/codes") {
          apiCall {
            val code = call.receive<CodeRequest>().code
            val codes = countedTry(attempts, listOf(LoginAttempts.code(call.loggedIn.user.id.toString())), Problem.WRONG_CODE) {
              twoFactor.newCodes(call.loggedIn.user, code)
            }
            call.respond(RecoveryCodeList(codes))
          }
        }
      }
    }
    get { call.respond(accounts.view(call.loggedIn.user)) }
    put("/name") {
      apiCall {
        accounts.rename(call.loggedIn.user, call.receive<NameRequest>().name)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/password") {
      apiCall {
        val request = call.receive<PasswordChangeRequest>()
        val token = countedTry(attempts, listOf(LoginAttempts.accountPassword(call.loggedIn.user.id)), Problem.WRONG_PASSWORD) {
          accounts.changePassword(call.loggedIn.user, request.current, request.password)
        }
        call.response.cookies.append(sessionCookie(token, LOGIN_DURATION.inWholeSeconds, secure))
        call.respond(HttpStatusCode.NoContent)
      }
    }
    post("/email") {
      apiCall {
        accounts.requestEmail(call.loggedIn.user, call.receive<EmailRequest>().email, call.language())
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/language") {
      apiCall {
        accounts.setLanguage(call.loggedIn.user, call.receive<LanguageRequest>().language)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/theme") {
      apiCall {
        val request = call.receive<ThemeRequest>()
        accounts.setTheme(call.loggedIn.user, request.theme, request.mode)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    // The browser's time zone, for the times in the mails
    put("/time-zone") {
      apiCall {
        accounts.setTimeZone(call.loggedIn.user, call.receive<TimeZoneRequest>().zone)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/font") {
      apiCall {
        val request = call.receive<FontRequest>()
        accounts.setFont(call.loggedIn.user, request.font, request.scope)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    put("/auto-delete") {
      apiCall {
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
      apiCall {
        val request = call.receive<InviteRequest>()
        val account = accounts.invite(request.email, request.suggestedName, call.loggedIn.user, call.language())
        call.respond(HttpStatusCode.Created, accounts.listed(account.id))
      }
    }
    put("/{user}/email") {
      apiCall {
        accounts.changeEmail(userId(), call.receive<EmailRequest>().email, call.language())
        call.respond(HttpStatusCode.NoContent)
      }
    }
    post("/{user}/invite") {
      apiCall {
        accounts.resend(userId(), call.language())
        call.respond(HttpStatusCode.NoContent)
      }
    }
    // How many running sessions of their own the account may have (null: the default again)
    put("/{user}/session-limit") {
      apiCall {
        accounts.setSessionLimit(userId(), call.receive<SessionLimitRequest>().limit, call.loggedIn.user)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    // Keeping an account out (a time-out or a suspension), letting it in again, deleting it: never an Administrator's
    post("/{user}/block") {
      apiCall {
        accounts.keepOut(userId(), call.receive<AccountActionRequest>(), call.loggedIn.user)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    delete("/{user}/block") {
      apiCall {
        accounts.letIn(userId(), call.loggedIn.user)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    post("/{user}/delete") {
      apiCall {
        accounts.remove(userId(), call.receive<AccountActionRequest>(), call.loggedIn.user)
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}

/** The account of the path's `{user}`; an [AccountProblem] (404) if it isn't an account's ID. */
internal fun RoutingContext.userId(): Uuid =
  call.parameters["user"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)

/**
 * [block], a step that checks a password or a code: refused (429) while one of [keys] has had too many wrong tries
 * ([LoginAttempts]); its refusal for [wrong] counts as one more under each of them.
 */
internal suspend fun <T> countedTry(attempts: LoginAttempts, keys: List<String>, wrong: Problem, block: suspend () -> T): T {
  if (keys.any { attempts.blocked(it) }) throw AccountProblem(HttpStatusCode.TooManyRequests, Problem.TOO_MANY_ATTEMPTS)
  try {
    return block()
  } catch (e: AccountProblem) {
    if (e.refusal.problem == wrong) keys.forEach { attempts.failed(it) }
    throw e
  }
}
