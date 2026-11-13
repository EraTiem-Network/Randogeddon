package net.eratiem.randogeddon.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

// The tables of db/migration; Flyway creates them, Exposed only reads and writes

object UsersTable : Table("users") {
  val id = uuid("id")
  /** `null` while invited (V7). */
  val name = varchar("name", 64).nullable()
  /** `null` while invited, and once deleted. */
  val passwordHash = varchar("password_hash", 255).nullable()
  val createdAt = timestamp("created_at")
  val deletedAt = timestamp("deleted_at").nullable()
  val isAdmin = bool("is_admin").default(false)
  val email = varchar("email", 254).nullable()
  /** When they chose their name and password; `null` while invited. */
  val signedUpAt = timestamp("signed_up_at").nullable()
  /** The shared AutoDelete's code. */
  val autoDelete = varchar("auto_delete", 8).default("never")
  val deleteWarnedAt = timestamp("delete_warned_at").nullable()
  override val primaryKey = PrimaryKey(id)
}

/** Invitation links, by the SHA-256 of their token; a new one replaces the user's older ones. */
object InvitationsTable : Table("invitations") {
  val tokenHash = varchar("token_hash", 64)
  val userId = uuid("user_id") references UsersTable.id
  val email = varchar("email", 254)
  val suggestedName = varchar("suggested_name", 64).nullable()
  val createdAt = timestamp("created_at")
  val expiresAt = timestamp("expires_at")
  val usedAt = timestamp("used_at").nullable()
  override val primaryKey = PrimaryKey(tokenHash)
}

/** New e-mail addresses waiting for their confirmation. */
object EmailChangesTable : Table("email_changes") {
  val tokenHash = varchar("token_hash", 64)
  val userId = uuid("user_id") references UsersTable.id
  val email = varchar("email", 254)
  val createdAt = timestamp("created_at")
  val expiresAt = timestamp("expires_at")
  override val primaryKey = PrimaryKey(tokenHash)
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
  /** The session's ID: its address and its name in links (V6); unique. */
  val publicId = varchar("public_id", 40)
  val seedName = varchar("seed_name", 64)
  val createdAt = timestamp("created_at")
  val runTimeMs = long("run_time_ms").default(0)
  val countedUntil = timestamp("counted_until").nullable()
  val apVersion = varchar("ap_version", 32).nullable()
  /** JSON of the shared SessionOptions; `null` for seeds deployed with the CLI. */
  val optionsJson = text("options").nullable()
  val finishedAt = timestamp("finished_at").nullable()
  /** Who finished it by hand; `null`: by itself (or not finished). */
  val finishedBy = varchar("finished_by", 64).nullable()
  val finishDueAt = timestamp("finish_due_at").nullable()
  /** Its auto-deletion is disabled (an admin's choice). */
  val keep = bool("keep").default(false)
  /** Its auto-deletion was enabled again then: the retention counts from there (`null`: from [finishedAt]). */
  val retainedSince = timestamp("retained_since").nullable()
  override val primaryKey = PrimaryKey(id)
}

/** Who plays which world of a session; a world with several users is played together. */
object SessionWorldsTable : Table("session_worlds") {
  val sessionId = uuid("session_id") references SessionsTable.id
  val slotName = varchar("slot_name", 64)
  val userId = uuid("user_id") references UsersTable.id
  /** The name the user had in the session (V7): follows a rename while it runs, stays once it is finished. */
  val userName = varchar("user_name", 64).nullable()
  override val primaryKey = PrimaryKey(sessionId, slotName, userId)
}

object SlotPasswordsTable : Table("slot_passwords") {
  val sessionId = uuid("session_id") references SessionsTable.id
  val slotName = varchar("slot_name", 64)
  val password = varchar("password", 64)
  override val primaryKey = PrimaryKey(sessionId, slotName)
}

/** The cheats admins ran from the admin panel, per session. */
object CheatLogTable : Table("cheat_log") {
  val id = long("id").autoIncrement()
  val sessionId = uuid("session_id") references SessionsTable.id
  val at = timestamp("at")
  val admin = varchar("admin", 64)
  val action = varchar("action", 32)
  val slotName = varchar("slot_name", 64)
  val value = varchar("value", 255).nullable()
  override val primaryKey = PrimaryKey(id)
}
