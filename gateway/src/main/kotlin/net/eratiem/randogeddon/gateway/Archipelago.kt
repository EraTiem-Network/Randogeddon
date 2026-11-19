package net.eratiem.randogeddon.gateway

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.streams.asSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

  fun home(version: String): Path {
    val home = root.resolve(version)
    require(home.resolve("MultiServer.py").exists()) {
      "Archipelago $version is not installed in this gateway (it has ${versions().joinToString().ifEmpty { "none" }})"
    }
    return home
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
    val proc = ProcessBuilder(archipelago.python, "room_info.py", seed.absolutePathString(), roomJson.absolutePathString())
      .withoutSecrets().directory(archipelago.home(archipelago.newest).toFile()).redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader().readText()
    check(proc.waitFor() == 0) { "room_info.py failed: ${output.trim().lines().lastOrNull()}" }
  }

  /**
   * serve.py = MultiServer plus the hint switches (AP_POINT_HINTS, AP_AUTO_HINTS in its environment), on [port]; the
   * seed's name comes from [roomJson].
   */
  fun launch(seed: Path, roomJson: Path, port: Int): Launch {
    val setup = readSeedName(roomJson)?.let { name -> runBlocking { setups.launchOf(name) } }
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
) {
  private val mutex = Mutex()
  /** How far each running generation is, by session (from Generate.py's output as it comes). */
  private val progress = ConcurrentHashMap<String, GenerationProgress>()

  /** Each session's last generation's log (while it runs, and a while after). */
  private val logs = ConcurrentHashMap<String, GenerationLog>()
  /** Generate.py of each running generation, and the generations to end ([cancel]). */
  private val processes = ConcurrentHashMap<String, Process>()
  private val cancelled = ConcurrentHashMap.newKeySet<String>()

  /** The last look at each running Generate.py's CPU time: (its CPU time, when), both in nanoseconds. */
  private val cpuSeen = ConcurrentHashMap<String, Pair<Long, Long>>()

  /**
   * How far session [id]'s generation is; `null`: none runs. With Generate.py's share of one core since the last ask
   * (so the panel can tell a working one from a stuck one while it says nothing, as in the playthrough).
   */
  fun progress(id: String): GenerationProgress? {
    val now = progress[id] ?: return null
    val handle = processes[id]?.toHandle() ?: return now.also { cpuSeen.remove(id) }
    val cpu = (sequenceOf(handle) + handle.descendants().asSequence())
      .sumOf { it.info().totalCpuDuration().map { d -> d.toNanos() }.orElse(0L) }
    val at = System.nanoTime()
    val before = cpuSeen.put(id, cpu to at) ?: return now
    val wall = at - before.second
    if (wall <= 0) return now
    return now.copy(cpu = ((cpu - before.first) * 100 / wall).toInt().coerceIn(0, 999))
  }

  /** Session [id]'s last generation's lines numbered above [since], at most [max]; `null`: none (or long ago). */
  fun log(id: String, since: Long, max: Int): LogLines? = logs[id]?.lines?.after(since, max)

  /** Session [id]'s last generation's whole log as text; `null`: none. */
  fun logText(id: String): String? = logs[id]?.text(id)

  /** Ends session [id]'s generation (also one that waits for its turn); `false`: none runs. */
  fun cancel(id: String): Boolean {
    if (!progress.containsKey(id)) return false
    cancelled += id
    processes[id]?.destroyForcibly()
    logger.info("Generating {}: cancelled", id)
    return true
  }

  /** Throws [IllegalStateException] if session [id] has a seed already. */
  suspend fun generate(id: String, request: GenerateRequest): GenerateResult {
    val now = System.currentTimeMillis()
    logs.entries.removeIf { (_, log) -> log.endedAt?.let { now - it > GENERATION_LOG_KEPT } == true }
    val log = GenerationLog(request.apVersion).also { logs[id] = it }
    cancelled -= id
    // One at a time: until it is this one's turn, it says that it waits
    progress[id] = GenerationProgress(GenerationStage.WAITING, 0)
    return try {
      mutex.withLock { generateNow(id, request, log) }
    } finally {
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
    val result = apworlds.holding(request.apVersion) {
      withContext(Dispatchers.IO) { run(id, request, home, dirs.dir(id), log) { progress[id] = it } }
    }
    // Stopped for its time or cancelled: no game is to blame
    return if (result.seedName != null || result.stoppedAfterMinutes != null || result.cancelled) result
    else result.copy(suspects = suspects(request.games, result.log, release, apworlds.files()))
  }

  private fun run(
    id: String, request: GenerateRequest, home: Path, target: Path, log: GenerationLog, onProgress: (GenerationProgress) -> Unit,
  ): GenerateResult {
    val work = createTempDirectory("generate")
    try {
      val players = work.resolve("players").createDirectories()
      val output = work.resolve("output").createDirectories()
      request.yamls.forEachIndexed { i, yaml -> players.resolve(fileName(yaml.fileName, i)).writeText(yaml.content) }
      if (request.locked.isNotEmpty()) players.resolve("zz-world-locks.yaml").writeText(locksYaml(request.locked, request.earlyKeys))

      logger.info("Generating a seed from {} YAMLs with Archipelago {}", request.yamls.size, request.apVersion)
      val command = listOf(
        archipelago.python, "Generate.py", "--player_files_path", players.absolutePathString(),
        "--outputpath", output.absolutePathString(),
      )
      // What ran, so it can be run again by hand
      log.lines.add("$ cd ${home.absolutePathString()}")
      log.lines.add("$ ${command.joinToString(" ") { if (it.any(Char::isWhitespace)) "'$it'" else it }}")
      log.lines.add("[gateway] YAMLs: ${players.listDirectoryEntries().map { it.name }.sorted().joinToString()}")
      val proc = ProcessBuilder(command).withoutSecrets().directory(home.toFile()).redirectErrorStream(true).start()
      processes[id] = proc
      // Cancelled while it started
      if (id in cancelled) proc.destroyForcibly()
      // After an error Generate.py waits for Enter (an atexit input()); no stdin makes that an EOF, so it ends
      proc.outputStream.close()
      // Reading blocks until the output ends: the time limit has to end the process from outside
      val stopped = AtomicBoolean(false)
      Thread {
        if (!proc.waitFor(minutes.toLong(), TimeUnit.MINUTES)) {
          stopped.set(true)
          proc.destroyForcibly()
        }
      }.apply { isDaemon = true }.start()
      // Only its end is of use (the error, the seed's name): the rest of a long output isn't kept
      val lines = ArrayDeque<String>()
      try {
        proc.inputStream.bufferedReader().useLines { output ->
          output.forEach { line ->
            lines += line.take(MAX_LOG_LINE)
            if (lines.size > MAX_LOG_LINES) lines.removeFirst()
            log.lines.add(line.take(MAX_LOG_LINE))
            generationProgress(line)?.let(onProgress)
          }
        }
      } catch (e: IOException) {
        // Ended from outside (cancelled, or over its time): Java closes its output then ("Stream closed")
        if (id !in cancelled && !stopped.get()) throw e
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
      if (zip == null) log.lines.add("[gateway] Generate.py ended with exit code $code, without a seed")
      zip ?: return GenerateResult(
        // The error from its traceback on, not the list of every world it prints first
        log = lines.indexOfLast { it.startsWith("Traceback") }.let { if (it >= 0) lines.drop(it).take(60) else lines.takeLast(60) }
          .ifEmpty { listOf("Generate.py ended without a seed") },
      )

      Files.move(zip, target.createDirectories().resolve(zip.name), StandardCopyOption.REPLACE_EXISTING)
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

/** How much of Generate.py's output is kept: its last lines, each cut. */
private const val MAX_LOG_LINES = 2000
private const val MAX_LOG_LINE = 2000

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
