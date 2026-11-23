package net.eratiem.randogeddon.server.admin

import io.ktor.http.HttpStatusCode
import java.lang.invoke.MethodHandles
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.database.SessionEventStore
import net.eratiem.randogeddon.database.SessionFinishStore
import net.eratiem.randogeddon.database.SessionRoleStore
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.SessionStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.account.PasswordGenerator
import net.eratiem.randogeddon.server.account.UnwantedWords
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.config.english
import net.eratiem.randogeddon.server.config.toApiError
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.require
import net.eratiem.randogeddon.server.session.PlayerYamls
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.server.session.completed
import net.eratiem.randogeddon.server.session.deletesAt
import net.eratiem.randogeddon.server.session.reopenUntil
import net.eratiem.randogeddon.server.util.ExpiringMap
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.server.util.secureRandom
import net.eratiem.randogeddon.shared.AdminOverview
import net.eratiem.randogeddon.shared.AdminSessionView
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CreateSessionRequest
import net.eratiem.randogeddon.shared.CreateSessionResult
import net.eratiem.randogeddon.shared.Creation
import net.eratiem.randogeddon.shared.CreationState
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.GenerateResult
import net.eratiem.randogeddon.shared.GenerationProgress
import net.eratiem.randogeddon.shared.GenerationStage
import net.eratiem.randogeddon.shared.InspectRequest
import net.eratiem.randogeddon.shared.SeedInfo
import net.eratiem.randogeddon.shared.SeedUpload
import net.eratiem.randogeddon.shared.YamlWorld
import net.eratiem.randogeddon.shared.InspectResult
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.NewSessionView
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.SlotPlayersRequest
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.shared.MINECRAFT_DIG
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.SessionIds
import net.eratiem.randogeddon.shared.SessionInfo
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.WorldPlan
import org.koin.core.annotation.Single

private val logger = logger(MethodHandles.lookup().lookupClass())

/** The World Locks slot's name in a seed made in the admin panel (gateway/.../Archipelago.kt, locksYaml). */
private const val LOCKS_SLOT = "Locks"

/** A request the admin panel can't carry out, with what to tell the admin. */
class SessionProblem(val status: HttpStatusCode, val refusal: Refusal) : Exception(refusal.english()) {
  constructor(status: HttpStatusCode, problem: Problem, vararg args: Pair<String, Any?>) : this(status, Refusal.of(problem, *args))
}

/** How long an ended creation stays to be asked about (its page shows the end, the log can be downloaded). */
private const val CREATION_KEPT = 60 * 60_000L

/** As long as the gateway keeps an uploaded seed for its session; the zip's 64 MB as base64. */
private val SEED_KEPT = 1.hours
private const val MAX_SEED_BASE64 = 64 * 1024 * 1024 / 3 * 4 + 4

/** A creation was cancelled: the gateway ended its generation. */
private class CreationCancelled : Exception("Cancelled")

/** One new session's creation, running in the background, and how it ended. */
private class CreationJob(val id: String, val creatorId: Uuid, val creator: String, val startedAt: Long) {
  @Volatile
  var state = CreationState.RUNNING

  @Volatile
  var created: CreateSessionResult? = null

  @Volatile
  var error: ApiError? = null

  @Volatile
  var endedAt: Long? = null

  @Volatile
  var job: Job? = null

  /** Its creator, and Administrators. */
  fun visibleTo(user: User): Boolean = user.id == creatorId || user.administrator

  fun view(progress: GenerationProgress?) = Creation(id, state, progress, created, error, creator, startedAt)
}

/** Two words for a session ID that is easy to say: `brave-otter`, `friday-chaos`. */
private val ID_WORDS = listOf(
  listOf(
    "brave", "quiet", "lucky", "swift", "sleepy", "bold", "clever", "fuzzy", "golden", "hidden", "jolly", "mighty",
    "nimble", "rapid", "silent", "sunny", "wild", "witty", "cosmic", "friday", "midnight", "stormy", "frozen", "crimson",
  ),
  listOf(
    "otter", "falcon", "badger", "comet", "dragon", "ember", "fox", "gecko", "harbor", "island", "lantern", "meadow",
    "nebula", "owl", "pixel", "quest", "raven", "summit", "tiger", "voyage", "chaos", "wizard", "yeti", "zephyr",
  ),
)

/** The part of a new session's bar that is the seed's generation, in percent. */
private const val GENERATING_FROM = 3
private const val GENERATING_TO = 92

/** A session ID from [ID_WORDS] that [taken] says is free; with a number after it if the pairs run out. */
internal fun suggestId(random: Random, taken: (String) -> Boolean): String {
  repeat(50) {
    val id = "${ID_WORDS[0].random(random)}-${ID_WORDS[1].random(random)}"
    if (!taken(id)) return id
  }
  return generateSequence(2) { it + 1 }.map { "session-$it" }.first { !taken(it) }
}

/**
 * Creating and deleting sessions; as many run at once as the gateway has game servers for. Every user can create one
 * and becomes its Session-Manager (as many running ones at once as the Administrators allow). A new session's seed is
 * generated by the gateway; then its slots get their players and slot passwords, and its game server starts. Deleting
 * keeps the accounts: they belong to people, who will play again.
 */
@Single
class SessionAdmin(
  private val config: TrackerConfig,
  private val gameServer: GameServerClient,
  private val users: UserStore,
  private val setups: SessionSetupStore,
  private val slotPasswords: SlotPasswordStore,
  private val store: SessionStore,
  private val finishes: SessionFinishStore,
  private val sessions: LiveSessions,
  private val list: SessionList,
  private val archives: Archives,
  private val spoilers: Spoilers,
  private val roles: SessionRoleStore,
  private val settings: AppSettingsStore,
  private val limit: SessionLimit,
  private val gameServers: GameServerLimit,
  private val events: SessionEventStore,
) {
  /**
   * The sessions [user] manages with their game servers (an Administrator: every session) or supports (their role
   * says which: support only gets what support may do), and what a new one needs. Only Administrators get the
   * invited accounts (by address) and the users' names.
   */
  suspend fun overview(user: User): AdminOverview {
    // As the sessions were read last (every 10 s, and right after each change made here): the panel asks every 15 s
    val taken = takenIds(fresh = false)
    val all = sessions.all()
    val managed = if (user.administrator) all else all.filter { user.id in it.managers() || it.roleOf(user) == SessionRole.SUPPORT }
    return AdminOverview(
      sessions = managed.map { list.entry(it, it.personOf(user), admin = true, role = it.roleOf(user)) },
      apVersions = runCatching { gameServer.versions() }.getOrDefault(emptyList()),
      users = if (user.administrator) users.active().map { it.name }.sorted() else emptyList(),
      people = if (user.administrator) people() else users.pickable(),
      capacity = gameServers.limit(),
      suggestedId = suggestId(random) { it in taken },
      taken = taken.sorted(),
      inUse = all.count { it.server?.port != null },
      left = if (user.administrator) null else limit.of(user.id)?.left,
    )
  }

  /**
   * What a new session of [user] can use: the releases, a free ID, the accounts to pick by name, and how many more
   * running sessions they may create.
   */
  suspend fun newSession(user: User): NewSessionView {
    sessions.refresh()
    val taken = takenIds()
    val servers = runCatching { gameServer.sessions() }.getOrNull()
    return NewSessionView(
      apVersions = runCatching { gameServer.versions() }.getOrDefault(emptyList()),
      suggestedId = suggestId(random) { it in taken },
      people = users.pickable(),
      left = if (user.administrator) null else limit.of(user.id)?.left,
      capacity = gameServers.limit(),
      running = servers?.sessions?.count { it.port != null } ?: 0,
      taken = taken.sorted(),
    )
  }

  /** One session as the admin panel shows it. */
  suspend fun view(id: String, user: User): AdminSessionView {
    val live = sessions.require(id)
    val setup = live.setup
    val finish = live.finish
    val finishedAt = finish?.finishedAt
    val totals = live.summary().totals
    val percent = totals.percent
    val info = SessionInfo(
      id = id,
      seedName = live.seedName,
      apVersion = setup?.apVersion,
      createdAt = live.record.createdAt.toEpochMilli(),
      options = live.options,
      worlds = worldPlans(live),
      finishesAt = finish?.dueAt?.toEpochMilli(),
      finishedAt = finishedAt?.toEpochMilli(),
      reopenUntil = finishedAt?.let { reopenUntil(it, completed(percent))?.toEpochMilli() },
      keep = finish?.keep == true,
      keepRunning = setup?.keepRunning == true,
      deletesAt = finishedAt?.let { deletesAt(it, finish.keep, config.sessionRetentionDays, finish.retainedSince)?.toEpochMilli() },
      percent = percent,
      checked = totals.checked,
      total = totals.total,
    )
    return AdminSessionView(
      session = info,
      apVersions = runCatching { gameServer.versions() }.getOrDefault(emptyList()),
      users = users.active().map { it.name }.sorted(),
      // Invited accounts (by their address) only for Administrators, as for a new session
      people = if (user.administrator) people() else users.pickable(),
    )
  }

  /**
   * The slots of the YAMLs and what's wrong with them, including games the release has no apworld for. If the gateway
   * can't list the release's games, that check is left to Generate.py.
   */
  suspend fun inspect(request: InspectRequest): InspectResult {
    val inspected = PlayerYamls.inspect(request.yamls)
    val release = runCatching { gameServer.archipelago() }.getOrNull()?.releases?.let { releases ->
      releases.firstOrNull { it.version == request.apVersion } ?: releases.firstOrNull().takeIf { request.apVersion == null }
    }
    if (release == null || release.error != null) return inspected
    val missing = PlayerYamls.missingGames(inspected.worlds, release.games.map { it.game }.toSet())
    return inspected.copy(problems = inspected.problems + missing.map { game ->
      Refusal.of(Problem.NO_APWORLD, "game" to game, "version" to release.version).toApiError()
    })
  }

  /** An uploaded seed waiting for its session: who uploaded it, what the gateway found in it. */
  private class WaitingSeed(val by: Uuid, val info: SeedInfo)

  /**
   * The uploaded seeds by the gateway's token; it keeps them as long ([SEED_KEPT]). Read whatever their age: the
   * gateway says whether it still has one.
   */
  private val seeds = ExpiringMap<String, WaitingSeed>(SEED_KEPT)

  /**
   * A seed generated elsewhere, for a new session of this Administrator: the gateway checks the zip and its
   * multidata and keeps it; the form then assigns its slots and creates the session with its token.
   */
  suspend fun takeSeed(upload: SeedUpload, user: User): SeedInfo {
    if (!user.administrator) throw SessionProblem(HttpStatusCode.Forbidden, Problem.ONLY_ADMINISTRATORS_SEED)
    if (upload.base64.length > MAX_SEED_BASE64) throw SessionProblem(HttpStatusCode.PayloadTooLarge, Problem.SEED_TOO_LARGE)
    val info = gameServer.uploadSeed(upload)
    if (setups.of(info.seedName) != null) {
      runCatching { gameServer.dropSeed(info.token) }
      throw SessionProblem(HttpStatusCode.Conflict, Problem.SEED_IN_USE)
    }
    seeds.put(info.token, WaitingSeed(user.id, info))
    logger.info("{} uploaded seed {} ({}, {} slots)", user.name, info.seedName, upload.fileName.take(80), info.slots.size)
    return info
  }

  /** The Administrator took an uploaded seed out of the form again: the gateway needn't keep it. */
  suspend fun dropSeed(token: String, user: User) {
    val seed = seeds.peek(token)?.takeIf { it.by == user.id } ?: return
    seeds.remove(token)
    runCatching { gameServer.dropSeed(seed.info.token) }
  }

  /** Who creates a session right now (its seed generates for minutes); each one at a time. */
  private val creating = ConcurrentHashMap.newKeySet<Uuid>()

  /** The creations running, and those that ended within [CREATION_KEPT] (until their page has seen the end). */
  private val creations = ConcurrentHashMap<String, CreationJob>()

  /** The creations run here, not in the request that started them: its page may be closed meanwhile. */
  private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * A new session of [creator], who becomes its Session-Manager. One at a time each: the limits (theirs, and the game
   * servers') and the form are checked now (a refusal answers at once); the seed generates and the session is set up
   * in the background ([creation] says how far, [cancel] ends it). It only counts once it has a seed.
   */
  suspend fun create(request: CreateSessionRequest, creator: User): Creation {
    if (!creating.add(creator.id)) throw SessionProblem(HttpStatusCode.Conflict, Problem.CREATING_ALREADY)
    val id = request.id.trim()
    // Whether this call reserved the ID: only then are its reservation and stage its own to remove
    var reserved = false
    val generate = try {
      prepare(request, creator) { reserved = true }
    } catch (e: Throwable) {
      creating.remove(creator.id)
      if (reserved) {
        stages.remove(id)
        reservedIds.remove(id)
      }
      throw e
    }
    val now = System.currentTimeMillis()
    creations.entries.removeIf { (_, job) -> job.endedAt?.let { now - it > CREATION_KEPT } == true }
    val creation = CreationJob(id, creator.id, creator.name.orEmpty(), now)
    creations[id] = creation
    creation.job = background.launch {
      try {
        creation.created = generate()
        creation.state = CreationState.DONE
      } catch (e: CreationCancelled) {
        creation.state = CreationState.CANCELLED
        logger.info("{}'s creation of session {} was cancelled", creator.name, id)
      } catch (e: CancellationException) {
        creation.state = CreationState.CANCELLED
        logger.info("{}'s creation of session {} was cancelled", creator.name, id)
      } catch (e: SessionProblem) {
        creation.error = e.refusal.toApiError()
        creation.state = CreationState.FAILED
      } catch (e: GameServerUnavailable) {
        creation.error = Refusal.of(Problem.GATEWAY_ANSWERED, "status" to e.status.value, detail = e.message).toApiError()
        creation.state = CreationState.FAILED
      } catch (e: Exception) {
        logger.warn("Creating session {} failed", id, e)
        creation.error = Refusal(Problem.NOT_POSSIBLE, detail = e.message).toApiError()
        creation.state = CreationState.FAILED
      } finally {
        creation.endedAt = System.currentTimeMillis()
        creating.remove(creator.id)
        stages.remove(id)
        reservedIds.remove(id)
      }
    }
    return creation.view(generationProgress(id))
  }

  /** New session [id]'s creation, for [user] (its creator or an Administrator); `null`: none (or long ago). */
  suspend fun creation(id: String, user: User): Creation? =
    creations[id]?.takeIf { it.visibleTo(user) }?.let { it.view(if (it.state == CreationState.RUNNING) generationProgress(id) else null) }

  /** [user]'s own newest creation (running, or ended within [CREATION_KEPT]), for their page after a reload. */
  suspend fun ownCreation(user: User): Creation? =
    creations.values.filter { it.creatorId == user.id }.maxByOrNull { it.startedAt }?.let { creation(it.id, user) }

  /** The lines of new session [id]'s generation log after [since]; `null`: none for [user]. */
  suspend fun creationLog(id: String, since: Long, user: User): LogLines? {
    if (creations[id]?.visibleTo(user) != true) return null
    return gameServer.generationLog(id, since)
  }

  /** New session [id]'s whole generation log as text (with the command that ran); `null`: none for [user]. */
  suspend fun creationLogText(id: String, user: User): String? {
    if (creations[id]?.visibleTo(user) != true) return null
    return gameServer.generationLogText(id)
  }

  /**
   * Cancels new session [id]'s creation (its creator or an Administrator): the gateway ends Generate.py, and the ID is
   * free again. Too late once the seed is there. One that ended is forgotten (its page has seen the end).
   */
  suspend fun cancel(id: String, user: User) {
    val creation = creations[id]?.takeIf { it.visibleTo(user) }
      ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
    if (creation.state != CreationState.RUNNING) {
      creations.remove(id)
      return
    }
    if (stages[id]?.stage in setOf(GenerationStage.SAVING, GenerationStage.SERVER)) {
      throw SessionProblem(HttpStatusCode.Conflict, Problem.CANCEL_TOO_LATE)
    }
    logger.info("{} cancels the creation of session {}", user.name, id)
    // Generating: the gateway ends it, and the creation ends with the answer. Not there yet: it ends here, and the
    // gateway is told once more in a moment (in case its request was on the way)
    if (!runCatching { gameServer.cancelGeneration(id) }.getOrDefault(false)) {
      creation.job?.cancel()
      background.launch {
        delay(2.seconds)
        runCatching { gameServer.cancelGeneration(id) }
      }
    }
  }

  /**
   * Refuses [id] as a new session's ID: its form, a word nobody wants in one, or taken. Asked while it is typed
   * too, so the form says so before anything is generated.
   */
  suspend fun checkId(id: String) {
    SessionIds.problem(id)?.let { throw SessionProblem(HttpStatusCode.BadRequest, it) }
    if (UnwantedWords.hit(id)) throw SessionProblem(HttpStatusCode.BadRequest, Problem.ID_UNWANTED)
    if (id in takenIds()) throw SessionProblem(HttpStatusCode.Conflict, Problem.ID_TAKEN, "id" to id)
  }

  /**
   * The IDs of the new sessions being created, reserved before anything is generated: a second creation of one is
   * refused from then on, until the first ends (then the session has it, or it is free again).
   */
  private val reservedIds = ConcurrentHashMap.newKeySet<String>()

  /**
   * Checks a new session and reserves its ID ([reservedIds]; [reserved] is told so); what generates its seed and sets
   * it up after (to run in the background).
   */
  private suspend fun prepare(request: CreateSessionRequest, creator: User, reserved: () -> Unit): suspend () -> CreateSessionResult {
    val admin = creator.name
    if (!creator.administrator) {
      limit.of(creator.id)?.takeIf { it.reached }?.let {
        throw SessionProblem(
          HttpStatusCode.Forbidden,
          if (it.limit == 0) Refusal(Problem.ONLY_ADMINISTRATORS_CREATE)
          else Refusal.of(Problem.SESSION_LIMIT, "running" to it.running, "limit" to it.limit),
        )
      }
    }
    val id = request.id.trim()
    checkId(id)
    // At once, so that of two creations of the same ID only one gets past here
    if (!reservedIds.add(id)) throw SessionProblem(HttpStatusCode.Conflict, Problem.ID_TAKEN, "id" to id)
    reserved()
    stages[id] = GenerationProgress(GenerationStage.CHECKING, 1)
    // The sessions others are creating right now will take a game server each too
    gameServers.check(null, starting = creating.size - 1)
    // A seed generated elsewhere (uploaded before, by this Administrator) instead of YAMLs: its slots as they are
    val uploaded = request.seed?.let { token ->
      if (!creator.administrator) throw SessionProblem(HttpStatusCode.Forbidden, Problem.ONLY_ADMINISTRATORS_SEED)
      seeds.peek(token)?.takeIf { it.by == creator.id }?.info ?: throw SessionProblem(HttpStatusCode.Gone, Problem.SEED_GONE)
    }
    val found = if (uploaded != null) uploaded.slots.map { YamlWorld(id, it.name, it.game) } else {
      val inspected = inspect(InspectRequest(request.yamls, request.apVersion))
      inspected.problems.firstOrNull()?.let { throw SessionProblem(HttpStatusCode.BadRequest, Refusal(it.code ?: Problem.NOT_POSSIBLE, it.args)) }
      inspected.worlds
    }
    if (found.isEmpty()) throw SessionProblem(HttpStatusCode.BadRequest, Problem.NO_SLOTS)
    val names = found.map { it.name }.toSet()
    request.worlds.map { it.name }.filter { it !in names }.takeIf { it.isNotEmpty() }?.let {
      throw SessionProblem(HttpStatusCode.BadRequest, Problem.NOT_IN_YAMLS, "names" to it.joinToString())
    }
    val plans = found.associate { world -> world.name to (request.worlds.firstOrNull { it.name == world.name } ?: WorldPlan(world.name)) }
      // A slot open to all names nobody: every player of the session plays it (or gets it, hosted). A hosted slot not
      // open goes to the players named for it, who join its game's server, not Archipelago
      .mapValues { (_, plan) -> if (plan.open) plan.copy(userIds = emptyList()) else plan }
    // Who plays: accounts by ID, invited ones too (they sign up later; the session shows them meanwhile)
    val accounts = users.accounts().associateBy { it.id.toString() }
    val people = plans.values.flatMap { it.userIds }.distinct()
    people.filter { it !in accounts }.takeIf { it.isNotEmpty() }?.let {
      throw SessionProblem(HttpStatusCode.BadRequest, Problem.NO_SUCH_USERS, "ids" to it.joinToString())
    }
    // Randogeddon runs a hosted Minecraft Dig slot's server only when an Administrator says so
    val direct = plans.values.filter { it.hosted && it.direct }.map { it.name }.toSet()
    if (direct.isNotEmpty() && !creator.administrator) throw SessionProblem(HttpStatusCode.Forbidden, Problem.ONLY_ADMINISTRATORS)
    direct.filter { name -> found.none { it.name == name && it.game == MINECRAFT_DIG } }.takeIf { it.isNotEmpty() }?.let {
      throw SessionProblem(HttpStatusCode.BadRequest, Problem.NOT_POSSIBLE)
    }
    // Every slot is played by someone, hosted, or open to all; and a slot everyone plays needs someone who plays
    val open = plans.values.filter { it.open }.map { it.name }.toSet()
    plans.values.filter { !it.hosted && !it.open && it.userIds.isEmpty() }.map { it.name }.takeIf { it.isNotEmpty() }?.let {
      throw SessionProblem(HttpStatusCode.BadRequest, Problem.SLOT_WITHOUT_PLAYER, "slots" to it.joinToString())
    }
    if (people.isEmpty()) plans.values.filter { it.open && !it.hosted }.map { it.name }.takeIf { it.isNotEmpty() }?.let {
      throw SessionProblem(HttpStatusCode.BadRequest, Problem.SLOT_WITHOUT_PLAYER, "slots" to it.joinToString())
    }
    val versions = gameServer.versions()
    val version = uploaded?.release ?: request.apVersion ?: versions.firstOrNull()
      ?: throw SessionProblem(HttpStatusCode.ServiceUnavailable, Problem.NO_RELEASE)
    if (version !in versions) {
      throw SessionProblem(HttpStatusCode.BadRequest, Problem.RELEASE_UNSUPPORTED, "version" to version, "versions" to versions.joinToString())
    }

    val locked = if (uploaded != null) emptyList() else when (val open = request.options.openSlots) {
      null -> plans.values.filter { it.locked }.map { it.name }
      else -> if (plans.values.any { it.random }) pickMixed(plans.values.toList(), open, random) else pickLocked(plans.keys.toList(), open, random)
    }
    // Someone has to start: the keys are found in the open slots
    if (locked.size >= plans.size) throw SessionProblem(HttpStatusCode.BadRequest, Problem.ONE_OPEN)
    logger.info("{} creates session {}: {} slots, {} players, Archipelago {}", admin, id, plans.size, people.size, version)
    val games = found.flatMap { it.game.split(" / ") }.distinct()
    return {
      val seed = if (uploaded != null) {
        // Nothing to generate: the gateway makes the uploaded seed the session's
        stages[id] = GenerationProgress(GenerationStage.SAVING, 50)
        val installed = gameServer.installSeed(id, uploaded.token) ?: throw SessionProblem(HttpStatusCode.Gone, Problem.SEED_GONE)
        seeds.remove(uploaded.token)
        installed.seedName
      } else {
        val generated = gameServer.generate(
          id,
          GenerateRequest(
            request.yamls, locked, request.options.earlyKeys, version, games,
            plando = request.options.plando, skipBalancing = request.options.skipBalancing, race = request.options.race,
          ),
        )
        if (generated.cancelled) throw CreationCancelled()
        generated.seedName ?: throw SessionProblem(HttpStatusCode.UnprocessableEntity, failure(generated))
      }
      stages[id] = GenerationProgress(GenerationStage.SAVING, 93)

      val created = setups.create(
        id, seed, version, ApiJson.encodeToString(SessionOptions.serializer(), request.options),
        plans.mapValues { (_, plan) -> plan.userIds.distinct().mapNotNull { accounts[it]?.id } },
        hosted = plans.filterValues { it.hosted }.keys,
        // It counts towards the creator's limit while it runs
        createdBy = creator.id,
        direct = direct,
        open = open,
      )
      if (!created) {
        // The same seed became another session meanwhile (uploaded twice at once): that one stays as it is, and the
        // gateway's files of this one go
        runCatching { gameServer.delete(id, true) }
        throw SessionProblem(HttpStatusCode.Conflict, Problem.SEED_IN_USE)
      }
      slotPasswords.ensure(seed, plans.keys + listOfNotNull(LOCKS_SLOT.takeIf { locked.isNotEmpty() }), { PasswordGenerator.generate() })
      // No one connects a locked slot until its key is found (the tracker opens it then)
      slotPasswords.setLocked(seed, locked.toSet())
      stages[id] = GenerationProgress(GenerationStage.SERVER, 97)
      gameServer.control(id, "start")
      sessions.refresh()
      roles.add(id, creator.id, SessionRole.MANAGER.code, creator.name, creator.name)
      sessions.refresh()
      logger.info("{} created session {} (seed {}) and manages it", admin, id, seed)
      CreateSessionResult(id, seed, locked)
    }
  }

  /** The tracker's own stages of the sessions being created (checking before the gateway generates, setting up after). */
  private val stages = ConcurrentHashMap<String, GenerationProgress>()

  /**
   * How far new session [id] is; `null`: none is being created. The gateway's part (the seed) takes the bar's
   * middle, the tracker's checks before and its setting up after the ends.
   */
  suspend fun generationProgress(id: String): GenerationProgress? {
    val own = stages[id]
    if (own != null && own.stage != GenerationStage.CHECKING) return own
    val seed = runCatching { gameServer.generationProgress(id) }.getOrNull()
    return seed?.let { it.copy(percent = GENERATING_FROM + it.percent * (GENERATING_TO - GENERATING_FROM) / 100) } ?: own
  }

  /** The accounts a new session can be played by, invited ones too. */
  private suspend fun people(): List<UserRef> = users.accounts()
    .map { UserRef(it.id.toString(), it.name, it.email.takeIf { _ -> it.name == null }) }
    .sortedBy { it.label.lowercase() }

  /** For the random locks and the suggested IDs. */
  private val random: Random = secureRandom.asKotlinRandom()

  /**
   * Deletes session [id]: its game server stops, its tracker files and setup go; the logins stay. A running one's
   * seed and save go to the gateway's `old/`, a finished one's are deleted for good.
   */
  suspend fun delete(id: String, admin: String) {
    val live = sessions.require(id)
    val purge = live.finished
    gameServer.delete(id, purge)
    live.disconnect()
    live.tracker.clear()
    // Its row, and with it (one statement) its setup, its events and its snapshot: its ID may be a new session's later,
    // nothing of this one is left to be heard then (V29)
    setups.delete(live.seedName)
    archives.delete(id)
    spoilers.forget(id)
    sessions.refresh()
    logger.info("{} deleted session {} (seed {}){}", admin, id, live.seedName, if (purge) " for good" else "")
  }

  /**
   * Deletes the finished sessions whose time has come ([TrackerConfig.sessionRetentionDays] after they finished, or
   * after their auto-deletion was enabled again), unless their auto-deletion is disabled. Once an hour, for as long as
   * [scope] runs.
   */
  fun cleanUp(scope: CoroutineScope) = scope.launch {
    while (true) {
      runCatching {
        val now = Instant.now()
        sessions.all().forEach { live ->
          val f = live.finish ?: return@forEach
          val finishedAt = f.finishedAt ?: return@forEach
          val due = deletesAt(finishedAt, f.keep, config.sessionRetentionDays, f.retainedSince)
          if (due != null && now.isAfter(due)) delete(live.id, "auto-deletion")
        }
      }.onFailure { logger.warn("Could not delete old sessions: {}", it.message) }
      delay(1.hours)
    }
  }

  /** [keep] disables the session's auto-deletion; deleting it by hand stays possible. */
  suspend fun setKeep(id: String, keep: Boolean, admin: String) {
    val live = sessions.require(id)
    if (!finishes.setKeep(live.seedName, keep)) throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
    sessions.refresh()
    logger.info("{} {} the auto-deletion of {}", admin, if (keep) "disabled" else "enabled", id)
  }

  /** [keep]: its game server keeps running without players; else it sleeps when idle (the gateway decides). */
  suspend fun setKeepRunning(id: String, keep: Boolean, admin: String) {
    val live = sessions.require(id)
    if (!setups.setKeepRunning(live.seedName, keep)) throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
    sessions.refresh()
    logger.info("{} let {} {}", admin, id, if (keep) "keep running without players" else "sleep when idle")
  }

  /**
   * Randogeddon runs session [id]'s hosted Minecraft Dig slot [slot]'s server itself ([direct]), or no longer: only an
   * Administrator switches it on ([administrator]); off, its server is stopped (its world stays).
   */
  suspend fun setDirect(id: String, slot: String, direct: Boolean, administrator: Boolean, actor: String) {
    // Whoever may not is told so whatever the session: not whether it is there
    if (direct && !administrator) throw SessionProblem(HttpStatusCode.Forbidden, Problem.ONLY_ADMINISTRATORS)
    val live = sessions.require(id)
    val game = Room.read(live.roomJson)?.players?.firstOrNull { it.name == slot }?.game
    if (slot !in live.setup?.hosted.orEmpty() || game != MINECRAFT_DIG) throw SessionProblem(HttpStatusCode.BadRequest, Problem.NOT_POSSIBLE)
    if (!setups.setDirect(live.seedName, slot, direct)) throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
    if (!direct) runCatching { gameServer.stopMinecraft(id, slot) }
    sessions.refresh()
    logger.info("{} {} {}'s Minecraft server of {} here", actor, if (direct) "hosts" else "no longer hosts", id, slot)
  }

  /**
   * Who plays session [id]'s slot [slot] now: [request]'s accounts, or every player of it (open); and whether it is
   * hosted. Whoever loses the slot loses its connection: it gets a new password and the gateway closes its connections
   * (they connect again with the new one). A slot no longer hosted loses its direct switch, its Minecraft server stops.
   */
  suspend fun setSlotPlayers(id: String, slot: String, request: SlotPlayersRequest, actor: String) {
    val live = sessions.require(id)
    val setup = live.setup ?: throw SessionProblem(HttpStatusCode.Conflict, Problem.NOT_POSSIBLE)
    if (Room.read(live.roomJson)?.players?.none { it.name == slot } != false) throw SessionProblem(HttpStatusCode.NotFound, Problem.NOT_POSSIBLE)
    val wasHosted = slot in setup.hosted
    val wasDirect = slot in setup.direct
    val hosted = request.hosted ?: wasHosted
    val accounts = users.accounts().associateBy { it.id.toString() }
    request.userIds.filter { it !in accounts }.takeIf { it.isNotEmpty() }?.let {
      throw SessionProblem(HttpStatusCode.BadRequest, Problem.NO_SUCH_USERS, "ids" to it.joinToString())
    }
    val named = if (request.open) emptyList() else request.userIds.distinct().mapNotNull { accounts[it]?.id }
    // A slot that isn't hosted is played by someone, or by everyone
    if (!hosted && !request.open && named.isEmpty()) throw SessionProblem(HttpStatusCode.BadRequest, Problem.SLOT_WITHOUT_PLAYER, "slots" to slot)
    val after = named.mapNotNull { setup.members[it] ?: accounts[it.toString()]?.name }.toSet()
    // Whoever holds its password before and after (`null`: every player of the session), its tunnel key counted: a
    // hosted slot whose server runs elsewhere has nobody among its players who holds it
    fun holders(hosted: Boolean, direct: Boolean, open: Boolean, names: Set<String>): Set<String>? = when {
      hosted && !direct -> emptySet()
      open -> null
      else -> names
    }
    val before = holders(wasHosted, wasDirect, slot in setup.open, setup.worlds[slot].orEmpty().toSet())
    val now = holders(hosted, hosted && wasDirect, request.open, after)
    val lost = when {
      before != null && before.isEmpty() -> false
      before == null -> now != null
      else -> now != null && !now.containsAll(before)
    }
    // Someone named for it before who plays nothing in the session now still holds the open slots' passwords
    val open = openSlotsToRenew(setup, slot, if (request.open) emptySet() else after, request.open, hosted, hosted && wasDirect)
    val renewed = (if (lost) setOf(slot) else emptySet()) + open
    if (!setups.setSlotPlayers(live.seedName, slot, named, request.open)) throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
    try {
      if (hosted != wasHosted) {
        setups.setHosted(live.seedName, slot, hosted)
        // No longer hosted: Randogeddon no longer runs its Minecraft server (its world stays)
        if (!hosted && wasDirect) runCatching { gameServer.stopMinecraft(id, slot) }
      }
      renewSlots(slotPasswords, gameServer, id, live.seedName, renewed)
    } finally {
      // The database changed, whether the gateway could be told or not
      sessions.refresh()
    }
    logger.info("{} gave {}'s slot {} to {}{}{}", actor, id, slot, if (request.open) "everyone" else after.joinToString().ifEmpty { "nobody" },
      if (hosted != wasHosted) (if (hosted) ", hosted" else ", no longer hosted") else "",
      if (renewed.isNotEmpty()) "; new passwords: ${renewed.sorted().joinToString()}" else "")
  }

  /**
   * [live]'s slots as the panel shows them: each with who plays it (or, hosted, who gets it), hosted, open to all,
   * hosted here. A session from before its setup was kept: the people its archive names.
   */
  private fun worldPlans(live: LiveSession): List<WorldPlan> {
    val setup = live.setup
    val ids = setup?.members?.entries?.associate { (id, name) -> name to id.toString() }.orEmpty()
    if (setup == null) return live.people().flatMap { (person, slots) -> slots.map { it to person } }
      .groupBy({ it.first }, { it.second }).map { (slot, names) -> WorldPlan(slot, names, userIds = names.mapNotNull { ids[it] }) }
    return (setup.worlds.keys + setup.hosted + setup.open).sortedBy { it.lowercase() }.map { slot ->
      val names = if (slot in setup.open) emptyList() else setup.worlds[slot].orEmpty()
      WorldPlan(
        slot, names, userIds = names.mapNotNull { ids[it] }, hosted = slot in setup.hosted, direct = slot in setup.direct,
        open = slot in setup.open,
      )
    }
  }

  /**
   * Every session ID in use: the database's, the creations' ([reservedIds] too) and the gateway's session directories
   * (a deleted one's files are in old/). [fresh]: the gateway asked now (before an ID is taken); else as it answered
   * last.
   */
  private suspend fun takenIds(fresh: Boolean = true): Set<String> = store.all().map { it.id }.toSet() +
    creations.values.filter { it.state == CreationState.RUNNING }.map { it.id } + reservedIds +
    if (fresh) runCatching { gameServer.sessions().sessions.map { it.id } }.getOrDefault(emptyList()) else sessions.onGateway
}

/**
 * Random locks: [open] of the slots, picked at random, start open; the others are locked. At least one has to be
 * open (or no key could ever be found), and there must be something to lock.
 */
internal fun pickLocked(slots: List<String>, open: Int, random: Random): List<String> {
  if (slots.size < 2) throw SessionProblem(HttpStatusCode.BadRequest, Problem.RANDOM_TWO_SLOTS)
  if (open < 1) throw SessionProblem(HttpStatusCode.BadRequest, Problem.ONE_OPEN)
  if (open >= slots.size) {
    throw SessionProblem(HttpStatusCode.BadRequest, Problem.RANDOM_MAX_OPEN, "slots" to slots.size, "max" to slots.size - 1)
  }
  val openSlots = slots.shuffled(random).take(open).toSet()
  return slots.filter { it !in openSlots }
}

/**
 * Mixed locks: the slots that aren't [WorldPlan.random] start open or locked as they say; of the random ones, as many
 * start open as make [open] in all, picked at random, and the rest are locked. At least one slot starts open.
 */
internal fun pickMixed(plans: List<WorldPlan>, open: Int, random: Random): List<String> {
  val sure = plans.count { !it.random && !it.locked }
  val pool = plans.filter { it.random }.map { it.name }
  val least = maxOf(1, sure)
  if (open < least || open > sure + pool.size) {
    throw SessionProblem(
      HttpStatusCode.BadRequest, Problem.MIXED_RANGE,
      "sure" to sure, "chance" to pool.size, "least" to least, "most" to sure + pool.size,
    )
  }
  val picked = pool.shuffled(random).take(open - sure).toSet()
  return plans.filter { if (it.random) it.name !in picked else it.locked }.map { it.name }
}

/** What to tell the admin when Generate.py made no seed: the games that may be the cause, then its output. */
internal fun failure(result: GenerateResult): Refusal {
  val output = result.log.joinToString("\n")
  result.stoppedAfterMinutes?.let { return Refusal.of(Problem.GENERATION_TOO_LONG, "minutes" to it, detail = output) }
  if (result.suspects.isEmpty()) return Refusal(Problem.GENERATION_FAILED, detail = output)
  val suspects = result.suspects.joinToString("\n") { s ->
    val file = listOfNotNull(s.apworld, s.from?.let { "Archipelago $it" })
    "• ${s.game}" + (if (file.isNotEmpty()) " (${file.joinToString(", ")})" else "") + ": ${s.reason}"
  }
  return Refusal.of(Problem.GENERATION_SUSPECTS, "suspects" to suspects, detail = output)
}
