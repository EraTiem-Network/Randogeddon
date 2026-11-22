package net.eratiem.randogeddon.gateway

import java.io.IOException
import java.io.OutputStream
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.GenerateResult
import net.eratiem.randogeddon.shared.GenerationProgress
import net.eratiem.randogeddon.shared.GenerationStage
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.SpoilerProgress
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Archipelago")

/**
 * The Archipelago releases this gateway has, one directory each under [root] (e.g. `/opt/archipelago/0.6.8`). A
 * session runs on the release it was generated with; one the gateway doesn't have can't be started.
 */
class Archipelago(private val root: Path, val python: String) {
  /** Newest first. */
  fun versions(): List<String> {
    if (!root.isDirectory()) return emptyList()
    return root.listDirectoryEntries().filter { it.resolve("MultiServer.py").exists() }.map { it.name }
      .sortedWith { a, b -> compareVersions(b, a) }
  }

  val newest: String get() = versions().firstOrNull() ?: error("No Archipelago release in $root")

  /** The directory of [version], one of [versions] only: the name becomes a path. */
  fun home(version: String): Path {
    val installed = versions()
    require(version in installed) {
      "Archipelago ${printable(version)} is not installed in this gateway (it has ${installed.joinToString().ifEmpty { "none" }})"
    }
    return root.resolve(version)
  }
}

/** 0.6.10 after 0.6.9: compares the numbers, not the text. */
internal fun compareVersions(a: String, b: String): Int {
  val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
  val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
  for (i in 0 until maxOf(x.size, y.size)) {
    val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
    if (c != 0) return c
  }
  return 0
}

/** A word for MultiServer's console, which splits its commands like a shell: in double quotes, `\` and `"` escaped. */
internal fun consoleWord(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * What Archipelago's processes keep of the gateway's environment: what Python and the release need, nothing else (the
 * last ones are what a process needs on Windows, where the tests run).
 */
private val KEPT = setOf(
  "PATH", "HOME", "LANG", "LC_ALL", "TZ", "TMPDIR", "PYTHONUNBUFFERED", "SKIP_REQUIREMENTS_UPDATE", "AP_HOME",
  "SYSTEMROOT", "PATHEXT", "COMSPEC", "TEMP", "TMP",
)

/**
 * Archipelago's Python (Generate.py, MultiServer, the worlds and apworlds) runs without the gateway's secrets: its
 * environment (the database login, the internal token, the admin password) isn't passed on.
 */
internal fun ProcessBuilder.withoutSecrets(): ProcessBuilder = apply { environment().keys.removeIf { it.uppercase() !in KEPT } }

/**
 * How long a line of a process's output is kept at most (the game server's, a generation's, the logic's): a longer one
 * is cut as it is read, before its passwords are masked.
 */
internal const val MAX_LOG_LINE = 2000

/**
 * Starts one of Archipelago's processes: [command] in [home], without the gateway's secrets ([withoutSecrets]) and
 * with [extra] variables added, its errors in its output. Its standard input is closed unless [input] (MultiServer's
 * console, the logic's questions): after an error Generate.py waits for Enter (an atexit input()), which the closed
 * input makes an end.
 */
internal fun startProcess(
  command: List<String>, home: Path?, extra: Map<String, String> = emptyMap(), input: Boolean = false,
): Process {
  val proc = ProcessBuilder(command)
    .withoutSecrets()
    .apply {
      home?.let { directory(it.toFile()) }
      environment().putAll(extra)
    }
    .redirectErrorStream(true)
    .start()
  if (!input) proc.outputStream.close()
  return proc
}

/** Ends the process and every process it started, and theirs (a world's own patcher): none of them runs on. */
internal fun Process.endTree() {
  runCatching { toHandle().descendants().forEach { it.destroyForcibly() } }
  destroyForcibly()
}

/**
 * Ends the process ([endTree]) once it ran longer than [limit], without the seconds [paused] says it was held; the
 * answer becomes `true` then. Reading its output blocks until the output ends: the limit has to end it from outside.
 */
internal fun Process.endAfter(limit: Duration, paused: () -> Boolean = { false }): AtomicBoolean {
  val stopped = AtomicBoolean(false)
  Thread {
    var ran = 0L
    while (!waitFor(1, TimeUnit.SECONDS)) {
      if (!paused()) ran++
      if (ran > limit.inWholeSeconds) {
        stopped.set(true)
        endTree()
        break
      }
    }
  }.apply { isDaemon = true; name = "limit-${pid()}" }.start()
  return stopped
}

/**
 * Hands the process's output to [onLine], line by line, each cut to [max] characters as it is read, until it ends. A
 * kill closes the output (Java's "Stream closed"): a read error is its end, it is ended ([endTree]) if it isn't yet,
 * and the error goes on unless [killed] says the gateway ended it on purpose.
 */
internal fun Process.eachLine(max: Int = MAX_LOG_LINE, killed: () -> Boolean = { true }, onLine: (String) -> Unit) {
  try {
    inputStream.reader().use { reader -> reader.cutLines(max).forEach(onLine) }
  } catch (e: IOException) {
    endTree()
    if (!killed()) throw e
  }
}

/**
 * The reader's lines as [java.io.BufferedReader.readLine] ends them (`\n`, `\r` or both), each cut to [max] characters
 * while it is read: a longer one is never held whole.
 */
internal fun Reader.cutLines(max: Int): Sequence<String> = sequence {
  val buffer = CharArray(8192)
  val line = StringBuilder()
  // A line has begun (an empty last line without its end isn't one); the last character was a `\r`
  var open = false
  var afterCr = false
  while (true) {
    val n = read(buffer)
    if (n < 0) break
    for (i in 0 until n) {
      val c = buffer[i]
      if (afterCr && c == '\n') {
        afterCr = false
        continue
      }
      afterCr = c == '\r'
      if (c == '\n' || c == '\r') {
        yield(line.toString())
        line.setLength(0)
        open = false
      } else {
        if (line.length < max) line.append(c)
        open = true
      }
    }
  }
  if (open) yield(line.toString())
}

/** Where Archipelago's processes get their temporary directories (the players' YAMLs, Generate.py's output). */
private val PROCESS_TEMP: Path = Path.of(System.getProperty("java.io.tmpdir"), "randogeddon")

/** A new temporary directory for one of Archipelago's processes, removed by its caller after it. */
internal fun processTempDir(prefix: String): Path = createTempDirectory(PROCESS_TEMP.createDirectories(), prefix)

/** What a gateway before this one left in the processes' temporary directories (a killed one removes nothing). */
fun clearProcessTemp() {
  runCatching { PROCESS_TEMP.toFile().deleteRecursively() }
    .onFailure { logger.warn("Could not empty {}: {}", PROCESS_TEMP, it.message) }
}

/** How long room_info.py may take (it reads the seed's slots and passwords). */
private val ROOM_INFO_TIME = 2.minutes

/**
 * Starts MultiServer for a seed with its session's Archipelago version and options (from the database, as the admin
 * panel created it). A seed from before without them runs on the newest release with the default options.
 */
class Launcher(
  private val archipelago: Archipelago,
  private val setups: SessionSetupStore,
  /** For `!admin` remote commands; empty: disabled. */
  private val adminPassword: String,
) {
  /** The slot list for the tracker and the slot passwords (room_info.py, next to serve.py) into [roomJson]. */
  fun prepare(seed: Path, roomJson: Path) {
    val command = listOf(archipelago.python, "room_info.py", seed.absolutePathString(), roomJson.absolutePathString())
    val proc = startProcess(command, archipelago.home(archipelago.newest))
    // A hung one would hold its game server's start, and with it every other session's
    val stopped = proc.endAfter(ROOM_INFO_TIME)
    var last = ""
    proc.eachLine { if (it.isNotBlank()) last = it.trimEnd() }
    val code = proc.waitFor()
    check(!stopped.get()) { "room_info.py took longer than $ROOM_INFO_TIME" }
    check(code == 0) { "room_info.py failed: $last" }
  }

  /**
   * serve.py = MultiServer plus the hint switches (AP_POINT_HINTS, AP_AUTO_HINTS in its environment), on [port]; the
   * seed's name comes from [room].
   */
  fun launch(seed: Path, room: RoomJson, port: Int): Launch {
    val setup = room.seedName()?.let { name -> runBlocking { setups.launchOf(name) } }
    val version = setup?.first ?: archipelago.newest
    val options = setup?.second?.let { ApiJson.decodeFromString(SessionOptions.serializer(), it) } ?: SessionOptions()
    val command = buildList {
      addAll(listOf(archipelago.python, "serve.py", seed.absolutePathString(), "--host", "0.0.0.0", "--port", port.toString()))
      addAll(listOf("--hint_cost", options.hintCost.toString(), "--location_check_points", options.locationCheckPoints.toString()))
      addAll(listOf("--release_mode", options.releaseMode, "--collect_mode", options.collectMode))
      addAll(listOf("--remaining_mode", options.remainingMode, "--countdown_mode", options.countdownMode))
      addAll(listOf("--auto_shutdown", "0", "--logtime"))
    }
    val environment = mapOf("AP_POINT_HINTS" to options.pointHints.toString(), "AP_AUTO_HINTS" to options.autoHints.toString())
    // The password for `!admin` goes in over the console, not on the command line (every process here can read that)
    val first = if (adminPassword.isBlank()) emptyList() else listOf("/option server_password ${consoleWord(adminPassword)}")
    return Launch(command, environment, archipelago.home(version), first)
  }
}

/**
 * One generation's log: what ran (Generate.py's command, its directory), then its output, numbered; kept for
 * [GENERATION_LOG_KEPT] after its end, for the panel to page through and download.
 */
class GenerationLog(val version: String) {
  val lines = LogBuffer(GENERATION_LOG_LINES)
  val startedAt: Long = System.currentTimeMillis()

  @Volatile
  var endedAt: Long? = null

  /** All of it as one text, for a download: which session and release, when, then the lines. */
  fun text(id: String): String = buildString {
    append("Randogeddon: generating session ").append(id).append(" with Archipelago ").append(version).append('\n')
    append("Started ").append(Instant.ofEpochMilli(startedAt))
    endedAt?.let { append(", ended ").append(Instant.ofEpochMilli(it)) }
    append("\n\n")
    lines.all().forEach { append(it.text).append('\n') }
  }
}

/**
 * Generate.py's arguments for [request]'s settings: the plando modules its YAMLs may use (always said, by their known
 * names only: without it Generate.py takes its own default), and no progression balancing.
 */
internal fun generatorArguments(request: GenerateRequest): List<String> = buildList {
  add("--plando")
  add(request.plando.sortedBy { it.ordinal }.joinToString(", ") { it.argument })
  if (request.skipBalancing) add("--skip_prog_balancing")
}

/** [command] as a shell would take it, for the log. */
private fun shown(command: List<String>): String =
  command.joinToString(" ") { if (it.isEmpty() || it.any(Char::isWhitespace)) "'$it'" else it }

/** How much longer than a seed a spoiler log's background run may take. */
private const val BACKGROUND_TIME_FACTOR = 4

/**
 * How many Generate.py run at once: [configured] (`GENERATIONS_AT_ONCE`), or without one as many as the machine has
 * cores, less one for everything else (the game servers, the relay); at least one. Each needs a core and its memory.
 */
fun generationsAtOnce(configured: Int = 0, cores: Int = Runtime.getRuntime().availableProcessors()): Int =
  if (configured > 0) configured.coerceAtMost(32) else (cores - 1).coerceAtLeast(1)

/** Exit code 137: killed from outside, which on a server is mostly for its memory. */
internal fun killedHint(code: Int): String =
  if (code == 137) " (killed: probably out of memory; fewer generations at once need less: GENERATIONS_AT_ONCE)" else ""

/** [file]'s SHA-256, in hex (read a part at a time: an APWorld can be large). */
internal fun sha256(file: Path): String {
  val digest = MessageDigest.getInstance("SHA-256")
  DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The first of the APWorlds a seed was generated with ([hashes]: file name to SHA-256) that is another in [release]'s
 * library now; `null`: none changed.
 */
internal fun Apworlds.changedSince(release: String, hashes: Map<String, String>): String? =
  hashes.entries.firstOrNull { (file, hash) -> fileOf(release, file)?.let(::sha256) != hash }?.key

/** How many lines of a generation's output are kept, and how long after its end. */
private const val GENERATION_LOG_LINES = 20_000
private const val GENERATION_LOG_KEPT = 60 * 60_000L

/**
 * Generates seeds with Archipelago's Generate.py: the uploaded YAMLs, plus a World Locks slot for the slots that
 * start locked. The new seed goes into its session's directory, which must not have one yet. One generation at a
 * time, with the release's apworlds as they are now. When it fails, the games that may be the cause come with the log.
 * Each one's whole output is kept a while ([log]); one can be cancelled ([cancel]), also while it waits for its turn.
 */
class Generator(
  private val archipelago: Archipelago,
  private val dirs: SessionDirs,
  private val apworlds: Apworlds,
  /** How long one may take before it is stopped. */
  private val minutes: Int = 60,
  /** How many Generate.py run at once: new sessions' seeds and the spoiler logs' later levels together. */
  private val atOnce: Int = generationsAtOnce(),
  /** How often the background looks for a spoiler log to complete (or a paused run to let go on). */
  private val lookEvery: Duration = 5.seconds,
  /** The spoiler logs completed after their seeds (one for the gateway: the logic reads them too). */
  private val stages: SpoilerStages = SpoilerStages(dirs),
) {
  /** A place for each Generate.py that runs. */
  private val places = Semaphore(atOnce)
  /** The spoiler logs' runs leave a place to new sessions, where there is more than one. */
  private val backgroundPlaces = (atOnce - 1).coerceAtLeast(1)
  /** How far each running generation is, by session (from Generate.py's output as it comes). */
  private val progress = ConcurrentHashMap<String, GenerationProgress>()

  /** Each session's last generation's log (while it runs, and a while after). */
  private val logs = ConcurrentHashMap<String, GenerationLog>()
  /** Generate.py of each running generation, and the generations to end ([cancel]). */
  private val processes = ConcurrentHashMap<String, Process>()
  private val cancelled = ConcurrentHashMap.newKeySet<String>()

  /** The last look at each running Generate.py's CPU time: (its CPU time, when), both in nanoseconds. */
  private val cpuSeen = ConcurrentHashMap<String, Pair<Long, Long>>()

  /** New sessions waiting for a place: no background run starts (or goes on) meanwhile. */
  private val foregroundWaiting = AtomicInteger()
  /** The background runs going now (paused ones too), by session. */
  private val background = ConcurrentHashMap<String, BackgroundRun>()
  private val seeds = SecureRandom()

  /** A spoiler log's run at [level]. */
  private class BackgroundRun(val level: Int) {
    /** It holds one of the places (not while it is paused). */
    val place = AtomicBoolean(true)

    /** Held (`SIGSTOP`) for a new session's generation; it goes on (`SIGCONT`) once there is room. */
    @Volatile
    var paused = false

    /** It is to end and start over later: a change of the library, its session deleted, or pausing didn't hold. */
    @Volatile
    var yields = false
  }

  /**
   * How far session [id]'s generation is; `null`: none runs. With Generate.py's share of one core since the last ask
   * (so the panel can tell a working one from a stuck one while it says nothing, as in the playthrough).
   */
  fun progress(id: String): GenerationProgress? {
    val now = progress[id] ?: return null
    return now.copy(cpu = cpuShare(id))
  }

  /** The process under [key]'s share of one core since the last ask; `null`: none runs, or asked for the first time. */
  private fun cpuShare(key: String): Int? {
    val handle = processes[key]?.toHandle() ?: return null.also { cpuSeen.remove(key) }
    val cpu = (sequenceOf(handle) + handle.descendants().asSequence())
      .sumOf { it.info().totalCpuDuration().map { d -> d.toNanos() }.orElse(0L) }
    val at = System.nanoTime()
    val before = cpuSeen.put(key, cpu to at) ?: return null
    val wall = at - before.second
    if (wall <= 0) return null
    return ((cpu - before.first) * 100 / wall).toInt().coerceIn(0, 999)
  }

  /** Session [id]'s spoiler log while it is completed in the background; `null`: complete (or from before 0.19.3). */
  fun spoiler(id: String): SpoilerProgress? {
    val state = stages.read(id)?.takeIf { it.level < SpoilerStages.COMPLETE } ?: return null
    val run = background[id]
    val cpu = run?.takeIf { !it.paused }?.let { cpuShare(stageKey(id)) }
    return SpoilerProgress(state.level, run?.level, cpu, state.stopped, paused = run?.paused == true)
  }

  /** The log of session [id]'s background spoiler run (the last one); `null`: none. */
  fun spoilerLog(id: String, since: Long, max: Int): LogLines? = logs[stageKey(id)]?.lines?.after(since, max)

  /**
   * The library is about to change: the level-3 runs (the paths, the longest; paused ones too) end and start over
   * after, so the change doesn't wait for them. Level-2 runs and new sessions' generations are waited for.
   */
  fun yieldBackground() {
    background.forEach { (id, run) -> if (run.level >= SpoilerStages.COMPLETE) end(id, run) }
  }

  /**
   * Session [id]'s spoiler log is tried again from where it stopped (a run that failed or took too long; the
   * gateway may run something else now than then); `false`: it didn't stop.
   */
  fun retrySpoiler(id: String): Boolean {
    val state = stages.read(id)?.takeIf { it.stopped != null && it.level < SpoilerStages.COMPLETE } ?: return false
    stages.save(id, state.copy(stopped = null))
    logger.info("Spoiler log of {}: tried again from level {}", id, state.level)
    return true
  }

  /**
   * Session [id] goes (deleted): its generation (also one that waits for its turn) and its background run end, and
   * its logs are forgotten, so a new session of the same ID doesn't get them. Its progress goes once its generation
   * has ended (until then another generation of the ID is refused).
   */
  fun dropSpoiler(id: String) {
    cancel(id)
    background[id]?.let { end(id, it) }
    logs.remove(id)
    logs.remove(stageKey(id))
  }

  /** The run ends now, and starts over later (if its session is still there). */
  private fun end(id: String, run: BackgroundRun) {
    run.yields = true
    processes[stageKey(id)]?.endTree()
  }

  /** No place is free for a new session: a spoiler's level-3 run (the paths) is paused and gives its place. */
  private fun makeRoom() {
    if (places.availablePermits > 0) return
    val (id, run) = background.entries.firstOrNull { (_, r) -> r.level >= SpoilerStages.COMPLETE && !r.paused && !r.yields }
      ?.toPair() ?: return
    // Its place is given once; another new session's makeRoom finds it paused
    if (!run.place.compareAndSet(true, false)) return
    run.paused = true
    if (signal(stageKey(id), "STOP")) {
      logs[stageKey(id)]?.lines?.add("[gateway] Paused: a new session's generation goes first")
      logger.info("Spoiler log of {}: its level {} run is paused for a new session", id, run.level)
    } else {
      // Pausing doesn't hold: it ends, and starts over later
      end(id, run)
    }
    places.release()
  }

  /** A paused run goes on, if a place is free; `false`: not yet. */
  private fun resume(id: String, run: BackgroundRun): Boolean {
    if (!places.tryAcquire()) return false
    run.place.set(true)
    run.paused = false
    if (signal(stageKey(id), "CONT")) {
      logs[stageKey(id)]?.lines?.add("[gateway] Goes on")
      logger.info("Spoiler log of {}: its level {} run goes on", id, run.level)
    } else {
      end(id, run)
    }
    return true
  }

  /** Sends [key]'s Generate.py a signal (`STOP` holds it, `CONT` lets it go on); `false`: it didn't get it. */
  private fun signal(key: String, signal: String): Boolean {
    val pid = processes[key]?.takeIf { it.isAlive }?.pid() ?: return false
    return runCatching {
      val kill = ProcessBuilder("sh", "-c", "kill -$signal $pid").withoutSecrets().redirectErrorStream(true).start()
      kill.waitFor(5, TimeUnit.SECONDS) && kill.exitValue() == 0
    }.getOrDefault(false)
  }

  private fun runningBackground() = background.values.count { !it.paused }

  /**
   * Completes the spoiler logs of the seeds that have only a part of theirs: level 2 (the playthrough), then level 3
   * (the paths), as many at once as [backgroundPlaces], each in a place of its own, and none started while a new
   * session waits for one. Runs for as long as the gateway does; after a restart it goes on where the sessions'
   * `stages.json` say.
   */
  suspend fun completeSpoilers() = coroutineScope {
    while (true) {
      delay(lookEvery)
      if (foregroundWaiting.get() > 0) continue
      // A paused one goes on before another starts
      background.forEach { (id, run) -> if (run.paused && !run.yields && runningBackground() < backgroundPlaces) resume(id, run) }
      while (foregroundWaiting.get() == 0 && runningBackground() < backgroundPlaces) {
        val (id, state) = stages.next(background.keys.toSet()) ?: break
        if (!places.tryAcquire()) break
        val run = BackgroundRun(state.level + 1)
        background[id] = run
        launch {
          try {
            completeOne(id, state, run)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            logger.warn("Completing {}'s spoiler log: {}", id, e.message)
          } finally {
            background.remove(id)
            if (run.place.getAndSet(false)) places.release()
          }
        }
      }
    }
  }

  private suspend fun completeOne(id: String, state: SpoilerStages.State, run: BackgroundRun) {
    val level = run.level
    val home = runCatching { archipelago.home(state.release) }.getOrElse {
      stages.save(id, state.copy(stopped = "Archipelago ${state.release} is gone from the gateway"))
      return
    }
    // Only with the very APWorlds the seed was generated with: another version would place differently
    apworlds.changedSince(state.release, state.apworlds)?.let {
      stages.save(id, state.copy(stopped = "$it changed since the seed was generated"))
      return
    }
    val log = GenerationLog(state.release).also { logs[stageKey(id)] = it }
    try {
      val text = apworlds.holding(state.release) { withContext(Dispatchers.IO) { runSpoiler(id, state, run, home, log) } }
        ?: return
      val current = dirs.spoiler(id) ?: return
      if (placementsOf(text) != placementsOf(current)) {
        log.lines.add("[gateway] Its placements differ from the seed's: the spoiler log stays at level ${state.level}")
        stages.save(id, state.copy(stopped = "the placements differ from the seed's (an APWorld that doesn't place the same with the same seed)"))
        return
      }
      val file = dirs.spoilerFile(id)
      val written = file.resolveSibling("spoiler.txt.new")
      written.writeText(text)
      Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      stages.save(id, state.copy(level = level))
      log.lines.add("[gateway] The spoiler log is at level $level now")
      logger.info("Spoiler log of {} at level {}", id, level)
    } finally {
      processes.remove(stageKey(id))
      cpuSeen.remove(stageKey(id))
      log.endedAt = System.currentTimeMillis()
    }
  }

  /** Generate.py once more for session [id], only its spoiler log at [run]'s level; its text, or `null` (ended, failed). */
  private fun runSpoiler(id: String, state: SpoilerStages.State, run: BackgroundRun, home: Path, log: GenerationLog): String? {
    val level = run.level
    val output = processTempDir("spoiler")
    try {
      // Generate.py with a playthrough that doesn't give up (spoiler_run.py, next to it in the image)
      val script = SpoilerStages.SPOILER_RUN.takeIf { home.resolve(it).isRegularFile() } ?: "Generate.py"
      val command = generateCommand(script, dirs.generationDir(id).resolve("players"), output, state.seed) +
        listOf("--spoiler", level.toString(), "--spoiler_only") + state.arguments
      log.lines.add("$ cd ${home.absolutePathString()}")
      log.lines.add("$ ${shown(command)}")
      val proc = startProcess(command, home)
      processes[stageKey(id)] = proc
      if (run.yields) proc.endTree()
      val limit = minutes.toLong() * BACKGROUND_TIME_FACTOR
      // Its time, without the time it is paused
      val stopped = proc.endAfter(limit.minutes) { run.paused }
      proc.eachLine(killed = { run.yields || stopped.get() }) { log.lines.add(it) }
      val code = proc.waitFor()
      if (run.yields) {
        log.lines.add("[gateway] Ended (a change of the APWorlds, or its session is gone): it starts over later")
        return null
      }
      if (stopped.get()) {
        log.lines.add("[gateway] Stopped: it took longer than $limit minutes")
        stages.save(id, state.copy(stopped = "it took longer than $limit minutes"))
        return null
      }
      val spoiler = output.listDirectoryEntries("*_Spoiler.txt").firstOrNull()
      if (spoiler == null) {
        log.lines.add("[gateway] Generate.py ended with exit code $code, without a spoiler log${killedHint(code)}")
        stages.save(id, state.copy(stopped = "Generate.py ended with exit code $code (see its log)${killedHint(code)}"))
        return null
      }
      return spoiler.readText()
    } finally {
      output.toFile().deleteRecursively()
    }
  }

  /** Generate.py's (or [script]'s, which takes the same) command: the players' YAMLs, the output, the seed number. */
  private fun generateCommand(script: String, players: Path, output: Path, seed: Long): List<String> = listOf(
    archipelago.python, script, "--player_files_path", players.absolutePathString(),
    "--outputpath", output.absolutePathString(), "--seed", seed.toString(),
  )

  private fun stageKey(id: String) = "$id spoiler"

  /** Session [id]'s last generation's lines numbered above [since], at most [max]; `null`: none (or long ago). */
  fun log(id: String, since: Long, max: Int): LogLines? = logs[id]?.lines?.after(since, max)

  /** Session [id]'s last generation's whole log as text; `null`: none. */
  fun logText(id: String): String? = logs[id]?.text(id)

  /** Ends session [id]'s generation (also one that waits for its turn); `false`: none runs. */
  fun cancel(id: String): Boolean {
    if (!progress.containsKey(id)) return false
    cancelled += id
    processes[id]?.endTree()
    logger.info("Generating {}: cancelled", id)
    return true
  }

  /** Throws [IllegalStateException] if session [id] has a seed already, or generates one now. */
  suspend fun generate(id: String, request: GenerateRequest): GenerateResult {
    // Until a place is free it says that it waits; a spoiler's level-3 run is paused to make one
    check(progress.putIfAbsent(id, GenerationProgress(GenerationStage.WAITING, 0)) == null) {
      "Session $id is generating already"
    }
    val now = System.currentTimeMillis()
    logs.entries.removeIf { (_, log) -> log.endedAt?.let { now - it > GENERATION_LOG_KEPT } == true }
    val log = GenerationLog(request.apVersion).also { logs[id] = it }
    cancelled -= id
    val waiting = AtomicBoolean(true)
    foregroundWaiting.incrementAndGet()
    return try {
      makeRoom()
      // Looked for again and again rather than queued for: a cancel ends the wait at once
      while (!places.tryAcquire()) {
        if (id in cancelled) {
          log.lines.add("[gateway] Cancelled before its turn")
          return GenerateResult(cancelled = true)
        }
        delay(PLACE_LOOK)
      }
      try {
        if (waiting.getAndSet(false)) foregroundWaiting.decrementAndGet()
        generateNow(id, request, log)
      } finally {
        places.release()
      }
    } finally {
      if (waiting.getAndSet(false)) foregroundWaiting.decrementAndGet()
      progress.remove(id)
      cpuSeen.remove(id)
      cancelled -= id
      log.endedAt = System.currentTimeMillis()
    }
  }

  private suspend fun generateNow(id: String, request: GenerateRequest, log: GenerationLog): GenerateResult {
    if (id in cancelled) {
      log.lines.add("[gateway] Cancelled before its turn")
      return GenerateResult(cancelled = true)
    }
    check(dirs.seed(id) == null) { "Session $id has a seed already" }
    val home = runCatching { archipelago.home(request.apVersion) }.getOrElse {
      return GenerateResult(log = listOf(it.message ?: "Archipelago ${request.apVersion} is missing"))
    }
    val release = apworlds.release(request.apVersion)
    progress[id] = GenerationProgress(GenerationStage.STARTING, 0)
    // The APWorlds of its games, kept by their checksum: the later runs of its spoiler need the very same
    val used = release.games.filter { it.game in request.games }.mapNotNull { it.apworld }.toSet()
    val result = apworlds.holding(request.apVersion) {
      val hashes = withContext(Dispatchers.IO) { used.associateWith { file -> apworlds.fileOf(request.apVersion, file)?.let(::sha256).orEmpty() } }
      withContext(Dispatchers.IO) { run(id, request, home, dirs.dir(id), log, hashes) { progress[id] = it } }
    }
    // Stopped for its time or cancelled: no game is to blame
    return if (result.seedName != null || result.stoppedAfterMinutes != null || result.cancelled) result
    else result.copy(suspects = suspects(request.games, result.log, release, apworlds.files()))
  }

  private fun run(
    id: String, request: GenerateRequest, home: Path, target: Path, log: GenerationLog, apworldHashes: Map<String, String>,
    onProgress: (GenerationProgress) -> Unit,
  ): GenerateResult {
    val work = processTempDir("generate")
    try {
      val players = work.resolve("players").createDirectories()
      val output = work.resolve("output").createDirectories()
      request.yamls.forEachIndexed { i, yaml -> players.resolve(fileName(yaml.fileName, i)).writeText(yaml.content) }
      if (request.locked.isNotEmpty()) players.resolve("zz-world-locks.yaml").writeText(locksYaml(request.locked, request.earlyKeys))

      logger.info("Generating a seed from {} YAMLs with Archipelago {}", request.yamls.size, request.apVersion)
      // A seed number of its own, kept: its spoiler log's later levels are generated with it again. The seed itself only
      // with a spoiler without playthrough (level 1), so the session can start; the playthrough comes in the background
      val seed = seeds.nextLong() ushr 1
      val settings = generatorArguments(request)
      // Race mode: nobody can reproduce the seed, and it has no spoiler log (so nothing to complete after)
      val spoiler = if (request.race) listOf("--race", "--spoiler", "0") else listOf("--spoiler", "1")
      val command = generateCommand("Generate.py", players, output, seed) + spoiler + settings
      // What ran, so it can be run again by hand
      log.lines.add("$ cd ${home.absolutePathString()}")
      log.lines.add("$ ${shown(command)}")
      log.lines.add("[gateway] YAMLs: ${players.listDirectoryEntries().map { it.name }.sorted().joinToString()}")
      val proc = startProcess(command, home)
      processes[id] = proc
      // Cancelled while it started
      if (id in cancelled) proc.endTree()
      val stopped = proc.endAfter(minutes.minutes)
      // Only its end is of use (the error, the seed's name): the rest of a long output isn't kept
      val lines = ArrayDeque<String>()
      // Ended from outside (cancelled, or over its time): its output ends then
      proc.eachLine(killed = { id in cancelled || stopped.get() }) { line ->
        lines += line
        if (lines.size > MAX_LOG_LINES) lines.removeFirst()
        log.lines.add(line)
        generationProgress(line)?.let(onProgress)
      }
      val code = proc.waitFor()
      processes.remove(id)
      if (id in cancelled) {
        log.lines.add("[gateway] Cancelled")
        return GenerateResult(log = lines.takeLast(20), cancelled = true)
      }
      if (stopped.get()) {
        logger.warn("Generating took longer than {} minutes: stopped", minutes)
        log.lines.add("[gateway] Stopped: it took longer than $minutes minutes")
        return GenerateResult(log = lines.takeLast(20), stoppedAfterMinutes = minutes)
      }
      val zip = output.listDirectoryEntries("AP_*.zip").firstOrNull()
      if (zip == null) log.lines.add("[gateway] Generate.py ended with exit code $code, without a seed${killedHint(code)}")
      zip ?: return GenerateResult(
        // The error from its traceback on, not the list of every world it prints first
        log = (lines.indexOfLast { it.startsWith("Traceback") }.let { if (it >= 0) lines.drop(it).take(60) else lines.takeLast(60) } +
          listOfNotNull(killedHint(code).trim().ifEmpty { null })).ifEmpty { listOf("Generate.py ended without a seed") },
      )

      Files.move(zip, target.createDirectories().resolve(zip.name), StandardCopyOption.REPLACE_EXISTING)
      // What it was generated from, for its spoiler log's later levels (a race has none)
      if (!request.race) {
        val kept = dirs.generationDir(id).resolve("players").createDirectories()
        players.listDirectoryEntries().forEach { Files.copy(it, kept.resolve(it.name), StandardCopyOption.REPLACE_EXISTING) }
        stages.save(id, SpoilerStages.State(seed, request.apVersion, level = 1, apworlds = apworldHashes, arguments = settings))
      }
      val seedName = zip.name.removePrefix("AP_").removeSuffix(".zip")
      logger.info("Generated seed {}", seedName)
      log.lines.add("[gateway] Seed $seedName")
      return GenerateResult(seedName = seedName, seedFile = zip.name, log = lines.takeLast(10))
    } finally {
      processes.remove(id)
      work.toFile().deleteRecursively()
    }
  }

  /**
   * Without any path, only letters, digits, dots, dashes and underscores; every file a `.yaml`, numbered so names
   * can't collide.
   */
  private fun fileName(given: String, index: Int): String {
    val base = given.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
      .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "player" }
    return "%03d-%s.yaml".format(index, base)
  }

  /** The World Locks slot (apworlds/world_locks): nobody plays it; it holds the keys. Names as JSON strings. */
  private fun locksYaml(locked: List<String>, earlyKeys: Boolean): String = """
    |name: Locks
    |game: World Locks
    |World Locks:
    |  locked_worlds: [${locked.joinToString { JsonPrimitive(it).toString() }}]
    |  early_keys: $earlyKeys
    |""".trimMargin()
}

/** Generate.py's stages by what it logs at their start, with about how far along that is in all (filling takes longest). */
private val STAGES = listOf(
  "Creating MultiWorld." to GenerationProgress(GenerationStage.CREATING, 5),
  "Creating Items." to GenerationProgress(GenerationStage.ITEMS, 10),
  "Calculating Access Rules." to GenerationProgress(GenerationStage.RULES, 15),
  "Running Item Plando." to GenerationProgress(GenerationStage.PLANDO, 25),
  "Running Pre Main Fill." to GenerationProgress(GenerationStage.PREFILL, 30),
  "Filling the multiworld" to GenerationProgress(GenerationStage.FILLING, 30),
  "Balancing multiworld progression" to GenerationProgress(GenerationStage.BALANCING, 52),
  "Progression balancing skipped." to GenerationProgress(GenerationStage.BALANCING, 54),
  "Running pre-output steps." to GenerationProgress(GenerationStage.PREOUTPUT, 56),
  "Beginning output" to GenerationProgress(GenerationStage.OUTPUT, 58),
  "Calculating playthrough." to GenerationProgress(GenerationStage.PLAYTHROUGH, 66),
  "Creating final archive" to GenerationProgress(GenerationStage.ARCHIVE, 97),
  "Done." to GenerationProgress(GenerationStage.DONE, 100),
)

/** How much of Generate.py's output is kept for its result: its last lines (each cut to [MAX_LOG_LINE]). */
private const val MAX_LOG_LINES = 2000

/** How often a generation that waits for its place looks for one (and for its cancel). */
private val PLACE_LOOK = 200.milliseconds

private val OUTPUT_FILES = Regex("""Generating output files \((\d+)/(\d+)\)""")
private val FILL_STEP = Regex("""Current fill step \(.+\) at (\d+)/(\d+) items placed""")

/**
 * The stage a line of Generate.py's output starts, if it starts one. The output files count themselves up, and so
 * does the fill (30 to 50 %; it only logs every 1000 items, so a small multiworld waits at its start). The playthrough
 * (66 to 97 %) says nothing until it is done: with Archipelago's spoiler level 3 (paths to every item) it can take
 * longer than all the rest.
 */
internal fun generationProgress(line: String): GenerationProgress? {
  FILL_STEP.find(line)?.destructured?.let { (placed, all) ->
    return GenerationProgress(GenerationStage.FILLING, 30 + 20 * placed.toInt() / all.toInt().coerceAtLeast(1))
  }
  OUTPUT_FILES.find(line)?.destructured?.let { (done, all) ->
    return GenerationProgress(GenerationStage.OUTPUT, 58 + 8 * done.toInt() / all.toInt().coerceAtLeast(1))
  }
  return STAGES.firstOrNull { (marker, _) -> marker in line }?.second
}
