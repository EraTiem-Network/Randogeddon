package net.eratiem.randogeddon.server.config

import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.server.config.refuse
import net.eratiem.randogeddon.server.config.apiError
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import net.eratiem.randogeddon.shared.i18n.Language
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
      call.refuse(HttpStatusCode.Unauthorized, Refusal(Problem.NOT_LOGGED_IN))
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
    if (!logins.maySee(me.user, id)) call.refuse(HttpStatusCode.Forbidden, Refusal.of(Problem.NOT_PART, "name" to me.user.name, "id" to id))
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
    logins.administratorProblem(call.loggedIn.user)?.let { call.refuse(HttpStatusCode.Forbidden, it) }
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
      user.administrator -> call.refuse(HttpStatusCode.Forbidden, problem)
      else -> call.refuse(HttpStatusCode.Forbidden, Refusal.of(Problem.ONLY_ROLES, "roles" to roles.joinToString(",") { it.code }))
    }
  }
}

/**
 * `POST /api/login`, then for users with two-factor login `POST /api/login/code`, for an admin without it
 * `POST /api/login/setup` and `.../setup/confirm`; `POST /api/logout`. With single sign-on: `GET /api/login/options`
 * (whether the login form shows its button), `GET /api/login/oidc` (to the provider) and `.../callback` (back from it),
 * `POST /api/login/oidc/deleted` and `.../updated` (its webhooks: a person was deleted or changed there) and `POST /api/login/link` (a CLI login
 * link). `GET /api/me` ([meRoute]) is in the protected routes.
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
      if (!sso.enabled) return@get call.refuse(HttpStatusCode.NotFound, Refusal(Problem.NO_SSO))
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
        code == null -> Refusal(Problem.SSO_CANCELLED, detail = call.request.queryParameters["error_description"])
        cookie == null || cookie != state -> Refusal(Problem.SSO_NOT_HERE)
        else -> try {
          loggedIn(sso.finish(state, code), LoginMethods.OIDC)
          null
        } catch (e: SsoRefused) {
          e.refusal
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          Refusal.of(Problem.SSO_UNREACHABLE, "provider" to sso.name)
        }
      }?.let { call.apiError(it).let { e -> e.message + (e.detail?.let { d -> " ($d)" } ?: "") } }
      // Back to the app; a problem shows on its login form
      call.respondRedirect(if (problem == null) "$publicUrl/#/" else "$publicUrl/#/login-problem/${problem.encodeURLPathPart()}")
    }
    // The provider's webhook: it deleted a person (`{"subject": "<their sub>"}`), their account here goes too
    post("/api/login/oidc/deleted") {
      if (!sso.deletionAllowed(call.request.headers[HttpHeaders.Authorization])) {
        return@post call.refuse(HttpStatusCode.Unauthorized, Refusal(Problem.WRONG_TOKEN))
      }
      val subject = runCatching {
        Json.parseToJsonElement(call.receiveText()).jsonObject["subject"]?.jsonPrimitive?.contentOrNull
      }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: return@post call.refuse(HttpStatusCode.BadRequest, Refusal(Problem.NO_SUBJECT))
      sso.deleted(subject)
      call.respond(HttpStatusCode.NoContent)
    }
    // The provider's webhook: it changed a person (`{"subject", "username", "name", "email"}`), their account follows
    post("/api/login/oidc/updated") {
      if (!sso.deletionAllowed(call.request.headers[HttpHeaders.Authorization])) {
        return@post call.refuse(HttpStatusCode.Unauthorized, Refusal(Problem.WRONG_TOKEN))
      }
      val fields = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
      fun field(key: String) = fields?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
      val subject = field("subject") ?: return@post call.refuse(HttpStatusCode.BadRequest, Refusal(Problem.NO_SUBJECT))
      sso.updated(subject, field("username"), field("name"), field("email"))
      call.respond(HttpStatusCode.NoContent)
    }
  }
  rateLimit(LOGIN_RATE_LIMIT) {
    post("/api/login/link") {
      val user = sso.useLink(call.receive<LinkLoginRequest>().token)
      if (user == null) {
        delay(1.seconds)
        return@post call.refuse(HttpStatusCode.Unauthorized, Refusal(Problem.LINK_GONE))
      }
      loggedIn(user, LoginMethods.LINK)
      call.respond(HttpStatusCode.NoContent)
    }
  }
  /** The pending login's user, or an answer that it's gone. */
  suspend fun RoutingContext.pendingUser(token: String): User? {
    val user = pending.userOf(token)?.let { logins.user(it) }
    if (user == null) call.refuse(HttpStatusCode.Unauthorized, Refusal(Problem.LOGIN_EXPIRED))
    return user
  }
  rateLimit(LOGIN_RATE_LIMIT) {
    post("/api/login") {
      val request = call.receive<LoginRequest>()
      val user = logins.verify(request.username, request.password)
      if (user == null) {
        // Slows down guessing on top of the rate limit
        delay(1.seconds)
        return@post call.refuse(HttpStatusCode.Unauthorized, Refusal(Problem.WRONG_LOGIN))
      }
      logins.passwordLoginProblem(user)?.let { return@post call.refuse(HttpStatusCode.Forbidden, it) }
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
        return@post call.refuse(HttpStatusCode.Unauthorized, Refusal(Problem.WRONG_CODE))
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
    call.respond(
      Me(
        name = user.name, administrator = user.administrator, manages = list.managedBy(user),
        language = Language.of(user.language), theme = Theme.of(user.theme), themeMode = ThemeMode.of(user.themeMode),
      )
    )
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
