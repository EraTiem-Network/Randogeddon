package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.eratiem.randogeddon.shared.GameServerStatus
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The supervisor with a small shell script in place of MultiServer: it says "server listening", echoes console lines,
 * saves and ends on `/exit`, and crashes on `crash`. Needs `sh`, so it is skipped on Windows (it runs in the Docker
 * build and in WSL).
 */
class GameServerTest {
  private lateinit var dir: Path
  private val scope = CoroutineScope(SupervisorJob())

  private val fakeMultiServer = """
    echo "serving ${'$'}1"
    echo "server listening on 0.0.0.0:38282"
    while read line; do
      echo "console: ${'$'}line"
      [ "${'$'}line" = "/exit" ] && { echo "saved"; exit 0; }
      [ "${'$'}line" = "crash" ] && exit 3
    done
  """.trimIndent()

  @BeforeTest
  fun setUp() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    dir = Files.createTempDirectory("gameserver")
    dir.resolve("fake.sh").writeText(fakeMultiServer)
    // A MultiServer that can't serve its seed: it ends with an error right away, every time
    dir.resolve("broken.sh").writeText("echo \"serving ${'$'}1\"\nexit 2\n")
    // One that doesn't end on /exit (hung, or a slow save), with a process of its own
    dir.resolve("deaf.sh").writeText(
      """
      sleep 600 &
      echo "child ${'$'}!"
      echo "server listening on 0.0.0.0:38282"
      while read line; do echo "console: ${'$'}line"; done
      sleep 600
      """.trimIndent()
    )
    // One that writes a line of a MiB (as a client's packet in MultiServer's log can be)
    dir.resolve("long.sh").writeText(
      """
      echo "server listening on 0.0.0.0:38282"
      head -c 1048576 /dev/zero | tr '\0' 'a'
      echo
      echo "after the long one"
      while read line; do [ "${'$'}line" = "/exit" ] && exit 0; done
      """.trimIndent()
    )
    // One that takes its room password from its first console line (as serve.py does) and names it, as MultiServer
    // and a world may; and says its out-of-memory score
    dir.resolve("room.sh").writeText(
      """
      read room
      echo "oom ${'$'}(cat /proc/${'$'}${'$'}/oom_score_adj)"
      echo "Hosting game at 0.0.0.0:38282 (Password: ${'$'}room)"
      echo "server listening on 0.0.0.0:38282"
      echo "a world prints ${'$'}room"
      while read line; do
        echo "console: ${'$'}line"
        [ "${'$'}line" = "/exit" ] && exit 0
      done
      """.trimIndent()
    )
  }

  @AfterTest
  fun tearDown() {
    scope.cancel()
  }

  private fun server(
    prepared: MutableList<String> = mutableListOf(), refuse: (Path) -> String? = { null }, script: String = "fake.sh",
    onEnded: (String) -> Unit = {}, stopTimeout: Duration = 2.seconds,
    launch: (Path) -> Launch = { seed -> Launch(listOf("sh", dir.resolve(script).toString(), seed.fileName.toString())) },
  ) = GameServer(
    GameServerConfig(
      dataDir = dir,
      launch = launch,
      prepare = { seed -> prepared += seed.fileName.toString() },
      seedPoll = 50.milliseconds,
      backoff = 50.milliseconds,
      maxBackoff = 200.milliseconds,
      stopTimeout = stopTimeout,
      onEnded = onEnded,
      refuse = refuse,
    ),
    scope,
  )

  /**
   * Process [pid] still runs. One that was killed and that nobody collected yet (a zombie: where no init runs, as in
   * the image's build) doesn't, though the JVM calls it alive.
   */
  private fun running(pid: Long): Boolean {
    if (!ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return false
    val state = runCatching { Path.of("/proc/$pid/stat").readText().substringAfterLast(") ").take(1) }.getOrNull()
    return state != "Z"
  }

  private suspend fun GameServer.awaitStatus(status: GameServerStatus) = withTimeout(5.seconds) {
    while (state.status != status) delay(20.milliseconds)
  }

  private suspend fun GameServer.awaitLine(text: String) = withTimeout(5.seconds) {
    while (log(0).lines.none { text in it.text }) delay(20.milliseconds)
  }

  @Test
  fun `a stop right after a start ends it, also before its process is there`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    repeat(20) {
      val server = server()
      withTimeout(5.seconds) {
        server.start()
        server.stop()
      }
      assertEquals(GameServerStatus.STOPPED, server.state.status)
    }
  }

  @Test
  fun `a refused seed (its session is finished) stays stopped, and is asked about on every start`() = runBlocking {
    var finished = true
    val server = server(refuse = { if (finished) "its session is finished" else null })
    dir.resolve("AP_1.zip").createFile()
    server.start()
    // Stopped as soon as the start returns: it never counts as starting (and takes no port)
    assertEquals(GameServerStatus.STOPPED, server.state.status)
    server.awaitLine("Not starting AP_1.zip: its session is finished")
    finished = false
    server.start()
    server.awaitStatus(GameServerStatus.RUNNING)
    server.stop()
  }

  @Test
  fun `waits for a seed, then runs the newest one`() = runBlocking {
    val prepared = mutableListOf<String>()
    val server = server(prepared)
    server.start()
    server.awaitStatus(GameServerStatus.NO_SEED)

    dir.resolve("AP_1.zip").createFile()
    Thread.sleep(20)
    dir.resolve("AP_2.zip").createFile()
    server.awaitStatus(GameServerStatus.RUNNING)
    assertEquals("AP_2.zip", server.state.seed)
    assertEquals(listOf("AP_2.zip"), prepared)
    server.awaitLine("serving AP_2.zip")
    server.stop()
  }

  @Test
  fun `console commands reach the server and show in the log`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    val server = server()
    server.start()
    server.awaitStatus(GameServerStatus.RUNNING)

    assertTrue(server.command("/players"))
    server.awaitLine("console: /players")
    val lines = server.log(0).lines.map { it.text }
    assertTrue("> /players" in lines.map { it.removePrefix("[gateway] ") })
    server.stop()
  }

  @Test
  fun `each start's room password goes in on its console, is there to hand out, and never in the log`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    val room = "r00m-9f2c4e1a"
    val script = dir.resolve("room.sh").toString()
    val server = server(launch = { seed ->
      Launch(listOf("sh", script, seed.fileName.toString()), firstLines = listOf(room), roomPassword = room)
    })
    server.start()
    server.awaitStatus(GameServerStatus.RUNNING)
    // For the relay, the gateway's listener and the tracker
    assertEquals(room, server.roomPassword)
    server.awaitLine("a world prints")
    // Raised as it started: when the container's memory is full, the kernel ends it before the gateway
    server.awaitLine("oom $OOM_GAME_SERVER")
    val lines = server.log(0).lines.map { it.text }
    assertTrue(lines.none { room in it }, lines.toString())
    assertTrue("a world prints ***" in lines, lines.toString())
    assertTrue(lines.any { it.endsWith("(Password: ***)") }, lines.toString())

    // A manager's console sets neither it nor a server password (for `!admin`): not passed on, the log says so
    assertTrue(server.command("/option password open"))
    assertTrue(server.command("/option server_password hunter2"))
    assertTrue(server.command("/say hi"))
    server.awaitLine("console: /say hi")
    val after = server.log(0).lines.map { it.text }
    assertTrue(after.none { "console: /option" in it || "hunter2" in it }, after.toString())
    assertTrue(after.any { "Not passed on" in it }, after.toString())
    server.stop()
    assertNull(server.roomPassword)
  }

  @Test
  fun `a stop saves and stays stopped, a start brings it back`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    val server = server()
    server.start()
    server.awaitStatus(GameServerStatus.RUNNING)

    server.stop()
    assertEquals(GameServerStatus.STOPPED, server.state.status)
    server.awaitLine("saved")
    assertFalse(server.command("/players"))
    delay(300.milliseconds)
    assertEquals(GameServerStatus.STOPPED, server.state.status)

    server.start()
    server.awaitStatus(GameServerStatus.RUNNING)
    server.stop()
  }

  @Test
  fun `a crash restarts it after a pause`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    val server = server()
    server.start()
    server.awaitStatus(GameServerStatus.RUNNING)

    server.command("crash")
    server.awaitLine("exit code 3")
    server.awaitStatus(GameServerStatus.RUNNING)
    assertEquals(1, server.state.restarts)
    assertEquals(3, server.state.lastExitCode)
    server.stop()
  }

  @Test
  fun `one that crashes again and again right after its start gives up, and a start tries again`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    val server = server(script = "broken.sh")
    server.start()
    // 5 quick crashes in a row (the pauses between: 50, 100, 200, 200 ms)
    server.awaitStatus(GameServerStatus.FAILED)
    // What it did, for a failure's message (timing differs on a busy build machine)
    fun seen() = "state ${server.state}, log: " + server.log(0).lines.joinToString(" | ") { it.text }
    assertEquals(5, server.state.restarts, seen())
    assertEquals(2, server.state.lastExitCode, seen())
    assertTrue(server.log(0).lines.any { "stays stopped until it is started again" in it.text }, seen())
    // It stays so: no more starts by itself
    delay(500.milliseconds)
    assertEquals(GameServerStatus.FAILED, server.state.status, seen())
    assertEquals(5, server.state.restarts, seen())
    // A start (an admin's) runs it again, with the count from the start
    server.start()
    server.awaitStatus(GameServerStatus.FAILED)
    assertEquals(5, server.state.restarts, seen())
    server.stop()
    assertEquals(GameServerStatus.STOPPED, server.state.status, seen())
  }

  @Test
  fun `one that had to be killed ends like any other, with the processes it started`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    val ended = mutableListOf<String>()
    val server = server(script = "deaf.sh", onEnded = { synchronized(ended) { ended += it } }, stopTimeout = 300.milliseconds)
    server.start()
    server.awaitStatus(GameServerStatus.RUNNING)
    server.awaitLine("child ")
    val child = server.log(0).lines.first { it.text.startsWith("child ") }.text.removePrefix("child ").trim().toLong()

    withTimeout(5.seconds) { server.stop() }
    server.awaitLine("killing it")
    assertEquals(GameServerStatus.STOPPED, server.state.status)
    assertEquals(listOf("AP_1.zip"), synchronized(ended) { ended.toList() })
    // Its own process went with it
    withTimeout(5.seconds) { while (running(child)) delay(20.milliseconds) }
  }

  @Test
  fun `one that can't be started counts as a crash, and fails in the end`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    val server = server(launch = { Launch(listOf(dir.resolve("no-such-program").toString())) })
    server.start()
    server.awaitStatus(GameServerStatus.FAILED)
    assertTrue(server.log(0).lines.any { "Can't start the game server on AP_1.zip" in it.text })
    assertEquals(-1, server.state.lastExitCode)
    server.stop()
  }

  @Test
  fun `a line of a MiB is kept cut, and the ones after it come`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    val server = server(script = "long.sh")
    server.start()
    server.awaitLine("after the long one")
    val long = server.log(0).lines.single { it.text.startsWith("aaaa") }.text
    assertEquals(MAX_LOG_LINE, long.length)
    server.stop()
  }

  @Test
  fun `sleep and a player's start look at the state under the lock`() = runBlocking {
    dir.resolve("AP_1.zip").createFile()
    val server = server()
    // Stopped by an admin meanwhile: the sweep's sleep (only a running one) and a player's start (only a sleeping one)
    // leave it stopped
    assertFalse(server.sleep("Asleep") { it.status == GameServerStatus.RUNNING })
    server.start(onlyIdle = true)
    assertEquals(GameServerStatus.STOPPED, server.state.status)
    // Asleep (the gateway's start): a player's start runs it
    assertTrue(server.sleep("Asleep"))
    assertEquals(GameServerStatus.IDLE, server.state.status)
    server.start(onlyIdle = true)
    server.awaitStatus(GameServerStatus.RUNNING)
    server.stop()
  }

  @Test
  fun `the log hands out only new lines`() {
    val log = LogBuffer(3)
    listOf("a", "b", "c", "d").forEach(log::add)
    assertEquals(listOf("b", "c", "d"), log.after(0).lines.map { it.text })
    val after = log.after(0).last
    log.add("e")
    assertEquals(listOf("e"), log.after(after).lines.map { it.text })
    assertEquals(5, log.after(after).last)
  }
}
