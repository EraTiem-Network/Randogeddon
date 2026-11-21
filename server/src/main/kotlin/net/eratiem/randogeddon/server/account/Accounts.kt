package net.eratiem.randogeddon.server.account

import java.lang.invoke.MethodHandles
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.eratiem.randogeddon.database.LoginSessionStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.minecraftTunnelKey
import net.eratiem.randogeddon.shared.MINECRAFT_DIG
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.MySlot
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.UserNames
import org.koin.core.annotation.Single

private val logger = logger(MethodHandles.lookup().lookupClass())

/** How long a login lasts; logging in again starts a new one. */
val LOGIN_DURATION = 30.days

/**
 * How long a login through the provider (or a CLI link) lasts: an Administrator's. Their place in the provider's
 * group is only asked at a login, so it is asked again each day (one click while the provider's session lasts);
 * leaving the group, or the provider, shows within a day.
 */
val SSO_LOGIN_DURATION = 1.days

/** The most logins of one kind (password, the provider, …) an account has at once; a new one ends the oldest. */
const val MAX_LOGINS = 20

/** How long a login made by [method] lasts (a CLI link's as short as the provider's: it stands in for a password). */
fun loginDuration(method: String): Duration =
  if (method == LoginMethods.OIDC || method == LoginMethods.LINK) SSO_LOGIN_DURATION else LOGIN_DURATION

/**
 * What the access check needs: whose login a cookie token is, and which sessions that user is part of. Every active
 * account gets in (accounts belong to people, not to a session); what they see of the sessions is checked per session.
 */
/** Until when a time-out lasts, as the person reads it (the server's time: UTC, said so). */
internal val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

/** Why [user] is kept out at [now] (timed out until when, or suspended; with the reason if one was given); `null`: they aren't. */
internal fun keptOutRefusal(user: User, now: Instant): Refusal? {
  val block = user.block?.takeIf { it.holds(now) } ?: return null
  val reason = listOfNotNull(block.reason?.let { "reason" to it }).toTypedArray()
  return block.until?.let { Refusal.of(Problem.ACCOUNT_TIMED_OUT, "until" to UNTIL.format(it), *reason) }
    ?: Refusal.of(Problem.ACCOUNT_SUSPENDED, *reason)
}

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

/**
 * With single sign-on through [provider]: only a login through it acts as an Administrator. No password, no CLI link:
 * who is an Administrator, and with which second factor, is the provider's to say, at every login.
 */
internal fun ssoAdministratorProblem(user: User, provider: String): Refusal? = when {
  !user.administrator -> Refusal(Problem.ONLY_ADMINISTRATORS)
  user.loginMethod == LoginMethods.OIDC -> null
  else -> Refusal.of(Problem.ADMINISTRATOR_USE_SSO, "provider" to provider)
}

/**
 * Logging in and out, and which sessions the logged-in users are part of. With single sign-on configured
 * ([TrackerConfig.oidc]), Administrators act as such only after logging in through the provider, and can't log in
 * with their password.
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
    keptOutProblem(user) ?: config.oidc?.takeIf { user.administrator }?.let { Refusal.of(Problem.ADMINISTRATOR_PASSWORD, "provider" to it.name) }

  /**
   * Why [user] can't log in for now: an Administrator timed them out (until when) or suspended them, with the reason
   * if one was given. Told only after the right password, so nobody else learns it. `null`: they aren't kept out.
   */
  fun keptOutProblem(user: User, now: Instant = Instant.now()): Refusal? = keptOutRefusal(user, now)

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
  /** A new login for [user]; [providerSession]: its session at the provider, for a login through it. */
  suspend fun start(user: User, method: String = LoginMethods.PASSWORD, providerSession: String? = null): String {
    logger.info("{} logged in ({})", user.name, method)
    val token = newToken()
    logins.create(tokenHash(token), user.id, Instant.now().plus(loginDuration(method).toJavaDuration()), method = method, providerSession = providerSession)
    // Only so many logins of a kind at once: the oldest give way (a browser logged in long ago and never out)
    logins.keepNewest(user.id, method, MAX_LOGINS)
    return token
  }

  override suspend fun userOf(token: String): User? =
    logins.loginOf(tokenHash(token))?.let { (id, method) ->
      // Someone kept out has no logins left; a login that is there all the same (made in the same moment) counts for nothing
      users.findActive(id)?.takeUnless { it.keptOut() }?.copy(loginMethod = method)
    }

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
   * The slots of [person] in [session] (a slot played together is theirs too, and every slot open to all) with game
   * and password; then its hosted slots: for its Session-Managers all of them ([manages]: with their passwords, to set
   * the game's server up), for a player the ones open to all and the ones naming them.
   */
  suspend fun of(session: LiveSession, person: String?, manages: Boolean = false): List<MySlot> {
    val room = Room.read(session.roomJson) ?: return emptyList()
    val passwords = current(session)
    val games = room.players.associate { it.name to it.game }
    val hosted = session.setup?.hosted.orEmpty()
    // A locked slot has no password until its key is found
    val locked = lockedNow(session)
    // Their own slots and the ones open to all of the session's players
    val own = session.playedBy(person).filter { it !in hosted }
      .mapNotNull { slot ->
        passwords[slot]?.let { MySlot(slot, games[slot].orEmpty(), if (slot in locked) "" else it, locked = slot in locked) }
      }
    if (person == null && !manages) return own
    val direct = session.setup?.direct.orEmpty()
    // Its Session-Managers get every hosted slot; a player the open ones and the ones naming them
    val shown = if (manages) hosted.sorted() else session.hostedFor(person)
    return own + shown.mapNotNull { slot ->
      passwords[slot]?.let {
        // Its Minecraft server run here: the bridge's tunnel to it for everyone who gets the slot
        val tunnel = if (slot in direct && games[slot] == MINECRAFT_DIG && slot !in locked) minecraftTunnelKey(it, slot) else ""
        MySlot(slot, games[slot].orEmpty(), if (manages && slot !in locked) it else "", hosted = true, locked = slot in locked, tunnel = tunnel)
      }
    }
  }

  /** [session]'s slots still locked, as its summary sees them (their key not found yet). */
  private suspend fun lockedNow(session: LiveSession): Set<String> = runCatching {
    session.summary().players.filter { it.lock?.unlocked == false }.map { it.name }.toSet()
  }.getOrDefault(emptySet())

  /** The locks as the gateway last got them, by seed: written again only when they change. */
  private val synced = mutableMapOf<String, Set<String>>()

  /**
   * Keeps the gateway's locks in step with [session]'s summary: a slot opens once its key has turned up. Only once the
   * summary knows the locks (the tracker has read World Locks' slot data: until then no slot has lock info, and the
   * locks of the session's creation stay); a session without locks needs nothing, a finished one stays as it is.
   */
  private suspend fun syncLocks(session: LiveSession, seed: String) {
    if (session.finished) return
    val players = runCatching { session.summary().players }.getOrDefault(emptyList())
    if (players.none { it.lock != null }) return
    val locked = players.filter { it.lock?.unlocked == false }.map { it.name }.toSet()
    if (synced[seed] == locked) return
    runCatching { store.setLocked(seed, locked) }
      .onSuccess { synced[seed] = locked; logger.info("Locked slots of {}: {}", session.id, locked.ifEmpty { setOf("none") }.joinToString()) }
      .onFailure { logger.warn("Could not write the locked slots of {}: {}", session.id, it.message) }
  }

  /** Creates the passwords as soon as a session's seed has its room.json, so the gateway knows them before a player joins. */
  fun watch(scope: CoroutineScope) = scope.launch {
    val ready = mutableSetOf<String>()
    while (true) {
      for (session in sessions.all()) {
        val seed = Room.read(session.roomJson)?.seedName ?: continue
        syncLocks(session, seed)
        if (seed in ready) continue
        runCatching { current(session) }
          .onSuccess { ready += seed; logger.info("Slot passwords ready for {} (seed {}, {} slots)", session.id, seed, it.size) }
          .onFailure { logger.warn("Could not create the slot passwords of {}: {}", session.id, it.message) }
      }
      delay(5.seconds)
    }
  }
}
