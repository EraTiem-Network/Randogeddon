package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.shared.LogLines
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.GameServer")

/** How many lines of a game server's output are kept (the panel pages through them). */
private const val LOG_LINES = 10_000

/**
 * One start of MultiServer: the command line, extra environment variables and the directory to run in. [firstLines]:
 * console lines it gets right after the start, not shown in the log (what must not stand on the command line, which
 * every process of the container can read).
 */
data class Launch(
  val command: List<String>,
  val environment: Map<String, String> = emptyMap(),
  val workDir: Path? = null,
  val firstLines: List<String> = emptyList(),
)

private val PASSWORD_FIELD = Regex("""(['"]password['"]\s*:\s*)(['"])(.*?)\2""")
/** A password's value that a cut line ([MAX_LOG_LINE]) left without its closing quote: up to the line's end. */
private val PASSWORD_OPEN = Regex("""(['"]password['"]\s*:\s*)(['"])(?:(?!\2).)*$""", RegexOption.DOT_MATCHES_ALL)
private val PASSWORD_OPTION = Regex("""((?:Set option|/option|Option)\s+(?:server_)?password(?:\s+(?:to|is set to))?\s+).+""")

/**
 * [line] of the game server's log without passwords: MultiServer logs a refused `Connect` whole, with the slot's
 * password in it, answers `/option password …` with the new one and lists both passwords for `/options`. The log is
 * read in the panel by the session's managers, who don't get the players' slot passwords otherwise, nor the host's
 * password for `!admin` (one for every session).
 */
internal fun withoutPasswords(line: String): String {
  val fields = PASSWORD_FIELD.replace(line) { "${it.groupValues[1]}${it.groupValues[2]}***${it.groupValues[2]}" }
  val open = PASSWORD_OPEN.replace(fields) { "${it.groupValues[1]}${it.groupValues[2]}***" }
  return PASSWORD_OPTION.replace(open) { "${it.groupValues[1]}***" }
}

/** [line] as one console line: MultiServer's console takes every line break (`\r` too) as the end of a command. */
internal fun oneLine(line: String): String = withoutControls(line, ' ')

/**
 * How to run MultiServer: [prepare] before each start (writes room.json), then [launch] for the seed file (which may
 * fail, e.g. for an Archipelago version this gateway doesn't have). Both are given from outside, so tests can use a
 * small script instead of Archipelago.
 */
class GameServerConfig(
  val dataDir: Path,
  val launch: (seed: Path) -> Launch,
  val prepare: (seed: Path) -> Unit = {},
  /** Until a seed shows up, the data directory is looked at this often. */
  val seedPoll: Duration = 15.seconds,
  /** The first pause before restarting after a crash; doubles up to [maxBackoff]. */
  val backoff: Duration = 1.seconds,
  val maxBackoff: Duration = 30.seconds,
  /**
   * After this many crashes in a row, each within [quickCrash] of its start, it isn't started again by itself
   * ([GameServerStatus.FAILED]): a seed MultiServer can't serve would otherwise be started every [maxBackoff] for good.
   */
  val giveUpAfter: Int = 5,
  val quickCrash: Duration = 60.seconds,
  /** After `/exit`, how long MultiServer gets to save and end before it is killed. */
  val stopTimeout: Duration = 10.seconds,
  /** MultiServer on this seed (file name) accepts clients now; for the session time. */
  val onRunning: (seed: String) -> Unit = {},
  /** It ended (stopped, crashed or killed) after it had been running. */
  val onEnded: (seed: String) -> Unit = {},
  /** Why this seed must not be served (its session is finished); `null`: it may. Asked before every start. */
  val refuse: (seed: Path) -> String? = { null },
)

/**
 * Runs MultiServer on the newest seed in the data directory and keeps it running: a crashed server starts again after
 * a growing pause; a stop by an admin stays until the next start. Its output goes into a buffer of the last lines
 * (for the admin panel) and the gateway's log; console commands go to its stdin.
 */
class GameServer(private val config: GameServerConfig, private val scope: CoroutineScope) {
  private val mutex = Mutex()
  private val log = LogBuffer(LOG_LINES)
  // Set by the supervisor's thread, read by the API's
  @Volatile
  private var process: Process? = null

  @Volatile
  private var stdin: BufferedWriter? = null
  private var supervisor: Job? = null

  // A stop right after a start, before the process is there: the supervisor doesn't start it then (a blocking start
  // isn't cancelled, and the stop would wait for it forever). Both under [processLock].
  @Volatile
  private var stopping = false
  private val processLock = Any()

  @Volatile
  var state = GameServerState(GameServerStatus.STOPPED)
    private set

  /**
   * Starts it (again) if it isn't running; resets the restart count. [onlyIdle]: only if it sleeps, as looked at under
   * its lock (a player's wake: a stop by an admin while the wake waited for its turn keeps it stopped).
   */
  suspend fun start(onlyIdle: Boolean = false) = mutex.withLock {
    if (supervisor?.isActive == true) return@withLock
    if (onlyIdle && state.status != GameServerStatus.IDLE) return@withLock
    // A refused seed (its session is finished) stays stopped right away: not starting even for a moment, it takes no
    // port from a session that runs (the gateway starts them all at once)
    val seed = withContext(Dispatchers.IO) { newestSeed() }
    val refusal = seed?.let { withContext(Dispatchers.IO) { refusalOf(it) } }
    if (seed != null && refusal != null) {
      note("Not starting ${seed.name}: $refusal")
      state = state.copy(status = GameServerStatus.STOPPED, seed = seed.name, since = null)
      return@withLock
    }
    // Right away, so the answer to a start says so; the supervisor goes on to running (or waiting for a seed)
    state = state.copy(status = GameServerStatus.STARTING, restarts = 0)
    stopping = false
    supervisor = scope.launch(Dispatchers.IO) { supervise() }
  }

  /**
   * Asks MultiServer to save and end (`/exit`), kills it if it doesn't, and keeps it stopped. One that sleeps stays
   * stopped too: a player's connection doesn't start it then.
   */
  suspend fun stop() = mutex.withLock {
    if (supervisor == null && state.status != GameServerStatus.IDLE) return@withLock
    end()
    state = state.copy(status = GameServerStatus.STOPPED, since = null)
    note("Stopped by an admin")
  }

  /**
   * Ends it like [stop] (if it runs), as [GameServerStatus.IDLE]: nobody plays, and a player's connection starts it
   * again ([why] in its log). A session that has no seed yet or must not be served (finished) doesn't sleep, nor one
   * whose state, as looked at under its lock, isn't [onlyIf] (the sweep's: still running, still without players).
   * `true`: it sleeps now.
   */
  suspend fun sleep(why: String, onlyIf: (GameServerState) -> Boolean = { true }): Boolean = mutex.withLock {
    if (!onlyIf(state)) return@withLock false
    val seed = withContext(Dispatchers.IO) { newestSeed() } ?: return@withLock false
    val refusal = withContext(Dispatchers.IO) { refusalOf(seed) }
    if (refusal != null) {
      if (supervisor == null) state = state.copy(status = GameServerStatus.STOPPED, seed = seed.name, since = null)
      return@withLock false
    }
    end()
    state = state.copy(status = GameServerStatus.IDLE, seed = seed.name, since = null)
    note(why)
    true
  }

  /** Ends the supervisor and its MultiServer (under [mutex]). */
  private suspend fun end() {
    val job = supervisor ?: return
    supervisor = null
    stopping = true
    job.cancel()
    withContext(Dispatchers.IO) { endProcess() }
    job.join()
  }

  suspend fun restart() {
    stop()
    start()
  }

  /** Writes [line] to the console; `false` if no server is running. */
  suspend fun command(line: String): Boolean = mutex.withLock {
    val writer = stdin ?: return@withLock false
    note("> ${oneLine(line)}")
    withContext(Dispatchers.IO) {
      writer.write(oneLine(line))
      writer.newLine()
      writer.flush()
    }
    true
  }

  fun log(since: Long): LogLines = log.after(since)

  private suspend fun supervise() {
    var backoff = config.backoff
    var quickCrashes = 0
    while (true) {
      val seed = newestSeed()
      if (seed == null) {
        if (state.status != GameServerStatus.NO_SEED) note("No seed in ${config.dataDir} yet")
        state = state.copy(status = GameServerStatus.NO_SEED, seed = null, since = null)
        delay(config.seedPoll)
        continue
      }
      val refusal = refusalOf(seed)
      if (refusal != null) {
        // Stays stopped, as after a stop by an admin; a start asks again
        note("Not starting ${seed.name}: $refusal")
        state = state.copy(status = GameServerStatus.STOPPED, seed = seed.name, since = null)
        return
      }
      state = state.copy(status = GameServerStatus.STARTING, seed = seed.name, since = System.currentTimeMillis())
      val started = System.currentTimeMillis()
      val code = run(seed)
      // Ended because an admin stopped it: not a crash
      if (!currentCoroutineContext().isActive) return
      // A run that lasted a while was no crash loop: the next crash starts with the short pause again
      val ran = System.currentTimeMillis() - started
      if (ran > config.maxBackoff.inWholeMilliseconds * 2) backoff = config.backoff
      quickCrashes = if (ran < config.quickCrash.inWholeMilliseconds) quickCrashes + 1 else 0
      if (quickCrashes >= config.giveUpAfter) {
        state = state.copy(status = GameServerStatus.FAILED, since = null, lastExitCode = code, restarts = state.restarts + 1)
        note("Game server ended with exit code $code, $quickCrashes times in a row within ${config.quickCrash} of its start; " +
          "it stays stopped until it is started again")
        return
      }
      state = state.copy(status = GameServerStatus.CRASHED, since = null, lastExitCode = code, restarts = state.restarts + 1)
      note("Game server ended with exit code $code; starting again in $backoff")
      delay(backoff)
      backoff = (backoff * 2).coerceAtMost(config.maxBackoff)
    }
  }

  /** Runs one MultiServer process until it ends; its exit code. */
  private fun run(seed: Path): Int {
    runCatching { config.prepare(seed) }.onFailure { note("Could not prepare ${seed.name}: ${it.message}") }
    val launch = runCatching { config.launch(seed) }.getOrElse {
      note("Can't start ${seed.name}: ${it.message}")
      return -1
    }
    note("Starting the game server on ${seed.name}")
    val proc = synchronized(processLock) {
      if (stopping) return -1
      // One that can't be started (no memory for it, Python missing) counts as a crash: its pauses, then FAILED
      runCatching { startProcess(launch.command, launch.workDir, launch.environment, input = true) }
        .getOrElse {
          note("Can't start the game server on ${seed.name}: ${it.message}")
          return -1
        }
        .also { process = it; stdin = it.outputStream.bufferedWriter() }
    }
    try {
      // What the server is told before anything else (it reads them once it is up); not noted in the log
      if (launch.firstLines.isNotEmpty()) {
        runCatching { stdin?.apply { launch.firstLines.forEach { write(oneLine(it)); newLine() }; flush() } }
          .onFailure { note("Could not pass the first console lines: ${it.message}") }
      }
      // A kill closes its output: that is its end too
      proc.eachLine(onLine = ::output)
      return proc.waitFor()
    } finally {
      process = null
      stdin = null
      // However it ended (also killed): what depends on it running hears of it
      if (state.status == GameServerStatus.RUNNING) runCatching { config.onEnded(seed.name) }
    }
  }

  private fun endProcess() {
    val proc = synchronized(processLock) { process } ?: return
    runCatching {
      stdin?.apply { write("/exit"); newLine(); flush() }
    }
    if (!proc.waitFor(config.stopTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
      note("The game server didn't end within ${config.stopTimeout}; killing it")
      proc.endTree()
      proc.waitFor()
    }
    process = null
    stdin = null
  }

  private fun newestSeed(): Path? {
    if (!config.dataDir.exists()) return null
    return config.dataDir.listDirectoryEntries().filter(::isSeed).maxByOrNull { it.getLastModifiedTime().toMillis() }
  }

  /** Why [seed] must not be served now; `null`: it may (also when that can't be asked). */
  private fun refusalOf(seed: Path): String? = runCatching { config.refuse(seed) }.getOrNull()

  /** A line of the server itself (cut as it was read); "server listening" means it accepts clients now. */
  private fun output(line: String) {
    if (state.status == GameServerStatus.STARTING && "server listening" in line) {
      state = state.copy(status = GameServerStatus.RUNNING)
      state.seed?.let { runCatching { config.onRunning(it) } }
    }
    val shown = withoutPasswords(line)
    log.add(shown)
    logger.info("[ap] {}", shown)
  }

  /** A line about the server, from the gateway (cut like the server's, then masked). */
  private fun note(text: String) {
    val shown = withoutPasswords(text.take(MAX_LOG_LINE))
    log.add("[gateway] $shown")
    logger.info(shown)
  }
}

/** The last [capacity] lines, numbered from 1 for as long as the gateway runs. */
class LogBuffer(private val capacity: Int) {
  private val lines = ArrayDeque<LogLine>()
  private var last = 0L

  @Synchronized
  fun add(text: String) {
    lines.addLast(LogLine(++last, System.currentTimeMillis(), text))
    if (lines.size > capacity) lines.removeFirst()
  }

  /**
   * Lines numbered above [since]; with 0, all that are kept. At most [max] at once: then [LogLines.last] is the last
   * one's, to ask on from.
   */
  @Synchronized
  fun after(since: Long, max: Int = Int.MAX_VALUE): LogLines {
    // Numbered without gaps: the first one wanted is found by its number
    val from = lines.firstOrNull()?.let { (since - it.n + 1).coerceIn(0L, lines.size.toLong()).toInt() } ?: 0
    if (lines.size - from <= max) return LogLines(lines.subList(from, lines.size).toList(), last)
    val part = lines.subList(from, from + max).toList()
    return LogLines(part, part.lastOrNull()?.n ?: since)
  }

  /** Every line kept, oldest first. */
  @Synchronized
  fun all(): List<LogLine> = lines.toList()
}
