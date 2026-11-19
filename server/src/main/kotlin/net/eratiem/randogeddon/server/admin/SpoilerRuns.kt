package net.eratiem.randogeddon.server.admin

import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.SpoilerRunView
import org.koin.core.annotation.Single

/**
 * How a session's spoiler log is completed after its seed, for its Session-Managers, support and Administrators: its
 * level, the run going now (with Generate.py's share of a core) or why it stopped, and that run's log.
 */
@Single
class SpoilerRuns(private val gameServer: GameServerClient, private val sessions: LiveSessions) {
  fun view(id: String): SpoilerRunView = SpoilerRunView(live(id).server?.spoiler)

  suspend fun log(id: String, since: Long): LogLines = gameServer.spoilerRunLog(live(id).id, since) ?: LogLines(emptyList(), since)

  private fun live(id: String) =
    sessions.get(id) ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
}
