package net.eratiem.randogeddon.server.account

import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.eratiem.randogeddon.database.PasswordResetStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.ForgotRequest
import net.eratiem.randogeddon.shared.PasswordRules
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.ResetView
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.shared.i18n.texts
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

private val logger = logger(MethodHandles.lookup().lookupClass())

/** How long a password reset link works. */
val PASSWORD_RESET_VALID = 15.minutes

/** The dots that stand for what an address hides: always as many, whatever its length. */
private const val HIDDEN = "•••"

/**
 * [address] partly hidden (`m•••@p•••.me`): the first character of the local part and of the domain, [HIDDEN] after
 * each, and the domain's last label; in lower case (addresses are compared without regard to case). [address] is a
 * valid one ([EmailAddresses.valid]).
 */
internal fun hiddenAddress(address: String): String {
  val clean = address.trim().lowercase()
  val local = clean.substringBeforeLast('@')
  val domain = clean.substringAfterLast('@')
  return "${local.firstCharacter()}$HIDDEN@${domain.firstCharacter()}$HIDDEN.${domain.substringAfterLast('.')}"
}

/** The first character, a whole one (not half of a surrogate pair). */
private fun String.firstCharacter(): String = String(Character.toChars(codePointAt(0)))

/** First letters of addresses' local parts: every letter, the common ones more often. */
private const val LOCAL_FIRSTS = "abcdefghijklmnopqrstuvwxyzaabcdeffjjklmmnprsstt"

/** First letters of mail domains, as often as people's addresses have them (the big providers' more often). */
private const val DOMAIN_FIRSTS = "gggggggwwwwoooopppptttmmiiyyhhabcdefklnrsu"

/** Last labels of mail domains, as often as people's addresses have them. */
private val TOPS = listOf("com", "com", "com", "com", "com", "de", "de", "de", "de", "net", "org", "me", "eu", "io", "at", "ch")

/**
 * An address as [hiddenAddress] shows one, made up from [mac] (a keyed hash of what was typed, hex): the same for the
 * same input, and in the shape a real one has. A name's own first letter is the local part's half the time, as it is
 * in many people's addresses. [input]: the name or address it is made up for.
 */
internal fun madeUpAddress(mac: String, input: String): String {
  val bytes = HexFormat.of().parseHex(mac)
  fun number(i: Int) = bytes[i].toInt() and 0xff
  fun pick(i: Int, from: String) = from[number(i) % from.length]
  val initial = input.trim().lowercase().firstOrNull()?.takeIf { it in 'a'..'z' && number(3) < 128 } ?: pick(0, LOCAL_FIRSTS)
  return "$initial$HIDDEN@${pick(1, DOMAIN_FIRSTS)}$HIDDEN.${TOPS[number(2) % TOPS.size]}"
}

/**
 * Whether [typed] is [own] address, compared as the database compares addresses (without regard to case, the spaces
 * around it left out), and in constant time: their hashes are compared, so neither the length nor where they differ
 * shows in how long it takes.
 */
internal fun sameAddress(own: String, typed: String): Boolean {
  fun digest(address: String) =
    MessageDigest.getInstance("SHA-256").digest(EmailAddresses.normalize(address).lowercase().toByteArray())
  return MessageDigest.isEqual(digest(own), digest(typed))
}

/**
 * A forgotten password, reset by a mail to the account's address. Nothing here tells whether an account exists: the
 * first step ([hint]) answers every name or address with an address partly hidden (the account's, or one made up for
 * it with the server's key, the same each time); the second ([ask]) takes the whole address, typed, and always
 * answers the same, after the same time, whether a link went out or not. The link goes out only if the address is the
 * account's, the account logs in with its password (not kept out, not an Administrator who logs in through the
 * provider; an invitation has its own link), and it had fewer than [MAILS_PER_HOUR] such mails in the last hour. It
 * works [PASSWORD_RESET_VALID] long and once ([view], [reset]), a new one replaces it. The reset sets the password only
 * (two-factor login stays), ends every login of the account and tells the address. Offered only with a mail server
 * ([offered]): without one the mail, with its link, would go to the log.
 */
@Single
class PasswordResets(
  private val users: UserStore,
  private val resets: PasswordResetStore,
  private val logins: LoginService,
  private val twoFactor: TwoFactor,
  private val mailer: Mailer,
  private val config: TrackerConfig,
  private val pending: PendingLogins,
) {
  /** Whether it is offered: a mail server is set (the login form shows the link then). */
  val offered: Boolean get() = config.mail.host.isNotBlank()

  /** The links each account got, by its ID, and the second steps from each network address. */
  private val accountMails = Counts(1.hours)
  private val addressTries = Counts(1.hours)

  /** Where the second step's work runs: its answer never waits for it, so it takes as long whatever happens. */
  private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * The first step: the address of the account [account] names (a name, or an address) partly hidden ([hiddenAddress]).
   * An address is shown as typed (an account's is that one); a name by its account's address, or for a name without an
   * account (or without an address) one made up ([madeUpAddress]). [AccountProblem] (400) for nothing or too much.
   */
  suspend fun hint(account: String): String {
    val input = account.trim()
    if (input.isEmpty() || input.length > ForgotRequest.MAX_LENGTH) {
      throw AccountProblem(HttpStatusCode.BadRequest, Problem.NAME_OR_ADDRESS, "max" to ForgotRequest.MAX_LENGTH)
    }
    // Names have no `@`: this is an address, the account's if it has one, and no account needs to be looked up
    if ('@' in input) {
      val address = EmailAddresses.normalize(input)
      return if (EmailAddresses.valid(address)) hiddenAddress(address) else madeUp(input)
    }
    return users.findLogin(input)?.email?.let(::hiddenAddress) ?: madeUp(input)
  }

  private fun madeUp(input: String) = madeUpAddress(twoFactor.mac(HINT_PURPOSE, input.trim().lowercase()), input)

  /**
   * The second step: [email], typed for [account] (as [hint] took it), from the network address [client]. Counted per
   * network address ([ADDRESS_TRIES] an hour, [AccountProblem] 429 past it, as too many mails: that doesn't depend on
   * the account, and nobody typed anything wrong);
   * otherwise the work ([send]) runs on its own and this answers after [ANSWER_AFTER], whatever it comes to.
   * [language]: the request's, for the mail of an account that hasn't chosen one.
   */
  suspend fun ask(account: String, email: String, client: String, language: Language, now: Long = System.currentTimeMillis()) {
    synchronized(addressTries) {
      if (addressTries.of(client, now) >= ADDRESS_TRIES) {
        throw AccountProblem(HttpStatusCode.TooManyRequests, Problem.TOO_MANY_MAILS)
      }
      addressTries.add(client, now)
    }
    background.launch {
      try {
        send(account, email, language)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn("Could not handle a password reset: {}", e.message)
      }
    }
    delay(ANSWER_AFTER)
  }

  /**
   * What the second step does: the link to the account's address if [email] is it and the account may have one (see
   * the class); whether it went out. Nothing of what was typed goes into the log, the account's name does.
   */
  suspend fun send(account: String, email: String, language: Language, now: Instant = Instant.now()): Boolean {
    if (!offered) return false
    val input = account.trim()
    if (input.isEmpty() || input.length > ForgotRequest.MAX_LENGTH) return false
    val user = users.findLogin(input) ?: return false
    val own = user.email ?: return false
    if (!sameAddress(own, email)) {
      logger.info("A password reset for {} was asked for with another address", user.name)
      return false
    }
    logins.passwordLoginProblem(user)?.let {
      logger.info("No password reset for {}: it doesn't log in with a password ({})", user.name, it.problem)
      return false
    }
    if (!counted(user.id, now.toEpochMilli())) {
      logger.info("No password reset for {}: {} links within the hour already", user.name, MAILS_PER_HOUR)
      return false
    }
    val token = newToken()
    val expires = now.plus(PASSWORD_RESET_VALID.toJavaDuration())
    resets.create(tokenHash(token), user.id, expires, now)
    val texts = texts(Language.of(user.language) ?: language).mails
    val link = "${config.publicUrl}/#/reset/$token"
    val minutes = PASSWORD_RESET_VALID.inWholeMinutes.toInt()
    try {
      mailer.send(Mails.passwordReset(own, user.name, link, minutes, timeFor(expires, user.timeZone), texts))
    } catch (e: IOException) {
      logger.warn("Could not send the password reset link of {}: {}", user.name, e.message)
      return false
    }
    return true
  }

  /** Counts a link of the account [id]; `false` (not counted) if it had [MAILS_PER_HOUR] within the hour already. */
  private fun counted(id: Uuid, now: Long): Boolean {
    val key = id.toString()
    synchronized(accountMails) {
      if (accountMails.of(key, now) >= MAILS_PER_HOUR) return false
      accountMails.add(key, now)
    }
    return true
  }

  /** What the link [token] shows: whose password it sets. [AccountProblem] (404) if it doesn't work (any more). */
  suspend fun view(token: String, now: Instant = Instant.now()): ResetView = ResetView(linked(token, now).name)

  /**
   * Sets [password] as the password of the link [token]'s account (the sign-up's rules: [AccountProblem] 400, and the
   * link still works). The link is used, every login of the account ends (its bridge keys too, and the ones begun with
   * the old password that wait for their code), two-factor login stays, and a mail tells the account's address. It doesn't log in. [AccountProblem] (404) for a link that doesn't work.
   */
  suspend fun reset(token: String, password: String, now: Instant = Instant.now()) {
    val user = linked(token, now)
    PasswordRules.problem(password, user.name, user.email)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) }
    val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
    resets.use(tokenHash(token), hash, now) ?: throw gone()
    pending.forget(user.id)
    logger.info("{} set a new password with a reset link: every login of the account ended", user.name)
    val to = user.email ?: return
    try {
      val texts = texts(Language.of(user.language) ?: Language.DEFAULT).mails
      mailer.send(Mails.passwordChanged(to, user.name, timeFor(now, user.timeZone), texts))
    } catch (e: IOException) {
      logger.warn("Could not tell {} about the new password: {}", user.name, e.message)
    }
  }

  /** The account of the link [token] while it works and the account may reset its password; [gone] otherwise. */
  private suspend fun linked(token: String, now: Instant): User {
    val id = resets.find(tokenHash(token), now) ?: throw gone()
    return users.findActive(id)?.takeIf { logins.passwordLoginProblem(it) == null } ?: throw gone()
  }

  private fun gone() = AccountProblem(HttpStatusCode.NotFound, Problem.LINK_GONE)

  /** Removes the links that ran out every few hours, for as long as [scope] runs. */
  fun cleanUp(scope: CoroutineScope) = scope.launch {
    while (true) {
      runCatching { resets.deleteExpired() }
        .onSuccess { if (it > 0) logger.info("Removed {} password reset links that ran out", it) }
        .onFailure { logger.warn("Could not remove the password reset links that ran out: {}", it.message) }
      delay(6.hours)
    }
  }

  companion object {
    /** Reset links an account gets within an hour at most. */
    const val MAILS_PER_HOUR = 3

    /** Second steps from one network address within an hour at most, whatever account they name. */
    const val ADDRESS_TRIES = 10

    /** How long the second step takes to answer, whatever it comes to. */
    val ANSWER_AFTER = 1.seconds

    /** What the made-up addresses are keyed for ([TwoFactor.mac]). */
    private const val HINT_PURPOSE = "password reset hints"
  }
}
