package net.eratiem.randogeddon.server.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.eratiem.randogeddon.database.FinishState
import net.eratiem.randogeddon.database.SessionEventStore
import net.eratiem.randogeddon.database.SessionSnapshotStore
import net.eratiem.randogeddon.database.SessionFinishStore
import net.eratiem.randogeddon.database.SessionRecord
import net.eratiem.randogeddon.database.SessionSetup
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.SessionStore
import net.eratiem.randogeddon.database.SessionTime
import net.eratiem.randogeddon.database.SessionTimeStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.legacySessionId
import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.server.ap.TrackerService
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.state.WORLD_LOCKS_GAME
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ArchivedSession
import net.eratiem.randogeddon.shared.AvailableRequest
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.LogicStatus
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SessionServer
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.UserNames
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * One session as the tracker follows it: its seed and setup (who plays which slot, the options) from the database,
 * its session time and finish state, its game server as the gateway reports it, and its own tracker connection to
 * that game server while it isn't finished. The dashboard's summary of it is built at most once per change, however
 * many dashboards are open; a finished session's is the one frozen when it finished (its archive).
 */
class LiveSession(
  val id: String,
  record: SessionRecord,
  private val config: TrackerConfig,
  private val archives: Archives,
  /** Where the one poller of its [changes] runs (the sessions' own scope, not a viewer's). */
  private val streams: CoroutineScope,
  /** What the gateway hears on the game servers. */
  events: SessionEventStore? = null,
  /** Where what its dashboard shows is kept, for while its tracker isn't connected. */
  private val snapshots: SessionSnapshotStore? = null,
) {
  /** Written by the gateway before each start of its game server. */
  val roomJson: Path = config.dataDir.resolve("sessions").resolve(id).resolve("room.json")

  val tracker = TrackerService(archives.history(id), roomJson, id, events, snapshots) {
    server?.port?.let { "ws://${config.gatewayHost}:$it" }
  }

  /**
   * What its dashboard showed the last time it was kept ([keep]): shown until its tracker has been connected since
   * its start (it knows its slots only from the game server; asleep after a deploy, that can take until a player
   * comes). Read once ([loadKept]).
   */
  @Volatile
  private var kept: ArchivedSession? = null

  /** The snapshot kept of it, while its tracker hasn't been connected since its start; `null` then: the live state. */
  val shownKept: ArchivedSession? get() = kept?.takeIf { !tracker.connectedOnce }

  suspend fun loadKept() {
    val snapshot = snapshots?.let { store -> runCatching { store.of(id) }.getOrNull() }?.takeIf { !it.final } ?: return
    kept = runCatching { ApiJson.decodeFromString(ArchivedSession.serializer(), snapshot.json) }.getOrNull()
  }

  /** Every slot's details, as the tracker knows them (the World Locks slot isn't one). */
  suspend fun details(): List<PlayerDetail> =
    tracker.read { players.values.filter { it.game != WORLD_LOCKS_GAME }.mapNotNull { playerDetail(it.slot) } }

  /**
   * Keeps what its dashboard shows now in the database, for while its tracker isn't connected; only once the tracker
   * has been (else it knows less than what is kept), and not over its archive.
   */
  suspend fun keep() {
    val store = snapshots ?: return
    if (finished || !tracker.connectedOnce) return
    val snapshot = ArchivedSession(build(), details())
    runCatching { store.saveLive(id, System.currentTimeMillis(), ApiJson.encodeToString(ArchivedSession.serializer(), snapshot)) }
      .onSuccess { kept = snapshot }
      .onFailure { logger.warn("Could not keep {}'s dashboard: {}", id, it.message) }
  }

  @Volatile
  var record: SessionRecord = record
    internal set

  val seedName: String get() = record.seedName

  @Volatile
  var finish: FinishState? = null
    internal set

  @Volatile
  var time: SessionTime? = null
    internal set

  @Volatile
  var setup: SessionSetup? = null
    internal set

  /** Its game server, as of the last look at the gateway; `null` if the gateway has none (or can't be reached). */
  @Volatile
  var server: SessionServer? = null
    internal set

  /** An admin cancelled the countdown; it doesn't start again until a slot isn't done any more. */
  @Volatile
  internal var countdownCancelled = false

  val finished: Boolean get() = finish?.finishedAt != null

  /** The options it was created with; `null` for a seed from before (the defaults apply). Decoded once per setup. */
  val options: SessionOptions?
    get() {
      val text = setup?.options ?: return null
      decodedOptions?.let { (from, options) -> if (from == text) return options }
      return runCatching { ApiJson.decodeFromString(SessionOptions.serializer(), text) }.getOrNull()
        .also { decodedOptions = text to it }
    }

  @Volatile
  private var decodedOptions: Pair<String, SessionOptions?>? = null

  /**
   * Person -> their slots, from the session's setup (a slot played together shows up for each of its users). A seed
   * from before without one: the people its archive names.
   */
  fun people(): Map<String, List<String>> {
    val worlds = setup?.worlds
    val hosted = setup?.hosted.orEmpty()
    if (!worlds.isNullOrEmpty() || hosted.isNotEmpty()) {
      val people = LinkedHashMap<String, MutableList<String>>()
      // A hosted slot's named players join its game's server, they don't play it
      for ((slot, users) in worlds.orEmpty()) if (slot !in hosted) for (user in users) people.getOrPut(user) { mutableListOf() }.add(slot)
      // Always in the same order: the people by name, each one's slots by name
      val ordered = LinkedHashMap<String, List<String>>()
      for ((person, slots) in people.entries.sortedBy { it.key.lowercase() }) ordered[person] = slots.sortedBy { it.lowercase() }
      // The slots everyone plays together, and the hosted ones, each as if one person played them (no account can be
      // called so)
      if (openSlots.isNotEmpty()) ordered[UserNames.EVERYONE] = openSlots.sortedBy { it.lowercase() }
      if (hosted.isNotEmpty()) ordered[UserNames.HOSTED] = hosted.sortedBy { it.lowercase() }
      return ordered
    }
    val s = archives.read(id)?.summary ?: return emptyMap()
    return s.people.associate { p -> p.name to p.slots.mapNotNull { slot -> s.players.firstOrNull { it.slot == slot }?.name } }
  }

  /**
   * What [user] is in this session: a Session-Manager, support or watcher by its roles (a Session-Manager who plays is a
   * manager), a player by its slots; `null`: nothing (an Administrator still sees it).
   */
  fun roleOf(user: User): SessionRole? {
    setup?.roles?.get(user.id)?.let { entry -> SessionRole.of(entry.role)?.let { return it } }
    return if (personOf(user) != null) SessionRole.PLAYER else null
  }

  /** The IDs of its Session-Managers. */
  fun managers(): Set<kotlin.uuid.Uuid> =
    setup?.roles?.filterValues { it.role == SessionRole.MANAGER.code }?.keys.orEmpty()

  /**
   * Who [user] is in this session: the name they have in it (by their UUID, so a rename doesn't matter); `null`: not
   * part of it. A seed from before without a setup: by name.
   */
  /** Its slots open to all of its players that aren't hosted: every player of it plays them. */
  val openSlots: Set<String> get() = setup?.open.orEmpty() - setup?.hosted.orEmpty()

  /** The slots [person] plays: their own and the ones open to all ([openSlots]); not the hosted ones. */
  fun playedBy(person: String?): List<String> {
    if (person == null || person == UserNames.HOSTED || person == UserNames.EVERYONE) return emptyList()
    return people()[person].orEmpty() + openSlots.sortedBy { it.lowercase() }
  }

  /** The hosted slots [person] gets (their patch files, the bridge's tunnel): the open ones, and the ones naming them. */
  fun hostedFor(person: String?): List<String> {
    if (person == null) return emptyList()
    val setup = setup ?: return emptyList()
    return setup.hosted.filter { it in setup.open || person in setup.worlds[it].orEmpty() }.sortedBy { it.lowercase() }
  }

  fun personOf(user: User): String? {
    val members = setup?.members
    if (!members.isNullOrEmpty()) return members[user.id]
    return people().keys.firstOrNull { it.equals(user.name, ignoreCase = true) }
  }

  // ------------------------------------------------------------------------------------------------------ summary

  private val mutex = Mutex()
  private var key: Any? = null
  private var json = ""
  private var cached: Summary? = null

  /** Changes with the session state, with its people and options, the session time's count and its finishing. */
  suspend fun version(): Long = tracker.read { version } + people().hashCode().toLong().shl(32) +
    listOf(time, options, finish, setup?.deleted, managerUnavailable(), asleep, spoilerCompleting, playthroughReady, server?.logic)
      .hashCode().toLong().shl(16)

  /** When its logic was last seen built: after each build every slot is asked anew. */
  private var logicSince: Long? = null

  /**
   * Asks its logic about the slots whose items or checks changed, while it runs and the tracker is connected (only
   * then does it know what they received).
   */
  suspend fun askLogic(gameServer: GameServerClient) {
    val logic = server?.logic?.takeIf { it.status == LogicStatus.RUNNING } ?: return
    if (finished || !tracker.read { connected }) return
    if (logic.since != logicSince) {
      tracker.forgetAvailableAsks()
      logicSince = logic.since
    }
    val asks = tracker.read { availableAsks() }
    // The slots' maps, once per build
    val maps = tracker.read { mapsWanted() }
    if (asks.isEmpty() && maps.isEmpty()) return
    val answer = gameServer.available(id, AvailableRequest(asks, maps)) ?: return
    tracker.onAvailable(asks, answer, maps)
  }

  /** Its game server sleeps (nobody played for a while): no connection to it, and that's no problem. */
  val asleep: Boolean get() = server?.state?.status == GameServerStatus.IDLE

  /**
   * Its spoiler log has its playthrough (completed after the seed, since 0.19.3): the spoiler page, the hints and the
   * cheats wait for it. Also when completing it stopped (they go on with the seed's own), or the gateway hasn't said.
   */
  val playthroughReady: Boolean get() = server?.spoiler?.let { it.level >= 2 || it.stopped != null } ?: true

  /** Its spoiler log is being completed after the seed (and nothing stopped that). */
  val spoilerCompleting: Boolean get() = server?.spoiler?.let { it.stopped == null } == true

  /** A Session-Manager of it can't act for it now (kept out by an Administrator, or gone), while it runs. */
  fun managerUnavailable(): Boolean = !finished &&
    setup?.roles?.values?.any { it.role == SessionRole.MANAGER.code && it.unavailable() } == true

  /** The live summary, built now. */
  suspend fun build(): Summary {
    val people = people()
    val options = options ?: SessionOptions()
    val asleep = asleep
    val error = tracker.lastError.takeUnless { asleep }
    val time = time
    val finish = finish
    val live = tracker.read { summary(config.title, error, people, options.pointHints, options.autoHints) }
    val shown = shownKept?.summary?.let { withPresence(it, live) } ?: live
    // The available checks not counted since the tracker's start: the last ones kept
    val keptAvailable = kept?.summary?.players?.associate { it.slot to it.available }.orEmpty()
    return shown.copy(
      players = shown.players.map { if (it.available == null) it.copy(available = keptAvailable[it.slot]) else it },
      logic = server?.logic,
      sessionId = id,
      errorRefused = error != null && tracker.lastErrorRefused,
      // As the gateway said at its last look; connected again: awake (it woke for a player since)
      serverAsleep = asleep && !tracker.read { connected },
      spoilerCompleting = spoilerCompleting,
      spoilerReady = playthroughReady,
      sessionTimeMs = time?.runTimeMs ?: 0,
      sessionRunningSince = time?.countedUntil?.toEpochMilli(),
      finishesAt = finish?.dueAt?.toEpochMilli(),
      finishedAt = finish?.finishedAt?.toEpochMilli(),
      managerUnavailable = managerUnavailable(),
      createdAt = record.createdAt.toEpochMilli(),
      releaseMode = options.releaseMode,
    )
  }

  /**
   * The kept summary ([kept]) as it is now: who is connected comes from the events the tracker replayed, which it has
   * (the slots from `room.json`) before it is connected; the connection's state is the live one.
   */
  private suspend fun withPresence(kept: Summary, live: Summary): Summary {
    val presence = tracker.read { players.values.associate { it.slot to (it.online to it.openSession?.start) } }
    val worlds = kept.players.map { w ->
      val (online, since) = presence[w.slot] ?: (0 to null)
      w.copy(online = online > 0, connections = online, onlineSince = since)
    }
    val people = kept.people.map { person -> person.copy(online = worlds.any { it.slot in person.slots && it.online }) }
    return kept.copy(
      players = worlds, people = people, totals = kept.totals.copy(online = worlds.count { it.online }),
      connected = live.connected, error = live.error, version = live.version, startedAt = live.startedAt,
    )
  }

  suspend fun summary(): Summary = mutex.withLock { current().first }

  suspend fun json(): String = mutex.withLock { current().second }

  private var publicFor: Summary? = null
  private var publicJson = ""

  /**
   * The summary for its share link: the people without their accounts, no connection error (it names our hosts).
   * Made once per summary, however many watch it.
   */
  suspend fun publicJson(): String = mutex.withLock {
    val summary = current().first
    if (summary !== publicFor) {
      publicJson = ApiJson.encodeToString(
        Summary.serializer(),
        summary.copy(error = null, people = summary.people.map { it.copy(userId = null) }, managerUnavailable = false),
      )
      publicFor = summary
    }
    publicJson
  }

  /**
   * Its [version] as one poller sees it, every [CHANGES_POLL], for every stream of it at once: the work grows with the
   * sessions watched, not with their viewers. It polls only while someone follows it (and a moment after the last
   * one went, so a page that reloads finds it running).
   */
  val changes: StateFlow<Long> by lazy {
    flow {
      while (true) {
        emit(version())
        delay(CHANGES_POLL)
      }
    }.stateIn(streams, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), NO_VERSION)
  }

  private suspend fun current(): Pair<Summary, String> {
    val archived = if (finished) archives.read(id) else null
    val now = if (archived != null) Triple("archived", finish?.finishedAt, setup?.deleted) else version()
    val summary = cached
    if (now != key || summary == null) {
      val fresh = withAccounts(archived?.summary?.copy(sessionId = id) ?: build())
      cached = fresh
      json = ApiJson.encodeToString(Summary.serializer(), fresh)
      key = now
      return fresh to json
    }
    return summary to json
  }

  /**
   * Each person with their account's UUID, and whether it is deleted (also in an archive written before: the setup
   * knows who was who).
   */
  private fun withAccounts(summary: Summary): Summary {
    val setup = setup ?: return summary
    val ids = setup.members.entries.associate { (id, name) -> name to id }
    return summary.copy(people = summary.people.map { person ->
      val id = ids[person.name] ?: return@map person
      person.copy(userId = id.toString(), deleted = id in setup.deleted)
    })
  }

  // --------------------------------------------------------------------------------------------------- connection

  private var job: Job? = null
  private var keeper: Job? = null

  /** Follows its game server until [disconnect] (or until [scope] ends), keeping what its dashboard shows. */
  @Synchronized
  fun connect(scope: CoroutineScope) {
    if (job?.isActive != true) job = tracker.start(scope)
    if (keeper?.isActive != true) keeper = scope.launch { keepWhenDue() }
  }

  /**
   * Keeps its dashboard ([keep]) when the tracker's connection ends (the game server sleeps or stops: it is shown
   * from then on, also after a restart) and, while connected, at most every [KEEP_EVERY] when something changed.
   */
  private suspend fun keepWhenDue() {
    var wasConnected = false
    var keptVersion = Long.MIN_VALUE
    var keptAt = 0L
    while (true) {
      delay(KEEP_LOOK)
      val (connected, version) = tracker.read { connected to version }
      val now = System.currentTimeMillis()
      val due = connected && version != keptVersion && now - keptAt >= KEEP_EVERY.inWholeMilliseconds
      if ((wasConnected && !connected) || due) {
        keep()
        keptVersion = version
        keptAt = now
      }
      wasConnected = connected
    }
  }

  val connecting: Boolean get() = job?.isActive == true

  /** Stops following it (it finished, or was deleted). */
  suspend fun disconnect() {
    keeper?.cancelAndJoin()
    keeper = null
    job?.cancelAndJoin()
    job = null
  }

  fun close() = tracker.close()
}

/**
 * Every session, as the database knows them, with their game servers as the gateway reports them: read every 10 s,
 * and right away after the admin panel changed something ([refresh]). The sessions that aren't finished get a tracker
 * connection of their own.
 */
/** How often a session looks whether its dashboard is to be kept, and how often at most while it is connected. */
private val KEEP_LOOK = 15.seconds
private val KEEP_EVERY = 5.minutes

/** How often a watched session's version is looked at: burst of item sends (a release) becomes one update. */
val CHANGES_POLL = 750.milliseconds

/** [LiveSession.changes] before its first look. */
const val NO_VERSION = Long.MIN_VALUE

@Single
class LiveSessions(
  private val config: TrackerConfig,
  private val store: SessionStore,
  private val times: SessionTimeStore,
  private val setups: SessionSetupStore,
  private val finishes: SessionFinishStore,
  private val gameServer: GameServerClient,
  private val archives: Archives,
  private val events: SessionEventStore,
  private val snapshots: SessionSnapshotStore,
) {
  private val mutex = Mutex()
  private val sessions = ConcurrentHashMap<String, LiveSession>()
  // The sessions' change pollers, for as long as the application runs
  private val streams = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Volatile
  private var scope: CoroutineScope? = null

  /** The sessions the gateway had files of the last time it answered (also those this tracker doesn't follow). */
  @Volatile
  var onGateway: Set<String> = emptySet()
    private set

  fun get(id: String): LiveSession? = sessions[id]

  /** The newest first. */
  fun all(): List<LiveSession> = sessions.values.sortedByDescending { it.record.createdAt }

  suspend fun refresh() = mutex.withLock {
    val records = store.all()
    val servers = runCatching { gameServer.sessions() }.getOrNull()
    servers?.let { onGateway = it.sessions.map { s -> s.id }.toSet() }
    // A session is one the gateway has the files of, or a finished one with its archive; older rows (seeds from before
    // whose files are gone) aren't. Without an answer from the gateway, the sessions stay as they were.
    val onGateway = servers?.sessions?.map { it.id }?.toSet()
    val ids = records.map { it.id }.filter { id ->
      if (onGateway == null) sessions.containsKey(id) else id in onGateway || archives.read(id) != null
    }.toSet()
    for (gone in sessions.keys.filter { it !in ids }) {
      sessions.remove(gone)?.let { runCatching { it.disconnect() } }
    }
    for (record in records.filter { it.id in ids }) {
      val known = sessions[record.id]
      val live = known ?: LiveSession(record.id, record, config, archives, streams, events, snapshots).also {
        // What was kept of it, for until its tracker is connected
        it.loadKept()
        sessions[record.id] = it
      }
      live.record = record
      live.time = runCatching { times.of(record.seedName) }.getOrNull()
      live.setup = runCatching { setups.of(record.seedName) }.getOrNull()
      live.finish = runCatching { finishes.of(record.seedName) }.getOrNull()
      if (servers != null) live.server = servers.sessions.firstOrNull { it.id == record.id }
      val scope = scope ?: continue
      if (live.finished) {
        if (live.connecting) live.disconnect()
      } else {
        live.connect(scope)
      }
    }
  }

  /**
   * Moves the tracker files of before into the per-session layout, then follows the sessions for as long as [scope]
   * runs.
   */
  fun watch(scope: CoroutineScope) = scope.launch {
    runCatching {
      archives.migrate { seed -> runBlocking { runCatching { store.idOf(seed) }.getOrNull() } ?: legacySessionId(seed) }
    }.onFailure { logger.warn("Could not move the tracker's files: {}", it.message) }
    // The finished sessions' archives (and those of before, from their files, once)
    runCatching { archives.load() }.onFailure { logger.warn("Could not read the archives: {}", it.message) }
    this@LiveSessions.scope = scope
    // The logic of the sessions whose game servers run is asked about what changed, every few seconds
    launch {
      while (true) {
        delay(3.seconds)
        sessions.values.forEach { live ->
          try {
            live.askLogic(gameServer)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            logger.debug("Could not ask {}'s logic: {}", live.id, e.message)
          }
        }
      }
    }
    while (true) {
      try {
        refresh()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn("Could not read the sessions: {}", e.message)
      }
      delay(10.seconds)
    }
  }

  /** The tracker stops: what every session's dashboard shows is kept first (10 s at most for all). */
  fun close() {
    streams.cancel()
    runBlocking { withTimeoutOrNull(10.seconds) { sessions.values.forEach { runCatching { it.keep() } } } }
    sessions.values.forEach { runCatching { it.close() } }
  }
}
