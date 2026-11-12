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
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
  }

  @AfterTest
  fun tearDown() {
    scope.cancel()
  }

  private fun server(prepared: MutableList<String> = mutableListOf(), refuse: (Path) -> String? = { null }) = GameServer(
    GameServerConfig(
      dataDir = dir,
      launch = { seed -> Launch(listOf("sh", dir.resolve("fake.sh").toString(), seed.fileName.toString())) },
      prepare = { seed -> prepared += seed.fileName.toString() },
      seedPoll = 50.milliseconds,
      backoff = 50.milliseconds,
      maxBackoff = 200.milliseconds,
      stopTimeout = 2.seconds,
      refuse = refuse,
    ),
    scope,
  )

  private suspend fun GameServer.awaitStatus(status: GameServerStatus) = withTimeout(5.seconds) {
    while (state.status != status) delay(20.milliseconds)
  }

  private suspend fun GameServer.awaitLine(text: String) = withTimeout(5.seconds) {
    while (log(0).lines.none { text in it.text }) delay(20.milliseconds)
  }

  @Test
  fun `a refused seed (its session is finished) stays stopped, and is asked about on every start`() = runBlocking {
    var finished = true
    val server = server(refuse = { if (finished) "its session is finished" else null })
    dir.resolve("AP_1.zip").createFile()
    server.start()
    server.awaitLine("Not starting AP_1.zip: its session is finished")
    server.awaitStatus(GameServerStatus.STOPPED)
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
