package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON format of the API, shared so both sides agree on defaults and unknown keys. */
val ApiJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  encodeDefaults = true
}

/** Everything the dashboard shows at once; sent by `GET /api/state` and on every change by `GET /api/stream`. */
@Serializable
data class Summary(
  val title: String,
  val connected: Boolean,
  val error: String? = null,
  val seedName: String? = null,
  val serverVersion: String? = null,
  val generatorVersion: String? = null,
  val hintCost: Int? = null,
  /** Which hint systems are in play; the dashboard hides hints when neither is. */
  val hintSettings: HintSettings = HintSettings(),
  /** When the tracker started, epoch milliseconds. */
  val startedAt: Long,
  /**
   * How long the game server has run for this seed since its first start, without the times it was stopped. Counted
   * up to [sessionRunningSince]; while it runs, add `now - sessionRunningSince`.
   */
  val sessionTimeMs: Long = 0,
  /** Epoch milliseconds the count runs from while the game server runs; `null` while it is stopped. */
  val sessionRunningSince: Long? = null,
  /** Increases with every change of the session state. */
  val version: Long,
  val totals: Totals,
  /** The people of `session.yaml` with their worlds; without that file, every world is its own person. */
  val people: List<PersonSummary> = emptyList(),
  /** Every world (slot) of the room, except the World Locks slot. */
  val players: List<PlayerSummary>,
  /** The most recent events, oldest first. */
  val events: List<FeedEvent>,
  val hints: List<HintView>,
)

@Serializable
data class Totals(
  val checked: Int,
  val total: Int,
  val percent: Double,
  /** Worlds (slots). */
  val players: Int,
  /** Worlds that reached their goal or were released. */
  val goals: Int,
  val online: Int,
  /** Worlds whose key has not been found yet. */
  val locked: Int = 0,
  /** Worlds that start locked (World Locks). */
  val lockable: Int = 0,
)

/**
 * A stretch of time a game client was connected to a world. [implicit]: the tracker didn't see it join (it was
 * restarted meanwhile) but saw it play, so the real start may be earlier. [cut]: ended because the tracker stopped or
 * lost the server, not because the client left; if the world plays on soon after, the session simply continues.
 */
@Serializable
data class ConnectionSession(
  val start: Long,
  val end: Long? = null,
  val implicit: Boolean = false,
  val cut: Boolean = false,
)

@Serializable
data class HintSettings(
  /** Players buy hints with points from checking locations (`!hint`). */
  val pointHints: Boolean = true,
  /** Games create hints on their own (e.g. shops). */
  val autoHints: Boolean = true,
  /** A hint costs this % of a world's locations in points; 0 = free. */
  val costPercent: Int = 0,
  /** Points per checked location. */
  val checkPoints: Int = 1,
) {
  val enabled: Boolean get() = pointHints || autoHints
}

/** One person and their worlds. */
@Serializable
data class PersonSummary(
  val name: String,
  /** Slots of [PlayerSummary.slot], in the order of `session.yaml`. */
  val slots: List<Int>,
  val checked: Int,
  val total: Int,
  val percent: Double,
  val goals: Int,
  val online: Boolean,
  val lastActivity: Long? = null,
  /** Sum of the worlds' finished sessions; add `now - onlineSince` of the online worlds. */
  val playTimeMs: Long = 0,
)

@Serializable
enum class PlayerStatus {
  @SerialName("idle") IDLE,
  /** Starts locked (World Locks) and its key has not been found yet. */
  @SerialName("locked") LOCKED,
  @SerialName("playing") PLAYING,
  @SerialName("complete") COMPLETE,
  @SerialName("goal") GOAL,
  @SerialName("released") RELEASED,
}

@Serializable
data class PlayerSummary(
  val slot: Int,
  val name: String,
  val game: String,
  val checked: Int,
  val total: Int,
  val percent: Double,
  val status: PlayerStatus,
  /** A game client (not a tracker or text client) is connected to this slot. */
  val online: Boolean,
  /** Epoch milliseconds of the last check, goal or join; `null` if nothing happened yet. */
  val lastActivity: Long? = null,
  val itemsReceived: Int,
  val progressionReceived: Int,
  val hintPoints: Int? = null,
  /** Points one hint costs in this world (0 = free); `hintPoints / hintCost` hints can be bought now. */
  val hintCost: Int? = null,
  /** The person this world belongs to (`session.yaml`). */
  val person: String? = null,
  /** Time a game client was connected, in finished sessions; add `now - onlineSince` while online. */
  val playTimeMs: Long = 0,
  /** Start of the current session, while online. */
  val onlineSince: Long? = null,
  val firstCheckAt: Long? = null,
  val goalAt: Long? = null,
  /** For worlds that start locked: the key and what is known about it. */
  val lock: LockInfo? = null,
)

@Serializable
data class LockInfo(
  val key: String,
  val unlocked: Boolean,
  /** Who found the key. */
  val foundBy: String? = null,
  val foundAt: Long? = null,
  /** Where the key is, if someone hinted it: "Location (Player's world)". */
  val hintedAt: String? = null,
)

@Serializable
enum class EventType {
  @SerialName("item") ITEM,
  @SerialName("hint") HINT,
  @SerialName("goal") GOAL,
  @SerialName("release") RELEASE,
  @SerialName("collect") COLLECT,
  @SerialName("join") JOIN,
  @SerialName("part") PART,
  @SerialName("chat") CHAT,
  @SerialName("countdown") COUNTDOWN,
  /** A world's key was found; [FeedEvent.slot] is the unlocked world, [FeedEvent.sender] who found it. */
  @SerialName("unlock") UNLOCK,
}

/**
 * One entry of the live feed. Stored by the server with ids only; the `*Name` fields are filled in when it is sent, so
 * names that become known later (data package, aliases) are used for old events too.
 */
@Serializable
data class FeedEvent(
  val id: Long,
  /** Epoch milliseconds. */
  val ts: Long,
  val type: EventType,
  /** The player of goal, release, collect, join, part and chat events; 0 is the server. */
  val slot: Int? = null,
  /** The player in whose world an item was found. */
  val sender: Int? = null,
  val receiver: Int? = null,
  /** The player in whose world a hinted item is. */
  val finder: Int? = null,
  val item: Long? = null,
  val location: Long? = null,
  val flags: Int = 0,
  val found: Boolean = false,
  val cheat: Boolean = false,
  val message: String? = null,
  val playerName: String? = null,
  val senderName: String? = null,
  val receiverName: String? = null,
  val finderName: String? = null,
  val itemName: String? = null,
  val locationName: String? = null,
)

@Serializable
data class HintView(
  val receivingPlayer: Int,
  val findingPlayer: Int,
  val location: Long,
  val item: Long,
  val found: Boolean,
  val entrance: String = "",
  val itemFlags: Int = 0,
  val status: Int = 0,
  val finderName: String,
  val receiverName: String,
  val itemName: String,
  val locationName: String,
)

/** `GET /api/player/{slot}`: what one player received and what is left in their world. */
@Serializable
data class PlayerDetail(
  val slot: Int,
  val name: String,
  val game: String,
  val items: List<ReceivedItemView>,
  val missing: List<String>,
  val checked: Int,
  val total: Int,
  /** Connection log, oldest first. */
  val sessions: List<ConnectionSession> = emptyList(),
  val hintPoints: Int? = null,
)

@Serializable
data class ReceivedItemView(
  val item: String,
  val from: String,
  /** Empty for items from the server (starting inventory, `/send`). */
  val location: String,
  val flags: Int,
)

/** `POST /api/login`. */
@Serializable
data class LoginRequest(val username: String, val password: String)

/** An answer that isn't data: why a login failed, or why access was refused. */
@Serializable
data class ApiError(val message: String)

/** `GET /api/me` (and the answer to a successful login): who is logged in and how they join the game. */
@Serializable
data class Me(
  val name: String,
  /** Where game clients connect, `host:port`. */
  val server: String,
  /** The person's worlds with their passwords; empty until the tracker knows the seed. */
  val slots: List<MySlot> = emptyList(),
  /** Sees and reaches the admin panel. */
  val admin: Boolean = false,
)

@Serializable
data class MySlot(val name: String, val game: String, val password: String)

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

/** A line for the server console, e.g. `/players`. */
@Serializable
data class CommandRequest(val line: String)

/** Archipelago's `ItemClassification` bits, as sent in `NetworkItem.flags`. */
object ItemFlags {
  const val PROGRESSION = 0b001
  const val USEFUL = 0b010
  const val TRAP = 0b100
}

enum class ItemKind { PROGRESSION, USEFUL, TRAP, FILLER }

/** Progression wins over trap and useful, as in Archipelago's own clients. */
fun itemKind(flags: Int): ItemKind = when {
  flags and ItemFlags.PROGRESSION != 0 -> ItemKind.PROGRESSION
  flags and ItemFlags.TRAP != 0 -> ItemKind.TRAP
  flags and ItemFlags.USEFUL != 0 -> ItemKind.USEFUL
  else -> ItemKind.FILLER
}
