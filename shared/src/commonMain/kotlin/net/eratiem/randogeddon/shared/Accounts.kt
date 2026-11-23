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

  /** The "player" of the slots open to all of a session's players (no account; the name is reserved). */
  const val EVERYONE = "Everyone"

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

/**
 * What a new password needs: its length, and with the account's [name][problem] or address given, that it isn't an
 * obvious one ([obvious]). No character types are asked for: a long phrase of unrelated words, or a password manager's,
 * passes. Only new passwords are checked, never the ones accounts have.
 */
object PasswordRules {
  const val MIN_LENGTH = 12
  const val MAX_LENGTH = 256

  /**
   * What's wrong with [password]; `null`: nothing. Without [name] and [email] only its length (the pages' own check);
   * with either, where a password is set (the sign-up, the settings, a reset), also [obvious] for that account.
   */
  fun problem(password: String, name: String? = null, email: String? = null): Refusal? = when {
    password.length < MIN_LENGTH -> Refusal(Problem.PASSWORD_TOO_SHORT)
    password.length > MAX_LENGTH -> Refusal(Problem.PASSWORD_TOO_LONG)
    name == null && email == null -> null
    else -> obvious(password, name, email)
  }

  /** The shortest part of a name or address a password must not contain: shorter ones are in many words. */
  const val MIN_OWN_PART = 4

  /** The longest unit a password may not be made of, repeated throughout (`abcabcabcabc`). */
  const val MAX_REPEATED_UNIT = 4

  /**
   * Why [password] is obvious for the account [name] with [email], compared without regard to case; `null`: it isn't.
   * - [Problem.PASSWORD_HAS_NAME]: it contains the name, or the address's part before the `@`, of at least
   *   [MIN_OWN_PART] characters.
   * - [Problem.PASSWORD_TOO_SIMPLE]: it is one unit of up to [MAX_REPEATED_UNIT] characters repeated throughout; or a
   *   run throughout, ascending or descending, of digits, of letters, or along a keyboard (QWERTY or QWERTZ, its rows
   *   one after the other), also past the end round to the start; or one of [COMMON_WORDS] (or itself again), with
   *   nothing but digits, symbols and spaces around and between, read after undoing the usual swaps (`@` a, `0` o,
   *   `1` i or l, `3` e, `$` s). A common word among other words (`my-password-is-very-long-indeed`) passes.
   */
  fun obvious(password: String, name: String?, email: String?): Refusal? {
    val lower = password.lowercase()
    val own = listOfNotNull(name?.trim(), email?.trim()?.substringBeforeLast('@'))
      .map { it.lowercase() }.filter { it.length >= MIN_OWN_PART }
    if (own.any { it in lower }) return Refusal(Problem.PASSWORD_HAS_NAME)
    if (repeated(lower) || run(lower) || commonWord(lower)) return Refusal(Problem.PASSWORD_TOO_SIMPLE)
    return null
  }

  /** One unit of up to [MAX_REPEATED_UNIT] characters, repeated throughout (the last time perhaps cut short). */
  private fun repeated(text: String): Boolean =
    (1..MAX_REPEATED_UNIT).any { unit -> text.length > unit && text.indices.all { text[it] == text[it % unit] } }

  /** The orders a run follows, each read round from its end to its start. */
  private val RUNS = listOf(
    "0123456789", "abcdefghijklmnopqrstuvwxyz",
    // The keyboards' letter rows, one after the other
    "qwertyuiopasdfghjklzxcvbnm", "qwertzuiopasdfghjklyxcvbnm",
  )

  /** Every character is the one before it, or the one after it, of one of the [RUNS] throughout. */
  private fun run(text: String): Boolean = RUNS.any { order ->
    fun index(c: Char) = order.indexOf(c)
    if (text.any { index(it) < 0 }) return@any false
    listOf(1, order.length - 1).any { step ->
      (1 until text.length).all { index(text[it]) == (index(text[it - 1]) + step) % order.length }
    }
  }

  /** What the usual swaps stand for (`1` either). */
  private val SWAPS = mapOf('@' to "a", '4' to "a", '0' to "o", '3' to "e", '$' to "s", '5' to "s", '7' to "t", '!' to "i")

  /** [text] with the swaps undone, each way of reading `1` (`i`, `l`). */
  private fun unswapped(text: String): List<String> = listOf('i', 'l').map { one ->
    text.map { c -> if (c == '1') one.toString() else SWAPS[c] ?: c.toString() }.joinToString("")
  }

  /**
   * A common word (or itself again) with nothing but digits, symbols and spaces around and between: read with the
   * leading and trailing ones taken away first, then the swaps undone, and the other way round.
   */
  private fun commonWord(text: String): Boolean {
    val stripped = text.trim { !it.isLetter() }
    val readings = unswapped(stripped) + unswapped(text).map { s -> s.trim { !it.isLetter() } }
    return readings.any { reading ->
      val words = letterRuns(reading)
      words.isNotEmpty() && COMMON_WORDS.any { word -> words.all { it.isRepeatOf(word) } }
    }
  }

  /** The runs of letters in [text], in their order (what is between them left out). */
  private fun letterRuns(text: String): List<String> = buildList {
    val word = StringBuilder()
    for (c in text + " ") {
      if (c.isLetter()) word.append(c)
      else if (word.isNotEmpty()) {
        add(word.toString())
        word.clear()
      }
    }
  }

  /** [word] once or more times over. */
  private fun String.isRepeatOf(word: String): Boolean = length % word.length == 0 && this == word.repeat(length / word.length)

  /**
   * The words of the most common passwords, in English and German, and this project's and Archipelago's names: a
   * password that is one of them with digits and symbols ([obvious]) is guessed first.
   */
  val COMMON_WORDS: Set<String> = setOf(
    "password", "passwort", "passwd", "pass", "kennwort", "welcome", "willkommen", "letmein", "admin", "administrator",
    "root", "login", "user", "master", "secret", "geheim", "hello", "hallo", "iloveyou", "ichliebedich", "love",
    "liebe", "monkey", "dragon", "football", "fussball", "baseball", "soccer", "sunshine", "princess", "prinzessin",
    "shadow", "superman", "batman", "trustno", "freedom", "whatever", "starwars", "pokemon", "minecraft", "computer",
    "internet", "michael", "jennifer", "jordan", "charlie", "thomas", "daniel", "andreas", "stefan", "sommer",
    "summer", "winter", "spring", "autumn", "herbst", "schatz", "schatzi", "hase", "mausi", "killer", "ninja",
    "mustang", "hunter", "buster", "test", "testing", "guest", "gast", "default", "changeme", "access", "zugang",
    "server", "system", "qwerty", "qwertz", "asdf", "abc", "randogeddon", "archipelago", "eratiem", "multiworld",
    "randomizer", "apworld", "zelda", "mario", "pikachu", "cheese", "banana", "chocolate", "cookie", "flower",
    "blume", "sonne", "sonnenschein", "deutschland", "germany", "berlin", "hamburg", "bayern", "schalke", "borussia",
    "dortmund", "lol", "omg", "hockey", "gaming", "gamer", "player", "spieler",
  )
}

/** A rough check of an e-mail address; whether it works shows when the invitation arrives. */
object EmailAddresses {
  const val MAX_LENGTH = 254

  /** What no part of an address has: an `@` of its own, spaces, control characters, what SMTP and headers split at. */
  private const val NOT = "@\\s<>,;\"\\u0000-\\u001F\\u007F"
  private val ADDRESS = Regex("[^$NOT]+@[^$NOT]+\\.[^$NOT.]+")

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

/**
 * `PUT /api/account/time-zone`: the account's time zone, an IANA name such as `Europe/Berlin` (the browser's); the
 * mails give their times in it. At most [MAX_LENGTH] characters.
 */
@Serializable
data class TimeZoneRequest(val zone: String) {
  companion object {
    const val MAX_LENGTH = 64
  }
}

/** `PUT /api/account/font`: [font] as [FontChoice] has it (`null`: the theme's), and where it shows. */
@Serializable
data class FontRequest(val font: String? = null, val scope: FontScope = FontScope.AUTO)

/** `PUT /api/account/auto-delete`. */
@Serializable
data class AutoDeleteRequest(val autoDelete: AutoDelete)
