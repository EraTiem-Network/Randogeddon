package net.eratiem.randogeddon.database

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.uuid.Uuid

data class User(val id: Uuid, val name: String, val passwordHash: String)

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

  /** Sets a new password and ends every login of the user. */
  suspend fun setPassword(id: Uuid, passwordHash: String) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.passwordHash] = passwordHash }
    LoginSessionsTable.deleteWhere { userId eq id }
  }

  private fun user(row: ResultRow) = User(row[UsersTable.id], row[UsersTable.name], row[UsersTable.passwordHash])
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

/** The game passwords of each seed's slots. */
class SlotPasswordStore(private val db: RandogeddonDatabase) {
  /**
   * The passwords of [seedName], creating the session and a password for every slot of [slots] that has none yet.
   * Slots keep their password across restarts; a new seed gets new ones.
   */
  suspend fun ensure(seedName: String, slots: Collection<String>, generate: () -> String, now: Instant = Instant.now()):
    Map<String, String> = db.query {
    val sessionId = SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()
      ?.get(SessionsTable.id)
      ?: Uuid.random().also { id ->
        SessionsTable.insert {
          it[SessionsTable.id] = id
          it[SessionsTable.seedName] = seedName
          it[createdAt] = now
        }
      }
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
