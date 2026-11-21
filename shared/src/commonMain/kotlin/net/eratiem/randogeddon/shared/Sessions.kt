package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Archipelago's plando modules: what a YAML may set by hand in the generation instead of leaving it to chance. Their
 * names are Generate.py's (`--plando`); only these are ever passed to it.
 */
@Serializable
enum class PlandoModule {
  @SerialName("bosses") BOSSES,
  @SerialName("connections") CONNECTIONS,
  @SerialName("texts") TEXTS,
  @SerialName("items") ITEMS;

  /** As `--plando` names it. */
  val argument: String get() = name.lowercase()

  companion object {
    /** Archipelago's own default: all but the items. */
    val DEFAULT: Set<PlandoModule> = setOf(BOSSES, CONNECTIONS, TEXTS)
  }
}

/**
 * A session's options, set when it is created: its game server's (the defaults are those of `.env.example`) and its
 * generation's ([plando], [skipBalancing], [race]).
 */
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
  /** The generation: the plando modules its YAMLs may use. */
  val plando: Set<PlandoModule> = PlandoModule.DEFAULT,
  /** The generation: without Archipelago's progression balancing. */
  val skipBalancing: Boolean = false,
  /** The generation: race mode (a seed nobody can reproduce, flagged as a race for the games, no spoiler log). */
  val race: Boolean = false,
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
  /** A hosted slot: the game runs as a server of its own, which its players join with their games; nobody plays it. */
  val hosted: Boolean = false,
  /**
   * A hosted Minecraft Dig slot whose server Randogeddon runs itself, reached through Randogeddon Bridge's tunnel
   * (Administrators only); else its Session-Managers run their own.
   */
  val direct: Boolean = false,
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
  /**
   * Instead of [yamls]: the token of a seed generated elsewhere that this Administrator uploaded before
   * ([SeedInfo.token]). Its slots are the seed's; it has no locks, and nothing is generated.
   */
  val seed: String? = null,
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
  /** Its game server keeps running without players (an Administrator's choice); else it sleeps when idle. */
  val keepRunning: Boolean = false,
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
  /** Its spoiler log has its playthrough (completed after the seed): until then no spoiler log, no hints, no cheats. */
  val spoilerReady: Boolean = true,
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

/** `PUT /api/admin/sessions/{id}/keep-running`: its game server doesn't sleep without players. */
@Serializable
data class KeepRunningRequest(val keepRunning: Boolean)

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
  /** The generation's settings (see [SessionOptions]). */
  val plando: Set<PlandoModule> = PlandoModule.DEFAULT,
  val skipBalancing: Boolean = false,
  val race: Boolean = false,
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
  /** It took longer than the gateway allows and was stopped after this many minutes (the log is how far it got). */
  val stoppedAfterMinutes: Int? = null,
  /** It was cancelled (by its creator or an Administrator) before it had a seed. */
  val cancelled: Boolean = false,
)

/**
 * The stage a new session is in while it is made, in their order: the tracker checks what was asked ([CHECKING]),
 * the gateway generates the seed (waiting for another generation first, [WAITING]; then as Archipelago's Generate.py
 * logs it, [STARTING] to [DONE]), the tracker sets the session up ([SAVING]) and has its game server started
 * ([SERVER]).
 */
enum class GenerationStage {
  CHECKING, WAITING,
  STARTING, CREATING, ITEMS, RULES, PLANDO, PREFILL, FILLING, BALANCING, PREOUTPUT, OUTPUT, PLAYTHROUGH, ARCHIVE, DONE,
  SAVING, SERVER;

  /** The step it belongs to. */
  val step: GenerationStep
    get() = when (this) {
      CHECKING -> GenerationStep.CHECK
      SAVING -> GenerationStep.SET_UP
      SERVER -> GenerationStep.START
      else -> GenerationStep.GENERATE
    }
}

/** The four steps of making a session, as the form shows them above the bar. */
enum class GenerationStep { CHECK, GENERATE, SET_UP, START }

/**
 * How far a new session's generation is: its [stage], about [percent] in all (each stage has a share; the fill counts
 * itself up, the playthrough reports nothing on its way). [cpu]: how much of one core Generate.py used since it was
 * asked last (a working one keeps one busy; `null`: no process right now, as while it waits or the tracker works).
 */
@Serializable
data class GenerationProgress(val stage: GenerationStage, val percent: Int, val cpu: Int? = null)

/** Where a new session's creation is: generating (and setting up), done, failed or cancelled. */
@Serializable
enum class CreationState {
  @SerialName("running") RUNNING,
  @SerialName("done") DONE,
  @SerialName("failed") FAILED,
  @SerialName("cancelled") CANCELLED,
}

/**
 * `POST /api/sessions`, `GET /api/sessions/new/creation[/{id}]`: a new session being created, in the background (its
 * page can be closed meanwhile): how far it is ([progress]), and at its end the session ([created]) or why it failed
 * ([error], Generate.py's output as its detail). Its log: `GET …/creation/{id}/log`, all of it as a file `…/log.txt`.
 */
@Serializable
data class Creation(
  val id: String,
  val state: CreationState,
  val progress: GenerationProgress? = null,
  val created: CreateSessionResult? = null,
  val error: ApiError? = null,
  /** Who creates it (an Administrator sees others' too). */
  val creator: String? = null,
  /** Epoch ms. */
  val startedAt: Long = 0,
)

/**
 * `GET /api/sessions/new`: what a new session can use: the releases, a free ID, the accounts to pick by name, and how
 * many running sessions the user may still create ([left]; `null`: no limit, an Administrator).
 */
@Serializable
data class NewSessionView(
  val apVersions: List<String> = emptyList(),
  val suggestedId: String = "",
  val people: List<UserRef> = emptyList(),
  val left: Int? = null,
  /** The game servers that can run at once, and how many do. */
  val capacity: Int = 0,
  val running: Int = 0,
  /** The session IDs taken already. */
  val taken: List<String> = emptyList(),
)
