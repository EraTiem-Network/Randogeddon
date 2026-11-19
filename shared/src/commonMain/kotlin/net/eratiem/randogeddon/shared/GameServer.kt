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
  /** Ended on its own again and again, each soon after its start: stays stopped until started again. */
  @SerialName("failed") FAILED,
  /** Stopped while nobody played (or not started since the gateway's start): starts when a player connects. */
  @SerialName("idle") IDLE,
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
data class SessionServer(
  val id: String,
  val state: GameServerState,
  val port: Int? = null,
  /** Its spoiler log while it is completed in the background (since 0.19.3); `null`: complete (or from before). */
  val spoiler: SpoilerProgress? = null,
)

/**
 * A session's spoiler log as the gateway completes it after the seed (with the same seed number and inputs, only the
 * spoiler worked out again): [level] 1 the seed's (no playthrough), 2 with the playthrough (the spoiler page, the hints
 * and the cheats wait for it), 3 with the paths to every item (complete). [running]: the level worked out now (`null`:
 * waiting for its turn), with Generate.py's share of a core ([cpu]). [stopped]: why it stays at [level].
 */
/** `GET /api/sessions/{id}/manage/spoiler-run` (and `…/support/…`): how its spoiler log is completed; `null`: complete. */
@Serializable
data class SpoilerRunView(val progress: SpoilerProgress? = null)

@Serializable
data class SpoilerProgress(
  val level: Int,
  val running: Int? = null,
  val cpu: Int? = null,
  val stopped: String? = null,
)

/** `GET /internal/sessions`: every session the gateway has files of. */
@Serializable
data class SessionServers(val sessions: List<SessionServer> = emptyList())

/** A line for the server console, e.g. `/players`. */
@Serializable
data class CommandRequest(val line: String)
