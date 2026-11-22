package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.require
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.SpoilerRunView
import org.koin.core.annotation.Single

/**
 * How a session's spoiler log is completed after its seed, for its Session-Managers, support and Administrators: its
 * level, the run going now (with Generate.py's share of a core) or why it stopped, and that run's log.
 */
@Single
class SpoilerRuns(private val gameServer: GameServerClient, private val sessions: LiveSessions) {
  fun view(id: String): SpoilerRunView = SpoilerRunView(sessions.require(id).server?.spoiler)

  suspend fun log(id: String, since: Long): LogLines = gameServer.spoilerRunLog(sessions.require(id).id, since) ?: LogLines(emptyList(), since)

  /** A stopped run is tried again (after a new version of the gateway or of an APWorld's fix). */
  suspend fun retry(id: String) {
    gameServer.retrySpoilerRun(sessions.require(id).id)
  }
}
