package net.eratiem.randogeddon.webapp.app

import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.manage.NEW_PAGE

/**
 * Where the page is, kept in the URL's hash so a view survives a reload and can be linked: `#/` the sessions,
 * `#/<id>` a session's dashboard, `#/<id>/spoiler` its spoiler log, `#/manage` the panel (`#/admin` before),
 * `#/manage/<id>` a session in it, `#/manage/new` a new session, `#/manage/<id>/spoiler` a spoiler log opened from it,
 * `#/settings` the user's account; `#/invite/<token>` and `#/email/<token>` are the links of the mails, `#/watch/<token>`
 * a share link (without a login). No session ID is one of these words (they are reserved).
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

  /** A session's share link: its dashboard without a login, read-only. */
  data class Watch(val token: String) : Route

  companion object {
    fun parse(hash: String): Route {
      val parts = hash.removePrefix("#").trim('/').split('/').filter { it.isNotEmpty() }
      return when {
        parts.isEmpty() -> Home
        parts[0] == "manage" || parts[0] == "admin" -> when {
          parts.size == 1 -> Admin(null)
          parts[1] == "new" -> Admin(NEW_PAGE)
          parts.size >= 3 && parts[2] == "spoiler" -> Spoiler(parts[1], fromAdmin = true)
          else -> Admin(parts[1])
        }
        parts[0] == "settings" -> Settings
        parts[0] == "invite" && parts.size >= 2 -> Invite(parts[1])
        parts[0] == "email" && parts.size >= 2 -> ConfirmEmail(parts[1])
        parts[0] == "watch" && parts.size >= 2 -> Watch(parts[1])
        // index.html's login form uses these (a CLI login link, a refused login through the provider)
        parts[0] == "login" || parts[0] == "login-problem" -> Home
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
    is Route.Spoiler -> if (fromAdmin) "#/manage/$id/spoiler" else "#/$id/spoiler"
    is Route.Admin -> when (page) {
      null -> "#/manage"
      NEW_PAGE -> "#/manage/new"
      else -> "#/manage/$page"
    }
    Route.Settings -> "#/settings"
    is Route.Invite -> "#/invite/$token"
    is Route.ConfirmEmail -> "#/email/$token"
    is Route.Watch -> "#/watch/$token"
  }
