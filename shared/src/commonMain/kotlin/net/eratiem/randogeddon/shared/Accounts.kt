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

  /** Names that would pass for Randogeddon itself or for someone with a say here: no one may be named so (any case). */
  private val RESERVED = setOf(
    "hosted", "admin", "admins", "administrator", "administrators", "moderator", "moderators", "mod", "mods", "support",
    "supporter", "system", "root", "staff", "owner", "official", "randogeddon", "archipelago", "server", "everyone",
    "anonymous", "guest", "bot", "null", "undefined",
  )

  /** A name's characters: letters without accents, digits and the underscore. */
  fun allowed(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'

  fun valid(name: String): Boolean = problem(name) == null

  /** What's wrong with [name]; `null`: nothing (except maybe being taken). */
  fun problem(name: String): Refusal? = when {
    name.length < MIN_LENGTH -> Problem.NAME_TOO_SHORT
    name.length > MAX_LENGTH -> Problem.NAME_TOO_LONG
    !name.all(::allowed) -> Problem.NAME_CHARACTERS
    name.lowercase() in RESERVED -> Problem.NAME_RESERVED
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
  /** The font they chose ([FontChoice]); `null`: the theme's. And where it shows. */
  val font: String? = null,
  val fontScope: FontScope = FontScope.AUTO,
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

/** `PUT /api/account/font`: [font] as [FontChoice] has it (`null`: the theme's), and where it shows. */
@Serializable
data class FontRequest(val font: String? = null, val scope: FontScope = FontScope.AUTO)

/** `PUT /api/account/auto-delete`. */
@Serializable
data class AutoDeleteRequest(val autoDelete: AutoDelete)
