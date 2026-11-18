package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.routing.get
import io.ktor.util.AttributeKey
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.LoginMethods
import net.eratiem.randogeddon.server.config.refuse
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.SessionRole

/**
 * The logged-in user of a call that passed [RequireLogin]. [strong]: this login may act as an Administrator
 * ([LoginCheck.administratorProblem]: with two-factor login, or through single sign-on); never a bridge key.
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
    if (!call.attributes.contains(LOGGED_IN)) return@onCall // RequireLogin has answered: nobody is logged in
    val me = call.actor
    val id = call.parameters["id"].orEmpty()
    if (logins.maySee(me, id)) return@onCall
    // An Administrator whose login may not act as one is told why (two-factor login, or the provider), like on the
    // admin routes; everyone else that they have no part in it
    val account = call.loggedIn.user
    val problem = logins.administratorProblem(account).takeIf { account.administrator }
    call.refuse(HttpStatusCode.Forbidden, problem ?: Refusal.of(Problem.NOT_PART, "name" to me.name, "id" to id))
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
    if (!call.attributes.contains(LOGGED_IN)) return@onCall // RequireLogin has answered: nobody is logged in
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
    if (!call.attributes.contains(LOGGED_IN)) return@onCall // RequireLogin has answered: nobody is logged in
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
