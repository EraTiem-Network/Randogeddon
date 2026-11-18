package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The game server (MultiServer, run by the gateway) as the admin panel shows it. */
@Serializable
enum class GameServerStatus {
  /** No seed in the data directory yet. */
  @SerialName("no_seed") NO_SEED,
  @SerialName("starting") STARTING,
  @SerialName("running") RUNNING,
  /** Stopped by an admin; stays stopped until started again. */
  @SerialName("stopped") STOPPED,
  /** Ended on its own; the gateway starts it again after a pause. */
  @SerialName("crashed") CRASHED,
}

@Serializable
data class GameServerState(
  val status: GameServerStatus,
  /** File name of the seed it serves (or last served). */
  val seed: String? = null,
  /** Epoch milliseconds of the current start, while starting or running. */
  val since: Long? = null,
  val lastExitCode: Int? = null,
  /** Restarts after crashes since the last start by an admin. */
  val restarts: Int = 0,
)

/** One line of the game server's output; [n] counts up from 1 over the gateway's lifetime. */
@Serializable
data class LogLine(val n: Long, val ts: Long, val text: String)

/** Lines after the asked-for number; [last] is the number to ask with next time. */
@Serializable
data class LogLines(val lines: List<LogLine>, val last: Long)

/** Gateway → tracker: one session's game server, and the port MultiServer listens on while it runs. */
@Serializable
data class SessionServer(val id: String, val state: GameServerState, val port: Int? = null)

/** `GET /internal/sessions`: every session the gateway has files of, and how many game servers can run at once. */
@Serializable
data class SessionServers(val sessions: List<SessionServer> = emptyList(), val capacity: Int = 0)

/** A line for the server console, e.g. `/players`. */
@Serializable
data class CommandRequest(val line: String)
