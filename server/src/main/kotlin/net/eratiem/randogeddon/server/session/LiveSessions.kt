package net.eratiem.randogeddon.server.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.eratiem.randogeddon.database.FinishState
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
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.UserNames
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SessionServer
import net.eratiem.randogeddon.shared.Summary
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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
) {
  /** Written by the gateway before each start of its game server. */
  val roomJson: Path = config.dataDir.resolve("sessions").resolve(id).resolve("room.json")

  val tracker = TrackerService(archives.history(id), roomJson) { server?.port?.let { "ws://${config.gatewayHost}:$it" } }

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

  /** The options it was created with; `null` for a seed from before (the defaults apply). */
  val options: SessionOptions?
    get() = setup?.options?.let { runCatching { ApiJson.decodeFromString(SessionOptions.serializer(), it) }.getOrNull() }

  /**
   * Person -> their slots, from the session's setup (a slot played together shows up for each of its users). A seed
   * from before without one: the people its archive names.
   */
  fun people(): Map<String, List<String>> {
    val worlds = setup?.worlds
    val hosted = setup?.hosted.orEmpty()
    if (!worlds.isNullOrEmpty() || hosted.isNotEmpty()) {
      val people = LinkedHashMap<String, MutableList<String>>()
      for ((slot, users) in worlds.orEmpty()) for (user in users) people.getOrPut(user) { mutableListOf() }.add(slot)
      // The hosted slots together, as if one person played them (no account can be called so)
      if (hosted.isNotEmpty()) people[UserNames.HOSTED] = hosted.sorted().toMutableList()
      return people
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
    listOf(time, options, finish, setup?.deleted).hashCode().toLong().shl(16)

  /** The live summary, built now. */
  suspend fun build(): Summary {
    val people = people()
    val options = options ?: SessionOptions()
    val error = tracker.lastError
    val time = time
    val finish = finish
    return tracker.read { summary(config.title, error, people, options.pointHints, options.autoHints) }.copy(
      sessionId = id,
      errorRefused = error != null && tracker.lastErrorRefused,
      sessionTimeMs = time?.runTimeMs ?: 0,
      sessionRunningSince = time?.countedUntil?.toEpochMilli(),
      finishesAt = finish?.dueAt?.toEpochMilli(),
      finishedAt = finish?.finishedAt?.toEpochMilli(),
    )
  }

  suspend fun summary(): Summary = mutex.withLock { current().first }

  suspend fun json(): String = mutex.withLock { current().second }

  /** The summary for its share link: the people without their accounts, no connection error (it names our hosts). */
  suspend fun publicJson(): String {
    val summary = summary()
    return ApiJson.encodeToString(
      Summary.serializer(),
      summary.copy(error = null, people = summary.people.map { it.copy(userId = null) }),
    )
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

  /** Follows its game server until [disconnect] (or until [scope] ends). */
  @Synchronized
  fun connect(scope: CoroutineScope) {
    if (job?.isActive != true) job = tracker.start(scope)
  }

  val connecting: Boolean get() = job?.isActive == true

  /** Stops following it (it finished, or was deleted); the history is saved. */
  suspend fun disconnect() {
    job?.cancelAndJoin()
    job = null
    tracker.saveNow()
  }

  fun close() = tracker.close()
}

/**
 * Every session, as the database knows them, with their game servers as the gateway reports them: read every 10 s,
 * and right away after the admin panel changed something ([refresh]). The sessions that aren't finished get a tracker
 * connection of their own.
 */
@Single
class LiveSessions(
  private val config: TrackerConfig,
  private val store: SessionStore,
  private val times: SessionTimeStore,
  private val setups: SessionSetupStore,
  private val finishes: SessionFinishStore,
  private val gameServer: GameServerClient,
  private val archives: Archives,
) {
  private val mutex = Mutex()
  private val sessions = ConcurrentHashMap<String, LiveSession>()

  @Volatile
  private var scope: CoroutineScope? = null

  /** How many game servers can run at once, as the gateway said the last time. */
  @Volatile
  var capacity: Int = 0
    private set

  fun get(id: String): LiveSession? = sessions[id]

  /** The newest first. */
  fun all(): List<LiveSession> = sessions.values.sortedByDescending { it.record.createdAt }

  suspend fun refresh() = mutex.withLock {
    val records = store.all()
    val servers = runCatching { gameServer.sessions() }.getOrNull()
    servers?.let { capacity = it.capacity }
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
      val live = sessions.getOrPut(record.id) { LiveSession(record.id, record, config, archives) }
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
    this@LiveSessions.scope = scope
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

  fun close() = sessions.values.forEach { runCatching { it.close() } }
}
