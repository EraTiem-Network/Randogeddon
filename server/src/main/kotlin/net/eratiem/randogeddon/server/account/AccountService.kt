package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.AccountActionRequest
import net.eratiem.randogeddon.shared.AccountBlock
import net.eratiem.randogeddon.shared.i18n.MailTexts
import java.time.Duration
import net.eratiem.randogeddon.server.config.MAX_SESSIONS_PER_USER
import net.eratiem.randogeddon.shared.FontChoice
import net.eratiem.randogeddon.shared.FontScope
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.i18n.texts
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.server.config.english
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.eratiem.randogeddon.database.Account
import net.eratiem.randogeddon.database.EmailChangeStore
import net.eratiem.randogeddon.database.InvitationStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.SignUp
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.config.OidcConfig
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.AccountState
import net.eratiem.randogeddon.shared.AccountView
import net.eratiem.randogeddon.shared.AdminUser
import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.InvitationInfo
import net.eratiem.randogeddon.shared.PasswordRules
import net.eratiem.randogeddon.shared.SignupRequest
import net.eratiem.randogeddon.shared.TimeZoneRequest
import java.time.ZoneId
import net.eratiem.randogeddon.shared.UserNames
import net.eratiem.randogeddon.shared.i18n.Language
import org.koin.core.annotation.Single
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

private val logger = logger(MethodHandles.lookup().lookupClass())

/** How long an invitation link works. */
val INVITATION_VALID = 7.days

/** How long the confirmation link for a new e-mail address works. */
val EMAIL_CONFIRMATION_VALID = 2.days

/** Why an account action can't be done; answered with [status] and the message. */
class AccountProblem(val status: HttpStatusCode, val refusal: Refusal) : Exception(refusal.english()) {
  constructor(status: HttpStatusCode, problem: Problem, vararg args: Pair<String, Any?>) : this(status, Refusal.of(problem, *args))
}

/**
 * What an account that can't play any more (kept out, or deleted) leaves in the running sessions: the slots only it
 * plays get new passwords and their connections end, so its game clients can't connect with what it was given. The
 * session side does it (`admin/SlotRenewals.kt`); the accounts don't depend on that.
 */
interface SlotRenewal {
  /** Never throws for a session it can't do it in (that is logged). */
  suspend fun renewOnlyTheirs(account: Uuid)
}

/**
 * The accounts as people get them: an admin invites them by e-mail (they get a link), they choose their name,
 * password and deletion period on the sign-up page. Until then an admin can send the link again, or to another
 * address.
 */
@Single
class AccountService(
  private val users: UserStore,
  private val invitations: InvitationStore,
  private val emailChanges: EmailChangeStore,
  private val twoFactor: TwoFactor,
  private val logins: LoginService,
  private val mailer: Mailer,
  private val sessions: LiveSessions,
  private val config: TrackerConfig,
  private val mailLimits: MailLimits,
  private val setups: SessionSetupStore,
  private val slots: SlotRenewal,
) {
  /**
   * Invites [email] by [by]; [suggestedName] is prefilled on the sign-up page. The mail goes out before this returns.
   * An address with an account is refused: with who has it (for Administrators), or with [existing] (for everyone
   * else, who picks people by name and doesn't learn their addresses; so each of their tries counts towards their
   * mails, a refused one too, and nobody tries address after address). If their mail can't be sent, the invited
   * account goes again, so they can try once more; an Administrator's stays, for *Send again*.
   */
  suspend fun invite(email: String, suggestedName: String?, by: User, language: Language, existing: Refusal? = null): Account {
    val address = address(email)
    if (existing != null) mailLimits.count(by.id)
    users.accountByEmail(address)?.let {
      if (existing != null) throw AccountProblem(HttpStatusCode.Conflict, existing)
      throw AccountProblem(
        HttpStatusCode.Conflict,
        if (it.signedUpAt == null) Refusal.of(Problem.ADDRESS_INVITED, "address" to address)
        else Refusal.of(Problem.ADDRESS_OF, "address" to address, "name" to it.name),
      )
    }
    val suggested = suggestedName?.trim()?.takeIf { it.isNotEmpty() }
    suggested?.let { name -> nameProblem(name)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) } }
    if (existing == null) mailLimits.count(by.id)
    val account = users.invite(address)
    logger.info("{} invites someone (account {})", by.name, account.id)
    try {
      sendInvitation(account.id, address, suggested, language)
    } catch (e: AccountProblem) {
      if (existing != null) {
        runCatching { users.delete(account.id) }
          .onSuccess { logger.info("The invitation of account {} wasn't sent: the account is gone again", account.id) }
          .onFailure { logger.warn("Could not take the invitation of account {} back: {}", account.id, it.message) }
      }
      throw e
    }
    return account
  }

  /** A new link for an invited user (the old one stops working). */
  suspend fun resend(id: Uuid, language: Language) {
    val account = invited(id)
    sendInvitation(id, account.email ?: throw AccountProblem(HttpStatusCode.Conflict, Problem.NO_ADDRESS), invitations.of(id)?.suggestedName, language)
  }

  /** Another address for an invited user; the link goes there, the old one stops working. */
  suspend fun changeEmail(id: Uuid, email: String, language: Language) {
    invited(id)
    val address = address(email)
    users.accountByEmail(address)?.takeIf { it.id != id }?.let {
      throw AccountProblem(HttpStatusCode.Conflict, Problem.ADDRESS_OTHER_ACCOUNT, "address" to address)
    }
    users.setEmail(id, address)
    sendInvitation(id, address, invitations.of(id)?.suggestedName, language)
  }

  /** What the sign-up page shows of the link [token]. */
  suspend fun invitation(token: String, language: Language = Language.DEFAULT, now: Instant = Instant.now()): InvitationInfo {
    val invitation = invitations.find(tokenHash(token))
    val problem = when {
      invitation == null -> Problem.INVITATION_UNKNOWN
      invitation.usedAt != null -> Problem.INVITATION_USED
      invitation.expiresAt <= now -> Problem.INVITATION_EXPIRED
      users.account(invitation.userId)?.signedUpAt != null -> Problem.SIGNED_UP_ALREADY
      else -> null
    }
    return if (problem != null || invitation == null) {
      InvitationInfo(valid = false, problem = problem?.let { texts(language).problems.text(it, emptyMap()) }, code = problem)
    }
    else InvitationInfo(valid = true, email = invitation.email, suggestedName = invitation.suggestedName)
  }

  /** Signs up with the invitation; the new login's token (for the cookie). */
  suspend fun signUp(request: SignupRequest, now: Instant = Instant.now()): String {
    val info = invitation(request.token, now = now)
    if (!info.valid) throw AccountProblem(HttpStatusCode.Gone, Refusal(info.code ?: Problem.LINK_DOESNT_WORK))
    val name = request.name.trim()
    nameProblem(name)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) }
    PasswordRules.problem(request.password)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) }
    if (users.findActive(name) != null) throw AccountProblem(HttpStatusCode.Conflict, Problem.NAME_TAKEN, "name" to name)
    val hash = tokenHash(request.token)
    val invitation = invitations.find(hash) ?: throw AccountProblem(HttpStatusCode.Gone, Problem.LINK_DOESNT_WORK)
    val passwordHash = withContext(Dispatchers.Default) { PasswordHasher.hash(request.password) }
    // The link is used with the sign-up or not at all: the unique index decides if two people take the same name at
    // once, and the one too late keeps their link for another name
    when (users.signUpInvited(hash, invitation.userId, name, passwordHash, request.autoDelete.code, now)) {
      SignUp.DONE -> Unit
      SignUp.LINK_USED -> throw AccountProblem(HttpStatusCode.Gone, Problem.INVITATION_USED)
      SignUp.SIGNED_UP_ALREADY -> throw AccountProblem(HttpStatusCode.Gone, Problem.SIGNED_UP_ALREADY)
      SignUp.NAME_TAKEN -> throw AccountProblem(HttpStatusCode.Conflict, Problem.NAME_TAKEN, "name" to name)
    }
    sessions.refresh()
    logger.info("Account {} signed up as {}", invitation.userId, name)
    val user = users.findActive(invitation.userId) ?: throw AccountProblem(HttpStatusCode.Gone, Problem.ACCOUNT_GONE)
    return logins.start(user)
  }

  /** [user]'s own account, for the settings page. */
  suspend fun view(user: User, now: Instant = Instant.now()): AccountView {
    val activity = users.activityOf(user.id)
    return AccountView(
      id = user.id.toString(),
      name = user.name,
      email = user.email,
      pendingEmail = emailChanges.pending(user.id, now),
      autoDelete = AutoDelete.of(user.autoDelete),
      deletesAt = activity?.let { deletesAt(it, now) }?.toEpochMilli(),
      administrator = user.administrator,
      twoFactor = user.twoFactor,
      recoveryCodesLeft = twoFactor.codesLeft(user),
      singleSignOn = config.providerOf(user)?.name,
      singleSignOnAccount = config.providerOf(user)?.account,
      language = Language.of(user.language),
      theme = Theme.of(user.theme),
      themeMode = ThemeMode.of(user.themeMode),
      font = user.font.takeIf(FontChoice::valid),
      fontScope = FontScope.of(user.fontScope),
    )
  }

  /**
   * [user]'s account as the provider has it ([wantedName], its [email]): at each login through it and when it
   * says a person changed. The name made unique (another account may have it), the address only if no other account
   * has it; an Administrator's two-factor login of before goes (the provider asks for the second factor; someone who
   * isn't one any more logs in with their password and keeps theirs). [emailVerified]: whether
   * the provider calls the address verified, kept for the Administrators to see (`null`: it didn't say, as its
   * webhook doesn't).
   */
  suspend fun syncFromProvider(user: User, wantedName: String, email: String?, emailVerified: Boolean? = null) {
    if (wantedName != user.name) {
      val name = uniqueName(wantedName) { taken -> users.findActive(taken)?.takeIf { it.id != user.id } != null }
      if (name != user.name) {
        users.rename(user.id, name)
        sessions.refresh()
        logger.info("{} is called {} now ({})", user.name, name, config.oidc?.name)
      }
    }
    val address = email?.let(EmailAddresses::normalize)?.takeIf(EmailAddresses::valid)
    var theirs = address != null && address.equals(user.email, ignoreCase = true)
    if (address != null && !theirs && users.accountByEmail(address) == null) {
      users.setEmail(user.id, address)
      theirs = true
      logger.info("{} has a new address ({})", user.name, config.oidc?.name)
    }
    // Only about the address the account has from the provider
    if (theirs && emailVerified != null) users.setEmailVerifiedByProvider(user.id, emailVerified)
    if (user.twoFactor && user.administrator) {
      twoFactor.reset(user.id)
      logger.info("{}'s two-factor login of before is off: {} asks for the second factor", user.name, config.oidc?.name)
    }
  }

  /** A new name: unique among active accounts; the running sessions show it, the finished ones keep the old one. */
  suspend fun rename(user: User, name: String) {
    config.requireOwn(user)
    val clean = name.trim()
    nameProblem(clean)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) }
    if (clean == user.name) return
    users.findActive(clean)?.takeIf { it.id != user.id }?.let {
      throw AccountProblem(HttpStatusCode.Conflict, Problem.NAME_TAKEN, "name" to clean)
    }
    runCatching { users.rename(user.id, clean) }
      .getOrElse { throw AccountProblem(HttpStatusCode.Conflict, Problem.NAME_TAKEN, "name" to clean) }
    sessions.refresh()
    logger.info("{} is called {} now", user.name, clean)
  }

  /** A new password after the current one; every login ends, this one gets a new token (for the cookie). */
  suspend fun changePassword(user: User, current: String, password: String): String {
    config.requireOwn(user)
    val valid = withContext(Dispatchers.Default) { PasswordHasher.verify(current, user.passwordHash) }
    if (!valid) throw AccountProblem(HttpStatusCode.Forbidden, Problem.WRONG_PASSWORD)
    PasswordRules.problem(password)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) }
    users.setPassword(user.id, withContext(Dispatchers.Default) { PasswordHasher.hash(password) })
    logger.info("{} has a new password", user.name)
    return logins.start(user)
  }

  /**
   * A new e-mail address: a confirmation link goes there; until it's opened, the old address stays. Each try counts
   * towards [user]'s mails, one refused for an address of another account too (nobody tries address after address).
   */
  suspend fun requestEmail(user: User, email: String, language: Language) {
    config.requireOwn(user)
    val address = address(email)
    if (address.equals(user.email, ignoreCase = true)) {
      throw AccountProblem(HttpStatusCode.BadRequest, Problem.SAME_ADDRESS)
    }
    mailLimits.count(user.id)
    users.accountByEmail(address)?.let { throw AccountProblem(HttpStatusCode.Conflict, Problem.ADDRESS_OTHER_ACCOUNT, "address" to address) }
    val token = newToken()
    emailChanges.create(tokenHash(token), user.id, address, Instant.now().plus(EMAIL_CONFIRMATION_VALID.toJavaDuration()))
    val link = "${config.publicUrl}/#/email/$token"
    try {
      mailer.send(Mails.emailConfirmation(address, user.name, link, EMAIL_CONFIRMATION_VALID.inWholeDays.toInt(), texts(language).mails))
    } catch (e: IOException) {
      logger.warn("Could not send the address confirmation of {}: {}", user.name, e.message)
      throw AccountProblem(HttpStatusCode.BadGateway, Problem.MAIL_FAILED, "address" to address, "reason" to e.message)
    }
    // The address so far is told (changing it asks for no password): whoever it wasn't can still act
    user.email?.let { old ->
      try {
        mailer.send(Mails.addressChange(old, user.name, address, texts(language).mails))
      } catch (e: IOException) {
        logger.warn("Could not tell {} at their address so far about the new one asked for: {}", user.name, e.message)
      }
    }
  }

  /** The link of a confirmation mail: its address becomes the account's; the address, for the answer. */
  suspend fun confirmEmail(token: String, now: Instant = Instant.now()): String {
    val change = emailChanges.take(tokenHash(token), now)
      ?: throw AccountProblem(HttpStatusCode.Gone, Problem.CONFIRMATION_GONE)
    users.accountByEmail(change.email)?.takeIf { it.id != change.userId }?.let {
      throw AccountProblem(HttpStatusCode.Conflict, Problem.ADDRESS_OTHER_ACCOUNT_NOW, "address" to change.email)
    }
    users.account(change.userId) ?: throw AccountProblem(HttpStatusCode.Gone, Problem.ACCOUNT_GONE)
    users.setEmail(change.userId, change.email)
    logger.info("Account {} confirmed its new address", change.userId)
    return change.email
  }

  /** The language [user] reads the app in (and gets their mails in); `null`: their browser's. */
  suspend fun setLanguage(user: User, language: Language?) = users.setLanguage(user.id, language?.code)

  /** The theme and mode [user] sees the app in; `null`: the default theme, automatic. */
  suspend fun setTheme(user: User, theme: Theme?, mode: ThemeMode?) = users.setTheme(user.id, theme?.code, mode?.code)

  /**
   * The time zone [user]'s mails give their times in: an IANA name (`Europe/Berlin`, the browser's), at most
   * [TimeZoneRequest.MAX_LENGTH] characters, one this Java knows; refused (400) otherwise.
   */
  suspend fun setTimeZone(user: User, zone: String) {
    val name = zone.trim()
    val known = name.length in 1..TimeZoneRequest.MAX_LENGTH && runCatching { ZoneId.of(name) }.isSuccess
    if (!known) throw AccountProblem(HttpStatusCode.BadRequest, Problem.NO_SUCH_TIME_ZONE)
    users.setTimeZone(user.id, name)
  }

  /** The font [user] reads the app in ([FontChoice]; `null`: the theme's), and where it shows. */
  suspend fun setFont(user: User, font: String?, scope: FontScope) {
    if (!FontChoice.valid(font)) throw AccountProblem(HttpStatusCode.BadRequest, Problem.NO_SUCH_FONT)
    users.setFont(user.id, font, scope.code.takeIf { scope != FontScope.AUTO })
  }

  suspend fun setAutoDelete(user: User, autoDelete: AutoDelete) {
    config.providerOf(user)?.let {
      throw AccountProblem(HttpStatusCode.Conflict, Problem.DELETED_WITH_SSO, "provider" to it.name)
    }
    users.setAutoDelete(user.id, autoDelete.code)
  }

  /** Every account for the admin panel, invited ones too. */
  suspend fun list(now: Instant = Instant.now()): List<AdminUser> {
    val due = users.activity().associate { it.user.id to deletesAt(it, now) }
    val expires = invitations.all().mapValues { it.value.expiresAt }
    val created = setups.runningCreated()
    val members = sessionsOf()
    return users.accounts().map { row(it, members, due[it.id], expires[it.id], created, now) }
      .sortedWith(compareBy({ it.state != AccountState.ACTIVE }, { (it.name ?: it.email.orEmpty()).lowercase() }))
  }

  /** The account [id] as [list] has it (an invitation just made); [AccountProblem] (404) if there is none. */
  suspend fun listed(id: Uuid, now: Instant = Instant.now()): AdminUser {
    val account = users.account(id) ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)
    val due = users.activityOf(id)?.let { deletesAt(it, now) }
    return row(account, sessionsOf(), due, invitations.of(id)?.expiresAt, mapOf(id to setups.runningCreatedBy(id)), now)
  }

  /** The sessions each account is in, by its ID. */
  private fun sessionsOf(): Map<Uuid, List<String>> =
    sessions.all().flatMap { s -> s.setup?.members?.keys.orEmpty().map { it to s.id } }.groupBy({ it.first }, { it.second })

  /**
   * [a]'s row in [list]: with the sessions it is in ([members]), when it is deleted ([deletesAt]) and its invitation
   * expires, and how many running sessions of its own it has ([created]).
   */
  private fun row(
    a: Account,
    members: Map<Uuid, List<String>>,
    deletesAt: Instant?,
    invitationExpires: Instant?,
    created: Map<Uuid, Int>,
    now: Instant,
  ) = AdminUser(
    id = a.id.toString(),
    name = a.name,
    email = a.email,
    state = if (a.signedUpAt == null) AccountState.INVITED else AccountState.ACTIVE,
    administrator = a.administrator,
    sessions = members[a.id].orEmpty().sorted(),
    createdAt = a.createdAt.toEpochMilli(),
    invitationExpiresAt = if (a.signedUpAt == null) invitationExpires?.toEpochMilli() else null,
    autoDelete = AutoDelete.of(a.autoDelete),
    deletesAt = deletesAt?.toEpochMilli(),
    password = a.hasPassword,
    singleSignOn = a.linked,
    provider = config.oidc?.name?.takeIf { a.linked },
    emailVerified = a.emailVerifiedByProvider.takeIf { a.linked && a.email != null },
    twoFactor = a.twoFactor,
    language = a.language,
    block = a.block?.takeIf { it.holds(now) }?.let { AccountBlock(it.at.toEpochMilli(), it.until?.toEpochMilli(), it.reason, it.by) },
    sessionLimit = a.sessionLimit,
    sessionsRunning = created[a.id] ?: 0,
  )

  /**
   * Keeps the account [id] out, by the Administrator [by]: timed out for [request]'s time, or suspended until it is
   * taken back. Its logins end at once (and the slots only it plays get new passwords, [SlotRenewal]), the next try
   * to log in is told why, and a mail tells them too. Never an Administrator's account (they are managed where they
   * log in), so never one's own; not an invitation.
   */
  suspend fun keepOut(id: Uuid, request: AccountActionRequest, by: User, now: Instant = Instant.now()) {
    val account = theirs(id, by)
    if (account.signedUpAt == null) throw AccountProblem(HttpStatusCode.Conflict, Problem.NOT_SIGNED_UP_YET, "name" to (account.email ?: id))
    val reason = reason(request.reason)
    val until = request.timeOut?.let { now.plus(Duration.ofHours(it.hours.toLong())) }
    users.block(id, until, reason, by.name, now)
    // Its game clients can't connect with the passwords of the slots only it plays any more
    slots.renewOnlyTheirs(id)
    sessions.refresh()
    logger.info("{} {} {}{}", by.name, if (until != null) "timed out (until $until)" else "suspended", account.name, reason?.let { ": $it" } ?: "")
    tell(account) { to, name, texts -> Mails.keptOut(to, name, until?.let { timeFor(it, account.timeZone) }, reason, texts) }
  }

  /** Lets the account [id] in again before its time (or after a suspension), by the Administrator [by]. */
  suspend fun letIn(id: Uuid, by: User, now: Instant = Instant.now()) {
    val account = theirs(id, by)
    if (account.block?.holds(now) != true) throw AccountProblem(HttpStatusCode.Conflict, Problem.NOT_KEPT_OUT, "name" to (account.name ?: id))
    users.unblock(id)
    sessions.refresh()
    logger.info("{} let {} in again", by.name, account.name)
    tell(account) { to, name, texts -> Mails.letIn(to, name, texts) }
  }

  /**
   * Deletes the account [id], by the Administrator [by]: as it deletes itself ([UserStore.delete]: the sessions keep
   * its name; the slots only it plays get new passwords), told by mail before its address goes. An invitation is
   * taken back without a mail.
   */
  suspend fun remove(id: Uuid, request: AccountActionRequest, by: User) {
    val account = theirs(id, by)
    val reason = reason(request.reason)
    if (account.signedUpAt != null) tell(account) { to, name, texts -> Mails.removed(to, name, reason, texts) }
    users.delete(id)
    slots.renewOnlyTheirs(id)
    sessions.refresh()
    logger.info("{} deleted the account of {}{}", by.name, account.name ?: id, reason?.let { ": $it" } ?: "")
  }

  /**
   * The account [id]'s own limit of running sessions ([limit]: 0 to [MAX_SESSIONS_PER_USER]; `null`: the default
   * again), by the Administrator [by]. An Administrator's account has no limit, so none is set for one.
   */
  suspend fun setSessionLimit(id: Uuid, limit: Int?, by: User) {
    val account = users.account(id) ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)
    if (account.administrator) throw AccountProblem(HttpStatusCode.Conflict, Problem.ADMINISTRATOR_ELSEWHERE, "name" to (account.name ?: id))
    if (limit != null && limit !in 0..MAX_SESSIONS_PER_USER) {
      throw AccountProblem(HttpStatusCode.BadRequest, Problem.SESSIONS_PER_USER, "max" to MAX_SESSIONS_PER_USER)
    }
    users.setSessionLimit(id, limit)
    logger.info("{} set the sessions of {} to {}", by.name, account.name ?: id, limit ?: "the default")
  }

  /** The account [id] for an action of the Administrator [by] on it: not their own, not an Administrator's. */
  private suspend fun theirs(id: Uuid, by: User): Account {
    val account = users.account(id) ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)
    if (id == by.id) throw AccountProblem(HttpStatusCode.Conflict, Problem.NOT_YOUR_OWN)
    if (account.administrator) throw AccountProblem(HttpStatusCode.Conflict, Problem.ADMINISTRATOR_ELSEWHERE, "name" to (account.name ?: id))
    return account
  }

  /** A reason as it is kept and told: one line, without control characters; `null` if there is none. */
  private fun reason(text: String?): String? {
    val reason = text.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().ifEmpty { return null }
    if (reason.length > AccountActionRequest.MAX_REASON) {
      throw AccountProblem(HttpStatusCode.BadRequest, Problem.REASON_TOO_LONG, "max" to AccountActionRequest.MAX_REASON)
    }
    return reason
  }

  /** A mail to [account] in its language, if it has an address; one that can't be sent is only logged. */
  private suspend fun tell(account: Account, mail: (to: String, name: String, texts: MailTexts) -> Mail) {
    val to = account.email ?: return
    try {
      mailer.send(mail(to, account.name.orEmpty(), texts(Language.of(account.language) ?: Language.DEFAULT).mails))
    } catch (e: IOException) {
      logger.warn("Could not send a mail to {}: {}", account.name, e.message)
    }
  }

  private suspend fun invited(id: Uuid): Account {
    val account = users.account(id) ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)
    if (account.signedUpAt != null) throw AccountProblem(HttpStatusCode.Conflict, Problem.SIGNED_UP, "name" to account.name)
    return account
  }

  private fun address(email: String): String {
    val address = EmailAddresses.normalize(email)
    if (!EmailAddresses.valid(address)) throw AccountProblem(HttpStatusCode.BadRequest, Problem.NOT_AN_ADDRESS, "address" to email)
    return address
  }

  /** The invitation mail, in the language of who invites (they know whom they invite). */
  private suspend fun sendInvitation(id: Uuid, email: String, suggestedName: String?, language: Language) {
    val token = newToken()
    invitations.create(tokenHash(token), id, email, suggestedName, Instant.now().plus(INVITATION_VALID.toJavaDuration()))
    try {
      val link = "${config.publicUrl}/#/invite/$token"
      mailer.send(Mails.invitation(email, link, INVITATION_VALID.inWholeDays.toInt(), texts(language).mails, account = "account $id"))
    } catch (e: IOException) {
      logger.warn("Could not send the invitation of account {}: {}", id, e.message)
      throw AccountProblem(HttpStatusCode.BadGateway, Problem.INVITATION_FAILED, "address" to email, "reason" to e.message)
    }
  }
}

/** The provider that manages [user]'s account: with single sign-on, an Administrator's; `null`: they manage it. */
internal fun TrackerConfig.providerOf(user: User): OidcConfig? = oidc?.takeIf { user.administrator }

/**
 * Refuses (403) to change what the provider manages of [user]'s account ([providerOf]): their name, password, address
 * and second factor.
 */
internal fun TrackerConfig.requireOwn(user: User) {
  providerOf(user)?.let { throw AccountProblem(HttpStatusCode.Forbidden, Problem.MANAGED_BY_SSO, "provider" to it.name) }
}

/** What's wrong with [name] as a user's name: its form ([UserNames]), or a word nobody should be called here. */
internal fun nameProblem(name: String): Refusal? =
  UserNames.problem(name) ?: Refusal(Problem.NAME_UNWANTED).takeIf { UnwantedWords.hit(name) }
