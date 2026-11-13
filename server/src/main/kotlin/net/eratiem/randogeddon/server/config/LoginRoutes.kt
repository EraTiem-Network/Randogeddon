package net.eratiem.randogeddon.server.config

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.account.LOGIN_DURATION
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.LoginService
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.LoginRequest
import net.eratiem.randogeddon.shared.Me
import kotlin.time.Duration.Companion.seconds

/**
 * HttpOnly, Secure, SameSite=Strict: unreachable for scripts, never sent over plain HTTP or from other sites. Secure
 * can be switched off for a local demo ([TrackerConfig.cookieSecure]).
 */
const val SESSION_COOKIE = "randogeddon_session"

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
 * Lets a call through only for admins (403 otherwise); goes inside [RequireLogin], which provides [loggedIn]. Checked
 * on every call, so hiding the panel in the web app is not what protects it.
 */
val RequireAdmin = createRouteScopedPlugin("RequireAdmin") {
  onCall { call ->
    if (!call.loggedIn.user.admin) call.respond(HttpStatusCode.Forbidden, ApiError("Only admins can do this"))
  }
}

/** `POST /api/login`, `POST /api/logout`; `GET /api/me` ([meRoute]) is in the protected routes. */
fun Route.loginRoutes(logins: LoginService, secure: Boolean = true) {
  rateLimit(LOGIN_RATE_LIMIT) {
    post("/api/login") {
      val request = call.receive<LoginRequest>()
      val token = logins.login(request.username, request.password)
      val user = token?.let { logins.userOf(it) }
      if (token == null || user == null) {
        // Slows down guessing on top of the rate limit
        delay(1.seconds)
        return@post call.respond(HttpStatusCode.Unauthorized, ApiError("Wrong name or password"))
      }
      call.response.cookies.append(sessionCookie(token, LOGIN_DURATION.inWholeSeconds, secure))
      call.respond(HttpStatusCode.NoContent)
    }
  }
  post("/api/logout") {
    call.request.cookies[SESSION_COOKIE]?.let { logins.logout(it) }
    call.response.cookies.append(sessionCookie("", 0, secure))
    call.respond(HttpStatusCode.NoContent)
  }
}

/** Under the protected `/api` branch. */
fun Route.meRoute() {
  get("/me") {
    val me = call.loggedIn
    call.respond(Me(name = me.user.name, admin = me.user.admin))
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
