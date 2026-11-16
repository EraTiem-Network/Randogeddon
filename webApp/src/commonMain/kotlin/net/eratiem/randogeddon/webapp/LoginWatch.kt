package net.eratiem.randogeddon.webapp

import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.Problem

/**
 * Notices a login that ended while the page is open (logged out elsewhere, at Authentik, expired): any answer of the
 * server saying so ([Problem.NOT_LOGGED_IN], a 401 of `/api`) calls [onEnded], which shows the login form, instead of
 * that request showing it as its own error. Other 401s (a wrong password or code at the login) don't say so.
 */
object LoginWatch {
  var onEnded: (() -> Unit)? = null

  /** Every answer passes here. */
  fun seen(status: Int, body: String) {
    if (status != 401) return
    val problem = runCatching { ApiJson.decodeFromString(ApiError.serializer(), body).code }.getOrNull()
    if (problem == Problem.NOT_LOGGED_IN) onEnded?.invoke()
  }
}
