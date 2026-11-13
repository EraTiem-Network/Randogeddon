package net.eratiem.randogeddon.webapp

/**
 * Where the page is, kept in the URL's hash so a view survives a reload and can be linked: `#/` the sessions,
 * `#/<id>` a session's dashboard, `#/<id>/spoiler` its spoiler log, `#/admin` the admin panel, `#/admin/<id>` a session
 * in it, `#/admin/new` a new session, `#/admin/<id>/spoiler` a spoiler log opened from it, `#/settings` the user's
 * account; `#/invite/<token>` and `#/email/<token>` are the links of the mails. No session ID is one of these words
 * (they are reserved).
 */
sealed interface Route {
  /** The sessions to pick from. */
  data object Home : Route

  data class Dashboard(val id: String) : Route

  /** [fromAdmin]: back goes to the admin panel. */
  data class Spoiler(val id: String, val fromAdmin: Boolean) : Route

  /** [page]: a session, [NEW_PAGE], or `null` for the newest running one. */
  data class Admin(val page: String?) : Route

  /** The user's own account. */
  data object Settings : Route

  /** An invitation's link; the sign-up form of index.html takes it without a login. */
  data class Invite(val token: String) : Route

  /** The link of the mail confirming a new e-mail address. */
  data class ConfirmEmail(val token: String) : Route

  companion object {
    fun parse(hash: String): Route {
      val parts = hash.removePrefix("#").trim('/').split('/').filter { it.isNotEmpty() }
      return when {
        parts.isEmpty() -> Home
        parts[0] == "admin" -> when {
          parts.size == 1 -> Admin(null)
          parts[1] == "new" -> Admin(NEW_PAGE)
          parts.size >= 3 && parts[2] == "spoiler" -> Spoiler(parts[1], fromAdmin = true)
          else -> Admin(parts[1])
        }
        parts[0] == "settings" -> Settings
        parts[0] == "invite" && parts.size >= 2 -> Invite(parts[1])
        parts[0] == "email" && parts.size >= 2 -> ConfirmEmail(parts[1])
        parts.size >= 2 && parts[1] == "spoiler" -> Spoiler(parts[0], fromAdmin = false)
        else -> Dashboard(parts[0])
      }
    }
  }
}

/** [this] as the URL's hash. */
val Route.hash: String
  get() = when (this) {
    Route.Home -> "#/"
    is Route.Dashboard -> "#/$id"
    is Route.Spoiler -> if (fromAdmin) "#/admin/$id/spoiler" else "#/$id/spoiler"
    is Route.Admin -> when (page) {
      null -> "#/admin"
      NEW_PAGE -> "#/admin/new"
      else -> "#/admin/$page"
    }
    Route.Settings -> "#/settings"
    is Route.Invite -> "#/invite/$token"
    is Route.ConfirmEmail -> "#/email/$token"
  }
