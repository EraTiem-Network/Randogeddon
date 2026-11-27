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
  /**
   * Ended on its own again and again, each soon after its start, or once because its save can't be read
   * ([GameServerState.saveUnreadable]): stays stopped until started again.
   */
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
  /**
   * The exit code of its last end on its own (a crash, or one that gave up); kept across starts, until it ends on its
   * own again.
   */
  val lastExitCode: Int? = null,
  /**
   * Restarts after crashes since the last start by an admin: the starts the gateway made by itself (the end it gives up
   * after, [GameServerStatus.FAILED], isn't followed by one).
   */
  val restarts: Int = 0,
)

/**
 * The exit code of `archipelago/serve.py` when the save can't be read: it keeps the save (and a copy) and ends, its last
 * line says why. It ends so for no other reason (Python's own codes are 1 and 2), and the gateway doesn't start it again.
 */
const val SAVE_UNREADABLE_EXIT = 3

/**
 * It stays stopped because its save can't be read ([SAVE_UNREADABLE_EXIT]), at the first start: not a server that
 * crashed again and again.
 */
val GameServerState.saveUnreadable: Boolean
  get() = status == GameServerStatus.FAILED && lastExitCode == SAVE_UNREADABLE_EXIT

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
  /** Its logic (the available checks); `null`: it has none (a seed from elsewhere, a race, from before 0.19.3). */
  val logic: LogicProgress? = null,
  /**
   * The room password of its MultiServer's current start (since 0.23.2.1), made by the gateway; only the tracker's own
   * connections give it in their `Connect`, it is never shown or passed on. `null`: none runs (or a gateway of before).
   */
  val roomPassword: String? = null,
)

/**
 * A session's logic: its seed's worlds generated again up to the fill (with the seed's number and inputs, as for its
 * spoiler log), kept to answer how many of each slot's locations its items reach now.
 */
@Serializable
enum class LogicStatus {
  /** Being built: no answers yet. */
  @SerialName("starting") STARTING,
  @SerialName("running") RUNNING,
  /** Not running: it starts with the game server. */
  @SerialName("stopped") STOPPED,
  /** Its build failed (see [LogicProgress.problem] and its log): it stays so until it is built again. */
  @SerialName("failed") FAILED,
}

/** [since]: when it got to [status] (epoch milliseconds). */
@Serializable
data class LogicProgress(val status: LogicStatus, val since: Long? = null, val problem: String? = null)

/** One slot as the logic is asked about it: the item IDs it received (not its starting inventory), its checked locations. */
@Serializable
data class AvailableSlot(val slot: Int, val items: List<Long>, val checked: List<Long>)

/**
 * The World Locks slot ([slot]) and the item IDs it received so far ([items]: its keys), the same for every asked slot:
 * the logic collects them through the locks' world, so a world whose key was found is open in its rules too.
 */
@Serializable
data class LocksFound(val slot: Int, val items: List<Long>)

/**
 * Tracker → gateway: `POST /internal/sessions/{id}/logic/available`; [maps]: the slots whose map it wants too; [locks]:
 * the keys found (`null`: the session has no locks; a logic of before ignores it).
 */
@Serializable
data class AvailableRequest(val slots: List<AvailableSlot>, val maps: List<Int> = emptyList(), val locks: LocksFound? = null)

/**
 * A slot's map as its logic has it (`logic_run.py`'s `slot_map`): its regions, its start first and then as they are
 * reached from it, and which of them connect ([links]: pairs of their indices).
 */
@Serializable
data class SlotMap(val regions: List<MapRegion> = emptyList(), val links: List<List<Int>> = emptyList())

/** A region of a slot's map and its locations (their IDs). */
@Serializable
data class MapRegion(val name: String, val locations: List<Long> = emptyList())

/**
 * How many of each asked slot's unchecked locations its logic reaches (by slot; `null`: no answer for it), which they
 * are ([locations], their IDs), and the maps asked for ([maps]; none for a slot it has no answers for). [regions]:
 * which of each slot's map's regions its logic reaches now (their places in [SlotMap.regions]); a slot it doesn't
 * say that for (a logic before 0.23.2.6) isn't in it. [mismatches]: of the asked slots, those whose rebuilt worlds
 * don't fit the seed's own order (its spheres), with one line of what didn't fit; they are answered all the same.
 */
@Serializable
data class AvailableAnswer(
  val available: Map<Int, Int?> = emptyMap(),
  val locations: Map<Int, List<Long>> = emptyMap(),
  val maps: Map<Int, SlotMap> = emptyMap(),
  val regions: Map<Int, List<Int>> = emptyMap(),
  val mismatches: Map<Int, String> = emptyMap(),
)

/** `GET /api/sessions/{id}/manage/logic`: its logic; `null`: it has none. */
@Serializable
data class LogicView(val progress: LogicProgress? = null)

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
  /** The run is held for a new session's generation (its level-3 run only); it goes on once there is room. */
  val paused: Boolean = false,
)

/** `GET /internal/sessions`: every session the gateway has files of. */
@Serializable
data class SessionServers(val sessions: List<SessionServer> = emptyList())

/** A line for the server console, e.g. `/players`. */
@Serializable
data class CommandRequest(val line: String)
