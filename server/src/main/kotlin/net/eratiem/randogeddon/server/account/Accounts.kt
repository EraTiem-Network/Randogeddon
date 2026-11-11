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
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.server.session.CurrentSession
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.MySlot
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val logger = logger(MethodHandles.lookup().lookupClass())

/** How long a login lasts; logging in again starts a new one. */
val LOGIN_DURATION = 30.days

/** What the access check needs: whose login a cookie token is, and whether that user is part of the session. */
interface LoginCheck {
  suspend fun userOf(token: String): User?

  /** The person of `session.yaml` this user is; `null`: not part of the session. */
  fun personOf(user: User): String?
}

/** Logging in and out, and who of the logged-in users is part of the session. */
@Single
class LoginService(
  private val users: UserStore,
  private val logins: LoginSessionStore,
  private val session: CurrentSession,
) : LoginCheck {
  /** The token for the session cookie, or `null` if the name or password is wrong. */
  suspend fun login(name: String, password: String): String? {
    val user = users.findActive(name.trim())
    // Argon2 is CPU work; an unknown name is checked against a dummy hash so it takes as long as a wrong password
    val valid = withContext(Dispatchers.Default) { PasswordHasher.verify(password, user?.passwordHash ?: PasswordHasher.dummyHash) }
    if (user == null || !valid) return null
    val token = newToken()
    logins.create(tokenHash(token), user.id, Instant.now().plus(LOGIN_DURATION.toJavaDuration()))
    logger.info("{} logged in", user.name)
    return token
  }

  override suspend fun userOf(token: String): User? = logins.userOf(tokenHash(token))?.let { users.findActive(it) }

  suspend fun logout(token: String) = logins.delete(tokenHash(token))

  /** Same name as the person, without regard to case. */
  override fun personOf(user: User): String? = session.people().keys.firstOrNull { it.equals(user.name, ignoreCase = true) }

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
 * The game passwords of the current seed: every slot a client can connect as gets one (the World Locks slot too, for
 * the host's hints from there). The gateway checks them; players see their own on the dashboard.
 */
@Single
class SlotPasswords(
  private val config: TrackerConfig,
  private val store: SlotPasswordStore,
  private val session: CurrentSession,
) {
  /** Slot name -> password of the seed in room.json, creating missing ones; empty while there is no seed. */
  suspend fun current(): Map<String, String> {
    val room = Room.read(config.roomJson) ?: return emptyMap()
    val seed = room.seedName ?: return emptyMap()
    return store.ensure(seed, room.players.map { it.name }, { PasswordGenerator.generate() })
  }

  /** The worlds of [person] (a world played together is theirs too) with game and password. */
  suspend fun of(person: String): List<MySlot> {
    val room = Room.read(config.roomJson) ?: return emptyList()
    val passwords = current()
    val games = room.players.associate { it.name to it.game }
    return session.people()[person].orEmpty().mapNotNull { slot ->
      passwords[slot]?.let { MySlot(slot, games[slot].orEmpty(), it) }
    }
  }

  /** Creates the passwords as soon as a new seed appears, so the gateway knows them before the first player joins. */
  fun watch(scope: CoroutineScope) = scope.launch {
    var seed: String? = null
    while (true) {
      val current = Room.read(config.roomJson)?.seedName
      if (current != null && current != seed) {
        runCatching { current() }
          .onSuccess { seed = current; logger.info("Slot passwords ready for seed {} ({} slots)", current, it.size) }
          .onFailure { logger.warn("Could not create the slot passwords: {}", it.message) }
      }
      delay(5.seconds)
    }
  }
}
