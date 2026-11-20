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
  /** The language's code (`en`, `de`; V12); `null`: their browser's. */
  val language = varchar("language", 8).nullable()
  /** The theme's code and the mode (`light`, `dark`, `auto`; V13); `null`: the default theme, automatic. */
  val theme = varchar("theme", 16).nullable()
  val themeMode = varchar("theme_mode", 8).nullable()
  /** The font (`original`, or a theme's code; V18); `null`: the chosen theme's. */
  val font = varchar("font", 16).nullable()
  /** Where it shows (`all`, `headings`; V19); `null`: as the font is made. */
  val fontScope = varchar("font_scope", 8).nullable()
  /**
   * Kept out by an Administrator (V20): since when, until when (`null`: suspended, until taken back), why and by
   * whom. A time-out that ran out counts as none.
   */
  val blockedAt = timestamp("blocked_at").nullable()
  val blockedUntil = timestamp("blocked_until").nullable()
  val blockedReason = varchar("blocked_reason", 200).nullable()
  val blockedBy = varchar("blocked_by", 64).nullable()
  /** How many running sessions of their own they may have at once (V21); `null`: the default (a setting). */
  val sessionLimit = integer("session_limit").nullable()
  val emailVerifiedByProvider = bool("email_verified_by_provider").nullable()
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
  /** `password`, `oidc` or `link` (V11), `bridge` (V14: a key of Randogeddon Bridge). */
  val method = varchar("method", 16).default("password")
  /** When a bridge key was last used (V14; updated every few minutes at most); `null`: not yet, or not a key. */
  val lastUsedAt = timestamp("last_used_at").nullable()
  /** A login through the provider: its session there (V17, the ID token's `sid`); its back-channel logout ends it. */
  val providerSession = varchar("provider_session", 128).nullable()
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
  /** Its game server keeps running without players (an Administrator's choice, V23); else it sleeps when idle. */
  val keepRunning = bool("keep_running").default(false)
  /** Who created it (V21): it counts towards their limit while it runs. `null`: nobody known (a seed from the CLI). */
  val createdBy = uuid("created_by").references(UsersTable.id).nullable()
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

/** A session's hosted slots (V15): games that run as a server of their own; no account plays them. */
object SessionHostedSlotsTable : Table("session_hosted_slots") {
  val sessionId = uuid("session_id") references SessionsTable.id
  val slotName = varchar("slot_name", 64)
  override val primaryKey = PrimaryKey(sessionId, slotName)
}

object SlotPasswordsTable : Table("slot_passwords") {
  val sessionId = uuid("session_id") references SessionsTable.id
  val slotName = varchar("slot_name", 64)
  val password = varchar("password", 64)
  /** Still locked (V16): its key isn't found yet, so the gateway refuses its Connect. */
  val locked = bool("locked").default(false)
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

/**
 * What happens on a session's game server, as the gateway hears it (V24): [kind] `packet` with the Archipelago
 * packet as JSON, or `start` and `stop` of the game server. In their order ([id]); by the session's public ID.
 */
object SessionEventsTable : Table("session_events") {
  val id = long("id").autoIncrement()
  val session = varchar("session", 40)
  val atMs = long("at_ms")
  val kind = varchar("kind", 8)
  val packet = text("packet")
  override val primaryKey = PrimaryKey(id)
}

/**
 * What a session's dashboard shows, kept by the tracker (V25): while it runs the last it knew, once it finished its
 * archive ([final]); and once the tracker's history file from before ([baseline]). By the session's public ID.
 */
object SessionSnapshotsTable : Table("session_snapshots") {
  val session = varchar("session", 40)
  val atMs = long("at_ms")
  val final = bool("final").default(false)
  val snapshot = text("snapshot").nullable()
  val baseline = text("baseline").nullable()
  override val primaryKey = PrimaryKey(session)
}
