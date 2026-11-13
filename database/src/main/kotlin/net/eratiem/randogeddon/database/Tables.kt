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
  /** The TOTP secret, encrypted (V8); set while it is being set up, counts once [totpEnabledAt] is. */
  val totpSecret = varchar("totp_secret", 255).nullable()
  val totpEnabledAt = timestamp("totp_enabled_at").nullable()
  /** The newest 30-second step a code was accepted for: no code works twice. */
  val totpLastStep = long("totp_last_step").nullable()
  /** The Authentik user (the ID token's `sub`) this account is linked to (V11). */
  val oidcSubject = varchar("oidc_subject", 255).nullable()
  override val primaryKey = PrimaryKey(id)
}

/** One-time codes for a lost authenticator, by their SHA-256. */
object RecoveryCodesTable : Table("recovery_codes") {
  val userId = uuid("user_id") references UsersTable.id
  val codeHash = varchar("code_hash", 64)
  val usedAt = timestamp("used_at").nullable()
  override val primaryKey = PrimaryKey(userId, codeHash)
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
  /** `password`, `oidc` or `link` (V11). */
  val method = varchar("method", 16).default("password")
  override val primaryKey = PrimaryKey(tokenHash)
}

/** One-time login links of the host's CLI (V11), by the SHA-256 of their token. */
object LoginLinksTable : Table("login_links") {
  val tokenHash = varchar("token_hash", 64)
  val userId = uuid("user_id") references UsersTable.id
  val createdAt = timestamp("created_at")
  val expiresAt = timestamp("expires_at")
  val usedAt = timestamp("used_at").nullable()
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

/** Per session besides the players: its Session-Managers, support and watchers (V9); one role per person. */
object SessionRolesTable : Table("session_roles") {
  val sessionId = uuid("session_id") references SessionsTable.id
  val userId = uuid("user_id") references UsersTable.id
  /** `manager`, `support` or `watcher`. */
  val role = varchar("role", 16)
  /** The name of then, like [SessionWorldsTable.userName]. */
  val userName = varchar("user_name", 64).nullable()
  val addedBy = varchar("added_by", 64).nullable()
  val addedAt = timestamp("added_at")
  override val primaryKey = PrimaryKey(sessionId, userId)
}

/** A session's share link (V10): only the token's SHA-256; one per session. */
object ShareLinksTable : Table("share_links") {
  val sessionId = uuid("session_id") references SessionsTable.id
  val tokenHash = varchar("token_hash", 64)
  val createdBy = varchar("created_by", 64).nullable()
  val createdAt = timestamp("created_at")
  override val primaryKey = PrimaryKey(sessionId)
}

/** What Administrators set for everyone (V9). */
object AppSettingsTable : Table("app_settings") {
  val key = varchar("key", 64)
  val value = text("value")
  override val primaryKey = PrimaryKey(key)
}

/** The cheats admins ran from the admin panel, per session. */
object CheatLogTable : Table("cheat_log") {
  val id = long("id").autoIncrement()
  val sessionId = uuid("session_id") references SessionsTable.id
  val at = timestamp("at")
  /** Who ran it (the column's name is from before the roles). */
  val admin = varchar("admin", 64)
  /** As what: `administrator`, `manager` or `support` (V9). */
  val byRole = varchar("by_role", 16).default("administrator")
  val action = varchar("action", 32)
  val slotName = varchar("slot_name", 64)
  val value = varchar("value", 255).nullable()
  override val primaryKey = PrimaryKey(id)
}
