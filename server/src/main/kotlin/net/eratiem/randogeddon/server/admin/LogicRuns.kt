package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.require
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.LogicView
import org.koin.core.annotation.Single

/**
 * A session's logic, which counts its slots' available checks: its state and building it again for its
 * Session-Managers and Administrators, its log for Administrators.
 */
@Single
class LogicRuns(private val gameServer: GameServerClient, private val sessions: LiveSessions) {
  fun view(id: String): LogicView = LogicView(sessions.require(id).server?.logic)

  suspend fun log(id: String, since: Long): LogLines = gameServer.logicLog(sessions.require(id).id, since) ?: LogLines(emptyList(), since)

  /** Built again from the start; `false`: it can have none. Its new state is read at once (not at the next look). */
  suspend fun rebuild(id: String): Boolean = gameServer.rebuildLogic(sessions.require(id).id).also { if (it) sessions.refresh() }
}
