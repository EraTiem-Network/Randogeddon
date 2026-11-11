package net.eratiem.randogeddon.database

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.uuid.Uuid

data class User(val id: Uuid, val name: String, val passwordHash: String, val admin: Boolean = false)

/** The accounts. Names are compared without regard to case, like the unique index in the schema. */
class UserStore(private val db: RandogeddonDatabase) {
  suspend fun active(): List<User> = db.query {
    UsersTable.selectAll().where { UsersTable.deletedAt.isNull() }.map(::user)
  }

  suspend fun findActive(name: String): User? = db.query {
    UsersTable.selectAll()
      .where { (UsersTable.name.lowerCase() eq name.lowercase()) and UsersTable.deletedAt.isNull() }
      .singleOrNull()?.let(::user)
  }

  suspend fun findActive(id: Uuid): User? = db.query {
    UsersTable.selectAll().where { (UsersTable.id eq id) and UsersTable.deletedAt.isNull() }.singleOrNull()?.let(::user)
  }

  suspend fun create(name: String, passwordHash: String, now: Instant = Instant.now()): User = db.query {
    val id = Uuid.random()
    UsersTable.insert {
      it[UsersTable.id] = id
      it[UsersTable.name] = name
      it[UsersTable.passwordHash] = passwordHash
      it[createdAt] = now
    }
    User(id, name, passwordHash)
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

  private fun user(row: ResultRow) =
    User(row[UsersTable.id], row[UsersTable.name], row[UsersTable.passwordHash], row[UsersTable.isAdmin])
}

/** Logged-in browsers, by the SHA-256 of their cookie token. */
class LoginSessionStore(private val db: RandogeddonDatabase) {
  suspend fun create(tokenHash: String, userId: Uuid, expiresAt: Instant, now: Instant = Instant.now()) = db.query {
    LoginSessionsTable.insert {
      it[LoginSessionsTable.tokenHash] = tokenHash
      it[LoginSessionsTable.userId] = userId
      it[createdAt] = now
      it[LoginSessionsTable.expiresAt] = expiresAt
    }
    Unit
  }

  /** The user of a session that hasn't expired yet. */
  suspend fun userOf(tokenHash: String, now: Instant = Instant.now()): Uuid? = db.query {
    LoginSessionsTable.selectAll()
      .where { (LoginSessionsTable.tokenHash eq tokenHash) and (LoginSessionsTable.expiresAt greater now) }
      .singleOrNull()?.get(LoginSessionsTable.userId)
  }

  suspend fun delete(tokenHash: String) = db.query {
    LoginSessionsTable.deleteWhere { LoginSessionsTable.tokenHash eq tokenHash }
    Unit
  }

  suspend fun deleteExpired(now: Instant = Instant.now()): Int = db.query {
    LoginSessionsTable.deleteWhere { expiresAt less now }
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

/** The id of [seedName]'s session, created if it doesn't exist yet (inside a transaction). */
internal fun sessionId(seedName: String, now: Instant): Uuid =
  SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()?.get(SessionsTable.id)
    ?: Uuid.random().also { id ->
      SessionsTable.insert {
        it[SessionsTable.id] = id
        it[SessionsTable.seedName] = seedName
        it[createdAt] = now
      }
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
