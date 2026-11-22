package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How far an account is. */
@Serializable
enum class AccountState {
  /** Invited by e-mail, not signed up yet. */
  @SerialName("invited") INVITED,
  @SerialName("active") ACTIVE,
}

/** `GET /api/admin/users`: one account as the admin panel lists it. */
@Serializable
data class AdminUser(
  val id: String,
  /** `null` while invited. */
  val name: String? = null,
  val email: String? = null,
  val state: AccountState = AccountState.ACTIVE,
  val administrator: Boolean = false,
  /** The IDs of the sessions they play in. */
  val sessions: List<String> = emptyList(),
  val createdAt: Long = 0,
  /** When the invitation link stops working (only while invited). */
  val invitationExpiresAt: Long? = null,
  /** An Administrator keeps them out for now (timed out or suspended); `null`: they can log in. */
  val block: AccountBlock? = null,
  /** How many running sessions of their own they may have at once; `null`: the default ([AppSettings]). */
  val sessionLimit: Int? = null,
  /** How many running sessions they created (what the limit counts). */
  val sessionsRunning: Int = 0,
  val autoDelete: AutoDelete = AutoDelete.NEVER,
  /** When the account will be deleted by itself, as of now; `null`: not planned. */
  val deletesAt: Long? = null,
  /** How they log in: a password of their own, through the identity provider (single sign-on), a second factor. */
  val password: Boolean = false,
  val singleSignOn: Boolean = false,
  /** The provider they log in through, by its name (e.g. Authentik); `null` without [singleSignOn]. */
  val provider: String? = null,
  /** Whether [provider] calls their [email] verified; `null`: not its address, or it didn't say. */
  val emailVerified: Boolean? = null,
  val twoFactor: Boolean = false,
  /** The language they chose (its code); `null`: their browser's. */
  val language: String? = null,
)

/**
 * An account an Administrator keeps out: since when, [until] when (`null`: suspended, until it is taken back), why
 * (what the Administrator wrote; the person reads it too) and who did it. While it lasts they can't log in and their
 * logins are ended; their sessions go on.
 */
@Serializable
data class AccountBlock(val since: Long, val until: Long? = null, val reason: String? = null, val by: String? = null)

/** How long a time-out lasts. */
@Serializable
enum class TimeOut(val hours: Int) {
  @SerialName("1h") HOUR(1),
  @SerialName("3h") HOURS_3(3),
  @SerialName("6h") HOURS_6(6),
  @SerialName("12h") HOURS_12(12),
  @SerialName("1d") DAY(24),
  @SerialName("2d") DAYS_2(24 * 2),
  @SerialName("3d") DAYS_3(24 * 3),
  @SerialName("7d") WEEK(24 * 7),
  @SerialName("14d") WEEKS_2(24 * 14),
  @SerialName("30d") MONTH(24 * 30),
}

/**
 * `POST /api/admin/users/{id}/block`: time the account out for [timeOut], or suspend it (`null`: until taken back),
 * with a [reason] the person is told (optional). `POST …/delete` takes the same, for its [reason].
 */
@Serializable
data class AccountActionRequest(val timeOut: TimeOut? = null, val reason: String? = null) {
  companion object {
    const val MAX_REASON = 200
  }
}

/** `PUT /api/admin/users/{id}/session-limit`: the account's own limit of running sessions; `null`: the default. */
@Serializable
data class SessionLimitRequest(val limit: Int? = null)

/** `POST /api/admin/users`: invite someone; [suggestedName] is prefilled on their sign-up page. */
@Serializable
data class InviteRequest(val email: String, val suggestedName: String? = null)

/** `GET/PUT /api/admin/settings` (Administrators): what applies to everyone. */
@Serializable
data class AppSettings(
  /** How many running sessions one user (not an Administrator) may manage at once. */
  val sessionsPerUser: Int = 1,
  /** How many game servers may run at once, of every session; 0: no limit. */
  val gameServers: Int = 8,
  /** How many minutes a game server runs without a player before it sleeps; 0: never. */
  val idleMinutes: Int = 30,
  /** How many live streams by share links one address may hold at once; 0: no limit. */
  val watchStreams: Int = 20,
)
