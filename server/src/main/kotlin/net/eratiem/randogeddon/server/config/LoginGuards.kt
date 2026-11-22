package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.routing.RoutingContext
import io.ktor.util.AttributeKey
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.LoginMethods
import net.eratiem.randogeddon.server.admin.SessionProblem
import net.eratiem.randogeddon.server.config.refuse
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.SessionRole

/**
 * The logged-in user of a call that passed [RequireLogin]. [strong]: this login may act as an Administrator
 * ([LoginCheck.administratorProblem]: through single sign-on, or without it with two-factor login where
 * [TrackerConfig.adminPasswordLogin] allows that); never a bridge key.
 */
data class LoggedIn(val user: User, val strong: Boolean = false)

private val LOGGED_IN = AttributeKey<LoggedIn>("LoggedIn")

val ApplicationCall.loggedIn: LoggedIn get() = attributes[LOGGED_IN]

/**
 * Who a call acts as: the logged-in user, as an Administrator only with a login that may be one. What an
 * Administrator sees beyond their own sessions (every session, its spoiler log, hosted slots' passwords, every slot's
 * files) goes by this, like the admin routes go by [RequireAdministrator]: an older password login of an account that
 * became an Administrator, or a bridge key, reads no more than any user. The account's own settings go by [loggedIn].
 */
val ApplicationCall.actor: User
  get() = loggedIn.let { if (it.user.administrator && !it.strong) it.user.copy(administrator = false) else it.user }

/** [user] as their login acts, by the rule of [actor]: an Administrator only with a login that may act as one. */
internal fun LoginCheck.actorOf(user: User): User =
  if (user.administrator && administratorProblem(user) != null) user.copy(administrator = false) else user

/** The caller's part in the path's session, as [RequireSessionMember] or [RequireSessionRole] found it. */
private val SESSION_ROLE = AttributeKey<SessionRole>("SessionRole")

/**
 * Whether the call may read what the gateway's client said when it failed (it names internal hosts and ports): an
 * Administrator ([actor]), or a Session-Manager of the path's session.
 */
internal val ApplicationCall.seesGatewayDetail: Boolean
  get() = attributes.contains(LOGGED_IN) && (actor.administrator || attributes.getOrNull(SESSION_ROLE) == SessionRole.MANAGER)

/**
 * Refuses (403, [problem]) a call that doesn't act as an Administrator ([actor]); before its body is read, so nobody
 * else gets a large upload taken in. Inside [apiCall].
 */
internal fun RoutingContext.onlyAdministrators(problem: Problem = Problem.ONLY_ADMINISTRATORS) {
  if (!call.actor.administrator) throw SessionProblem(HttpStatusCode.Forbidden, problem)
}

/**
 * Lets a call through only with a valid login: 401 without (the web app then shows the login). A session's own routes
 * check [RequireSessionMember] on top, the admin panel's [RequireAdministrator].
 */
class RequireLoginConfig {
  lateinit var logins: LoginCheck
}

val RequireLogin = createRouteScopedPlugin("RequireLogin", ::RequireLoginConfig) {
  val logins = pluginConfig.logins
  onCall { call ->
    // A bridge key reaches only the bridge's routes ([RequireBridgeKey]), even if someone sets it as the cookie
    val user = call.request.cookies[SESSION_COOKIE]?.let { logins.userOf(it) }?.takeIf { it.loginMethod != LoginMethods.BRIDGE }
    if (user == null) {
      call.refuse(HttpStatusCode.Unauthorized, Refusal(Problem.NOT_LOGGED_IN))
      return@onCall
    }
    call.attributes.put(LOGGED_IN, LoggedIn(user, strong = logins.administratorProblem(user) == null))
  }
}

class RequireBridgeKeyConfig {
  /** The user of a bridge key ([net.eratiem.randogeddon.server.account.BridgeKeys.userOf]); `null`: none. */
  lateinit var userOf: suspend (String) -> User?
}

/** The bridge key a call carries (`Authorization: Bearer <key>`). */
val ApplicationCall.bridgeKey: String?
  get() = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

/**
 * Randogeddon Bridge's routes (`/bridge/…`): a call needs a bridge key that hasn't ended or expired (401 otherwise),
 * and nothing else takes one, so a key reaches only who the user is, their sessions and a session's connection.
 */
val RequireBridgeKey = createRouteScopedPlugin("RequireBridgeKey", ::RequireBridgeKeyConfig) {
  val userOf = pluginConfig.userOf
  onCall { call ->
    val user = call.bridgeKey?.let { userOf(it) }
    if (user == null) {
      call.refuse(HttpStatusCode.Unauthorized, Refusal(Problem.BRIDGE_KEY_GONE))
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
    // RequireLogin has answered (nobody is logged in), or a guard before this one: a second answer would fail
    if (call.response.isCommitted || !call.attributes.contains(LOGGED_IN)) return@onCall
    val me = call.actor
    val id = call.parameters["id"].orEmpty()
    if (logins.maySee(me, id)) {
      logins.roleIn(me, id)?.let { call.attributes.put(SESSION_ROLE, it) }
      return@onCall
    }
    // An Administrator whose login may not act as one is told why (two-factor login, or the provider), like on the
    // admin routes; everyone else that they have no part in it
    val account = call.loggedIn.user
    val problem = logins.administratorProblem(account).takeIf { account.administrator }
    call.refuse(HttpStatusCode.Forbidden, problem ?: Refusal.of(Problem.NOT_PART, "name" to me.name, "id" to id))
  }
}

/**
 * Lets a call through only for Administrators whose login may act as one ([LoginCheck.administratorProblem]: through
 * single sign-on, or with two-factor login where [TrackerConfig.adminPasswordLogin] allows that), 403 otherwise; goes
 * inside [RequireLogin], which provides [loggedIn].
 * Checked on every call, so hiding the panel in the web app is not what protects it.
 */
val RequireAdministrator = createRouteScopedPlugin("RequireAdministrator", ::RequireLoginConfig) {
  val logins = pluginConfig.logins
  onCall { call ->
    // RequireLogin has answered (nobody is logged in), or a guard before this one: a second answer would fail
    if (call.response.isCommitted || !call.attributes.contains(LOGGED_IN)) return@onCall
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
    // RequireLogin has answered (nobody is logged in), or a guard before this one: a second answer would fail
    if (call.response.isCommitted || !call.attributes.contains(LOGGED_IN)) return@onCall
    val user = call.loggedIn.user
    val role = logins.roleIn(user, call.parameters["id"].orEmpty())
    role?.let { call.attributes.put(SESSION_ROLE, it) }
    if (role in roles) return@onCall
    val problem = logins.administratorProblem(user)
    when {
      problem == null -> Unit
      user.administrator -> call.refuse(HttpStatusCode.Forbidden, problem)
      else -> call.refuse(HttpStatusCode.Forbidden, Refusal.of(Problem.ONLY_ROLES, "roles" to roles.joinToString(",") { it.code }))
    }
  }
}
