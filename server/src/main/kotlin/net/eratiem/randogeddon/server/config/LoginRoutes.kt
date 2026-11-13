package net.eratiem.randogeddon.server.config

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.account.LOGIN_DURATION
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.LoginMethods
import net.eratiem.randogeddon.server.account.LoginService
import net.eratiem.randogeddon.server.account.PendingLogins
import net.eratiem.randogeddon.server.account.SingleSignOn
import net.eratiem.randogeddon.server.account.SsoRefused
import net.eratiem.randogeddon.server.account.TwoFactor
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.CodeRequest
import net.eratiem.randogeddon.shared.LinkLoginRequest
import net.eratiem.randogeddon.shared.LoginOptions
import net.eratiem.randogeddon.shared.LoginNext
import net.eratiem.randogeddon.shared.LoginStep
import net.eratiem.randogeddon.shared.PendingRequest
import net.eratiem.randogeddon.shared.RecoveryCodeList
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.LoginRequest
import net.eratiem.randogeddon.shared.Me
import kotlin.time.Duration.Companion.seconds

/**
 * HttpOnly, Secure, SameSite=Strict: unreachable for scripts, never sent over plain HTTP or from other sites. Secure
 * can be switched off for a local demo ([TrackerConfig.cookieSecure]).
 */
const val SESSION_COOKIE = "randogeddon_session"

/** The `state` of a login through the provider, for its callback (only on those two paths, 10 minutes). */
private const val OIDC_COOKIE = "randogeddon_oidc"

private fun oidcCookie(value: String, maxAge: Int, secure: Boolean) = Cookie(
  OIDC_COOKIE, value, maxAge = maxAge, path = "/api/login/oidc", secure = secure, httpOnly = true,
  // Lax: the provider's redirect back is a top-level navigation from another site
  extensions = mapOf("SameSite" to "Lax"),
)

/** The logged-in user of a call that passed [RequireLogin]. */
data class LoggedIn(val user: User)

private val LOGGED_IN = AttributeKey<LoggedIn>("LoggedIn")

val ApplicationCall.loggedIn: LoggedIn get() = attributes[LOGGED_IN]

/**
 * Lets a call through only with a valid login: 401 without (the web app then shows the login). A session's own routes
 * check [RequireSessionMember] on top, the admin panel's [RequireAdmin].
 */
class RequireLoginConfig {
  lateinit var logins: LoginCheck
}

val RequireLogin = createRouteScopedPlugin("RequireLogin", ::RequireLoginConfig) {
  val logins = pluginConfig.logins
  onCall { call ->
    val user = call.request.cookies[SESSION_COOKIE]?.let { logins.userOf(it) }
    if (user == null) {
      call.respond(HttpStatusCode.Unauthorized, ApiError("Not logged in"))
      return@onCall
    }
    call.attributes.put(LOGGED_IN, LoggedIn(user))
  }
}

/**
 * A session's routes (`.../{id}/...`) only for its players and admins (403 otherwise, also for a session that doesn't
 * exist); goes inside [RequireLogin].
 */
val RequireSessionMember = createRouteScopedPlugin("RequireSessionMember", ::RequireLoginConfig) {
  val logins = pluginConfig.logins
  onCall { call ->
    val me = call.loggedIn
    val id = call.parameters["id"].orEmpty()
    if (!logins.maySee(me.user, id)) call.respond(HttpStatusCode.Forbidden, ApiError("${me.user.name} is not part of session $id"))
  }
}

/**
 * Lets a call through only for Administrators whose login may act as one ([LoginCheck.administratorProblem]: with
 * two-factor login, or through single sign-on), 403 otherwise; goes inside [RequireLogin], which provides [loggedIn].
 * Checked on every call, so hiding the panel in the web app is not what protects it.
 */
val RequireAdministrator = createRouteScopedPlugin("RequireAdministrator", ::RequireLoginConfig) {
  val logins = pluginConfig.logins
  onCall { call ->
    logins.administratorProblem(call.loggedIn.user)?.let { call.respond(HttpStatusCode.Forbidden, ApiError(it)) }
  }
}

class RequireSessionRoleConfig {
  lateinit var logins: LoginCheck
  var roles: Set<SessionRole> = emptySet()
}

/**
 * A session's routes (`.../{id}/...`) only for those with one of [RequireSessionRoleConfig.roles] in it, and for
 * Administrators whose login may act as one: 403 otherwise. Goes inside [RequireLogin].
 */
val RequireSessionRole = createRouteScopedPlugin("RequireSessionRole", ::RequireSessionRoleConfig) {
  val logins = pluginConfig.logins
  val roles = pluginConfig.roles
  onCall { call ->
    val user = call.loggedIn.user
    if (logins.roleIn(user, call.parameters["id"].orEmpty()) in roles) return@onCall
    val problem = logins.administratorProblem(user)
    when {
      problem == null -> Unit
      user.administrator -> call.respond(HttpStatusCode.Forbidden, ApiError(problem))
      else -> call.respond(HttpStatusCode.Forbidden, ApiError("Only the session's ${roles.joinToString(" or ") { it.label }} can do this"))
    }
  }
}

/** How a role is called in messages. */
val SessionRole.label: String
  get() = when (this) {
    SessionRole.PLAYER -> "players"
    SessionRole.MANAGER -> "Session-Managers"
    SessionRole.SUPPORT -> "support"
    SessionRole.WATCHER -> "watchers"
  }

/**
 * `POST /api/login`, then for users with two-factor login `POST /api/login/code`, for an admin without it
 * `POST /api/login/setup` and `.../setup/confirm`; `POST /api/logout`. With single sign-on: `GET /api/login/options`
 * (whether the login form shows its button), `GET /api/login/oidc` (to the provider) and `.../callback` (back from it),
 * and `POST /api/login/link` (a CLI login link). `GET /api/me` ([meRoute]) is in the protected routes.
 */
fun Route.loginRoutes(
  logins: LoginService,
  twoFactor: TwoFactor,
  pending: PendingLogins,
  sso: SingleSignOn,
  publicUrl: String,
  secure: Boolean = true,
) {
  suspend fun RoutingContext.loggedIn(user: User, method: String = LoginMethods.PASSWORD) {
    call.response.cookies.append(sessionCookie(logins.start(user, method), LOGIN_DURATION.inWholeSeconds, secure))
  }
  // A rate limit of their own; outside it (or the login's) they would be under /api's login check
  rateLimit(SSO_RATE_LIMIT) {
    get("/api/login/options") { call.respond(LoginOptions(singleSignOn = sso.name.takeIf { sso.enabled })) }
    get("/api/login/oidc") {
      if (!sso.enabled) return@get call.respond(HttpStatusCode.NotFound, ApiError("No single sign-on here"))
      val (state, url) = try {
        sso.start()
      } catch (e: Exception) {
        return@get call.respondRedirect("$publicUrl/#/login-problem/${"${sso.name} can't be reached".encodeURLPathPart()}")
      }
      call.response.cookies.append(oidcCookie(state, 600, secure))
      call.respondRedirect(url)
    }
    get("/api/login/oidc/callback") {
      val state = call.request.queryParameters["state"].orEmpty()
      val code = call.request.queryParameters["code"]
      val cookie = call.request.cookies[OIDC_COOKIE]
      call.response.cookies.append(oidcCookie("", 0, secure))
      // The state must be this browser's (no logins slipped in from elsewhere)
      val problem = when {
        code == null -> call.request.queryParameters["error_description"] ?: "The login was cancelled"
        cookie == null || cookie != state -> "The login didn't start here: start it again"
        else -> try {
          loggedIn(sso.finish(state, code), LoginMethods.OIDC)
          null
        } catch (e: SsoRefused) {
          e.message
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          "The login didn't work: ${sso.name} can't be reached"
        }
      }
      // Back to the app; a problem shows on its login form
      call.respondRedirect(if (problem == null) "$publicUrl/#/" else "$publicUrl/#/login-problem/${problem.encodeURLPathPart()}")
    }
  }
  rateLimit(LOGIN_RATE_LIMIT) {
    post("/api/login/link") {
      val user = sso.useLink(call.receive<LinkLoginRequest>().token)
      if (user == null) {
        delay(1.seconds)
        return@post call.respond(HttpStatusCode.Unauthorized, ApiError("This link doesn't work (any more): ask for a new one"))
      }
      loggedIn(user, LoginMethods.LINK)
      call.respond(HttpStatusCode.NoContent)
    }
  }
  /** The pending login's user, or an answer that it's gone. */
  suspend fun RoutingContext.pendingUser(token: String): User? {
    val user = pending.userOf(token)?.let { logins.user(it) }
    if (user == null) call.respond(HttpStatusCode.Unauthorized, ApiError("The login has expired: log in again"))
    return user
  }
  rateLimit(LOGIN_RATE_LIMIT) {
    post("/api/login") {
      val request = call.receive<LoginRequest>()
      val user = logins.verify(request.username, request.password)
      if (user == null) {
        // Slows down guessing on top of the rate limit
        delay(1.seconds)
        return@post call.respond(HttpStatusCode.Unauthorized, ApiError("Wrong name or password"))
      }
      logins.passwordLoginProblem(user)?.let { return@post call.respond(HttpStatusCode.Forbidden, ApiError(it)) }
      when {
        user.twoFactor -> call.respond(LoginStep(LoginNext.CODE, pending.create(user.id)))
        // Admins can cheat and delete sessions: they set up two-factor login before anything else
        user.administrator -> call.respond(LoginStep(LoginNext.SETUP, pending.create(user.id)))
        else -> {
          loggedIn(user)
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
    post("/api/login/code") {
      val request = call.receive<CodeRequest>()
      val token = request.pending.orEmpty()
      val user = pendingUser(token) ?: return@post
      if (!twoFactor.check(user, request.code)) {
        pending.wrongCode(token)
        delay(1.seconds)
        return@post call.respond(HttpStatusCode.Unauthorized, ApiError("That code doesn't fit"))
      }
      pending.finish(token)
      loggedIn(user)
      call.respond(HttpStatusCode.NoContent)
    }
    post("/api/login/setup") {
      val user = pendingUser(call.receive<PendingRequest>().pending) ?: return@post
      accountCall { call.respond(twoFactor.setUp(user)) }
    }
    post("/api/login/setup/confirm") {
      val request = call.receive<CodeRequest>()
      val token = request.pending.orEmpty()
      val user = pendingUser(token) ?: return@post
      accountCall {
        val codes = twoFactor.enable(user, request.code)
        pending.finish(token)
        loggedIn(user)
        call.respond(RecoveryCodeList(codes))
      }
    }
  }
  post("/api/logout") {
    call.request.cookies[SESSION_COOKIE]?.let { logins.logout(it) }
    call.response.cookies.append(sessionCookie("", 0, secure))
    call.respond(HttpStatusCode.NoContent)
  }
}

/** Under the protected `/api` branch: who is logged in, an Administrator, and which sessions they manage. */
fun Route.meRoute(list: SessionList) {
  get("/me") {
    val user = call.loggedIn.user
    call.respond(Me(name = user.name, administrator = user.administrator, manages = list.managedBy(user)))
  }
}

internal fun sessionCookie(value: String, maxAgeSeconds: Long, secure: Boolean) = Cookie(
  name = SESSION_COOKIE,
  value = value,
  maxAge = maxAgeSeconds.toInt(),
  path = "/",
  secure = secure,
  httpOnly = true,
  extensions = mapOf("SameSite" to "Strict"),
)
