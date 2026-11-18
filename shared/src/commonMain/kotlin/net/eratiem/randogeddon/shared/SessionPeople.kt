package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Someone's part in a session: a [PLAYER] has slots; a [MANAGER] (Session-Manager) manages it (the creator, and the
 * co-managers they add; they can also play); [SUPPORT] helps its players; a [WATCHER] follows it. Administrators
 * manage every session without a role in it.
 */
@Serializable
enum class SessionRole {
  @SerialName("player") PLAYER,
  @SerialName("manager") MANAGER,
  @SerialName("support") SUPPORT,
  @SerialName("watcher") WATCHER,
  ;

  /** How it is stored (`session_roles.role`; players aren't stored there). */
  val code: String get() = name.lowercase()

  companion object {
    fun of(code: String): SessionRole? = entries.firstOrNull { it.code == code }
  }
}

/** One person of a session: their account, name in it, and role (a Session-Manager who plays is listed once, as manager). */
@Serializable
data class SessionPerson(
  val id: String,
  val name: String,
  val role: SessionRole,
  /** Their slots (players and playing Session-Managers). */
  val slots: List<String> = emptyList(),
  val deleted: Boolean = false,
)

/**
 * `GET /api/sessions/{id}/people`: everyone with a part in the session. [you]: the asking user's ID, and whether they
 * may take their own part as Session-Manager ([mayLeave]: only an Administrator; another one or an Administrator can).
 */
@Serializable
data class SessionPeople(val people: List<SessionPerson> = emptyList(), val you: String? = null, val mayLeave: Boolean = false)

/**
 * `PUT /api/sessions/{id}/people/{user}` (Session-Managers): gives someone of the session another part: a player, or
 * someone with a part, becomes [role] (not [SessionRole.PLAYER]: a player keeps their slots either way).
 */
@Serializable
data class RoleRequest(val role: SessionRole)

/**
 * `POST /api/sessions/{id}/people` (Session-Managers): gives someone [role] (not [SessionRole.PLAYER]: players come with
 * the slots), by account ([userId]), or by [email] (an invitation, only for an address without an account).
 */
@Serializable
data class AddPersonRequest(
  val role: SessionRole,
  val userId: String? = null,
  val email: String? = null,
  val suggestedName: String? = null,
)

/**
 * `POST /api/sessions/{id}/watchers` (players and Session-Managers): a watcher by account ([userId]), or by [email] (an
 * invitation, only for an address without an account).
 */
@Serializable
data class WatcherRequest(
  val userId: String? = null,
  val email: String? = null,
  val suggestedName: String? = null,
)

/**
 * `GET/POST /api/sessions/{id}/share`: the session's share link, to follow it without a login (read-only). The link's
 * [token] is only in the answer that made it (`POST`); after that only whether one is on, by whom and since when.
 */
@Serializable
data class ShareLinkView(
  val on: Boolean = false,
  val createdBy: String? = null,
  /** Epoch milliseconds. */
  val createdAt: Long? = null,
  val token: String? = null,
)
