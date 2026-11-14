package net.eratiem.randogeddon.server.account

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
class AccountProblem(val status: HttpStatusCode, message: String) : Exception(message)

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
) {
  /**
   * Invites [email]; [suggestedName] is prefilled on the sign-up page. The mail goes out before this returns. An address
   * with an account is refused: with who has it (for Administrators), or with [existing] (for everyone else, who
   * picks people by name and doesn't learn their addresses).
   */
  suspend fun invite(email: String, suggestedName: String?, by: String, existing: String? = null): Account {
    val address = address(email)
    users.accountByEmail(address)?.let {
      if (existing != null) throw AccountProblem(HttpStatusCode.Conflict, existing)
      val what = if (it.signedUpAt == null) "invited already (send the link again instead)" else "the address of ${it.name}"
      throw AccountProblem(HttpStatusCode.Conflict, "$address is $what")
    }
    val suggested = suggestedName?.trim()?.takeIf { it.isNotEmpty() }
    suggested?.let { name -> UserNames.problem(name)?.let { throw AccountProblem(HttpStatusCode.BadRequest, "Name: $it") } }
    val account = users.invite(address)
    logger.info("{} invites {}", by, address)
    sendInvitation(account.id, address, suggested)
    return account
  }

  /** A new link for an invited user (the old one stops working). */
  suspend fun resend(id: Uuid) {
    val account = invited(id)
    sendInvitation(id, account.email ?: throw AccountProblem(HttpStatusCode.Conflict, "No address"), invitations.of(id)?.suggestedName)
  }

  /** Another address for an invited user; the link goes there, the old one stops working. */
  suspend fun changeEmail(id: Uuid, email: String) {
    invited(id)
    val address = address(email)
    users.accountByEmail(address)?.takeIf { it.id != id }?.let {
      throw AccountProblem(HttpStatusCode.Conflict, "$address belongs to another account")
    }
    users.setEmail(id, address)
    sendInvitation(id, address, invitations.of(id)?.suggestedName)
  }

  /** What the sign-up page shows of the link [token]. */
  suspend fun invitation(token: String, now: Instant = Instant.now()): InvitationInfo {
    val invitation = invitations.find(tokenHash(token))
    val problem = when {
      invitation == null -> "This link doesn't work: it was replaced by a newer one, or never existed"
      invitation.usedAt != null -> "This link was used already: log in with the name you chose"
      invitation.expiresAt <= now -> "This link has expired: ask an admin for a new one"
      users.account(invitation.userId)?.signedUpAt != null -> "You signed up already: log in"
      else -> null
    }
    return if (problem != null || invitation == null) InvitationInfo(valid = false, problem = problem)
    else InvitationInfo(valid = true, email = invitation.email, suggestedName = invitation.suggestedName)
  }

  /** Signs up with the invitation; the new login's token (for the cookie). */
  suspend fun signUp(request: SignupRequest, now: Instant = Instant.now()): String {
    val info = invitation(request.token, now)
    if (!info.valid) throw AccountProblem(HttpStatusCode.Gone, info.problem ?: "This link doesn't work")
    val name = request.name.trim()
    UserNames.problem(name)?.let { throw AccountProblem(HttpStatusCode.BadRequest, "Name: $it") }
    PasswordRules.problem(request.password)?.let { throw AccountProblem(HttpStatusCode.BadRequest, "Password: $it") }
    if (users.findActive(name) != null) throw AccountProblem(HttpStatusCode.Conflict, "The name $name is taken")
    val hash = tokenHash(request.token)
    val invitation = invitations.find(hash) ?: throw AccountProblem(HttpStatusCode.Gone, "This link doesn't work")
    val passwordHash = withContext(Dispatchers.Default) { PasswordHasher.hash(request.password) }
    if (!invitations.use(hash, now)) throw AccountProblem(HttpStatusCode.Gone, "This link was used already")
    // The unique index decides if two people take the same name at once
    val signedUp = runCatching { users.signUp(invitation.userId, name, passwordHash, request.autoDelete.code, now) }
      .getOrElse { throw AccountProblem(HttpStatusCode.Conflict, "The name $name is taken") }
    if (!signedUp) throw AccountProblem(HttpStatusCode.Gone, "You signed up already: log in")
    sessions.refresh()
    logger.info("{} signed up as {}", invitation.email, name)
    val user = users.findActive(invitation.userId) ?: throw AccountProblem(HttpStatusCode.Gone, "The account is gone")
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
    )
  }

  /** A new name: unique among active accounts; the running sessions show it, the finished ones keep the old one. */
  suspend fun rename(user: User, name: String) {
    val clean = name.trim()
    UserNames.problem(clean)?.let { throw AccountProblem(HttpStatusCode.BadRequest, "Name: $it") }
    if (clean == user.name) return
    users.findActive(clean)?.takeIf { it.id != user.id }?.let {
      throw AccountProblem(HttpStatusCode.Conflict, "The name $clean is taken")
    }
    runCatching { users.rename(user.id, clean) }
      .getOrElse { throw AccountProblem(HttpStatusCode.Conflict, "The name $clean is taken") }
    sessions.refresh()
    logger.info("{} is called {} now", user.name, clean)
  }

  /** A new password after the current one; every login ends, this one gets a new token (for the cookie). */
  suspend fun changePassword(user: User, current: String, password: String): String {
    val valid = withContext(Dispatchers.Default) { PasswordHasher.verify(current, user.passwordHash) }
    if (!valid) throw AccountProblem(HttpStatusCode.Forbidden, "The current password is wrong")
    PasswordRules.problem(password)?.let { throw AccountProblem(HttpStatusCode.BadRequest, "Password: $it") }
    users.setPassword(user.id, withContext(Dispatchers.Default) { PasswordHasher.hash(password) })
    logger.info("{} has a new password", user.name)
    return logins.start(user)
  }

  /** A new e-mail address: a confirmation link goes there; until it's opened, the old address stays. */
  suspend fun requestEmail(user: User, email: String) {
    val address = address(email)
    if (address.equals(user.email, ignoreCase = true)) {
      throw AccountProblem(HttpStatusCode.BadRequest, "That's your address already")
    }
    users.accountByEmail(address)?.let { throw AccountProblem(HttpStatusCode.Conflict, "$address belongs to another account") }
    val token = newToken()
    emailChanges.create(tokenHash(token), user.id, address, Instant.now().plus(EMAIL_CONFIRMATION_VALID.toJavaDuration()))
    val link = "${config.publicUrl}/#/email/$token"
    try {
      mailer.send(Mails.emailConfirmation(address, user.name, link, EMAIL_CONFIRMATION_VALID.inWholeDays.toInt()))
    } catch (e: IOException) {
      logger.warn("Could not send the address confirmation to {}: {}", address, e.message)
      throw AccountProblem(HttpStatusCode.BadGateway, "The mail to $address couldn't be sent (${e.message}); try again")
    }
  }

  /** The link of a confirmation mail: its address becomes the account's; the address, for the answer. */
  suspend fun confirmEmail(token: String, now: Instant = Instant.now()): String {
    val change = emailChanges.take(tokenHash(token), now)
      ?: throw AccountProblem(HttpStatusCode.Gone, "This link doesn't work (any more): ask for a new one in your settings")
    users.accountByEmail(change.email)?.takeIf { it.id != change.userId }?.let {
      throw AccountProblem(HttpStatusCode.Conflict, "${change.email} belongs to another account now")
    }
    users.account(change.userId) ?: throw AccountProblem(HttpStatusCode.Gone, "The account is gone")
    users.setEmail(change.userId, change.email)
    logger.info("An account's address is {} now", change.email)
    return change.email
  }

  suspend fun setAutoDelete(user: User, autoDelete: AutoDelete) {
    config.oidc?.takeIf { user.administrator }?.let {
      throw AccountProblem(HttpStatusCode.Conflict, "Your account goes when ${it.name} deletes you")
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
      )
    }.sortedWith(compareBy({ it.state != AccountState.ACTIVE }, { (it.name ?: it.email.orEmpty()).lowercase() }))
  }

  private suspend fun invited(id: Uuid): Account {
    val account = users.account(id) ?: throw AccountProblem(HttpStatusCode.NotFound, "No such account")
    if (account.signedUpAt != null) throw AccountProblem(HttpStatusCode.Conflict, "${account.name} signed up already")
    return account
  }

  private fun address(email: String): String {
    val address = EmailAddresses.normalize(email)
    if (!EmailAddresses.valid(address)) throw AccountProblem(HttpStatusCode.BadRequest, "Not an e-mail address: $email")
    return address
  }

  private suspend fun sendInvitation(id: Uuid, email: String, suggestedName: String?) {
    val token = newToken()
    invitations.create(tokenHash(token), id, email, suggestedName, Instant.now().plus(INVITATION_VALID.toJavaDuration()))
    try {
      mailer.send(Mails.invitation(email, "${config.publicUrl}/#/invite/$token", INVITATION_VALID.inWholeDays.toInt()))
    } catch (e: IOException) {
      logger.warn("Could not send the invitation to {}: {}", email, e.message)
      throw AccountProblem(HttpStatusCode.BadGateway, "The invitation to $email couldn't be sent (${e.message}); try again")
    }
  }
}
