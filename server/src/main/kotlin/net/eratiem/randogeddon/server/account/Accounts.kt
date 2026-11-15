package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.database.LoginSessionStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.MySlot
import net.eratiem.randogeddon.shared.UserNames
import net.eratiem.randogeddon.shared.SessionRole
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val logger = logger(MethodHandles.lookup().lookupClass())

/** How long a login lasts; logging in again starts a new one. */
val LOGIN_DURATION = 30.days

/**
 * What the access check needs: whose login a cookie token is, and which sessions that user is part of. Every active
 * account gets in (accounts belong to people, not to a session); what they see of the sessions is checked per session.
 */
interface LoginCheck {
  suspend fun userOf(token: String): User?

  /** The user sees session [id]: an Administrator, or anyone with a part in it. */
  fun maySee(user: User, id: String): Boolean

  /** The user's part in session [id] (`null`: none). */
  fun roleIn(user: User, id: String): SessionRole?

  /**
   * Why [user]'s login can't act as an Administrator; `null`: it can. Without single sign-on: an Administrator with
   * two-factor login.
   */
  fun administratorProblem(user: User): Refusal? = when {
    !user.administrator -> Refusal(Problem.ONLY_ADMINISTRATORS)
    !user.twoFactor -> Refusal(Problem.NEEDS_TWO_FACTOR)
    else -> null
  }
}

/** With single sign-on through [provider]: only a login through it (or a CLI link) acts as an Administrator. */
internal fun ssoAdministratorProblem(user: User, provider: String): Refusal? = when {
  !user.administrator -> Refusal(Problem.ONLY_ADMINISTRATORS)
  user.loginMethod == LoginMethods.OIDC || user.loginMethod == LoginMethods.LINK -> null
  else -> Refusal.of(Problem.ADMINISTRATOR_USE_SSO, "provider" to provider)
}

/**
 * Logging in and out, and which sessions the logged-in users are part of. With single sign-on configured
 * ([TrackerConfig.oidc]), Administrators act as such only after logging in through the provider (or a CLI link), and
 * not with their password.
 */
@Single
class LoginService(
  private val users: UserStore,
  private val logins: LoginSessionStore,
  private val sessions: SessionList,
  private val config: TrackerConfig,
) : LoginCheck {
  override fun administratorProblem(user: User): Refusal? {
    val oidc = config.oidc ?: return super.administratorProblem(user)
    return ssoAdministratorProblem(user, oidc.name)
  }

  /** Why [user] can't log in with their password; `null`: they can. With single sign-on, Administrators can't. */
  fun passwordLoginProblem(user: User): Refusal? =
    config.oidc?.takeIf { user.administrator }?.let { Refusal.of(Problem.ADMINISTRATOR_PASSWORD, "provider" to it.name) }

  /**
   * The token for the session cookie, or `null` if the name (or e-mail address) or the password is wrong. Invited
   * users who haven't signed up have no password and can't log in.
   */
  suspend fun login(nameOrEmail: String, password: String): String? = verify(nameOrEmail, password)?.let { start(it) }

  /** The user whose name (or address) and password these are; `null` if either is wrong. */
  suspend fun verify(nameOrEmail: String, password: String): User? {
    val user = users.findLogin(nameOrEmail.trim())
    // Argon2 is CPU work; an unknown name is checked against a dummy hash so it takes as long as a wrong password
    val valid = withContext(Dispatchers.Default) { PasswordHasher.verify(password, user?.passwordHash ?: PasswordHasher.dummyHash) }
    return user.takeIf { valid }
  }

  suspend fun user(id: Uuid): User? = users.findActive(id)

  /** A new login of [user] (after the password, or right after signing up; [method]: how); its token for the cookie. */
  suspend fun start(user: User, method: String = LoginMethods.PASSWORD): String {
    logger.info("{} logged in ({})", user.name, method)
    val token = newToken()
    logins.create(tokenHash(token), user.id, Instant.now().plus(LOGIN_DURATION.toJavaDuration()), method = method)
    return token
  }

  override suspend fun userOf(token: String): User? =
    logins.loginOf(tokenHash(token))?.let { (id, method) -> users.findActive(id)?.copy(loginMethod = method) }

  suspend fun logout(token: String) = logins.delete(tokenHash(token))

  override fun maySee(user: User, id: String): Boolean = sessions.canSee(user, id)

  override fun roleIn(user: User, id: String): SessionRole? = sessions.roleIn(user, id)

  /** Removes expired logins every few hours, for as long as [scope] runs. */
  fun cleanUp(scope: CoroutineScope) = scope.launch {
    while (true) {
      runCatching { logins.deleteExpired() }
        .onSuccess { if (it > 0) logger.info("Removed {} expired logins", it) }
        .onFailure { logger.warn("Could not remove expired logins: {}", it.message) }
      delay(6.hours)
    }
  }
}

/**
 * The game passwords of each session's seed: every slot a client can connect as gets one (the World Locks slot too,
 * for the host's hints from there). The gateway checks them; players see their own on the dashboard.
 */
@Single
class SlotPasswords(private val store: SlotPasswordStore, private val sessions: LiveSessions) {
  /** Slot name -> password of [session]'s seed, creating missing ones; empty while its room.json isn't there. */
  suspend fun current(session: LiveSession): Map<String, String> {
    val room = Room.read(session.roomJson) ?: return emptyMap()
    val seed = room.seedName ?: return emptyMap()
    return store.ensure(seed, room.players.map { it.name }, { PasswordGenerator.generate() })
  }

  /**
   * The slots of [person] in [session] (a slot played together is theirs too) with game and password; then its hosted
   * slots for everyone who plays in it or manages it ([manages]: with their passwords, to set the game's server up).
   */
  suspend fun of(session: LiveSession, person: String?, manages: Boolean = false): List<MySlot> {
    val room = Room.read(session.roomJson) ?: return emptyList()
    val passwords = current(session)
    val games = room.players.associate { it.name to it.game }
    val hosted = session.setup?.hosted.orEmpty()
    val own = person?.takeIf { it != UserNames.HOSTED }?.let { session.people()[it] }.orEmpty().filter { it !in hosted }
      .mapNotNull { slot -> passwords[slot]?.let { MySlot(slot, games[slot].orEmpty(), it) } }
    if (person == null && !manages) return own
    return own + hosted.sorted().mapNotNull { slot ->
      passwords[slot]?.let { MySlot(slot, games[slot].orEmpty(), if (manages) it else "", hosted = true) }
    }
  }

  /** Creates the passwords as soon as a session's seed has its room.json, so the gateway knows them before a player joins. */
  fun watch(scope: CoroutineScope) = scope.launch {
    val ready = mutableSetOf<String>()
    while (true) {
      for (session in sessions.all()) {
        val seed = Room.read(session.roomJson)?.seedName ?: continue
        if (seed in ready) continue
        runCatching { current(session) }
          .onSuccess { ready += seed; logger.info("Slot passwords ready for {} (seed {}, {} slots)", session.id, seed, it.size) }
          .onFailure { logger.warn("Could not create the slot passwords of {}: {}", session.id, it.message) }
      }
      delay(5.seconds)
    }
  }
}
