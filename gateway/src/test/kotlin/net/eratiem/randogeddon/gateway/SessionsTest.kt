package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.shared.GameServerStatus
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SessionsTest {
  private val scope = CoroutineScope(SupervisorJob())

  @AfterTest
  fun tearDown() {
    scope.cancel()
  }

  @Test
  fun `ports are given as ranges or lists`() {
    assertEquals(listOf(38300, 38301, 38302, 38303), parsePorts("38300-38303"))
    assertEquals(listOf(38300, 38305, 38306), parsePorts("38300, 38305-38306,38300"))
    assertFailsWith<IllegalArgumentException> { parsePorts("38303-38300") }
  }

  @Test
  fun `a client names its session by the host, or by the path without a domain`() {
    val domain = "randogeddon.eratiem.net"
    assertEquals("friday-chaos", sessionIdOf("friday-chaos.randogeddon.eratiem.net", emptyList(), domain))
    assertEquals("friday-chaos", sessionIdOf("Friday-Chaos.Randogeddon.Eratiem.Net:443", emptyList(), domain))
    // Not one label, not an ID: then the path
    assertEquals("quiet-fox", sessionIdOf("a.b.randogeddon.eratiem.net", listOf("quiet-fox"), domain))
    assertEquals("quiet-fox", sessionIdOf("localhost", listOf("quiet-fox"), ""))
    assertEquals("quiet-fox", sessionIdOf("localhost:38281", listOf("", "quiet-fox"), domain))
    assertNull(sessionIdOf("localhost", emptyList(), ""))
    assertNull(sessionIdOf("localhost", listOf("12345"), ""))
    assertNull(sessionIdOf("randogeddon.eratiem.net", emptyList(), domain))
  }

  /** Game servers with a shell script as MultiServer (needs sh, so skipped on Windows). */
  @Test
  fun `each running session gets a port of its own, as long as one is free`() = runBlocking {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val data: Path = Files.createTempDirectory("data")
    val fake = data.resolve("fake.sh")
    fake.writeText("echo \"server listening on ${'$'}1\"\nwhile read line; do [ \"${'$'}line\" = \"/exit\" ] && exit 0; done\n")
    for (id in listOf("brave-otter", "friday-chaos", "quiet-fox")) {
      data.resolve("sessions/$id").createDirectories().resolve("AP_$id.zip").createFile()
    }
    val dirs = SessionDirs(data)
    val sessions = Sessions(
      dirs, listOf(40001, 40002),
      create = { _, dir, port ->
        GameServer(
          GameServerConfig(
            dataDir = dir,
            launch = { Launch(listOf("sh", fake.toString(), port().toString())) },
            seedPoll = 50.milliseconds, backoff = 50.milliseconds, stopTimeout = 2.seconds,
          ),
          scope,
        )
      },
      // Never asked here: no client connects
      passwords = { SlotPasswordCache(SlotPasswordStore(error("no database")), it) },
    )
    suspend fun await(id: String, status: GameServerStatus) = withTimeout(5.seconds) {
      while (sessions.get(id)?.server?.state?.status != status) delay(20.milliseconds)
    }

    // Two ports: the third session stays stopped
    sessions.startAll()
    await("brave-otter", GameServerStatus.RUNNING)
    await("friday-chaos", GameServerStatus.RUNNING)
    assertEquals(GameServerStatus.STOPPED, sessions.get("quiet-fox")?.server?.state?.status)
    assertEquals(listOf(40001, 40002, null), sessions.list().sessions.map { it.port })
    assertEquals(2, sessions.list().capacity)
    assertFailsWith<NoFreePort> { sessions.start("quiet-fox") }

    // Stopping one frees its port for another
    sessions.stop("brave-otter")
    sessions.start("quiet-fox")
    await("quiet-fox", GameServerStatus.RUNNING)
    assertEquals(40001, sessions.get("quiet-fox")?.port)
    assertNull(sessions.list().sessions.first { it.id == "brave-otter" }.port)
    assertNull(sessions.onlyActive())
    sessions.remove("friday-chaos", purge = true)
    assertEquals("quiet-fox", sessions.onlyActive()?.id)
    assertEquals(listOf("brave-otter", "quiet-fox"), dirs.ids())
    sessions.stopAll()
  }
}
