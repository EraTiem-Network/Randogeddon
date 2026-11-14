package net.eratiem.randogeddon.database

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
import java.time.Instant
import kotlin.uuid.Uuid

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
)

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

  /** Links [id] to the Authentik user [subject]; `false` if another account has it, or [id] is linked to another. */
  suspend fun linkSubject(id: Uuid, subject: String): Boolean = db.query {
    val taken = UsersTable.selectAll().where { (UsersTable.oidcSubject eq subject) and (UsersTable.id neq id) }.any()
    !taken && UsersTable.update({ (UsersTable.id eq id) and (UsersTable.oidcSubject.isNull() or (UsersTable.oidcSubject eq subject)) }) {
      it[oidcSubject] = subject
    } == 1
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
    db.query {
      val updated = UsersTable.update({ (UsersTable.id eq id) and UsersTable.signedUpAt.isNull() and UsersTable.deletedAt.isNull() }) {
        it[UsersTable.name] = name
        it[UsersTable.passwordHash] = passwordHash
        it[UsersTable.autoDelete] = autoDelete
        it[signedUpAt] = now
      }
      if (updated > 0) nameInRunningSessions(id, name)
      updated > 0
    }

  /** A new name: it shows in the sessions that aren't finished; finished ones keep the old one. */
  suspend fun rename(id: Uuid, name: String) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.name] = name }
    nameInRunningSessions(id, name)
  }

  suspend fun setEmail(id: Uuid, email: String?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.email] = email }
    Unit
  }

  /** A new deletion period; a warning sent for the old one doesn't count any more. */
  /** The language's code [id] reads the app in; `null`: their browser's. */
  suspend fun setLanguage(id: Uuid, language: String?) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.language] = language }
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
   * Deletes the account: its password, e-mail address, settings and link to Authentik go, its logins, invitations and
   * pending addresses too. The row stays (marked deleted), so the sessions keep its UUID, with the name it had in each of them; the name
   * is free again.
   */
  suspend fun delete(id: Uuid, now: Instant = Instant.now()) = db.query {
    UsersTable.update({ UsersTable.id eq id }) {
      it[passwordHash] = null
      it[email] = null
      it[isAdmin] = false
      it[autoDelete] = "never"
      it[deleteWarnedAt] = null
      it[totpSecret] = null
      it[totpEnabledAt] = null
      it[totpLastStep] = null
      it[oidcSubject] = null
      it[deletedAt] = now
    }
    LoginSessionsTable.deleteWhere { userId eq id }
    RecoveryCodesTable.deleteWhere { userId eq id }
    InvitationsTable.deleteWhere { userId eq id }
    EmailChangesTable.deleteWhere { userId eq id }
    Unit
  }

  /** The signed-up accounts with an auto-deletion period, and what it counts from. */
  suspend fun activity(): List<AccountActivity> = db.query {
    val users = UsersTable.selectAll().where { signedUp() and (UsersTable.autoDelete neq "never") }.toList()
    if (users.isEmpty()) return@query emptyList()
    val ids = users.map { it[UsersTable.id] }
    val played = (SessionWorldsTable innerJoin SessionsTable).selectAll()
      .where { SessionWorldsTable.userId inList ids }
      .map { it[SessionWorldsTable.userId] to it[SessionsTable.finishedAt] } +
      (SessionRolesTable innerJoin SessionsTable).selectAll()
        .where { SessionRolesTable.userId inList ids }
        .map { it[SessionRolesTable.userId] to it[SessionsTable.finishedAt] }
    val byUser = played.groupBy({ it.first }, { it.second })
    users.map { row ->
      val ends = byUser[row[UsersTable.id]].orEmpty()
      AccountActivity(account(row), ends.filterNotNull().maxOrNull(), ends.any { it == null }, row[UsersTable.deleteWarnedAt])
    }
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
    )
  }

  private fun account(row: ResultRow) = Account(
    row[UsersTable.id], row[UsersTable.name], row[UsersTable.email], row[UsersTable.isAdmin], row[UsersTable.createdAt],
    row[UsersTable.signedUpAt], row[UsersTable.autoDelete],
  )
}

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

  /** The change of [tokenHash] if it hasn't expired; it's gone afterwards (a link works once). */
  suspend fun take(tokenHash: String, now: Instant = Instant.now()): EmailChange? = db.query {
    val row = EmailChangesTable.selectAll().where { EmailChangesTable.tokenHash eq tokenHash }.singleOrNull()
      ?: return@query null
    EmailChangesTable.deleteWhere { EmailChangesTable.tokenHash eq tokenHash }
    EmailChange(row[EmailChangesTable.userId], row[EmailChangesTable.email], row[EmailChangesTable.expiresAt])
      .takeIf { it.expiresAt > now }
  }

  /** [userId]'s address waiting for confirmation. */
  suspend fun pending(userId: Uuid, now: Instant = Instant.now()): String? = db.query {
    EmailChangesTable.selectAll().where { (EmailChangesTable.userId eq userId) and (EmailChangesTable.expiresAt greater now) }
      .singleOrNull()?.get(EmailChangesTable.email)
  }
}

/** Logged-in browsers, by the SHA-256 of their cookie token. */
class LoginSessionStore(private val db: RandogeddonDatabase) {
  /** [method]: how the login was made (`password`, `oidc`, `link`). */
  suspend fun create(tokenHash: String, userId: Uuid, expiresAt: Instant, now: Instant = Instant.now(), method: String = "password") =
    db.query {
      LoginSessionsTable.insert {
        it[LoginSessionsTable.tokenHash] = tokenHash
        it[LoginSessionsTable.userId] = userId
        it[createdAt] = now
        it[LoginSessionsTable.expiresAt] = expiresAt
        it[LoginSessionsTable.method] = method
      }
      Unit
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
}

/** One-time login links (`randogeddon admins --login`), by the SHA-256 of their token. */
class LoginLinkStore(private val db: RandogeddonDatabase) {
  suspend fun create(tokenHash: String, userId: Uuid, expiresAt: Instant, now: Instant = Instant.now()) = db.query {
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
    val used = LoginLinksTable.update({ (LoginLinksTable.tokenHash eq tokenHash) and LoginLinksTable.usedAt.isNull() }) {
      it[usedAt] = now
    }
    row[LoginLinksTable.userId].takeIf { used == 1 }
  }
}

/** How long a seed's game server has run: [runTimeMs] up to [countedUntil], which is `null` while it isn't running. */
data class SessionTime(val runTimeMs: Long, val countedUntil: Instant?)

/**
 * The running time of each seed's game server, kept by the gateway: [started] when MultiServer accepts clients,
 * [tick] while it runs, [stopped] when it ends. Times it was stopped don't count.
 */
class SessionTimeStore(private val db: RandogeddonDatabase) {
  suspend fun started(seedName: String, now: Instant = Instant.now()) = db.query {
    sessionId(seedName, now)
    SessionsTable.update({ SessionsTable.seedName eq seedName }) { it[countedUntil] = now }
    Unit
  }

  /** Adds the time since the last count; [running] = false ends the count. */
  suspend fun tick(seedName: String, now: Instant = Instant.now(), running: Boolean = true) = db.query {
    val row = SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull() ?: return@query
    val since = row[SessionsTable.countedUntil] ?: return@query
    val added = (now.toEpochMilli() - since.toEpochMilli()).coerceAtLeast(0)
    SessionsTable.update({ SessionsTable.seedName eq seedName }) {
      it[runTimeMs] = row[SessionsTable.runTimeMs] + added
      it[countedUntil] = if (running) now else null
    }
    Unit
  }

  suspend fun stopped(seedName: String, now: Instant = Instant.now()) = tick(seedName, now, running = false)

  /**
   * After the gateway itself ended without counting (killed): what was counted stays, the open stretch is dropped,
   * since nobody knows how long the server really ran after the last count.
   */
  suspend fun dropOpenCounts() = db.query {
    SessionsTable.update({ SessionsTable.countedUntil.isNotNull() }) { it[countedUntil] = null }
    Unit
  }

  suspend fun of(seedName: String): SessionTime? = db.query {
    SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()
      ?.let { SessionTime(it[SessionsTable.runTimeMs], it[SessionsTable.countedUntil]) }
  }
}

/**
 * A session as the admin panel set it up: the Archipelago version, the options (JSON), and which users play which
 * world ([worlds]: slot name -> the names they have in this session, in the order they were given; [members]: user
 * ID -> that name; [deleted]: the members whose account is deleted; [roles]: who else has a role in it).
 */
data class SessionSetup(
  val seedName: String,
  val apVersion: String?,
  val options: String?,
  val createdAt: Instant,
  val worlds: Map<String, List<String>>,
  val members: Map<Uuid, String> = emptyMap(),
  val deleted: Set<Uuid> = emptySet(),
  val roles: Map<Uuid, SessionRoleEntry> = emptyMap(),
)

/** Someone's role in a session besides playing: `manager`, `support` or `watcher`, with their name in it. */
data class SessionRoleEntry(val role: String, val name: String, val deleted: Boolean = false)

/** The sessions the admin panel creates and deletes. */
class SessionSetupStore(private val db: RandogeddonDatabase) {
  /** Records [seedName]'s session as [publicId]; [worlds]: slot name -> the users who play it. */
  suspend fun create(
    publicId: String,
    seedName: String,
    apVersion: String,
    options: String,
    worlds: Map<String, List<Uuid>>,
    now: Instant = Instant.now(),
  ) =
    db.query {
      val id = sessionId(seedName, now, publicId)
      SessionsTable.update({ SessionsTable.id eq id }) {
        it[SessionsTable.publicId] = publicId
        it[SessionsTable.apVersion] = apVersion
        it[SessionsTable.optionsJson] = options
      }
      val names = names(worlds.values.flatten().distinct())
      for ((slot, users) in worlds) {
        for (user in users.distinct()) {
          SessionWorldsTable.insert {
            it[sessionId] = id
            it[slotName] = slot
            it[userId] = user
            it[userName] = names[user]
          }
        }
      }
    }

  /**
   * What [users] are called in a new session: their name, or for an invited user the name the admin suggested (or
   * their address's local part) until they sign up.
   */
  private fun names(users: List<Uuid>): Map<Uuid, String> {
    if (users.isEmpty()) return emptyMap()
    val suggested = InvitationsTable.selectAll().where { InvitationsTable.userId inList users }
      .associate { it[InvitationsTable.userId] to it[InvitationsTable.suggestedName] }
    return UsersTable.selectAll().where { UsersTable.id inList users }.associate { row ->
      val id = row[UsersTable.id]
      id to (row[UsersTable.name] ?: suggested[id] ?: row[UsersTable.email]?.substringBefore('@') ?: id.toString().take(8))
    }
  }

  suspend fun of(seedName: String): SessionSetup? = db.query {
    val row = SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull() ?: return@query null
    val rows = (SessionWorldsTable innerJoin UsersTable).selectAll()
      .where { SessionWorldsTable.sessionId eq row[SessionsTable.id] }.toList()
    // The name of then; rows from before V7 without one: the current name
    fun name(r: ResultRow) = r[SessionWorldsTable.userName] ?: r[UsersTable.name] ?: r[SessionWorldsTable.userId].toString().take(8)
    val worlds = rows.groupBy({ it[SessionWorldsTable.slotName] }, ::name)
    val members = rows.associate { it[SessionWorldsTable.userId] to name(it) }
    val deleted = rows.filter { it[UsersTable.deletedAt] != null }.map { it[SessionWorldsTable.userId] }.toSet()
    val roles = (SessionRolesTable innerJoin UsersTable).selectAll()
      .where { SessionRolesTable.sessionId eq row[SessionsTable.id] }
      .associate {
        val name = it[SessionRolesTable.userName] ?: it[UsersTable.name] ?: it[SessionRolesTable.userId].toString().take(8)
        it[SessionRolesTable.userId] to SessionRoleEntry(it[SessionRolesTable.role], name, it[UsersTable.deletedAt] != null)
      }
    SessionSetup(
      seedName, row[SessionsTable.apVersion], row[SessionsTable.optionsJson], row[SessionsTable.createdAt], worlds, members,
      deleted, roles,
    )
  }

  /** Removes the session with its slot passwords, world assignments and session time; the users stay. */
  suspend fun delete(seedName: String): Boolean = db.query {
    SessionsTable.deleteWhere { SessionsTable.seedName eq seedName } > 0
  }
}

/**
 * The ID a session of [seedName] gets when nothing names it otherwise (a seed from before Phase 4): `s-<seed>`, as
 * migration V6 gives them.
 */
fun legacySessionId(seedName: String): String =
  "s-" + seedName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(38)

/** The id of [seedName]'s session, created as [publicId] if it doesn't exist yet (inside a transaction). */
internal fun sessionId(seedName: String, now: Instant, publicId: String = legacySessionId(seedName)): Uuid =
  SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()?.get(SessionsTable.id)
    ?: Uuid.random().also { id ->
      SessionsTable.insert {
        it[SessionsTable.id] = id
        it[SessionsTable.publicId] = publicId
        it[SessionsTable.seedName] = seedName
        it[createdAt] = now
      }
    }

/** A session by its ID: its seed, when it was created and finished, its Archipelago release. */
data class SessionRecord(
  val id: String,
  val seedName: String,
  val createdAt: Instant,
  val apVersion: String?,
  val finishedAt: Instant?,
)

/** The sessions by their IDs (see `SessionIds` in the shared module). */
class SessionStore(private val db: RandogeddonDatabase) {
  /** Every session, the newest first. */
  suspend fun all(): List<SessionRecord> = db.query {
    SessionsTable.selectAll().orderBy(SessionsTable.createdAt, SortOrder.DESC).map(::record)
  }

  suspend fun byId(id: String): SessionRecord? = db.query {
    SessionsTable.selectAll().where { SessionsTable.publicId eq id }.singleOrNull()?.let(::record)
  }

  /** The ID of [seedName]'s session; `null` if there is none. */
  suspend fun idOf(seedName: String): String? = db.query {
    SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()?.get(SessionsTable.publicId)
  }

  private fun record(row: ResultRow) = SessionRecord(
    row[SessionsTable.publicId], row[SessionsTable.seedName], row[SessionsTable.createdAt], row[SessionsTable.apVersion],
    row[SessionsTable.finishedAt],
  )
}

/** The game passwords of each seed's slots. */
class SlotPasswordStore(private val db: RandogeddonDatabase) {
  /**
   * The passwords of [seedName], creating the session and a password for every slot of [slots] that has none yet.
   * Slots keep their password across restarts; a new seed gets new ones.
   */
  suspend fun ensure(seedName: String, slots: Collection<String>, generate: () -> String, now: Instant = Instant.now()):
    Map<String, String> = db.query {
    val sessionId = sessionId(seedName, now)
    val existing = passwords(sessionId)
    for (slot in slots - existing.keys) {
      SlotPasswordsTable.insert {
        it[SlotPasswordsTable.sessionId] = sessionId
        it[slotName] = slot
        it[password] = generate()
      }
    }
    passwords(sessionId)
  }

  /** Slot name -> password of [seedName]; empty if the seed is unknown. */
  suspend fun passwords(seedName: String): Map<String, String> = db.query {
    SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()
      ?.let { passwords(it[SessionsTable.id]) }.orEmpty()
  }

  private fun passwords(sessionId: Uuid): Map<String, String> =
    SlotPasswordsTable.selectAll().where { SlotPasswordsTable.sessionId eq sessionId }
      .associate { it[SlotPasswordsTable.slotName] to it[SlotPasswordsTable.password] }
}

/**
 * Whether a session is finished ([finishedAt], and by whom: `null` by itself), when it finishes by itself ([dueAt]:
 * every slot is done), whether its auto-deletion is disabled ([keep]), and since when its retention counts
 * ([retainedSince], once that was enabled again; `null`: since it finished).
 */
data class FinishState(
  val finishedAt: Instant?,
  val finishedBy: String?,
  val dueAt: Instant?,
  val keep: Boolean,
  val retainedSince: Instant? = null,
)

data class FinishedSession(
  val seedName: String,
  val createdAt: Instant,
  val finishedAt: Instant,
  val finishedBy: String?,
  val keep: Boolean,
  val retainedSince: Instant? = null,
)

/** Finishing and reopening sessions; the tracker decides when, the gateway only asks whether one is finished. */
class SessionFinishStore(private val db: RandogeddonDatabase) {
  suspend fun of(seedName: String): FinishState? = db.query {
    SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()?.let {
      FinishState(
        it[SessionsTable.finishedAt], it[SessionsTable.finishedBy], it[SessionsTable.finishDueAt], it[SessionsTable.keep],
        it[SessionsTable.retainedSince],
      )
    }
  }

  /** The session finishes by itself at [at]; `null`: not (any more). */
  suspend fun setDue(seedName: String, at: Instant?) = db.query {
    SessionsTable.update({ SessionsTable.seedName eq seedName }) { it[finishDueAt] = at }
    Unit
  }

  suspend fun finish(seedName: String, by: String?, now: Instant = Instant.now()) = db.query {
    SessionsTable.update({ SessionsTable.seedName eq seedName }) {
      it[finishedAt] = now
      it[finishedBy] = by
      it[finishDueAt] = null
    }
    Unit
  }

  suspend fun reopen(seedName: String) = db.query {
    SessionsTable.update({ SessionsTable.seedName eq seedName }) {
      it[finishedAt] = null
      it[finishedBy] = null
      it[finishDueAt] = null
    }
    Unit
  }

  /** [keep]: its auto-deletion is disabled. Enabling it again starts its retention anew, at [now]. */
  suspend fun setKeep(seedName: String, keep: Boolean, now: Instant = Instant.now()): Boolean = db.query {
    SessionsTable.update({ SessionsTable.seedName eq seedName }) {
      it[SessionsTable.keep] = keep
      if (!keep) it[retainedSince] = now
    } > 0
  }

  /** Every finished session, the newest first. */
  suspend fun finished(): List<FinishedSession> = db.query {
    SessionsTable.selectAll().where { SessionsTable.finishedAt.isNotNull() }
      .orderBy(SessionsTable.finishedAt, SortOrder.DESC)
      .map {
        FinishedSession(
          it[SessionsTable.seedName], it[SessionsTable.createdAt], it[SessionsTable.finishedAt]!!,
          it[SessionsTable.finishedBy], it[SessionsTable.keep], it[SessionsTable.retainedSince],
        )
      }
  }
}

/** One cheat of an admin; [value]: the item or location, if the action has one. */
data class CheatEntry(
  val at: Instant,
  val admin: String,
  val action: String,
  val slotName: String,
  val value: String?,
  /** `administrator`, `manager` or `support`. */
  val byRole: String = "administrator",
)

/** Every cheat from the admin panel, per session, with the admin's name at the time. */
class CheatLogStore(private val db: RandogeddonDatabase) {
  suspend fun add(seedName: String, entry: CheatEntry) = db.query {
    val id = sessionId(seedName, entry.at)
    CheatLogTable.insert {
      it[sessionId] = id
      it[at] = entry.at
      it[admin] = entry.admin
      it[byRole] = entry.byRole
      it[action] = entry.action
      it[slotName] = entry.slotName
      it[value] = entry.value?.take(255)
    }
    Unit
  }

  /** The newest first. */
  suspend fun of(seedName: String, limit: Int = 50): List<CheatEntry> = db.query {
    (CheatLogTable innerJoin SessionsTable).selectAll().where { SessionsTable.seedName eq seedName }
      .orderBy(CheatLogTable.at, SortOrder.DESC).limit(limit)
      .map {
        CheatEntry(
          it[CheatLogTable.at], it[CheatLogTable.admin], it[CheatLogTable.action], it[CheatLogTable.slotName],
          it[CheatLogTable.value], it[CheatLogTable.byRole],
        )
      }
  }
}

/** Who has which role in a session besides the players (Session-Managers, support, watchers). */
class SessionRoleStore(private val db: RandogeddonDatabase) {
  /**
   * Gives [userId] [role] in the session of [publicId] ([name]: what it shows of them); `false` if they have a role
   * there already. Whether they may get it is the caller's to check.
   */
  suspend fun add(publicId: String, userId: Uuid, role: String, name: String?, by: String?, now: Instant = Instant.now()): Boolean =
    db.query {
      val session = SessionsTable.selectAll().where { SessionsTable.publicId eq publicId }.singleOrNull()?.get(SessionsTable.id)
        ?: return@query false
      val exists = SessionRolesTable.selectAll()
        .where { (SessionRolesTable.sessionId eq session) and (SessionRolesTable.userId eq userId) }.any()
      if (exists) return@query false
      SessionRolesTable.insert {
        it[sessionId] = session
        it[SessionRolesTable.userId] = userId
        it[SessionRolesTable.role] = role
        it[userName] = name
        it[addedBy] = by
        it[addedAt] = now
      }
      true
    }

  /** Takes [userId]'s role in the session of [publicId] away; `false` if they had none. */
  suspend fun remove(publicId: String, userId: Uuid): Boolean = db.query {
    val session = SessionsTable.selectAll().where { SessionsTable.publicId eq publicId }.singleOrNull()?.get(SessionsTable.id)
      ?: return@query false
    SessionRolesTable.deleteWhere { (sessionId eq session) and (SessionRolesTable.userId eq userId) } > 0
  }

  /** How many sessions that aren't finished [userId] manages (for the per-user limit). */
  suspend fun runningManagedBy(userId: Uuid): Int = db.query {
    (SessionRolesTable innerJoin SessionsTable).selectAll()
      .where { (SessionRolesTable.userId eq userId) and (SessionRolesTable.role eq "manager") and SessionsTable.finishedAt.isNull() }
      .count().toInt()
  }
}

/** What Administrators set for everyone, by key. */
class AppSettingsStore(private val db: RandogeddonDatabase) {
  suspend fun get(key: String): String? = db.query {
    AppSettingsTable.selectAll().where { AppSettingsTable.key eq key }.singleOrNull()?.get(AppSettingsTable.value)
  }

  suspend fun set(key: String, value: String) = db.query {
    val updated = AppSettingsTable.update({ AppSettingsTable.key eq key }) { it[AppSettingsTable.value] = value }
    if (updated == 0) {
      AppSettingsTable.insert {
        it[AppSettingsTable.key] = key
        it[AppSettingsTable.value] = value
      }
    }
    Unit
  }
}

/** A session's share link as it is on: who made it, and when. */
data class ShareLinkInfo(val createdBy: String?, val createdAt: Instant)

/** Share links: a session followed without a login. One per session, stored only as the token's SHA-256. */
class ShareLinkStore(private val db: RandogeddonDatabase) {
  /** A new link for the session of [publicId] (the old one stops working); `false` if there's no such session. */
  suspend fun replace(publicId: String, tokenHash: String, by: String?, now: Instant = Instant.now()): Boolean = db.query {
    val session = sessionOf(publicId) ?: return@query false
    ShareLinksTable.deleteWhere { sessionId eq session }
    ShareLinksTable.insert {
      it[sessionId] = session
      it[ShareLinksTable.tokenHash] = tokenHash
      it[createdBy] = by
      it[createdAt] = now
    }
    true
  }

  /** Turns the session's link off; `false` if it had none. */
  suspend fun remove(publicId: String): Boolean = db.query {
    val session = sessionOf(publicId) ?: return@query false
    ShareLinksTable.deleteWhere { sessionId eq session } > 0
  }

  /** The session's link, if it has one. */
  suspend fun of(publicId: String): ShareLinkInfo? = db.query {
    (ShareLinksTable innerJoin SessionsTable).selectAll().where { SessionsTable.publicId eq publicId }.singleOrNull()
      ?.let { ShareLinkInfo(it[ShareLinksTable.createdBy], it[ShareLinksTable.createdAt]) }
  }

  /** The public ID of the session whose link has [tokenHash]; `null`: no such link (any more). */
  suspend fun sessionWith(tokenHash: String): String? = db.query {
    (ShareLinksTable innerJoin SessionsTable).select(SessionsTable.publicId)
      .where { ShareLinksTable.tokenHash eq tokenHash }.singleOrNull()?.get(SessionsTable.publicId)
  }

  private fun sessionOf(publicId: String): Uuid? =
    SessionsTable.selectAll().where { SessionsTable.publicId eq publicId }.singleOrNull()?.get(SessionsTable.id)
}
