package net.eratiem.randogeddon.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.eratiem.randogeddon.shared.i18n.Language

/** The JSON format of the API, shared so both sides agree on defaults and unknown keys. */
val ApiJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  encodeDefaults = true
}

/**
 * The ID of a session: its address for game clients (`<id>.<domain>`) and in the dashboard's links. A DNS label that
 * starts with a letter (so it never looks like a seed name), 3 to 40 lowercase letters, digits and hyphens.
 */
object SessionIds {
  const val MIN_LENGTH = 3
  const val MAX_LENGTH = 40
  private val ID = Regex("[a-z][a-z0-9-]{1,${MAX_LENGTH - 2}}[a-z0-9]")

  /** Words the API and the dashboard's links use themselves. */
  private val RESERVED = setOf(
    "admin", "api", "email", "inspect", "invite", "login", "login-problem", "manage", "new", "reset", "sessions",
    "settings", "watch", "www",
  )

  fun valid(id: String): Boolean = ID.matches(id) && "--" !in id && id !in RESERVED

  /**
   * [text] as typed into a session ID's field, kept to what a subdomain allows: capitals small, a space, `_` or `.` a
   * hyphen, anything else left out, nothing before its first letter, no two hyphens in a row, at most [MAX_LENGTH].
   * A hyphen at the end stays while typing (the next word needs it); the ID itself is without it ([trimEnd]).
   */
  fun typed(text: String): String = text.lowercase()
    .map { if (it == ' ' || it == '_' || it == '.') '-' else it }
    .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    .dropWhile { it !in 'a'..'z' }
    .joinToString("")
    .replace(Regex("-{2,}"), "-")
    .take(MAX_LENGTH)

  /** The ID [typed] stands for: without a hyphen at its end. */
  fun trimEnd(typed: String): String = typed.trimEnd('-')

  /** What's wrong with [id] as a session ID; `null`: nothing. */
  fun problem(id: String): Refusal? = when {
    id.length < MIN_LENGTH -> Refusal(Problem.ID_TOO_SHORT)
    id.length > MAX_LENGTH -> Refusal(Problem.ID_TOO_LONG)
    id[0] !in 'a'..'z' -> Refusal(Problem.ID_LETTER_FIRST)
    id.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' } -> Refusal(Problem.ID_CHARACTERS)
    id.endsWith('-') -> Refusal(Problem.ID_TRAILING_DASH)
    "--" in id -> Refusal(Problem.ID_DOUBLE_DASH)
    id in RESERVED -> Refusal.of(Problem.ID_RESERVED, "id" to id)
    else -> null
  }
}

/**
 * An answer that isn't data: why a login failed, or why access was refused. [message] is worded in the request's
 * language (the `ap-language` cookie, else its Accept-Language); [code] and [args] let the web app word it itself,
 * [detail] is what another program said (shown as it is).
 */
@Serializable
data class ApiError(
  val message: String,
  val code: Problem? = null,
  val args: Map<String, String> = emptyMap(),
  val detail: String? = null,
)

/** `GET /api/me`: who is logged in. Their sessions are `GET /api/sessions`. */
@Serializable
data class Me(
  val name: String,
  /** An Administrator: sees and manages every session, the APWorlds, the users and the settings. */
  val administrator: Boolean = false,
  /** The sessions the user is a Session-Manager of. */
  val manages: List<String> = emptyList(),
  /** The language they chose; `null`: their browser's. */
  val language: Language? = null,
  /** The theme and mode they chose; `null`: the default theme, automatic. */
  val theme: Theme? = null,
  val themeMode: ThemeMode? = null,
  /** The font they chose ([FontChoice]); `null`: the theme's. And where it shows. */
  val font: String? = null,
  val fontScope: FontScope = FontScope.AUTO,
  /** They may create sessions at all: an Administrator, or someone whose limit isn't 0. */
  val createsSessions: Boolean = false,
  /** The time zone the mails give their times in (an IANA name, the browser's); `null`: not known, UTC. */
  val timeZone: String? = null,
)

/**
 * `GET /api/admin/versions` (Administrators): what runs now, to check a deploy: the tracker's version, the gateway's
 * (`null`: it can't be reached) and its Archipelago releases.
 */
@Serializable
data class Versions(val tracker: String, val gateway: String? = null, val releases: List<String> = emptyList())
