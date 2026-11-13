package net.eratiem.randogeddon.server.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

  /** The user sees session [id]: an admin, or one of its players. */
  fun maySee(user: User, id: String): Boolean
}

/** Logging in and out, and which sessions the logged-in users are part of. */
@Single
class LoginService(
  private val users: UserStore,
  private val logins: LoginSessionStore,
  private val sessions: SessionList,
) : LoginCheck {
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

  /** A new login of [user] (after the password, or right after signing up); its token for the cookie. */
  suspend fun start(user: User): String {
    logger.info("{} logged in", user.name)
    val token = newToken()
    logins.create(tokenHash(token), user.id, Instant.now().plus(LOGIN_DURATION.toJavaDuration()))
    return token
  }

  override suspend fun userOf(token: String): User? = logins.userOf(tokenHash(token))?.let { users.findActive(it) }

  suspend fun logout(token: String) = logins.delete(tokenHash(token))

  override fun maySee(user: User, id: String): Boolean = sessions.canSee(user, id)

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

  /** The slots of [person] in [session] (a slot played together is theirs too) with game and password. */
  suspend fun of(session: LiveSession, person: String): List<MySlot> {
    val room = Room.read(session.roomJson) ?: return emptyList()
    val passwords = current(session)
    val games = room.players.associate { it.name to it.game }
    return session.people()[person].orEmpty().mapNotNull { slot ->
      passwords[slot]?.let { MySlot(slot, games[slot].orEmpty(), it) }
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
