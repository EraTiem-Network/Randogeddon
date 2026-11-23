package net.eratiem.randogeddon.database

import java.sql.SQLException
import java.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * An account an Administrator keeps out: since [at], [until] when (`null`: suspended, until it is taken back), the
 * [reason] the person is told and [by] whom.
 */
data class Block(val at: Instant, val until: Instant?, val reason: String?, val by: String?) {
  /** Whether it still holds at [now]: a time-out that ran out doesn't. */
  fun holds(now: Instant = Instant.now()): Boolean = until == null || now.isBefore(until)
}

/** A user who signed up: they have a name and a password. */
data class User(
  val id: Uuid,
  val name: String,
  val passwordHash: String,
  val administrator: Boolean = false,
  val email: String? = null,
  /** The shared AutoDelete's code. */
  val autoDelete: String = "never",
  /** Logs in with a code from an authenticator after the password. */
  val twoFactor: Boolean = false,
  /** How the current login was made (`password`, `oidc`, `link`); `null` outside a login. */
  val loginMethod: String? = null,
  /** The language's code they chose (`en`, `de`); `null`: their browser's. */
  val language: String? = null,
  /** The theme's code and the mode they chose; `null`: the default theme, automatic. */
  val theme: String? = null,
  val themeMode: String? = null,
  /** The font they chose (`original`, or a theme's code); `null`: the theme's. */
  val font: String? = null,
  /** Where it shows (`all`, `headings`); `null`: as the font is made. */
  val fontScope: String? = null,
  /** An Administrator keeps them out (it may have run out: [Block.holds]); `null`: not. */
  val block: Block? = null,
  /** How many running sessions of their own they may have at once; `null`: the default. */
  val sessionLimit: Int? = null,
  /** Their time zone (an IANA name, from their browser): the mails' times are in it; `null`: not known, UTC. */
  val timeZone: String? = null,
) {
  /** They can't log in at [now]: timed out or suspended. */
  fun keptOut(now: Instant = Instant.now()): Boolean = block?.holds(now) == true
}

/** An account as the admin panel lists it, invited ones (no name, no password yet) too. */
data class Account(
  val id: Uuid,
  val name: String?,
  val email: String?,
  val administrator: Boolean,
  val createdAt: Instant,
  /** `null` while invited. */
  val signedUpAt: Instant?,
  val autoDelete: String,
  /** The language's code they chose (`en`, `de`); `null`: their browser's. */
  val language: String? = null,
  /** How they log in: with a password of their own, through the identity provider (linked to it), a second factor. */
  val hasPassword: Boolean = false,
  val linked: Boolean = false,
  val twoFactor: Boolean = false,
  /** An Administrator keeps them out (it may have run out: [Block.holds]); `null`: not. */
  val block: Block? = null,
  /** How many running sessions of their own they may have at once; `null`: the default. */
  val sessionLimit: Int? = null,
  /** Whether the single sign-on provider calls their address verified; `null`: not its address, or it didn't say. */
  val emailVerifiedByProvider: Boolean? = null,
  /** Their time zone (an IANA name): the mails' times are in it; `null`: not known, UTC. */
  val timeZone: String? = null,
)

/** What the automatic deletion of [user]'s account goes by. */
data class AccountActivity(
  val user: Account,
  /** The end of their last finished session; `null`: none finished. */
  val lastSessionEnd: Instant?,
  /** They play in a session that isn't finished: no deletion while it runs. */
  val playing: Boolean,
  val warnedAt: Instant?,
)

/**
 * The accounts. Names and e-mail addresses are compared without regard to case, like the unique indexes in the schema.
 * Deleting keeps the row (its UUID stays in the sessions) and clears the rest.
 */
class UserStore(private val db: RandogeddonDatabase) {
  /** The users who signed up and aren't deleted. */
  suspend fun active(): List<User> = db.query {
    UsersTable.selectAll().where { signedUp() }.mapNotNull(::user)
  }

  suspend fun findActive(name: String): User? = db.query {
    UsersTable.selectAll().where { (UsersTable.name.lowerCase() eq name.lowercase()) and signedUp() }
      .singleOrNull()?.let(::user)
  }

  /** The user who logs in as [nameOrEmail]: an address if it has an `@` (names can't), a name otherwise. */
  suspend fun findLogin(nameOrEmail: String): User? = db.query {
    val column = if ('@' in nameOrEmail) UsersTable.email.lowerCase() else UsersTable.name.lowerCase()
    UsersTable.selectAll().where { (column eq nameOrEmail.lowercase()) and signedUp() }.singleOrNull()?.let(::user)
  }

  suspend fun findActive(id: Uuid): User? = db.query {
    UsersTable.selectAll().where { (UsersTable.id eq id) and signedUp() }.singleOrNull()?.let(::user)
  }

  /** The account linked to the Authentik user [subject] (the ID token's `sub`). */
  suspend fun findBySubject(subject: String): User? = db.query {
    UsersTable.selectAll().where { (UsersTable.oidcSubject eq subject) and signedUp() }.singleOrNull()?.let(::user)
  }

  /**
   * Links [id] to the Authentik user [subject]; `false` if another account has it (the column's unique index decides,
   * also for two at once), or [id] is linked to another.
   */
  suspend fun linkSubject(id: Uuid, subject: String): Boolean = try {
    db.query {
      UsersTable.update({ (UsersTable.id eq id) and (UsersTable.oidcSubject.isNull() or (UsersTable.oidcSubject eq subject)) }) {
        it[oidcSubject] = subject
      } == 1
    }
  } catch (e: SQLException) {
    if (e.uniqueViolation()) false else throw e
  }

  /** A signed-up user right away (the host's commands, tests); the others are [invite]d. */
  suspend fun create(name: String, passwordHash: String, now: Instant = Instant.now(), email: String? = null): User =
    db.query {
      val id = Uuid.random()
      UsersTable.insert {
        it[UsersTable.id] = id
        it[UsersTable.name] = name
        it[UsersTable.passwordHash] = passwordHash
        it[UsersTable.email] = email
        it[createdAt] = now
        it[signedUpAt] = now
      }
      User(id, name, passwordHash, email = email)
    }

  /** Admins reach the admin panel. */
  suspend fun setAdmin(id: Uuid, admin: Boolean) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[isAdmin] = admin }
    Unit
  }

  /** Sets a new password and ends every login of the user. */
  suspend fun setPassword(id: Uuid, passwordHash: String) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.passwordHash] = passwordHash }
    LoginSessionsTable.deleteWhere { userId eq id }
  }

  /** Every account that isn't deleted, invited ones too. */
  suspend fun accounts(): List<Account> = db.query {
    UsersTable.selectAll().where { UsersTable.deletedAt.isNull() }.map(::account)
  }

  suspend fun account(id: Uuid): Account? = db.query {
    UsersTable.selectAll().where { (UsersTable.id eq id) and UsersTable.deletedAt.isNull() }.singleOrNull()?.let(::account)
  }

  /** The active account with [email] (signed up or invited). */
  suspend fun accountByEmail(email: String): Account? = db.query {
    UsersTable.selectAll().where { (UsersTable.email.lowerCase() eq email.lowercase()) and UsersTable.deletedAt.isNull() }
      .singleOrNull()?.let(::account)
  }

  /** A new account for [email], without a name and password until its user signs up. */
  suspend fun invite(email: String, now: Instant = Instant.now()): Account = db.query {
    val id = Uuid.random()
    UsersTable.insert {
      it[UsersTable.id] = id
      it[UsersTable.email] = email
      it[createdAt] = now
    }
    Account(id, null, email, false, now, null, "never")
  }

  /**
   * The invited user [id] chose their name and password; `false` if they had signed up already (or were deleted).
   * Their name goes into the sessions they were put in meanwhile.
   */
  suspend fun signUp(id: Uuid, name: String, passwordHash: String, autoDelete: String, now: Instant = Instant.now()): Boolean =
    db.query { signUpIn(id, name, passwordHash, autoDelete, now) }

  /**
   * The invited user [id] signs up with the invitation link [invitation] (its token's hash): the link is used and the
   * account gets its name and password ([signUp]) in one transaction, so a sign-up that doesn't happen leaves the link
   * as it was. [SignUp.NAME_TAKEN]: another account took [name] a moment before (the unique index decided).
   */
  suspend fun signUpInvited(
    invitation: String,
    id: Uuid,
    name: String,
    passwordHash: String,
    autoDelete: String,
    now: Instant = Instant.now(),
  ): SignUp = try {
    db.query {
      val used = InvitationsTable.update({ (InvitationsTable.tokenHash eq invitation) and InvitationsTable.usedAt.isNull() }) {
        it[InvitationsTable.usedAt] = now
      } > 0
      when {
        !used -> SignUp.LINK_USED
        // The link stays used: it is no good for an account that has signed up
        signUpIn(id, name, passwordHash, autoDelete, now) -> SignUp.DONE
        else -> SignUp.SIGNED_UP_ALREADY
      }
    }
  } catch (e: SQLException) {
    // Only the name's unique index (the transaction was rolled back, the link with it); anything else goes on
    if (e.uniqueViolation()) SignUp.NAME_TAKEN else throw e
  }

  /** A new name: it shows in the sessions that aren't finished; finished ones keep the old one. */
  suspend fun rename(id: Uuid, name: String) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.name] = name }
    nameInRunningSessions(id, name)
  }

  suspend fun setEmail(id: Uuid, email: String?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[UsersTable.email] = email
      // What the provider said was about the address before
      it[emailVerifiedByProvider] = null
    }
    Unit
  }

  /** Whether the single sign-on provider calls [id]'s address verified; `null`: it didn't say. */
  suspend fun setEmailVerifiedByProvider(id: Uuid, verified: Boolean?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[emailVerifiedByProvider] = verified }
    Unit
  }

  /** A new deletion period; a warning sent for the old one doesn't count any more. */
  /** The language's code [id] reads the app in; `null`: their browser's. */
  suspend fun setLanguage(id: Uuid, language: String?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.language] = language }
    Unit
  }

  /** The theme and mode [id] sees the app in (codes); `null`: the default theme, automatic. */
  suspend fun setTheme(id: Uuid, theme: String?, mode: String?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[UsersTable.theme] = theme
      it[themeMode] = mode
    }
    Unit
  }

  /** The font [id] reads the app in (`original`, a theme's code; `null`: the theme's), and where it shows. */
  suspend fun setFont(id: Uuid, font: String?, scope: String?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[UsersTable.font] = font
      it[fontScope] = scope
    }
    Unit
  }

  /** The time zone [id]'s mails give their times in (an IANA name, checked by the caller); `null`: not known, UTC. */
  suspend fun setTimeZone(id: Uuid, zone: String?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[timeZone] = zone }
    Unit
  }

  suspend fun setAutoDelete(id: Uuid, autoDelete: String) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[UsersTable.autoDelete] = autoDelete
      it[deleteWarnedAt] = null
    }
    Unit
  }

  suspend fun markWarned(id: Uuid, at: Instant?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[deleteWarnedAt] = at }
    Unit
  }

  /**
   * Deletes the account: its name, password, e-mail address, settings and link to Authentik go, its logins,
   * invitations, pending addresses and login links too. The row stays (marked deleted) while a session names it (as a
   * player, in a role, as its creator), so the sessions keep its UUID, with the name it had in each of them; one no
   * session ever named goes for good. The name is free again.
   */
  suspend fun delete(id: Uuid, now: Instant = Instant.now()) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[name] = null
      it[language] = null
      it[theme] = null
      it[themeMode] = null
      it[font] = null
      it[fontScope] = null
      it[sessionLimit] = null
      it[emailVerifiedByProvider] = null
      it[timeZone] = null
      it[passwordHash] = null
      it[email] = null
      it[isAdmin] = false
      it[autoDelete] = "never"
      it[deleteWarnedAt] = null
      it[totpSecret] = null
      it[totpEnabledAt] = null
      it[totpLastStep] = null
      it[oidcSubject] = null
      it[blockedAt] = null
      it[blockedUntil] = null
      it[blockedReason] = null
      it[blockedBy] = null
      it[deletedAt] = now
    }
    LoginSessionsTable.deleteWhere { userId eq id }
    RecoveryCodesTable.deleteWhere { userId eq id }
    InvitationsTable.deleteWhere { userId eq id }
    EmailChangesTable.deleteWhere { userId eq id }
    LoginLinksTable.deleteWhere { userId eq id }
    val named = SessionWorldsTable.select(SessionWorldsTable.userId).where { SessionWorldsTable.userId eq id }.limit(1).any() ||
      SessionRolesTable.select(SessionRolesTable.userId).where { SessionRolesTable.userId eq id }.limit(1).any() ||
      SessionsTable.select(SessionsTable.id).where { SessionsTable.createdBy eq id }.limit(1).any()
    if (!named) UsersTable.deleteWhere { UsersTable.id eq id }
    Unit
  }

  /**
   * Keeps [id] out from [now] [until] (`null`: suspended, until taken back), with the [reason] they are told, noted
   * as done [by]. Their logins end (bridge keys are logins too).
   */
  suspend fun block(id: Uuid, until: Instant?, reason: String?, by: String, now: Instant = Instant.now()) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[blockedAt] = now
      it[blockedUntil] = until
      it[blockedReason] = reason
      it[blockedBy] = by
    }
    LoginSessionsTable.deleteWhere { userId eq id }
    Unit
  }

  /** How many running sessions of their own [id] may have at once; `null`: the default. */
  suspend fun setSessionLimit(id: Uuid, limit: Int?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[sessionLimit] = limit }
    Unit
  }

  /** Lets [id] in again. */
  suspend fun unblock(id: Uuid) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[blockedAt] = null
      it[blockedUntil] = null
      it[blockedReason] = null
      it[blockedBy] = null
    }
    Unit
  }

  /** The signed-up accounts with an auto-deletion period, and what it counts from. */
  suspend fun activity(): List<AccountActivity> = db.query { activity(null) }

  /** [id]'s [activity]; `null` if it isn't signed up or has no auto-deletion period. */
  suspend fun activityOf(id: Uuid): AccountActivity? = db.query { activity(id).singleOrNull() }

  /** [activity] of every account, or only of [id]. */
  private fun activity(id: Uuid?): List<AccountActivity> {
    val users = UsersTable.selectAll().where {
      val periods = signedUp() and (UsersTable.autoDelete neq "never")
      if (id == null) periods else periods and (UsersTable.id eq id)
    }.toList()
    if (users.isEmpty()) return emptyList()
    val ids = users.map { it[UsersTable.id] }
    val played = (SessionWorldsTable innerJoin SessionsTable).select(SessionWorldsTable.userId, SessionsTable.finishedAt)
      .where { SessionWorldsTable.userId inList ids }
      .map { it[SessionWorldsTable.userId] to it[SessionsTable.finishedAt] } +
      (SessionRolesTable innerJoin SessionsTable).select(SessionRolesTable.userId, SessionsTable.finishedAt)
        .where { SessionRolesTable.userId inList ids }
        .map { it[SessionRolesTable.userId] to it[SessionsTable.finishedAt] }
    val byUser = played.groupBy({ it.first }, { it.second })
    return users.map { row ->
      val ends = byUser[row[UsersTable.id]].orEmpty()
      AccountActivity(account(row), ends.filterNotNull().maxOrNull(), ends.any { it == null }, row[UsersTable.deleteWarnedAt])
    }
  }

  private fun signUpIn(id: Uuid, name: String, passwordHash: String, autoDelete: String, now: Instant): Boolean {
    val updated = UsersTable.update({ (UsersTable.id eq id) and UsersTable.signedUpAt.isNull() and UsersTable.deletedAt.isNull() }) {
      it[UsersTable.name] = name
      it[UsersTable.passwordHash] = passwordHash
      it[UsersTable.autoDelete] = autoDelete
      it[signedUpAt] = now
    }
    if (updated > 0) nameInRunningSessions(id, name)
    return updated > 0
  }

  private fun nameInRunningSessions(id: Uuid, name: String) {
    val running = SessionsTable.select(SessionsTable.id).where { SessionsTable.finishedAt.isNull() }
    SessionWorldsTable.update({ (SessionWorldsTable.userId eq id) and (SessionWorldsTable.sessionId inSubQuery running) }) {
      it[userName] = name
    }
    val stillRunning = SessionsTable.select(SessionsTable.id).where { SessionsTable.finishedAt.isNull() }
    SessionRolesTable.update({ (SessionRolesTable.userId eq id) and (SessionRolesTable.sessionId inSubQuery stillRunning) }) {
      it[userName] = name
    }
  }

  private fun signedUp() = UsersTable.deletedAt.isNull() and UsersTable.signedUpAt.isNotNull()

  private fun user(row: ResultRow): User? {
    val name = row[UsersTable.name] ?: return null
    val hash = row[UsersTable.passwordHash] ?: return null
    return User(
      row[UsersTable.id], name, hash, row[UsersTable.isAdmin], row[UsersTable.email], row[UsersTable.autoDelete],
      twoFactor = row[UsersTable.totpEnabledAt] != null, language = row[UsersTable.language],
      theme = row[UsersTable.theme], themeMode = row[UsersTable.themeMode],
      font = row[UsersTable.font], fontScope = row[UsersTable.fontScope], block = block(row),
      sessionLimit = row[UsersTable.sessionLimit], timeZone = row[UsersTable.timeZone],
    )
  }

  private fun block(row: ResultRow): Block? = row[UsersTable.blockedAt]?.let {
    Block(it, row[UsersTable.blockedUntil], row[UsersTable.blockedReason], row[UsersTable.blockedBy])
  }

  private fun account(row: ResultRow) = Account(
    row[UsersTable.id], row[UsersTable.name], row[UsersTable.email], row[UsersTable.isAdmin], row[UsersTable.createdAt],
    row[UsersTable.signedUpAt], row[UsersTable.autoDelete], language = row[UsersTable.language],
    hasPassword = row[UsersTable.passwordHash] != null, linked = row[UsersTable.oidcSubject] != null,
    twoFactor = row[UsersTable.totpEnabledAt] != null, block = block(row), sessionLimit = row[UsersTable.sessionLimit],
    emailVerifiedByProvider = row[UsersTable.emailVerifiedByProvider], timeZone = row[UsersTable.timeZone],
  )
}

/** What a sign-up with an invitation link came to ([UserStore.signUpInvited]). */
enum class SignUp {
  DONE,
  /** The link was used already (a second sign-up with it at the same time). */
  LINK_USED,
  SIGNED_UP_ALREADY,
  /** Another account has the name. */
  NAME_TAKEN,
}

/** PostgreSQL's SQLSTATE for a unique index that refused a row. */
private const val UNIQUE_VIOLATION = "23505"

/** A unique index refused a row (here, or in a cause). */
internal fun SQLException.uniqueViolation(): Boolean =
  generateSequence<Throwable>(this) { it.cause }.any { (it as? SQLException)?.sqlState == UNIQUE_VIOLATION }

/** A user's TOTP: the encrypted secret, since when it counts (`null`: being set up), the newest step used. */
data class TotpState(val secret: String, val enabledAt: Instant?, val lastStep: Long?)

/** Two-factor login: each user's TOTP secret (encrypted by the caller) and their recovery codes (hashed). */
class TwoFactorStore(private val db: RandogeddonDatabase) {
  suspend fun of(id: Uuid): TotpState? = db.query {
    UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.let { row ->
      row[UsersTable.totpSecret]?.let { TotpState(it, row[UsersTable.totpEnabledAt], row[UsersTable.totpLastStep]) }
    }
  }

  /** A new secret being set up; two-factor login is off until [enable] (an older one is replaced). */
  suspend fun setUp(id: Uuid, encryptedSecret: String) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[totpSecret] = encryptedSecret
      it[totpEnabledAt] = null
      it[totpLastStep] = null
    }
    Unit
  }

  /** The secret was confirmed with a code: it counts from now, with new recovery codes ([codeHashes]). */
  suspend fun enable(id: Uuid, codeHashes: List<String>, now: Instant = Instant.now()) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[totpEnabledAt] = now }
    replace(id, codeHashes)
  }

  /** Off: the secret and the recovery codes go. */
  suspend fun disable(id: Uuid) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[totpSecret] = null
      it[totpEnabledAt] = null
      it[totpLastStep] = null
    }
    RecoveryCodesTable.deleteWhere { userId eq id }
    Unit
  }

  /**
   * Takes [step] as used, if it is newer than the last one used; `false` otherwise (the same code a second time). The
   * database decides, so two logins with one code at once can't both get through.
   */
  suspend fun useStep(id: Uuid, step: Long): Boolean = db.query {
    UsersTable.update({
      (UsersTable.id eq id) and (UsersTable.totpLastStep.isNull() or (UsersTable.totpLastStep less step))
    }) { it[totpLastStep] = step } > 0
  }

  /** New recovery codes in place of the old ones. */
  suspend fun replaceCodes(id: Uuid, codeHashes: List<String>) = db.query { replace(id, codeHashes) }

  /** Uses a recovery code once; `false` if it isn't one of [id]'s, or was used. */
  suspend fun useCode(id: Uuid, codeHash: String, now: Instant = Instant.now()): Boolean = db.query {
    RecoveryCodesTable.update({
      (RecoveryCodesTable.userId eq id) and (RecoveryCodesTable.codeHash eq codeHash) and RecoveryCodesTable.usedAt.isNull()
    }) { it[usedAt] = now } > 0
  }

  /** How many of [id]'s recovery codes are left. */
  suspend fun codesLeft(id: Uuid): Int = db.query {
    RecoveryCodesTable.selectAll().where { (RecoveryCodesTable.userId eq id) and RecoveryCodesTable.usedAt.isNull() }.count().toInt()
  }

  private fun replace(id: Uuid, codeHashes: List<String>) {
    RecoveryCodesTable.deleteWhere { userId eq id }
    for (hash in codeHashes.distinct()) {
      RecoveryCodesTable.insert {
        it[userId] = id
        it[codeHash] = hash
      }
    }
  }
}

/** An invitation link: whose, to which address, and how long it works. */
data class Invitation(
  val userId: Uuid,
  val email: String,
  val suggestedName: String?,
  val expiresAt: Instant,
  val usedAt: Instant?,
)

/** The invitation links, by the SHA-256 of their token. A user has at most one: a new one replaces it. */
class InvitationStore(private val db: RandogeddonDatabase) {
  suspend fun create(
    tokenHash: String,
    userId: Uuid,
    email: String,
    suggestedName: String?,
    expiresAt: Instant,
    now: Instant = Instant.now(),
  ) = db.query {
    // One at a time per user (and at most one row: V29's unique index)
    lockUser(userId)
    InvitationsTable.deleteWhere { InvitationsTable.userId eq userId }
    InvitationsTable.insert {
      it[InvitationsTable.tokenHash] = tokenHash
      it[InvitationsTable.userId] = userId
      it[InvitationsTable.email] = email
      it[InvitationsTable.suggestedName] = suggestedName
      it[createdAt] = now
      it[InvitationsTable.expiresAt] = expiresAt
    }
    Unit
  }

  /** The invitation of [tokenHash], used or expired ones too; `null`: no such link (or replaced). */
  suspend fun find(tokenHash: String): Invitation? = db.query {
    InvitationsTable.selectAll().where { InvitationsTable.tokenHash eq tokenHash }.singleOrNull()?.let(::invitation)
  }

  /** [userId]'s current invitation. */
  suspend fun of(userId: Uuid): Invitation? = db.query {
    InvitationsTable.selectAll().where { InvitationsTable.userId eq userId }.singleOrNull()?.let(::invitation)
  }

  /** Every user's current invitation, by the user. */
  suspend fun all(): Map<Uuid, Invitation> = db.query {
    InvitationsTable.selectAll().map(::invitation).associateBy { it.userId }
  }

  /** Marks the link used; `false` if it was used already (a second sign-up with it at the same time). */
  suspend fun use(tokenHash: String, now: Instant = Instant.now()): Boolean = db.query {
    InvitationsTable.update({ (InvitationsTable.tokenHash eq tokenHash) and InvitationsTable.usedAt.isNull() }) {
      it[usedAt] = now
    } > 0
  }

  private fun invitation(row: ResultRow) = Invitation(
    row[InvitationsTable.userId], row[InvitationsTable.email], row[InvitationsTable.suggestedName],
    row[InvitationsTable.expiresAt], row[InvitationsTable.usedAt],
  )
}

/** A new e-mail address of [userId], waiting for the confirmation link in a mail to it. */
data class EmailChange(val userId: Uuid, val email: String, val expiresAt: Instant)

/** New e-mail addresses waiting for their confirmation; a user has at most one. */
class EmailChangeStore(private val db: RandogeddonDatabase) {
  suspend fun create(tokenHash: String, userId: Uuid, email: String, expiresAt: Instant, now: Instant = Instant.now()) =
    db.query {
      // One at a time per user (and at most one row: V29's unique index)
      lockUser(userId)
      EmailChangesTable.deleteWhere { EmailChangesTable.userId eq userId }
      EmailChangesTable.insert {
        it[EmailChangesTable.tokenHash] = tokenHash
        it[EmailChangesTable.userId] = userId
        it[EmailChangesTable.email] = email
        it[createdAt] = now
        it[EmailChangesTable.expiresAt] = expiresAt
      }
      Unit
    }

  /**
   * The change of [tokenHash] if it hasn't expired; it's gone afterwards (a link works once: of two takes at once, the
   * one whose delete removed the row gets it).
   */
  suspend fun take(tokenHash: String, now: Instant = Instant.now()): EmailChange? = db.query {
    val row = EmailChangesTable.selectAll().where { EmailChangesTable.tokenHash eq tokenHash }.singleOrNull()
      ?: return@query null
    val taken = EmailChangesTable.deleteWhere { EmailChangesTable.tokenHash eq tokenHash } > 0
    EmailChange(row[EmailChangesTable.userId], row[EmailChangesTable.email], row[EmailChangesTable.expiresAt])
      .takeIf { taken && it.expiresAt > now }
  }

  /** [userId]'s address waiting for confirmation. */
  suspend fun pending(userId: Uuid, now: Instant = Instant.now()): String? = db.query {
    EmailChangesTable.selectAll().where { (EmailChangesTable.userId eq userId) and (EmailChangesTable.expiresAt greater now) }
      .singleOrNull()?.get(EmailChangesTable.email)
  }
}

/** Logged-in browsers, by the SHA-256 of their cookie token. */
class LoginSessionStore(private val db: RandogeddonDatabase) {
  /** [method]: how the login was made (`password`, `oidc`, `link`); [providerSession]: its session at the provider. */
  suspend fun create(
    tokenHash: String,
    userId: Uuid,
    expiresAt: Instant,
    now: Instant = Instant.now(),
    method: String = "password",
    providerSession: String? = null,
  ) = db.query { insertLogin(tokenHash, userId, expiresAt, now, method, providerSession) }

  /**
   * As [create], unless [userId] has [most] logins of [method] already that haven't expired: `false` then, and none is
   * made. Counted and made in one transaction with the user's row locked, so logins made at the same moment can't
   * pass [most] together.
   */
  suspend fun createWithin(
    most: Int,
    tokenHash: String,
    userId: Uuid,
    expiresAt: Instant,
    now: Instant = Instant.now(),
    method: String = "password",
  ): Boolean = db.query {
    UsersTable.select(UsersTable.id).where { UsersTable.id eq userId }.forUpdate().singleOrNull()
    val has = LoginSessionsTable.selectAll()
      .where { (LoginSessionsTable.userId eq userId) and (LoginSessionsTable.method eq method) and (LoginSessionsTable.expiresAt greater now) }
      .count()
    if (has >= most) return@query false
    insertLogin(tokenHash, userId, expiresAt, now, method, null)
    true
  }

  private fun insertLogin(
    tokenHash: String,
    userId: Uuid,
    expiresAt: Instant,
    now: Instant,
    method: String,
    providerSession: String?,
  ) {
    LoginSessionsTable.insert {
      it[LoginSessionsTable.tokenHash] = tokenHash
      it[LoginSessionsTable.userId] = userId
      it[createdAt] = now
      it[LoginSessionsTable.expiresAt] = expiresAt
      it[LoginSessionsTable.method] = method
      it[LoginSessionsTable.providerSession] = providerSession
    }
  }

  /**
   * Ends the logins of [method] that came from the provider's session [providerSession]; with [userId] also that
   * user's logins of [method] without one (made before it was kept). How many ended.
   */
  suspend fun deleteOfProviderSession(method: String, providerSession: String, userId: Uuid?): Int = db.query {
    LoginSessionsTable.deleteWhere {
      val ofSession = (LoginSessionsTable.method eq method) and (LoginSessionsTable.providerSession eq providerSession)
      if (userId == null) ofSession
      else ofSession or ((LoginSessionsTable.method eq method) and (LoginSessionsTable.userId eq userId) and LoginSessionsTable.providerSession.isNull())
    }
  }

  /**
   * Ends [userId]'s logins made by one of [methods], all but [keep] (a token's hash; `null`: all of them). How many
   * ended.
   */
  suspend fun deleteOthers(userId: Uuid, methods: Collection<String>, keep: String? = null): Int = db.query {
    LoginSessionsTable.deleteWhere {
      val theirs = (LoginSessionsTable.userId eq userId) and (LoginSessionsTable.method inList methods)
      if (keep == null) theirs else theirs and (LoginSessionsTable.tokenHash neq keep)
    }
  }

  /** Ends all of [userId]'s logins made by [method]. How many ended. */
  suspend fun deleteOf(userId: Uuid, method: String): Int = db.query {
    LoginSessionsTable.deleteWhere { (LoginSessionsTable.userId eq userId) and (LoginSessionsTable.method eq method) }
  }

  /** The user of a session that hasn't expired yet. */
  suspend fun userOf(tokenHash: String, now: Instant = Instant.now()): Uuid? = loginOf(tokenHash, now)?.first

  /** The user of a session that hasn't expired yet, and how it was made. */
  suspend fun loginOf(tokenHash: String, now: Instant = Instant.now()): Pair<Uuid, String>? = db.query {
    LoginSessionsTable.selectAll()
      .where { (LoginSessionsTable.tokenHash eq tokenHash) and (LoginSessionsTable.expiresAt greater now) }
      .singleOrNull()?.let { it[LoginSessionsTable.userId] to it[LoginSessionsTable.method] }
  }

  suspend fun delete(tokenHash: String) = db.query {
    LoginSessionsTable.deleteWhere { LoginSessionsTable.tokenHash eq tokenHash }
    Unit
  }

  suspend fun deleteExpired(now: Instant = Instant.now()): Int = db.query {
    LoginSessionsTable.deleteWhere { expiresAt less now }
  }

  /** [userId]'s logins made by [method] that haven't expired, newest first. */
  suspend fun logins(userId: Uuid, method: String, now: Instant = Instant.now()): List<LoginRow> = db.query {
    LoginSessionsTable.selectAll()
      .where { (LoginSessionsTable.userId eq userId) and (LoginSessionsTable.method eq method) and (LoginSessionsTable.expiresAt greater now) }
      .orderBy(LoginSessionsTable.createdAt, SortOrder.DESC)
      .map {
        LoginRow(
          it[LoginSessionsTable.tokenHash], it[LoginSessionsTable.createdAt], it[LoginSessionsTable.expiresAt],
          it[LoginSessionsTable.lastUsedAt],
        )
      }
  }

  /** Notes that the login was used [now], unless that was noted less than [every] ago. */
  suspend fun touch(tokenHash: String, now: Instant = Instant.now(), every: java.time.Duration = java.time.Duration.ofMinutes(5)) =
    db.query {
      LoginSessionsTable.update({
        (LoginSessionsTable.tokenHash eq tokenHash) and
          (LoginSessionsTable.lastUsedAt.isNull() or (LoginSessionsTable.lastUsedAt less now.minus(every)))
      }) { it[lastUsedAt] = now }
      Unit
    }

  /** Ends [userId]'s login [tokenHash] (not someone else's); whether there was one. */
  suspend fun delete(userId: Uuid, tokenHash: String): Boolean = db.query {
    LoginSessionsTable.deleteWhere { (LoginSessionsTable.tokenHash eq tokenHash) and (LoginSessionsTable.userId eq userId) } > 0
  }

  /** Ends [userId]'s oldest logins made by [method] beyond the newest [keep]. How many ended. */
  suspend fun keepNewest(userId: Uuid, method: String, keep: Int): Int = db.query {
    val old = LoginSessionsTable.selectAll()
      .where { (LoginSessionsTable.userId eq userId) and (LoginSessionsTable.method eq method) }
      .orderBy(LoginSessionsTable.createdAt, SortOrder.DESC)
      .map { it[LoginSessionsTable.tokenHash] }.drop(keep)
    if (old.isEmpty()) 0 else LoginSessionsTable.deleteWhere { LoginSessionsTable.tokenHash inList old }
  }
}

/** A login as a list shows it: by the hash of its token. */
data class LoginRow(val tokenHash: String, val createdAt: Instant, val expiresAt: Instant, val lastUsedAt: Instant?)

/**
 * One-time login links (`randogeddon admins --login`), by the SHA-256 of their token. A used one is gone at once, the
 * expired ones go when the next is made.
 */
class LoginLinkStore(private val db: RandogeddonDatabase) {
  suspend fun create(tokenHash: String, userId: Uuid, expiresAt: Instant, now: Instant = Instant.now()) = db.query {
    LoginLinksTable.deleteWhere { (LoginLinksTable.expiresAt less now) or LoginLinksTable.usedAt.isNotNull() }
    LoginLinksTable.insert {
      it[LoginLinksTable.tokenHash] = tokenHash
      it[LoginLinksTable.userId] = userId
      it[createdAt] = now
      it[LoginLinksTable.expiresAt] = expiresAt
    }
    Unit
  }

  /** Uses the link: its user if it exists, hasn't expired and wasn't used; it works only once. */
  suspend fun use(tokenHash: String, now: Instant = Instant.now()): Uuid? = db.query {
    val row = LoginLinksTable.selectAll()
      .where { (LoginLinksTable.tokenHash eq tokenHash) and LoginLinksTable.usedAt.isNull() and (LoginLinksTable.expiresAt greater now) }
      .singleOrNull() ?: return@query null
    // The delete decides: of two uses at once, only one removes it
    val used = LoginLinksTable.deleteWhere { (LoginLinksTable.tokenHash eq tokenHash) and LoginLinksTable.usedAt.isNull() }
    row[LoginLinksTable.userId].takeIf { used == 1 }
  }
}

/** Locks [userId]'s row until the transaction ends (inside one): what is done for one user is done one at a time. */
private fun lockUser(userId: Uuid) {
  UsersTable.select(UsersTable.id).where { UsersTable.id eq userId }.forUpdate().singleOrNull()
}
