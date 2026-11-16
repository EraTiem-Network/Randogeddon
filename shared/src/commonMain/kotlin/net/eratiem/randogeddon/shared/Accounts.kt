package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.eratiem.randogeddon.shared.i18n.Language

/**
 * A user's name (the display name, and what they log in with besides their e-mail address): 3 to 32 characters, no
 * `@` (so it can't be taken for an address at the login), no control characters, not starting or ending with a
 * space. Unique among active accounts without regard to case; the server checks that.
 */
object UserNames {
  const val MIN_LENGTH = 3
  const val MAX_LENGTH = 32

  /** A hosted slot's "player" (no account): no one may be named so, in any case. */
  const val HOSTED = "Hosted"

  fun valid(name: String): Boolean = problem(name) == null

  /** What's wrong with [name]; `null`: nothing (except maybe being taken). */
  fun problem(name: String): Refusal? = when {
    name.length < MIN_LENGTH -> Problem.NAME_TOO_SHORT
    name.length > MAX_LENGTH -> Problem.NAME_TOO_LONG
    name != name.trim() -> Problem.NAME_SPACES
    '@' in name -> Problem.NAME_AT
    name.any { it.isISOControl() } -> Problem.NAME_CONTROL
    name.equals(HOSTED, ignoreCase = true) -> Problem.NAME_RESERVED
    else -> null
  }?.let { Refusal(it) }
}

/** What a password needs: length; a password manager makes the rest. */
object PasswordRules {
  const val MIN_LENGTH = 12
  const val MAX_LENGTH = 256

  /** What's wrong with [password]; `null`: nothing. */
  fun problem(password: String): Refusal? = when {
    password.length < MIN_LENGTH -> Refusal(Problem.PASSWORD_TOO_SHORT)
    password.length > MAX_LENGTH -> Refusal(Problem.PASSWORD_TOO_LONG)
    else -> null
  }
}

/** A rough check of an e-mail address; whether it works shows when the invitation arrives. */
object EmailAddresses {
  const val MAX_LENGTH = 254
  private val ADDRESS = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s.]+")

  fun valid(address: String): Boolean = address.length <= MAX_LENGTH && ADDRESS.matches(address)

  /** Compared and stored like this: without spaces around, the domain in lower case. */
  fun normalize(address: String): String = address.trim().let { a ->
    val at = a.lastIndexOf('@')
    if (at < 0) a else a.substring(0, at) + a.substring(at).lowercase()
  }
}

/**
 * When an account is deleted by itself: this long after the end of the user's last session (or the sign-up, without
 * one), or never. The user is told 14 days before.
 */
@Serializable
enum class AutoDelete(val days: Int?, val label: String) {
  @SerialName("2w") WEEKS_2(14, "2 weeks"),
  @SerialName("4w") WEEKS_4(28, "4 weeks"),
  @SerialName("3m") MONTHS_3(91, "3 months"),
  @SerialName("6m") MONTHS_6(182, "6 months"),
  @SerialName("1y") YEAR_1(365, "1 year"),
  @SerialName("never") NEVER(null, "Never"),
  ;

  /** How it is stored. */
  val code: String get() = when (this) {
    WEEKS_2 -> "2w"
    WEEKS_4 -> "4w"
    MONTHS_3 -> "3m"
    MONTHS_6 -> "6m"
    YEAR_1 -> "1y"
    NEVER -> "never"
  }

  companion object {
    fun of(code: String?): AutoDelete = entries.firstOrNull { it.code == code } ?: NEVER
  }
}

/** A user as a choice in a form: their ID, and their name (`null` while invited and not signed up). */
@Serializable
data class UserRef(
  val id: String,
  val name: String? = null,
  /** For an invited user without a name yet. */
  val email: String? = null,
) {
  /** What a list shows. */
  val label: String get() = name ?: email ?: id
}

/** `GET /api/invitations/{token}`: whether the link still works, and the name the admin suggested. */
@Serializable
data class InvitationInfo(
  val valid: Boolean,
  val email: String? = null,
  val suggestedName: String? = null,
  /** Why it doesn't work (used, expired, replaced by a newer one), in the request's language, and as a code. */
  val problem: String? = null,
  val code: Problem? = null,
)

/** `POST /api/signup`: the invitation's token, and what the new user chose. Logs them in. */
@Serializable
data class SignupRequest(
  val token: String,
  val name: String,
  val password: String,
  val autoDelete: AutoDelete = AutoDelete.NEVER,
)

/** `POST /api/email/confirm`: the token from the confirmation mail to a new address. */
@Serializable
data class TokenRequest(val token: String)

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
  val autoDelete: AutoDelete = AutoDelete.NEVER,
  /** When the account will be deleted by itself, as of now; `null`: not planned. */
  val deletesAt: Long? = null,
  /** How they log in: a password of their own, through the identity provider (single sign-on), a second factor. */
  val password: Boolean = false,
  val singleSignOn: Boolean = false,
  val twoFactor: Boolean = false,
  /** The language they chose (its code); `null`: their browser's. */
  val language: String? = null,
)

/** `POST /api/admin/users`: invite someone; [suggestedName] is prefilled on their sign-up page. */
@Serializable
data class InviteRequest(val email: String, val suggestedName: String? = null)

/** `PUT /api/admin/users/{id}/email`: another address for an invited user (a new link goes there). */
@Serializable
data class EmailRequest(val email: String)

/** `GET /api/account`: the logged-in user's own account, for the settings page. */
@Serializable
data class AccountView(
  val id: String,
  val name: String,
  val email: String? = null,
  /** A new address waiting for its confirmation (the link went there). */
  val pendingEmail: String? = null,
  val autoDelete: AutoDelete = AutoDelete.NEVER,
  /** When the account will be deleted by itself, as of now; `null`: not planned (never, or a session runs). */
  val deletesAt: Long? = null,
  val administrator: Boolean = false,
  /** Logs in with a code from an authenticator after the password (Administrators always, without single sign-on). */
  val twoFactor: Boolean = false,
  /** How many recovery codes are left. */
  val recoveryCodesLeft: Int = 0,
  /**
   * For Administrators with single sign-on: the provider they log in with (its own two-factor check replaces this
   * app's, and their account here goes when it deletes them, not by itself); `null` otherwise.
   */
  val singleSignOn: String? = null,
  /** With [singleSignOn]: the provider's page where they change their name, password, address, second factor. */
  val singleSignOnAccount: String? = null,
  /** The language they chose; `null`: their browser's. */
  val language: Language? = null,
  /** The theme and mode they chose; `null`: the default theme, automatic. */
  val theme: Theme? = null,
  val themeMode: ThemeMode? = null,
)

/** `PUT /api/account/name`. */
@Serializable
data class NameRequest(val name: String)

/** `PUT /api/account/password`: the current password, and the new one. The other logins end. */
@Serializable
data class PasswordChangeRequest(val current: String, val password: String)

/** `PUT /api/account/language`: `null` follows the browser. */
@Serializable
data class LanguageRequest(val language: Language? = null)

/** `PUT /api/account/theme`: `null` for the default theme, automatic. */
@Serializable
data class ThemeRequest(val theme: Theme? = null, val mode: ThemeMode? = null)

/** `PUT /api/account/auto-delete`. */
@Serializable
data class AutoDeleteRequest(val autoDelete: AutoDelete)

/** What comes after the password at the login. */
@Serializable
enum class LoginNext {
  /** A code from the authenticator (or a recovery code). */
  @SerialName("code") CODE,
  /** An admin without two-factor login sets it up first. */
  @SerialName("setup") SETUP,
}

/**
 * `POST /api/login` with the right password of someone with two-factor login (or an admin who has to set it up): no
 * cookie yet, but [pending], valid for a few minutes, for the next step.
 */
@Serializable
data class LoginStep(val next: LoginNext, val pending: String)

/** `POST /api/login/code` (with [pending]), or a code that confirms or changes the user's own two-factor login. */
@Serializable
data class CodeRequest(val code: String, val pending: String? = null)

/**
 * `GET /api/login/options`, before the login: [singleSignOn] names the provider Administrators log in through (the
 * login form's button, to `GET /api/login/oidc`); `null`: none.
 */
@Serializable
data class LoginOptions(val singleSignOn: String? = null)

/** `POST /api/login/link`: a one-time login link of the host's CLI (`randogeddon admins --login`). */
@Serializable
data class LinkLoginRequest(val token: String)

/** `POST /api/login/setup`: the pending login of an admin who sets up two-factor login now. */
@Serializable
data class PendingRequest(val pending: String)

/**
 * A new TOTP secret to add to an authenticator app: as a QR code ([svg], or [rows] of `0`/`1` to draw), as the
 * `otpauth://` link it encodes, and as [secret] to type in by hand. It counts once a code from it confirms it.
 */
@Serializable
data class TotpSetup(val secret: String, val uri: String, val svg: String, val rows: List<String> = emptyList())

/** The recovery codes, shown only this once. */
@Serializable
data class RecoveryCodeList(val codes: List<String>)

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

/**
 * `GET /api/sessions/new`: what a new session can use: the releases, a free ID, the accounts to pick by name, and how
 * many running sessions the user may still create ([left]; `null`: no limit, an Administrator).
 */
@Serializable
data class NewSessionView(
  val apVersions: List<String> = emptyList(),
  val suggestedId: String = "",
  val people: List<UserRef> = emptyList(),
  val left: Int? = null,
  /** The game servers that can run at once, and how many do. */
  val capacity: Int = 0,
  val running: Int = 0,
  /** The session IDs taken already. */
  val taken: List<String> = emptyList(),
)

/** `GET/PUT /api/admin/settings` (Administrators): what applies to everyone. */
@Serializable
data class AppSettings(
  /** How many running sessions one user (not an Administrator) may manage at once. */
  val sessionsPerUser: Int = 1,
)
