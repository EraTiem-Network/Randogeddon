package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.eratiem.randogeddon.shared.i18n.Language

/** The JSON format of the API, shared so both sides agree on defaults and unknown keys. */
val ApiJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  encodeDefaults = true
}

/**
 * The ID of a session: its address for game clients (`<id>.<domain>`) and in the dashboard's links. A DNS label that
 * starts with a letter (so it never looks like a seed name), 3 to 40 lowercase letters, digits and hyphens.
 */
object SessionIds {
  const val MIN_LENGTH = 3
  const val MAX_LENGTH = 40
  private val ID = Regex("[a-z][a-z0-9-]{1,${MAX_LENGTH - 2}}[a-z0-9]")

  /** Words the API and the dashboard's links use themselves. */
  private val RESERVED = setOf(
    "admin", "api", "email", "inspect", "invite", "login", "login-problem", "manage", "new", "sessions", "settings",
    "watch", "www",
  )

  fun valid(id: String): Boolean = ID.matches(id) && "--" !in id && id !in RESERVED

  /** What's wrong with [id] as a session ID; `null`: nothing. */
  fun problem(id: String): Refusal? = when {
    id.length < MIN_LENGTH -> Refusal(Problem.ID_TOO_SHORT)
    id.length > MAX_LENGTH -> Refusal(Problem.ID_TOO_LONG)
    id[0] !in 'a'..'z' -> Refusal(Problem.ID_LETTER_FIRST)
    id.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' } -> Refusal(Problem.ID_CHARACTERS)
    id.endsWith('-') -> Refusal(Problem.ID_TRAILING_DASH)
    "--" in id -> Refusal(Problem.ID_DOUBLE_DASH)
    id in RESERVED -> Refusal.of(Problem.ID_RESERVED, "id" to id)
    else -> null
  }
}

/** How far a session is: running (maybe about to end by itself), or finished, paused or ended (completed). */
@Serializable
enum class SessionStatus {
  @SerialName("live") LIVE,
  /** Every slot is done: it ends by itself soon. */
  @SerialName("finishing") FINISHING,
  /** Finished before it was complete; it can be reopened for a while. */
  @SerialName("paused") PAUSED,
  /** Finished complete (100 %). */
  @SerialName("ended") ENDED,
}

/** Everything the dashboard shows at once; sent by `GET /api/sessions/{id}/state` and on every change by `.../stream`. */
@Serializable
data class Summary(
  val title: String,
  /** The session's ID (see [SessionIds]). */
  val sessionId: String? = null,
  val connected: Boolean,
  /** Why the tracker can't follow the game server: what the connection said, as it is. */
  val error: String? = null,
  /** [error] came from the game server turning the tracker away (not from a broken connection). */
  val errorRefused: Boolean = false,
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
  /** Every slot is done: the session finishes by itself at this time (epoch ms), unless an admin cancels it. */
  val finishesAt: Long? = null,
  /** The session is finished (read-only from then on); epoch ms. */
  val finishedAt: Long? = null,
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
  /** The name the person has in this session (the one of then, once it is finished). */
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
  /** Their account's UUID (tells two people of the same name apart); `null` for a session from before accounts. */
  val userId: String? = null,
  /** Their account is deleted; the session keeps the name. */
  val deleted: Boolean = false,
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
  /** Where the key is, if someone hinted it: "Location (in Slot)" (for clients before 0.9.3). */
  val hintedAt: String? = null,
  /** The same in its parts: the location, and the slot it is in. */
  val hintLocation: String? = null,
  val hintSlot: String? = null,
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
  /** An admin cheated from the admin panel; [FeedEvent.message] says who and what. */
  @SerialName("cheat") CHEAT,
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
  /** A chat line, a countdown; for a cheat the English sentence (for clients before 0.9.3, and events before it). */
  val message: String? = null,
  /** A cheat, in its parts: what was done, by whom (and in which role), to which slot, with what. */
  val cheatAction: CheatAction? = null,
  val cheatBy: String? = null,
  /** `administrator`, `manager` or `support`. */
  val cheatRole: String? = null,
  val cheatSlot: String? = null,
  val cheatValue: String? = null,
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

/**
 * An answer that isn't data: why a login failed, or why access was refused. [message] is worded in the request's
 * language (the `ap-language` cookie, else its Accept-Language); [code] and [args] let the web app word it itself,
 * [detail] is what another program said (shown as it is).
 */
@Serializable
data class ApiError(
  val message: String,
  val code: Problem? = null,
  val args: Map<String, String> = emptyMap(),
  val detail: String? = null,
)

/** `GET /api/me`: who is logged in. Their sessions are `GET /api/sessions`. */
@Serializable
data class Me(
  val name: String,
  /** An Administrator: sees and manages every session, the APWorlds, the users and the settings. */
  val administrator: Boolean = false,
  /** The sessions the user is a Session-Manager of. */
  val manages: List<String> = emptyList(),
  /** The language they chose; `null`: their browser's. */
  val language: Language? = null,
)

/** `GET /api/sessions/{id}/connection`: where game clients connect to the session, and the person's slots in it. */
@Serializable
data class Connection(
  /** E.g. `friday-chaos.randogeddon.eratiem.net:443`, or `ws://localhost:38281/friday-chaos` locally. */
  val server: String,
  /** The person's slots with their passwords; empty until the tracker knows the seed. */
  val slots: List<MySlot> = emptyList(),
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

/** Gateway → tracker: one session's game server, and the port MultiServer listens on while it runs. */
@Serializable
data class SessionServer(val id: String, val state: GameServerState, val port: Int? = null)

/** `GET /internal/sessions`: every session the gateway has files of, and how many game servers can run at once. */
@Serializable
data class SessionServers(val sessions: List<SessionServer> = emptyList(), val capacity: Int = 0)

/** A line for the server console, e.g. `/players`. */
@Serializable
data class CommandRequest(val line: String)

/** A session's game server options, set when it is created (the defaults are those of `.env.example`). */
@Serializable
data class SessionOptions(
  /** Players buy hints with points (`!hint`). */
  val pointHints: Boolean = true,
  /** Games create hints on their own (e.g. shops). */
  val autoHints: Boolean = true,
  /** % of a world's locations a hint costs; 0 = free. */
  val hintCost: Int = 10,
  val locationCheckPoints: Int = 1,
  val releaseMode: String = "auto",
  val collectMode: String = "auto",
  val remainingMode: String = "goal",
  val countdownMode: String = "auto",
  /** World Locks: keys reachable from the start, so locked slots open early. */
  val earlyKeys: Boolean = false,
  /**
   * Random locks: this many slots start open in all, and all others start locked. Which ones is picked at random:
   * among every slot, or, once a slot is [WorldPlan.random], among those (the others start as [WorldPlan.locked] says).
   * `null`: the locks are chosen per slot ([WorldPlan.locked]).
   */
  val openSlots: Int? = null,
  /** Admins may give free hints from the admin panel (cheats). */
  val cheatHints: Boolean = false,
)

/** A player YAML as uploaded in the admin panel. */
@Serializable
data class UploadedYaml(val fileName: String, val content: String)

/** A slot found in an uploaded YAML. */
@Serializable
data class YamlWorld(val fileName: String, val name: String, val game: String)

/** `POST /api/admin/session/inspect`: the YAMLs, checked against the games of an Archipelago release. */
@Serializable
data class InspectRequest(
  val yamls: List<UploadedYaml>,
  /** `null`: the gateway's newest release. */
  val apVersion: String? = null,
)

/** The slots of the uploaded YAMLs, and what's wrong with them (a game the release has no apworld for, too). */
@Serializable
data class InspectResult(
  val worlds: List<YamlWorld>,
  /** What's wrong with the YAMLs; a file's problems name it in their `file` value. */
  val problems: List<ApiError> = emptyList(),
  /** The files that aren't player YAMLs at all (or not all of them): the panel doesn't take them. */
  val invalid: List<String> = emptyList(),
)

/**
 * One slot of a new session: who plays it (one or more users; new people are invited first) and whether it starts
 * locked, or whether that is left to chance ([random], with [SessionOptions.openSlots]).
 */
@Serializable
data class WorldPlan(
  val name: String,
  /** Who plays it, by name: what the admin panel shows of an existing session. */
  val users: List<String> = emptyList(),
  /** Who plays it, by user ID ([UserRef.id]): what a new session is created with. */
  val userIds: List<String> = emptyList(),
  val locked: Boolean = false,
  val random: Boolean = false,
)

@Serializable
data class CreateSessionRequest(
  /** The new session's ID (see [SessionIds]); it must be free. */
  val id: String,
  val yamls: List<UploadedYaml>,
  val worlds: List<WorldPlan>,
  val options: SessionOptions = SessionOptions(),
  /** `null`: the gateway's newest Archipelago release. */
  val apVersion: String? = null,
)

@Serializable
data class CreateSessionResult(
  val id: String,
  val seedName: String,
  /** The slots that start locked (with random locks, the ones picked). */
  val locked: List<String> = emptyList(),
)

/** A session as the admin panel shows it. */
@Serializable
data class SessionInfo(
  val id: String,
  val seedName: String,
  /** `null` for a seed deployed with the CLI. */
  val apVersion: String? = null,
  val createdAt: Long? = null,
  val options: SessionOptions? = null,
  val worlds: List<WorldPlan> = emptyList(),
  /** It finishes by itself then (every slot is done); epoch ms. */
  val finishesAt: Long? = null,
  val finishedAt: Long? = null,
  /** A finished session can be reopened until then; `null`: it can't (completed, or not finished). */
  val reopenUntil: Long? = null,
  /** Its auto-deletion is disabled. */
  val keep: Boolean = false,
  /** Once it is a past session, it is deleted then; `null`: never (kept, or no auto-deletion). */
  val deletesAt: Long? = null,
  /** How far its slots got, live (or as it finished). */
  val percent: Double = 0.0,
)

/** A session in the lists: of the sessions a player is part of, of every session for admins. */
@Serializable
data class SessionEntry(
  val id: String,
  val seedName: String,
  val title: String,
  val status: SessionStatus = SessionStatus.LIVE,
  val createdAt: Long? = null,
  /** `null` while it runs. */
  val finishedAt: Long? = null,
  /** Who finished it by hand; `null`: by itself. */
  val finishedBy: String? = null,
  val players: List<String> = emptyList(),
  /** The slots of the one asking (none for someone who doesn't play). */
  val mySlots: List<String> = emptyList(),
  /** What the one asking is in it; `null` for an Administrator without a role there. */
  val myRole: SessionRole? = null,
  val slots: Int = 0,
  val percent: Double = 0.0,
  val goals: Int = 0,
  /** Players with a game client connected now. */
  val online: Int = 0,
  /** It can be reopened until then; `null`: it can't (it was completed, or it isn't finished). */
  val reopenUntil: Long? = null,
  /** Its auto-deletion is disabled. */
  val keep: Boolean = false,
  /** It is deleted then; `null`: never (kept, no auto-deletion, or not finished). */
  val deletesAt: Long? = null,
  /** Its game server, for its Session-Managers and Administrators only. */
  val server: GameServerState? = null,
)

/** A finished session as it was when it finished: the dashboard's summary and every slot's details. */
@Serializable
data class ArchivedSession(
  val summary: Summary,
  val details: List<PlayerDetail> = emptyList(),
  val finishedBy: String? = null,
)

/** `POST /api/admin/sessions/{id}/keep`: [keep] disables the session's auto-deletion. */
@Serializable
data class KeepRequest(val keep: Boolean)

/** One sphere of the spoiler's playthrough: what is reachable (and needed) at that step, "Location (Slot): Item (Slot)". */
@Serializable
data class Sphere(val number: Int, val entries: List<String>)

/** `GET /api/sessions/{id}/spoiler`: the seed's spoiler log, and its playthrough sphere by sphere (empty if not found in it). */
@Serializable
data class Spoiler(val seedName: String, val spheres: List<Sphere> = emptyList(), val text: String)

/** What an admin can cheat. */
@Serializable
enum class CheatAction {
  /** Gives the slot an item (`/send`). */
  @SerialName("item") ITEM,
  /** Checks a location of the slot (`/send_location`): its item goes to whoever it belongs to. */
  @SerialName("location") LOCATION,
  /** Sends the slot's remaining items to their owners (`/release`). */
  @SerialName("release") RELEASE,
  /** Gives the slot its items that are still in other slots (`/collect`). */
  @SerialName("collect") COLLECT,
  /** A free hint for one of the slot's items (`/hint`), if the session allows cheat hints. */
  @SerialName("hint") HINT,
  /** A free hint for what is at one of the slot's locations (`/hint_location`), likewise. */
  @SerialName("hint_location") HINT_LOCATION,
}

/** `POST /api/admin/cheat`; [value]: the item or location, for the actions that need one. */
@Serializable
data class CheatRequest(val action: CheatAction, val slot: String, val value: String? = null)

/** One cheat, as logged. */
@Serializable
data class CheatLogEntry(val ts: Long, val admin: String, val action: CheatAction, val slot: String, val value: String? = null)

/**
 * `GET /api/admin/cheat`: the slots to cheat for (those with locations left to check), whether hints are allowed, and
 * the session's cheats so far.
 */
@Serializable
data class CheatView(
  val slots: List<String> = emptyList(),
  /** Every slot has checked all its locations (100 %): nothing is left to cheat. */
  val done: Boolean = false,
  val cheatHints: Boolean = false,
  /** The game server runs and the session isn't finished. */
  val available: Boolean = false,
  val log: List<CheatLogEntry> = emptyList(),
)

/**
 * A file of the seed that belongs to a slot, for its players to download: a ROM patch (`.apsm`, `.aplttp`, …), a mod
 * (Factorio's zip), other output of the slot's game. Never the multidata, a spoiler or readable text.
 */
@Serializable
data class SlotFile(
  /** As in the seed, e.g. `AP_7213_P3_Alice-SM.apsm`. */
  val name: String,
  /** The slot's number in the seed. */
  val slot: Int,
  /** Bytes. */
  val size: Long = 0,
  /** The slot's name and game; filled in by the tracker. */
  val slotName: String? = null,
  val game: String? = null,
)

/** `GET /api/sessions/{id}/files`: the files the user may download (gateway → tracker: every slot's). */
@Serializable
data class SlotFiles(val files: List<SlotFile> = emptyList())

/** `GET /api/admin/cheat/names?slot=`: the items of the slot's game, and its locations not checked yet. */
@Serializable
data class CheatNames(val items: List<String> = emptyList(), val locations: List<String> = emptyList())

/** `GET /api/admin/sessions/{id}`: one session, what a new one can use, and who has a login. */
@Serializable
data class AdminSessionView(
  val session: SessionInfo? = null,
  /** Archipelago releases the gateway can run, newest first. */
  val apVersions: List<String> = emptyList(),
  val users: List<String> = emptyList(),
  /** The accounts a new session can be played by, invited ones too. */
  val people: List<UserRef> = emptyList(),
)

/**
 * `GET /api/manage` (`/api/admin/sessions` for Administrators): the sessions the user manages (an Administrator: every
 * session) with their game servers, what a new one can use, who has a login, and how many game servers can run at once
 * ([capacity]; finished sessions don't count).
 */
@Serializable
data class AdminOverview(
  val sessions: List<SessionEntry> = emptyList(),
  val apVersions: List<String> = emptyList(),
  val users: List<String> = emptyList(),
  /** The accounts a new session can be played by: by name (Administrators also see invited ones, by address). */
  val people: List<UserRef> = emptyList(),
  val capacity: Int = 0,
  /** A free ID for the next session, e.g. `friday-chaos`. */
  val suggestedId: String = "",
  /** Every session ID in use, also of sessions the user doesn't see. */
  val taken: List<String> = emptyList(),
  /** How many game servers run, of every session. */
  val inUse: Int = 0,
  /** How many more running sessions the user may create; `null`: no limit (an Administrator). */
  val left: Int? = null,
)

/** Tracker → gateway: generate a seed from these YAMLs; World Locks is added from [locked]. */
@Serializable
data class GenerateRequest(
  val yamls: List<UploadedYaml>,
  val locked: List<String> = emptyList(),
  val earlyKeys: Boolean = false,
  val apVersion: String,
  /** The games of the YAMLs, to point at the one that failed. */
  val games: List<String> = emptyList(),
)

/**
 * The generated seed (now the session's seed, in its directory), or why it failed: the last lines of Generate.py's output, and
 * the games that may be the cause.
 */
@Serializable
data class GenerateResult(
  val seedName: String? = null,
  val seedFile: String? = null,
  val slots: List<String> = emptyList(),
  val log: List<String> = emptyList(),
  val suspects: List<Suspect> = emptyList(),
)

/**
 * A game that may have made a generation fail: named in the error, or without a tested apworld. [apworld]: its file,
 * if it isn't bundled; [from]: the release that apworld was uploaded for, if it is another one.
 */
@Serializable
data class Suspect(val game: String, val reason: String, val apworld: String? = null, val from: String? = null)

/** Where a game of a release comes from. */
@Serializable
enum class GameSource {
  /** Part of the Archipelago release (or of the gateway image, like World Locks). */
  @SerialName("bundled") BUNDLED,
  /** An apworld in the server's `custom_worlds/` directory. */
  @SerialName("manual") MANUAL,
  /** An apworld uploaded in the admin panel. */
  @SerialName("uploaded") UPLOADED,
}

/** A game an Archipelago release can generate and serve. */
@Serializable
data class GameInfo(
  val game: String,
  val source: GameSource,
  /** The apworld file, if it isn't bundled. */
  val apworld: String? = null,
  /** Its Python module (`worlds.hk`), to find it in an error. */
  val module: String? = null,
)

/** An apworld that didn't load, and why (as Archipelago says). [name]: the game or the file. */
@Serializable
data class FailedLoad(val name: String, val reason: String)

/** An apworld file of a release's library: uploaded in the admin panel, or put in `custom_worlds/` by hand. */
@Serializable
data class ApworldFile(
  val file: String,
  val release: String,
  val source: GameSource,
  /** Copied from the library of this release ("try with"): not tested with [release]. */
  val from: String? = null,
  val size: Long = 0,
)

/** One Archipelago release of the gateway: its games, and the apworlds that didn't load. */
@Serializable
data class ReleaseInfo(
  val version: String,
  val games: List<GameInfo> = emptyList(),
  val failed: List<FailedLoad> = emptyList(),
  /** The games couldn't be listed (Python failed). */
  val error: String? = null,
)

/** `GET /api/admin/apworlds`: the gateway's releases (newest first) and the apworld library of each. */
@Serializable
data class ArchipelagoInfo(val releases: List<ReleaseInfo> = emptyList(), val apworlds: List<ApworldFile> = emptyList())

/** `POST /api/admin/apworlds`: an apworld file for a release's library, base64. */
@Serializable
data class ApworldUpload(val release: String, val fileName: String, val base64: String)

/** `POST /api/admin/apworlds/try`: copies an apworld of [from]'s library to [release]'s, to try it there. */
@Serializable
data class ApworldTry(val release: String, val from: String, val file: String)

/** What an upload (or a try) brought: the games the file adds, or why it didn't load. */
@Serializable
data class ApworldResult(val file: String, val games: List<String> = emptyList(), val error: String? = null)

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
