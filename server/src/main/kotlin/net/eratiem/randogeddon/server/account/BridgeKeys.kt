package net.eratiem.randogeddon.server.account

import io.ktor.http.HttpStatusCode
import java.lang.invoke.MethodHandles
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import net.eratiem.randogeddon.database.LoginSessionStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.util.ExpiringMap
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.BridgeKey
import net.eratiem.randogeddon.shared.BridgeKeyView
import net.eratiem.randogeddon.shared.Problem
import org.koin.core.annotation.Single

private val logger = logger(MethodHandles.lookup().lookupClass())

/** What a key starts with, so it is told apart from a cookie token (and found by secret scanners). */
const val BRIDGE_KEY_PREFIX = "rgb_"

/** The first characters of a key's hash that name it in the list (enough to tell a person's keys apart). */
private const val ID_LENGTH = 16

/** The most keys one account has at once. */
const val MAX_KEYS = 10

/** How often at most a key's last use is noted. */
private val TOUCH_EVERY = 5.minutes

/**
 * Keys of Randogeddon Bridge: each a login of its own kind ([LoginMethods.BRIDGE], [LOGIN_DURATION] like the others),
 * sent by the app as a bearer token and only taken on `/bridge/…` (never as the cookie). Stored as their SHA-256, like
 * the cookie tokens; a new password ends them with every other login.
 */
@Single
class BridgeKeys(private val users: UserStore, private val logins: LoginSessionStore) {
  /** A new key for [user], shown once; [AccountProblem] (409) once they have [MAX_KEYS]. */
  suspend fun make(user: User, now: Instant = Instant.now()): BridgeKey {
    val key = BRIDGE_KEY_PREFIX + newToken(48)
    val expires = now.plus(LOGIN_DURATION.toJavaDuration())
    // Counted and made at once: keys asked for at the same moment don't pass the limit together
    if (!logins.createWithin(MAX_KEYS, tokenHash(key), user.id, expires, now, LoginMethods.BRIDGE)) {
      throw AccountProblem(HttpStatusCode.Conflict, Problem.TOO_MANY_KEYS, "max" to MAX_KEYS)
    }
    logger.info("{} made a bridge key", user.name)
    return BridgeKey(key, expires.toEpochMilli())
  }

  /**
   * The keys noted as used within [TOUCH_EVERY], by their hash: a bridge asks every few seconds, the database gets the
   * note at most once in that time (in memory, so a restart notes each key again).
   */
  private val touched = ExpiringMap<String, Unit>(TOUCH_EVERY)

  /** The user of [key], if it is a bridge key that hasn't ended or expired; notes that it was used. */
  suspend fun userOf(key: String, now: Instant = Instant.now()): User? {
    if (!key.startsWith(BRIDGE_KEY_PREFIX)) return null
    val hash = tokenHash(key)
    val (id, method) = logins.loginOf(hash, now) ?: return null
    if (method != LoginMethods.BRIDGE) return null
    val user = users.findActive(id)?.takeUnless { it.keptOut(now) } ?: return null
    if (touched.putIfAbsent(hash, Unit, now.toEpochMilli())) logins.touch(hash, now, TOUCH_EVERY.toJavaDuration())
    return user.copy(loginMethod = method)
  }

  /** [user]'s keys that haven't expired, newest first. */
  suspend fun list(user: User): List<BridgeKeyView> = logins.logins(user.id, LoginMethods.BRIDGE).map {
    BridgeKeyView(it.tokenHash.take(ID_LENGTH), it.createdAt.toEpochMilli(), it.expiresAt.toEpochMilli(), it.lastUsedAt?.toEpochMilli())
  }

  /** Ends [user]'s key [id] (from [list]); whether there was one. */
  suspend fun end(user: User, id: String): Boolean {
    if (id.length != ID_LENGTH) return false
    val hash = logins.logins(user.id, LoginMethods.BRIDGE).firstOrNull { it.tokenHash.startsWith(id) }?.tokenHash ?: return false
    return logins.delete(user.id, hash).also { if (it) logger.info("{} ended a bridge key", user.name) }
  }

  /** The app's logout: ends [key] itself. */
  suspend fun logout(user: User, key: String) {
    logins.delete(user.id, tokenHash(key))
    logger.info("{} logged the bridge out", user.name)
  }
}
