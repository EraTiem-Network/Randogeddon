package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.shared.ArchivedSession
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.Summary

/**
 * Why a slot's logic doesn't fit its seed ([net.eratiem.randogeddon.shared.PlayerSummary.logicMismatch]) is for those
 * who look after the session: an Administrator, its Session-Managers and its support. Everyone else (its players, its
 * watchers, a share link) gets the warning without it: an empty reason. `null` stays: no warning.
 */
fun LiveSession.readsLogicReasons(user: User): Boolean =
  user.administrator || roleOf(user) in setOf(SessionRole.MANAGER, SessionRole.SUPPORT)

/** [reason] without its words: an empty one is a warning without a reason; `null` (no warning) stays. */
internal fun withoutReason(reason: String?): String? = reason?.let { "" }

/** [this] with every slot's logic warning, without its reason ([readsLogicReasons]). */
fun Summary.withoutLogicReasons(): Summary =
  if (players.none { !it.logicMismatch.isNullOrEmpty() }) this
  else copy(players = players.map { it.copy(logicMismatch = withoutReason(it.logicMismatch)) })

/** [this] with its logic warning, without its reason ([readsLogicReasons]). */
fun PlayerDetail.withoutLogicReason(): PlayerDetail =
  if (logicMismatch.isNullOrEmpty()) this else copy(logicMismatch = "")

/** [this] (a finished session as it is served) with every logic warning, without its reason ([readsLogicReasons]). */
fun ArchivedSession.withoutLogicReasons(): ArchivedSession =
  copy(summary = summary.withoutLogicReasons(), details = details.map { it.withoutLogicReason() })
