package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.shared.ThemeMode
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
import net.eratiem.randogeddon.database.UserStore
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
) {
  /**
   * Invites [email]; [suggestedName] is prefilled on the sign-up page. The mail goes out before this returns. An address
   * with an account is refused: with who has it (for Administrators), or with [existing] (for everyone else, who
   * picks people by name and doesn't learn their addresses).
   */
  suspend fun invite(email: String, suggestedName: String?, by: String, language: Language, existing: Refusal? = null): Account {
    val address = address(email)
    users.accountByEmail(address)?.let {
      if (existing != null) throw AccountProblem(HttpStatusCode.Conflict, existing)
      throw AccountProblem(
        HttpStatusCode.Conflict,
        if (it.signedUpAt == null) Refusal.of(Problem.ADDRESS_INVITED, "address" to address)
        else Refusal.of(Problem.ADDRESS_OF, "address" to address, "name" to it.name),
      )
    }
    val suggested = suggestedName?.trim()?.takeIf { it.isNotEmpty() }
    suggested?.let { name -> UserNames.problem(name)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) } }
    mailLimits.count(by)
    val account = users.invite(address)
    logger.info("{} invites {}", by, address)
    sendInvitation(account.id, address, suggested, language)
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
    UserNames.problem(name)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) }
    PasswordRules.problem(request.password)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) }
    if (users.findActive(name) != null) throw AccountProblem(HttpStatusCode.Conflict, Problem.NAME_TAKEN, "name" to name)
    val hash = tokenHash(request.token)
    val invitation = invitations.find(hash) ?: throw AccountProblem(HttpStatusCode.Gone, Problem.LINK_DOESNT_WORK)
    val passwordHash = withContext(Dispatchers.Default) { PasswordHasher.hash(request.password) }
    if (!invitations.use(hash, now)) throw AccountProblem(HttpStatusCode.Gone, Problem.INVITATION_USED)
    // The unique index decides if two people take the same name at once
    val signedUp = runCatching { users.signUp(invitation.userId, name, passwordHash, request.autoDelete.code, now) }
      .getOrElse { throw AccountProblem(HttpStatusCode.Conflict, Problem.NAME_TAKEN, "name" to name) }
    if (!signedUp) throw AccountProblem(HttpStatusCode.Gone, Problem.SIGNED_UP_ALREADY)
    sessions.refresh()
    logger.info("{} signed up as {}", invitation.email, name)
    val user = users.findActive(invitation.userId) ?: throw AccountProblem(HttpStatusCode.Gone, Problem.ACCOUNT_GONE)
    return logins.start(user)
  }

  /** [user]'s own account, for the settings page. */
  suspend fun view(user: User, now: Instant = Instant.now()): AccountView {
    val activity = users.activity().firstOrNull { it.user.id == user.id }
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
      singleSignOn = config.oidc?.takeIf { user.administrator }?.name,
      singleSignOnAccount = config.oidc?.takeIf { user.administrator }?.account,
      language = Language.of(user.language),
      theme = Theme.of(user.theme),
      themeMode = ThemeMode.of(user.themeMode),
    )
  }

  /** With single sign-on, an Administrator's name, password, address and deletion are the provider's to change. */
  private fun requireOwn(user: User) {
    config.oidc?.takeIf { user.administrator }?.let {
      throw AccountProblem(HttpStatusCode.Forbidden, Problem.MANAGED_BY_SSO, "provider" to it.name)
    }
  }

  /**
   * [user]'s account as the provider has it ([wantedName], its verified [email]): at each login through it and when it
   * says a person changed. The name made unique (another account may have it), the address only if no other account
   * has it; a two-factor login of before goes (the provider asks for the second factor).
   */
  suspend fun syncFromProvider(user: User, wantedName: String, email: String?) {
    if (wantedName != user.name) {
      val name = uniqueName(wantedName) { taken -> users.findActive(taken)?.takeIf { it.id != user.id } != null }
      if (name != user.name) {
        users.rename(user.id, name)
        sessions.refresh()
        logger.info("{} is called {} now ({})", user.name, name, config.oidc?.name)
      }
    }
    val address = email?.let(EmailAddresses::normalize)?.takeIf(EmailAddresses::valid)
    if (address != null && !address.equals(user.email, ignoreCase = true) && users.accountByEmail(address) == null) {
      users.setEmail(user.id, address)
      logger.info("{}'s address is {} now ({})", user.name, address, config.oidc?.name)
    }
    if (user.twoFactor) {
      twoFactor.reset(user.id)
      logger.info("{}'s two-factor login of before is off: {} asks for the second factor", user.name, config.oidc?.name)
    }
  }

  /** A new name: unique among active accounts; the running sessions show it, the finished ones keep the old one. */
  suspend fun rename(user: User, name: String) {
    requireOwn(user)
    val clean = name.trim()
    UserNames.problem(clean)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) }
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
    requireOwn(user)
    val valid = withContext(Dispatchers.Default) { PasswordHasher.verify(current, user.passwordHash) }
    if (!valid) throw AccountProblem(HttpStatusCode.Forbidden, Problem.WRONG_PASSWORD)
    PasswordRules.problem(password)?.let { throw AccountProblem(HttpStatusCode.BadRequest, it) }
    users.setPassword(user.id, withContext(Dispatchers.Default) { PasswordHasher.hash(password) })
    logger.info("{} has a new password", user.name)
    return logins.start(user)
  }

  /** A new e-mail address: a confirmation link goes there; until it's opened, the old address stays. */
  suspend fun requestEmail(user: User, email: String, language: Language) {
    requireOwn(user)
    val address = address(email)
    if (address.equals(user.email, ignoreCase = true)) {
      throw AccountProblem(HttpStatusCode.BadRequest, Problem.SAME_ADDRESS)
    }
    users.accountByEmail(address)?.let { throw AccountProblem(HttpStatusCode.Conflict, Problem.ADDRESS_OTHER_ACCOUNT, "address" to address) }
    mailLimits.count(user.name)
    val token = newToken()
    emailChanges.create(tokenHash(token), user.id, address, Instant.now().plus(EMAIL_CONFIRMATION_VALID.toJavaDuration()))
    val link = "${config.publicUrl}/#/email/$token"
    try {
      mailer.send(Mails.emailConfirmation(address, user.name, link, EMAIL_CONFIRMATION_VALID.inWholeDays.toInt(), texts(language).mails))
    } catch (e: IOException) {
      logger.warn("Could not send the address confirmation to {}: {}", address, e.message)
      throw AccountProblem(HttpStatusCode.BadGateway, Problem.MAIL_FAILED, "address" to address, "reason" to e.message)
    }
    // The address so far is told (changing it asks for no password): whoever it wasn't can still act
    user.email?.let { old ->
      try {
        mailer.send(Mails.addressChange(old, user.name, address, texts(language).mails))
      } catch (e: IOException) {
        logger.warn("Could not tell {} about the new address asked for: {}", old, e.message)
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
    logger.info("An account's address is {} now", change.email)
    return change.email
  }

  /** The language [user] reads the app in (and gets their mails in); `null`: their browser's. */
  suspend fun setLanguage(user: User, language: Language?) = users.setLanguage(user.id, language?.code)

  /** The theme and mode [user] sees the app in; `null`: the default theme, automatic. */
  suspend fun setTheme(user: User, theme: Theme?, mode: ThemeMode?) = users.setTheme(user.id, theme?.code, mode?.code)

  suspend fun setAutoDelete(user: User, autoDelete: AutoDelete) {
    config.oidc?.takeIf { user.administrator }?.let {
      throw AccountProblem(HttpStatusCode.Conflict, Problem.DELETED_WITH_SSO, "provider" to it.name)
    }
    users.setAutoDelete(user.id, autoDelete.code)
  }

  /** Every account for the admin panel, invited ones too. */
  suspend fun list(now: Instant = Instant.now()): List<AdminUser> {
    val members = sessions.all().flatMap { s -> s.setup?.members?.keys.orEmpty().map { it to s.id } }
      .groupBy({ it.first }, { it.second })
    val due = users.activity().associate { it.user.id to deletesAt(it, now) }
    return users.accounts().map { a ->
      AdminUser(
        id = a.id.toString(),
        name = a.name,
        email = a.email,
        state = if (a.signedUpAt == null) AccountState.INVITED else AccountState.ACTIVE,
        administrator = a.administrator,
        sessions = members[a.id].orEmpty().sorted(),
        createdAt = a.createdAt.toEpochMilli(),
        invitationExpiresAt = if (a.signedUpAt == null) invitations.of(a.id)?.expiresAt?.toEpochMilli() else null,
        autoDelete = AutoDelete.of(a.autoDelete),
        deletesAt = due[a.id]?.toEpochMilli(),
        password = a.hasPassword,
        singleSignOn = a.linked,
        twoFactor = a.twoFactor,
        language = a.language,
      )
    }.sortedWith(compareBy({ it.state != AccountState.ACTIVE }, { (it.name ?: it.email.orEmpty()).lowercase() }))
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
      mailer.send(Mails.invitation(email, "${config.publicUrl}/#/invite/$token", INVITATION_VALID.inWholeDays.toInt(), texts(language).mails))
    } catch (e: IOException) {
      logger.warn("Could not send the invitation to {}: {}", email, e.message)
      throw AccountProblem(HttpStatusCode.BadGateway, Problem.INVITATION_FAILED, "address" to email, "reason" to e.message)
    }
  }
}
