package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.database.LoginSessionStore
import net.eratiem.randogeddon.database.TwoFactorStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.util.ExpiringMap
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.TotpSetup
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * Two-factor login (TOTP): set up with a QR code, confirmed with a first code (then it counts, with 10 recovery
 * codes), checked at every login after the password. Admins can't turn it off without single sign-on (with it, they
 * log in through the provider and its own two-factor check); everyone else can, with a code.
 */
@Single
class TwoFactor(
  private val store: TwoFactorStore,
  private val config: TrackerConfig,
  private val logins: LoginSessionStore,
) {
  private val box = SecretBox.from(config.totpKey, config.stateDir.resolve("totp.key"))

  /** What is stored of a recovery code: keyed with the server's key (41 bits are guessed at once from a plain hash). */
  private fun codeHash(code: String): String = box.mac("recovery codes", RecoveryCodes.normalize(code))

  /** A new secret for [user]; it counts once [enable] confirms it. Refused while two-factor login is on. */
  suspend fun setUp(user: User): TotpSetup {
    // With single sign-on, an Administrator's second factor is the provider's
    config.requireOwn(user)
    if (user.twoFactor) throw AccountProblem(HttpStatusCode.Conflict, Problem.TWO_FACTOR_ON)
    val secret = Totp.newSecret()
    store.setUp(user.id, box.seal(secret))
    val uri = Totp.uri(secret, user.name)
    val code = qr(uri)
    return TotpSetup(Base32.encode(secret), uri, code.svg, code.rows)
  }

  /**
   * The first code from the new secret turns it on; the recovery codes, shown once. An Administrator's other logins end
   * (all but [keep], the token of the login that turned it on): a login that never passed a code doesn't become one
   * that may act as an Administrator by it.
   */
  suspend fun enable(user: User, code: String, now: Instant = Instant.now(), keep: String? = null): List<String> {
    config.requireOwn(user)
    val state = store.of(user.id)
    if (state == null || state.enabledAt != null) {
      throw AccountProblem(HttpStatusCode.Conflict, if (state == null) Problem.SET_UP_FIRST else Problem.TWO_FACTOR_ON)
    }
    val step = Totp.match(box.open(state.secret), code, now.toEpochMilli())
      ?: throw AccountProblem(HttpStatusCode.BadRequest, Problem.CODE_MISMATCH)
    val codes = RecoveryCodes.generate()
    store.enable(user.id, codes.map(::codeHash), now)
    store.useStep(user.id, step)
    logger.info("{} turned two-factor login on", user.name)
    if (user.administrator) {
      val ended = logins.deleteOthers(user.id, LoginMethods.BROWSER, keep?.let(::tokenHash))
      if (ended > 0) logger.info("{}'s other logins ended ({})", user.name, ended)
    }
    return codes
  }

  /**
   * Whether [code] is [user]'s: a TOTP code (each one once), or one of their recovery codes (used up by this). A user
   * without two-factor login has no codes.
   */
  suspend fun check(user: User, code: String, now: Instant = Instant.now()): Boolean {
    val state = store.of(user.id)?.takeIf { it.enabledAt != null } ?: return false
    if (RecoveryCodes.looksLike(code)) {
      // A code from before the keyed hash is still taken (its plain hash), until it is used or the codes are replaced
      val used = store.useCode(user.id, codeHash(code), now) || store.useCode(user.id, RecoveryCodes.hash(code), now)
      if (used) logger.info("{} used a recovery code ({} left)", user.name, store.codesLeft(user.id))
      return used
    }
    val step = Totp.match(box.open(state.secret), code, now.toEpochMilli()) ?: return false
    return store.useStep(user.id, step)
  }

  /** Off, after a code (not for Administrators: without single sign-on they need it, with it it's the provider's). */
  suspend fun disable(user: User, code: String) {
    config.requireOwn(user)
    if (user.administrator) throw AccountProblem(HttpStatusCode.Forbidden, Problem.ADMINISTRATORS_NEED_TWO_FACTOR)
    if (!check(user, code)) throw AccountProblem(HttpStatusCode.Forbidden, Problem.WRONG_CODE)
    store.disable(user.id)
    logger.info("{} turned two-factor login off", user.name)
  }

  /** New recovery codes after a code; the old ones stop working. */
  suspend fun newCodes(user: User, code: String): List<String> {
    config.requireOwn(user)
    if (!check(user, code)) throw AccountProblem(HttpStatusCode.Forbidden, Problem.WRONG_CODE)
    val codes = RecoveryCodes.generate()
    store.replaceCodes(user.id, codes.map(::codeHash))
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
  private class Pending(val userId: Uuid, var wrongCodes: Int = 0)

  private val pending = ExpiringMap<String, Pending>(VALID)

  fun create(userId: Uuid, now: Long = System.currentTimeMillis()): String {
    val token = newToken()
    pending.put(tokenHash(token), Pending(userId), now)
    return token
  }

  /** Whose pending login [token] is; `null` once it expired, was finished or had too many wrong codes. */
  fun userOf(token: String, now: Long = System.currentTimeMillis()): Uuid? = pending.get(tokenHash(token), now)?.userId

  /** A wrong code; after 5 the pending login is gone (the password is needed again). */
  fun wrongCode(token: String) {
    val key = tokenHash(token)
    // Whatever its age: an expired one has no user for [userOf] already
    val entry = pending.peek(key) ?: return
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
