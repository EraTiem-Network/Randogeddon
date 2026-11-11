package net.eratiem.randogeddon.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

// The tables of db/migration; Flyway creates them, Exposed only reads and writes

object UsersTable : Table("users") {
  val id = uuid("id")
  val name = varchar("name", 64)
  val passwordHash = varchar("password_hash", 255)
  val createdAt = timestamp("created_at")
  val deletedAt = timestamp("deleted_at").nullable()
  override val primaryKey = PrimaryKey(id)
}

object LoginSessionsTable : Table("login_sessions") {
  val tokenHash = varchar("token_hash", 64)
  val userId = uuid("user_id") references UsersTable.id
  val createdAt = timestamp("created_at")
  val expiresAt = timestamp("expires_at")
  override val primaryKey = PrimaryKey(tokenHash)
}

object SessionsTable : Table("sessions") {
  val id = uuid("id")
  val seedName = varchar("seed_name", 64)
  val createdAt = timestamp("created_at")
  override val primaryKey = PrimaryKey(id)
}

object SlotPasswordsTable : Table("slot_passwords") {
  val sessionId = uuid("session_id") references SessionsTable.id
  val slotName = varchar("slot_name", 64)
  val password = varchar("password", 64)
  override val primaryKey = PrimaryKey(sessionId, slotName)
}
