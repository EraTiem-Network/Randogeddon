package net.eratiem.randogeddon.server.admin

import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.shared.Problem
import org.koin.core.annotation.Single

/** Whether one more game server may start: [running] others run, [limit] may (0: no limit). */
internal fun roomForOneMore(running: Int, limit: Int): Boolean = limit == 0 || running < limit

/**
 * How many game servers may run at once, of every session: the Administrators' setting (`game_servers`; 0: no limit).
 * Asked before a session is created, before a game server starts from the panel, and when a session is reopened; the
 * gateway keeps to it too (for a start that doesn't come through here, and at its own start).
 */
@Single
class GameServerLimit(private val gameServer: GameServerClient, private val settings: AppSettingsStore) {
  /** The setting. */
  suspend fun limit(): Int = settings.gameServers()

  /**
   * Refuses ([Problem.SERVERS_IN_USE]) when as many game servers run as may: those of the gateway but session [id]'s
   * (it may run already, as at a restart), and [starting] more that are about to (sessions being created).
   */
  suspend fun check(id: String?, starting: Int = 0) {
    val max = limit()
    if (max == 0) return
    val running = gameServer.sessions().sessions.count { it.port != null && it.id != id }
    if (!roomForOneMore(running + starting, max)) {
      throw SessionProblem(HttpStatusCode.Conflict, Problem.SERVERS_IN_USE, "capacity" to max)
    }
  }
}
