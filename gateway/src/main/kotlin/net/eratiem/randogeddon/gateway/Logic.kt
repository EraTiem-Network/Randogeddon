package net.eratiem.randogeddon.gateway

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AvailableAnswer
import net.eratiem.randogeddon.shared.AvailableRequest
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.LogicProgress
import net.eratiem.randogeddon.shared.LogicStatus
import net.eratiem.randogeddon.shared.SlotMap
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Logic")

/** The script next to Generate.py in the image. */
const val LOGIC_RUN = "logic_run.py"

/** What the script's answers start with; every other line is its log. */
private const val PREFIX = "[randogeddon-logic] "

/** How many lines of each session's logic log are kept. */
private const val LOGIC_LOG_LINES = 5_000

/** How long it stays after its game server ended, at least (with game servers that never sleep: after a stop). */
private const val LINGER_MINUTES = 10

/** How long a build may take. */
private val BUILD_TIME = 30.minutes

/**
 * The longest line of the logic's output that is read (characters): an answer with the maps of a large world is some
 * MB; a longer line (a world that prints without a line end) is cut as it is read, and as an answer it fails.
 */
internal const val MAX_LOGIC_LINE = 64 * 1024 * 1024

/** The longest reason a slot's mismatch is kept with (one line of the script's). */
private const val MAX_MISMATCH = 500

/**
 * The slots whose rebuild doesn't fit the seed, from the script's ready line (`"mismatches": {"<slot>": "<why>"}`), each
 * reason on one line; none from a script of before.
 */
internal fun mismatchesOf(ready: JsonObject): Map<Int, String> =
  (ready["mismatches"] as? JsonObject).orEmpty().entries.mapNotNull { (slot, why) ->
    val reason = (why as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    slot.toIntOrNull()?.let { it to oneLine(reason).take(MAX_MISMATCH) }
  }.toMap()

/**
 * Each session's logic: its seed's worlds generated again up to the fill (`logic_run.py`, with the seed's number and
 * inputs, as for its spoiler log), kept running to answer how many of each slot's locations its items reach now (the
 * available checks). It starts with the session's game server and ends as long after that one ended as the game
 * servers wait before they sleep (so twice that long without players), at least [LINGER_MINUTES]. Only for seeds
 * generated here since 0.19.3 (their inputs are kept); not for races or seeds from elsewhere.
 */
class Logic(
  private val archipelago: Archipelago,
  private val dirs: SessionDirs,
  private val apworlds: Apworlds,
  private val scope: CoroutineScope,
  /** How many are built at once (each a short Generate.py without its fill). */
  atOnce: Int = 2,
  /** The seeds' kept inputs (one for the gateway: the generator writes them). */
  private val stages: SpoilerStages = SpoilerStages(dirs),
  /** How long an answer may take. */
  private val answerTime: Duration = 60.seconds,
) {
  private val builds = Semaphore(atOnce)
  private val runs = ConcurrentHashMap<String, Run>()
  private val logs = ConcurrentHashMap<String, LogBuffer>()
  /** When each session's game server ended, while its logic waits to end too. */
  private val endedAt = ConcurrentHashMap<String, Long>()

  private class Run {
    @Volatile
    var status = LogicStatus.STARTING

    @Volatile
    var since = System.currentTimeMillis()

    @Volatile
    var problem: String? = null

    @Volatile
    var process: Process? = null

    /** Ended on purpose ([stop]): not a failure. */
    @Volatile
    var stopping = false
    var job: Job? = null
    val ready = CompletableFuture<Boolean>()
    val answers = Channel<JsonObject>(Channel.UNLIMITED)
    val asking = Mutex()

    /** The last question's number (under [asking]): an answer that gives it back is taken for that question only. */
    var asked = 0L

    /**
     * Answers still to come for questions whose wait ran out (under [asking]), from a script that doesn't give the
     * number back: it answers in order, so the next question drops that many first, within its own wait.
     */
    var owed = 0

    /**
     * The slots whose rebuilt worlds don't fit the seed's own order (its spheres), with what didn't fit, as the script
     * said when it was built: they are answered all the same, and every answer says so.
     */
    @Volatile
    var mismatches: Map<Int, String> = emptyMap()

    fun to(status: LogicStatus, problem: String? = null) {
      this.status = status
      this.problem = problem
      since = System.currentTimeMillis()
    }
  }

  /** Session [id]'s logic; `null`: it can have none. */
  fun progress(id: String): LogicProgress? {
    if (stages.read(id) == null) return null
    val run = runs[id] ?: return LogicProgress(LogicStatus.STOPPED)
    return LogicProgress(run.status, run.since, run.problem)
  }

  /** Session [id]'s logic log: its lines numbered above [since]; `null`: it has none yet. */
  fun log(id: String, since: Long): LogLines? = logs[id]?.after(since, 1000)

  /** Its game server runs: its logic too (built unless it is there; not again after a failed build). */
  fun serverRunning(id: String) {
    endedAt.remove(id)
    start(id)
  }

  /** Its game server ended: its logic ends [lingerMinutes] later, unless the game server runs again first. */
  fun serverEnded(id: String) {
    if (runs.containsKey(id)) endedAt[id] = System.currentTimeMillis()
  }

  /** Ends the logic of the game servers that ended long enough ago; [idleMinutes]: when a game server sleeps. */
  fun sweep(idleMinutes: Int): List<String> {
    val linger = idleMinutes.coerceAtLeast(LINGER_MINUTES).minutes.inWholeMilliseconds
    val now = System.currentTimeMillis()
    return endedAt.filter { (_, at) -> now - at >= linger }.keys.onEach { id ->
      endedAt.remove(id)
      logs[id]?.add("[gateway] Ended: its game server hasn't run for $idleMinutes min")
      stop(id)
    }.toList()
  }

  /** Built again from the start (by a Session-Manager or an Administrator, also after a failed build). */
  fun rebuild(id: String): Boolean {
    if (stages.read(id) == null) return false
    stop(id)
    start(id)
    return true
  }

  /** Ends session [id]'s logic (its session sleeps long enough, is deleted, or the gateway stops). */
  fun stop(id: String) {
    val run = runs.remove(id) ?: return
    run.stopping = true
    // What a build waits for: its build place and the APWorld library are free at once
    run.ready.complete(false)
    run.process?.endTree()
    run.job?.cancel()
    run.answers.close()
  }

  fun stopAll() = runs.keys.toList().forEach(::stop)

  /** Session [id] is deleted: its log and its game server's end are forgotten (after [stop]), for a new one of its ID. */
  fun forget(id: String) {
    logs.remove(id)
    endedAt.remove(id)
  }

  /**
   * How many of each asked slot's unchecked locations its logic reaches; `null`: it isn't running, or no answer came
   * within [answerTime]. The question is passed on as it is (with the keys found, [AvailableRequest.locks]), with its
   * number (`id`): an answer that gives it back is taken for it only, and a late one of a question before is dropped
   * when it comes. A script that doesn't give it back answers in order: the late answers it owes are dropped as they
   * come, within this question's one wait (not a wait of its own for each). Every question gets an answer line (one
   * that can't be read counts as a failed one), so none is lost on the way.
   */
  suspend fun available(id: String, request: AvailableRequest): AvailableAnswer? {
    val run = runs[id]?.takeIf { it.status == LogicStatus.RUNNING } ?: return null
    val process = run.process ?: return null
    return run.asking.withLock {
      val number = ++run.asked
      // One line, as the script reads it: {"slots": [{"slot": …, "items": […], "checked": […]}], …, "id": 7}
      val question = ApiJson.encodeToJsonElement(AvailableRequest.serializer(), request).jsonObject
      val line = JsonObject(question + ("id" to JsonPrimitive(number))).toString()
      val written = runCatching {
        withContext(Dispatchers.IO) {
          process.outputStream.write((line + "\n").toByteArray())
          process.outputStream.flush()
        }
      }.isSuccess
      if (!written) return@withLock null
      val until = TimeSource.Monotonic.markNow() + answerTime
      var answer: JsonObject? = null
      while (answer == null) {
        val left = -until.elapsedNow()
        val received = if (left.isPositive()) withTimeoutOrNull(left) { run.answers.receiveCatching() } else null
        if (received == null) {
          run.owed++
          return@withLock null
        }
        val message = received.getOrNull() ?: return@withLock null
        val answered = (message["id"] as? JsonPrimitive)?.longOrNull
        when {
          answered != null -> if (answered == number) answer = message
          // Without its number: the late ones come first
          run.owed > 0 -> run.owed--
          else -> answer = message
        }
      }
      val reply = answer ?: return@withLock null
      val answers = reply["available"]?.jsonObject ?: return@withLock null
      // Each slot's reachable locations (a number from a logic before 0.21.8: only how many)
      val locations = answers.entries.mapNotNull { (slot, found) ->
        (found as? JsonArray)?.let { list -> slot.toInt() to list.mapNotNull { it.jsonPrimitive.longOrNull } }
      }.toMap()
      // The maps asked for (none from a logic before 0.22.4)
      val maps = reply["maps"]?.jsonObject?.entries?.mapNotNull { (slot, map) ->
        runCatching { slot.toInt() to ApiJson.decodeFromJsonElement(SlotMap.serializer(), map) }.getOrNull()
      }?.toMap().orEmpty()
      // Which of each slot's map's regions it reaches (none from a logic before 0.23.2.6)
      val regions = reply["regions"]?.jsonObject?.entries?.mapNotNull { (slot, reached) ->
        (reached as? JsonArray)?.let { list -> slot.toInt() to list.mapNotNull { it.jsonPrimitive.intOrNull } }
      }?.toMap().orEmpty()
      // The asked slots whose rebuild doesn't fit the seed: with every answer
      val asked = request.slots.map { it.slot }.toSet() + request.maps
      AvailableAnswer(
        answers.entries.associate { (slot, found) -> slot.toInt() to ((found as? JsonArray)?.size ?: (found as? JsonPrimitive)?.intOrNull) },
        locations,
        maps,
        regions,
        run.mismatches.filterKeys { it in asked },
      )
    }
  }

  private fun start(id: String) {
    val run = Run()
    // Built or being built; a failed build stays failed until it is built again by hand ([rebuild] ends it first)
    if (runs.putIfAbsent(id, run) != null) return
    val log = logs.computeIfAbsent(id) { LogBuffer(LOGIC_LOG_LINES) }
    run.job = scope.launch {
      runCatching { build(id, run, log) }.onFailure { e ->
        if (!run.stopping) {
          log.add("[gateway] Failed: ${e.message}")
          run.to(LogicStatus.FAILED, e.message)
          run.process?.endTree()
        }
      }
    }
  }

  private suspend fun build(id: String, run: Run, log: LogBuffer) {
    val state = stages.read(id) ?: error("its seed's inputs aren't kept")
    val seed = dirs.seed(id) ?: error("it has no seed")
    val home = runCatching { archipelago.home(state.release) }.getOrElse { error("Archipelago ${state.release} is gone from the gateway") }
    // Only with the very APWorlds the seed was generated with: another version would build other worlds
    apworlds.changedSince(state.release, state.apworlds)?.let { error("$it changed since the seed was generated") }
    if (!home.resolve(LOGIC_RUN).isRegularFile()) error("$LOGIC_RUN isn't in this gateway's image")
    log.add("[gateway] Waiting for its turn to be built")
    builds.withPermit {
      // The library stays as it is while the worlds are loaded
      apworlds.holding(state.release) {
        withContext(Dispatchers.IO) { launchScript(id, run, log, state, seed, home) }
        val built = withContext(Dispatchers.IO) {
          runCatching { run.ready.get(BUILD_TIME.inWholeSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        }
        if (!built) {
          if (run.stopping) return@holding
          run.process?.endTree()
          error(run.problem ?: "it wasn't built within ${BUILD_TIME.inWholeMinutes} min")
        }
      }
    }
    if (run.stopping) return
    run.to(LogicStatus.RUNNING)
    log.add("[gateway] Running: it answers how many checks each slot can make now")
    logger.info("Logic of {} running", id)
  }

  private fun launchScript(id: String, run: Run, log: LogBuffer, state: SpoilerStages.State, seed: Path, home: Path) {
    val output = processTempDir("logic")
    val command = listOf(
      archipelago.python, LOGIC_RUN, seed.absolutePathString(),
      "--player_files_path", dirs.generationDir(id).resolve("players").absolutePathString(),
      "--outputpath", output.absolutePathString(), "--seed", state.seed.toString(), "--spoiler", "1",
    ) + state.arguments
    log.add("[gateway] Building: the seed's worlds up to the fill")
    log.add("$ cd ${home.absolutePathString()}")
    log.add("$ ${command.joinToString(" ")}")
    // Its questions go to its standard input
    val process = try {
      startProcess(command, home, input = true, worlds = true)
    } catch (e: Exception) {
      deleteTree(output)
      throw e
    }
    run.process = process
    if (run.stopping) process.endTree()
    Thread {
      // An answer is one line (a slot's map can make it long), each cut at MAX_LOGIC_LINE as it is read, never held
      // longer; what a world printed before it on the same line (without a line end) is its log
      process.eachLine(max = MAX_LOGIC_LINE) { line ->
        // The script writes one before each answer (so no answer is glued to what a world printed): nothing to keep
        if (line.isBlank()) return@eachLine
        val at = line.indexOf(PREFIX)
        if (at < 0) {
          log.add(line.take(MAX_LOG_LINE))
          return@eachLine
        }
        line.substring(0, at).takeIf { it.isNotBlank() }?.let { log.add(it.take(MAX_LOG_LINE)) }
        val message = runCatching { ApiJson.parseToJsonElement(line.substring(at + PREFIX.length)).jsonObject }.getOrNull()
        when {
          message == null -> {
            log.add("[gateway] An answer that can't be read (cut at ${MAX_LOGIC_LINE / 1024 / 1024} M characters, or no JSON): " +
              line.substring(at).take(MAX_LOG_LINE))
            // Its question gets no answer at once, not after its whole wait
            if (run.ready.isDone) run.answers.trySend(JsonObject(mapOf("error" to JsonPrimitive("its answer can't be read"))))
          }
          message.containsKey("ready") -> {
            run.mismatches = mismatchesOf(message)
            // Each said once, in the logic's log
            run.mismatches.toSortedMap().forEach { (slot, why) ->
              log.add("[gateway] Slot $slot: its rebuilt worlds don't fit the seed's order (its numbers and map may be wrong): $why")
            }
            run.ready.complete(true)
          }
          else -> {
            message["error"]?.jsonPrimitive?.contentOrNull
              ?.let { log.add("[gateway] An answer failed: ${it.take(MAX_LOG_LINE)}") }
            run.answers.trySend(message)
          }
        }
      }
      val code = process.waitFor()
      deleteTree(output)
      if (!run.stopping) {
        val why = "it ended with exit code $code${killedHint(code)}"
        log.add("[gateway] ${why.replaceFirstChar { it.uppercase() }}")
        run.to(LogicStatus.FAILED, why)
        run.ready.complete(false)
        run.answers.close()
        logger.warn("Logic of {}: {}", id, why)
      }
      // Whatever ended it, its build doesn't wait any more
      run.ready.complete(false)
    }.apply { isDaemon = true; name = "logic-$id" }.start()
  }
}
