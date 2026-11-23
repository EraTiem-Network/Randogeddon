package net.eratiem.randogeddon.shared.web

import net.eratiem.randogeddon.shared.PlayerSummary
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionStatus

/** Not finished yet. */
val SessionStatus.running: Boolean get() = this == SessionStatus.LIVE || this == SessionStatus.FINISHING

/**
 * The session the sessions' page opens by itself, of [sessions] (the ones the user sees): the user's only one, always
 * (there is nothing to pick, and the top card has no switch then); [arriving] (the first page after loading it or the
 * login), their only running one with a slot of theirs. `null`: none, the page lists them. Asked for on purpose (the
 * name on top, *All sessions*), the list shows for whoever has more than one.
 */
fun homeTarget(sessions: List<SessionEntry>, arriving: Boolean): String? {
  sessions.singleOrNull { it.myRole != null }?.let { return it.id }
  if (!arriving) return null
  return sessions.filter { it.mySlots.isNotEmpty() && it.status.running }.singleOrNull()?.id
}

/**
 * The logic's warning of [worlds] together ([PlayerSummary.logicMismatch]): `null` when every one fits; else each one
 * that doesn't by its name, one a line, with its reason where there is one; empty when none has a reason (the viewer
 * gets the warning, not why).
 */
fun mismatches(worlds: List<PlayerSummary>): String? {
  val off = worlds.filter { it.logicMismatch != null }.takeIf { it.isNotEmpty() } ?: return null
  if (off.all { it.logicMismatch.isNullOrEmpty() }) return ""
  return off.joinToString("\n") { w -> w.logicMismatch?.takeIf { it.isNotEmpty() }?.let { "${w.name}: $it" } ?: w.name }
}
