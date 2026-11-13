package net.eratiem.randogeddon.server.account

import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.database.TwoFactorStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.TotpSetup
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * Two-factor login (TOTP): set up with a QR code, confirmed with a first code (then it counts, with 10 recovery
 * codes), checked at every login after the password. Admins can't turn it off; everyone else can, with a code.
 */
@Single
class TwoFactor(private val store: TwoFactorStore, config: TrackerConfig) {
  private val box = SecretBox.from(config.totpKey, config.stateDir.resolve("totp.key"))

  /** A new secret for [user]; it counts once [enable] confirms it. Refused while two-factor login is on. */
  suspend fun setUp(user: User): TotpSetup {
    if (user.twoFactor) throw AccountProblem(HttpStatusCode.Conflict, "Two-factor login is on already")
    val secret = Totp.newSecret()
    store.setUp(user.id, box.seal(secret))
    val uri = Totp.uri(secret, user.name)
    val code = qr(uri)
    return TotpSetup(Base32.encode(secret), uri, code.svg, code.rows)
  }

  /** The first code from the new secret turns it on; the recovery codes, shown once. */
  suspend fun enable(user: User, code: String, now: Instant = Instant.now()): List<String> {
    val state = store.of(user.id)
    if (state == null || state.enabledAt != null) {
      throw AccountProblem(HttpStatusCode.Conflict, if (state == null) "Set it up first" else "Two-factor login is on already")
    }
    val step = Totp.match(box.open(state.secret), code, now.toEpochMilli())
      ?: throw AccountProblem(HttpStatusCode.BadRequest, "That code doesn't fit: check the authenticator's clock, or scan again")
    val codes = RecoveryCodes.generate()
    store.enable(user.id, codes.map(RecoveryCodes::hash), now)
    store.useStep(user.id, step)
    logger.info("{} turned two-factor login on", user.name)
    return codes
  }

  /**
   * Whether [code] is [user]'s: a TOTP code (each one once), or one of their recovery codes (used up by this). A user
   * without two-factor login has no codes.
   */
  suspend fun check(user: User, code: String, now: Instant = Instant.now()): Boolean {
    val state = store.of(user.id)?.takeIf { it.enabledAt != null } ?: return false
    if (RecoveryCodes.looksLike(code)) {
      val used = store.useCode(user.id, RecoveryCodes.hash(code), now)
      if (used) logger.info("{} used a recovery code ({} left)", user.name, store.codesLeft(user.id))
      return used
    }
    val step = Totp.match(box.open(state.secret), code, now.toEpochMilli()) ?: return false
    return store.useStep(user.id, step)
  }

  /** Off, after a code (not for admins: they need it). */
  suspend fun disable(user: User, code: String) {
    if (user.administrator) throw AccountProblem(HttpStatusCode.Forbidden, "Admins need two-factor login")
    if (!check(user, code)) throw AccountProblem(HttpStatusCode.Forbidden, "That code doesn't fit")
    store.disable(user.id)
    logger.info("{} turned two-factor login off", user.name)
  }

  /** New recovery codes after a code; the old ones stop working. */
  suspend fun newCodes(user: User, code: String): List<String> {
    if (!check(user, code)) throw AccountProblem(HttpStatusCode.Forbidden, "That code doesn't fit")
    val codes = RecoveryCodes.generate()
    store.replaceCodes(user.id, codes.map(RecoveryCodes::hash))
    return codes
  }

  suspend fun codesLeft(user: User): Int = if (user.twoFactor) store.codesLeft(user.id) else 0

  /** The host's way back for an admin who lost both the authenticator and the recovery codes. */
  suspend fun reset(id: Uuid) = store.disable(id)
}

/**
 * Logins whose password was right and that wait for the second step (a code, or setting it up): by a random token,
 * for 5 minutes, at most 5 wrong codes. In memory: a restart only means typing the password again.
 */
@Single
class PendingLogins {
  private data class Pending(val userId: Uuid, val expiresAt: Long, var wrongCodes: Int = 0)

  private val pending = ConcurrentHashMap<String, Pending>()

  fun create(userId: Uuid, now: Long = System.currentTimeMillis()): String {
    pending.entries.removeIf { it.value.expiresAt <= now }
    val token = newToken()
    pending[tokenHash(token)] = Pending(userId, now + VALID.inWholeMilliseconds)
    return token
  }

  /** Whose pending login [token] is; `null` once it expired, was finished or had too many wrong codes. */
  fun userOf(token: String, now: Long = System.currentTimeMillis()): Uuid? =
    pending[tokenHash(token)]?.takeIf { it.expiresAt > now }?.userId

  /** A wrong code; after 5 the pending login is gone (the password is needed again). */
  fun wrongCode(token: String) {
    val key = tokenHash(token)
    val entry = pending[key] ?: return
    if (++entry.wrongCodes >= MAX_WRONG_CODES) pending.remove(key)
  }

  fun finish(token: String) {
    pending.remove(tokenHash(token))
  }

  private companion object {
    val VALID = 5.minutes
    const val MAX_WRONG_CODES = 5
  }
}
