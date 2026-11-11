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

/**
 * How to run MultiServer: [command] for a seed file, [prepare] before each start (writes room.json). Both are given
 * from outside, so tests can use a small script instead of Archipelago.
 */
class GameServerConfig(
  val dataDir: Path,
  val command: (seed: Path) -> List<String>,
  val prepare: (seed: Path) -> Unit = {},
  val workDir: Path? = null,
  /** Until a seed shows up, the data directory is looked at this often. */
  val seedPoll: Duration = 15.seconds,
  /** The first pause before restarting after a crash; doubles up to [maxBackoff]. */
  val backoff: Duration = 1.seconds,
  val maxBackoff: Duration = 30.seconds,
  /** After `/exit`, how long MultiServer gets to save and end before it is killed. */
  val stopTimeout: Duration = 10.seconds,
  /** MultiServer on this seed (file name) accepts clients now; for the session time. */
  val onRunning: (seed: String) -> Unit = {},
  /** It ended (stopped, crashed or killed) after it had been running. */
  val onEnded: (seed: String) -> Unit = {},
)

/**
 * Runs MultiServer on the newest seed in the data directory and keeps it running: a crashed server starts again after
 * a growing pause; a stop by an admin stays until the next start. Its output goes into a buffer of the last lines
 * (for the admin panel) and the gateway's log; console commands go to its stdin.
 */
class GameServer(private val config: GameServerConfig, private val scope: CoroutineScope) {
  private val mutex = Mutex()
  private val log = LogBuffer(2000)
  // Set by the supervisor's thread, read by the API's
  @Volatile
  private var process: Process? = null

  @Volatile
  private var stdin: BufferedWriter? = null
  private var supervisor: Job? = null

  @Volatile
  var state = GameServerState(GameServerStatus.STOPPED)
    private set

  /** Starts it (again) if it isn't running; resets the restart count. */
  suspend fun start() = mutex.withLock {
    if (supervisor?.isActive == true) return@withLock
    // Right away, so the answer to a start says so; the supervisor goes on to running (or waiting for a seed)
    state = state.copy(status = GameServerStatus.STARTING, restarts = 0)
    supervisor = scope.launch(Dispatchers.IO) { supervise() }
  }

  /** Asks MultiServer to save and end (`/exit`), kills it if it doesn't, and keeps it stopped. */
  suspend fun stop() = mutex.withLock {
    val job = supervisor ?: return@withLock
    supervisor = null
    job.cancel()
    withContext(Dispatchers.IO) { endProcess() }
    job.join()
    state = state.copy(status = GameServerStatus.STOPPED, since = null)
    note("Stopped by an admin")
  }

  suspend fun restart() {
    stop()
    start()
  }

  /** Writes [line] to the console; `false` if no server is running. */
  suspend fun command(line: String): Boolean = mutex.withLock {
    val writer = stdin ?: return@withLock false
    note("> $line")
    withContext(Dispatchers.IO) {
      writer.write(line.replace('\n', ' '))
      writer.newLine()
      writer.flush()
    }
    true
  }

  fun log(since: Long): LogLines = log.after(since)

  private suspend fun supervise() {
    var backoff = config.backoff
    while (true) {
      val seed = newestSeed()
      if (seed == null) {
        if (state.status != GameServerStatus.NO_SEED) note("No seed in ${config.dataDir} yet")
        state = state.copy(status = GameServerStatus.NO_SEED, seed = null, since = null)
        delay(config.seedPoll)
        continue
      }
      state = state.copy(status = GameServerStatus.STARTING, seed = seed.name, since = System.currentTimeMillis())
      val started = System.currentTimeMillis()
      val code = run(seed)
      // Ended because an admin stopped it: not a crash
      if (!currentCoroutineContext().isActive) return
      // A run that lasted a while was no crash loop: the next crash starts with the short pause again
      if (System.currentTimeMillis() - started > config.maxBackoff.inWholeMilliseconds * 2) backoff = config.backoff
      state = state.copy(status = GameServerStatus.CRASHED, since = null, lastExitCode = code, restarts = state.restarts + 1)
      note("Game server ended with exit code $code; starting again in $backoff")
      delay(backoff)
      backoff = (backoff * 2).coerceAtMost(config.maxBackoff)
    }
  }

  /** Runs one MultiServer process until it ends; its exit code. */
  private fun run(seed: Path): Int {
    runCatching { config.prepare(seed) }.onFailure { note("Could not prepare ${seed.name}: ${it.message}") }
    note("Starting the game server on ${seed.name}")
    val proc = ProcessBuilder(config.command(seed))
      .apply { config.workDir?.let { directory(it.toFile()) } }
      .redirectErrorStream(true)
      .start()
    process = proc
    stdin = proc.outputStream.bufferedWriter()
    proc.inputStream.bufferedReader().useLines { lines -> lines.forEach(::output) }
    val code = proc.waitFor()
    process = null
    stdin = null
    if (state.status == GameServerStatus.RUNNING) runCatching { config.onEnded(seed.name) }
    return code
  }

  private fun endProcess() {
    val proc = process ?: return
    runCatching {
      stdin?.apply { write("/exit"); newLine(); flush() }
    }
    if (!proc.waitFor(config.stopTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
      note("The game server didn't end within ${config.stopTimeout}; killing it")
      proc.destroyForcibly().waitFor()
    }
    process = null
    stdin = null
  }

  private fun newestSeed(): Path? {
    if (!config.dataDir.exists()) return null
    return config.dataDir.listDirectoryEntries()
      .filter { it.name.endsWith(".archipelago") || (it.name.startsWith("AP_") && it.name.endsWith(".zip")) }
      .maxByOrNull { it.getLastModifiedTime().toMillis() }
  }

  /** A line of the server itself; "server listening" means it accepts clients now. */
  private fun output(line: String) {
    if (state.status == GameServerStatus.STARTING && "server listening" in line) {
      state = state.copy(status = GameServerStatus.RUNNING)
      state.seed?.let { runCatching { config.onRunning(it) } }
    }
    log.add(line)
    logger.info("[ap] {}", line)
  }

  /** A line about the server, from the gateway. */
  private fun note(text: String) {
    log.add("[gateway] $text")
    logger.info(text)
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

  /** Lines numbered above [since]; with 0, all that are kept. */
  @Synchronized
  fun after(since: Long): LogLines = LogLines(lines.filter { it.n > since }, last)
}
