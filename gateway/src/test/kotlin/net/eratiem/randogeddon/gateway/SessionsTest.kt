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
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SessionsTest {
  private val scope = CoroutineScope(SupervisorJob())

  @AfterTest
  fun tearDown() {
    scope.cancel()
  }

  @Test
  fun `a game server gets a free port, its last one while nothing else listens there`() {
    val port = freePort(null, emptySet())
    assertEquals(port, freePort(port, emptySet()))
    // Taken by another game server, or by something else listening: another one
    assertNotEquals(port, freePort(port, setOf(port)))
    ServerSocket(port).use { assertNotEquals(port, freePort(port, emptySet())) }
  }

  @Test
  fun `a game server sleeps once it ran without players for the time set, unless kept running`() {
    val running = GameServerStatus.RUNNING
    assertTrue(sleepsNow(running, players = 0, idleFor = 30.minutes, idleMinutes = 30, keepRunning = false))
    assertFalse(sleepsNow(running, players = 0, idleFor = 29.minutes, idleMinutes = 30, keepRunning = false))
    assertFalse(sleepsNow(running, players = 1, idleFor = 3.minutes * 60, idleMinutes = 30, keepRunning = false))
    assertFalse(sleepsNow(running, players = 0, idleFor = 3.minutes * 60, idleMinutes = 30, keepRunning = true))
    // 0: never
    assertFalse(sleepsNow(running, players = 0, idleFor = 3.minutes * 60, idleMinutes = 0, keepRunning = false))
    // Only one that runs: not one starting, stopped or asleep already
    for (status in GameServerStatus.entries - running) {
      assertFalse(sleepsNow(status, players = 0, idleFor = 3.minutes * 60, idleMinutes = 30, keepRunning = false))
    }
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

  /**
   * Game servers with a shell script as MultiServer (needs sh, so skipped on Windows), one per session of [ids]; the
   * [finished] ones are refused.
   */
  private fun sessions(
    ids: List<String>, limit: Int, finished: Set<String> = emptySet(),
  ): Pair<Sessions, SessionDirs> {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val data: Path = Files.createTempDirectory("data")
    val fake = data.resolve("fake.sh")
    fake.writeText("echo \"server listening on ${'$'}1\"\nwhile read line; do [ \"${'$'}line\" = \"/exit\" ] && exit 0; done\n")
    for (id in ids) data.resolve("sessions/$id").createDirectories().resolve("AP_$id.zip").createFile()
    val dirs = SessionDirs(data)
    val sessions = Sessions(
      dirs, { limit },
      create = { id, dir, _, port ->
        GameServer(
          GameServerConfig(
            dataDir = dir,
            launch = { Launch(listOf("sh", fake.toString(), port().toString())) },
            refuse = { if (id in finished) "its session is finished" else null },
            seedPoll = 50.milliseconds, backoff = 50.milliseconds, stopTimeout = 2.seconds,
          ),
          scope,
        )
      },
      // Never asked here: no client connects
      passwords = { SlotPasswordCache(SlotPasswordStore(error("no database")), it) },
      // In order from 40001, so the test knows them; its last one again while no other session has it
      port = { last, taken -> last?.takeIf { it !in taken } ?: (40001..40100).first { it !in taken } },
    )
    return sessions to dirs
  }

  private suspend fun Sessions.await(id: String, status: GameServerStatus) = withTimeout(5.seconds) {
    while (get(id)?.server?.state?.status != status) delay(20.milliseconds)
  }

  @Test
  fun `only as many run as the limit allows, each on a port of its own`() = runBlocking {
    val (sessions, dirs) = sessions(listOf("brave-otter", "friday-chaos", "quiet-fox"), limit = 2)

    // All asleep at the gateway's start; a limit of two: the third player's session stays asleep
    sessions.sleepAll()
    assertEquals(List(3) { GameServerStatus.IDLE }, sessions.all().map { it.server.state.status })
    assertTrue(sessions.wake("brave-otter"))
    assertTrue(sessions.wake("friday-chaos"))
    assertEquals(listOf(40001, 40002, null), sessions.list().sessions.map { it.port })
    assertFailsWith<ServersBusy> { sessions.wake("quiet-fox") }
    assertEquals(GameServerStatus.IDLE, sessions.get("quiet-fox")?.server?.state?.status)
    assertFailsWith<ServersBusy> { sessions.start("quiet-fox") }

    // Stopping one makes room, and frees its port for another
    sessions.stop("brave-otter")
    sessions.start("quiet-fox")
    sessions.await("quiet-fox", GameServerStatus.RUNNING)
    assertEquals(40001, sessions.get("quiet-fox")?.port)
    assertNull(sessions.list().sessions.first { it.id == "brave-otter" }.port)
    assertNull(sessions.onlyActive())
    sessions.remove("friday-chaos", purge = true)
    assertEquals("quiet-fox", sessions.onlyActive()?.id)
    assertEquals(listOf("brave-otter", "quiet-fox"), dirs.ids())
    sessions.stopAll()
  }

  @Test
  fun `finished sessions don't sleep and don't wake, and don't count`() = runBlocking {
    val (sessions, _) = sessions(
      listOf("brave-otter", "calm-heron", "friday-chaos", "quiet-fox"), limit = 2,
      finished = setOf("brave-otter", "calm-heron"),
    )
    sessions.sleepAll()
    assertEquals(
      listOf(GameServerStatus.STOPPED, GameServerStatus.STOPPED, GameServerStatus.IDLE, GameServerStatus.IDLE),
      sessions.all().map { it.server.state.status },
    )
    assertFalse(sessions.wake("brave-otter"))
    assertTrue(sessions.wake("friday-chaos"))
    assertTrue(sessions.wake("quiet-fox"))
    assertEquals(listOf(null, null, 40001, 40002), sessions.list().sessions.map { it.port })
    sessions.stopAll()
  }

  @Test
  fun `without a limit every session runs`() = runBlocking {
    val (sessions, _) = sessions(listOf("brave-otter", "friday-chaos", "quiet-fox"), limit = 0)
    sessions.sleepAll()
    for (id in listOf("brave-otter", "friday-chaos", "quiet-fox")) assertTrue(sessions.wake(id))
    assertEquals(listOf(40001, 40002, 40003), sessions.list().sessions.map { it.port })
    sessions.stopAll()
  }

  @Test
  fun `one nobody plays on goes to sleep, but not with a player or kept running, and a stop keeps it from waking`() = runBlocking {
    val (sessions, _) = sessions(listOf("brave-otter", "friday-chaos", "quiet-fox"), limit = 0)
    sessions.sleepAll()
    for (id in listOf("brave-otter", "friday-chaos", "quiet-fox")) assertTrue(sessions.wake(id))
    // Each ran an hour; one has a player, one is kept running
    for (session in sessions.all()) session.idleSince = TimeSource.Monotonic.markNow() - 60.minutes
    val leave = sessions.get("brave-otter")!!.joined()
    val kept = { s: SessionGameServer -> SleepRules(keepRunning = s.id == "friday-chaos") }
    assertEquals(listOf("quiet-fox"), sessions.sweep(30, kept))
    assertEquals(GameServerStatus.IDLE, sessions.get("quiet-fox")?.server?.state?.status)
    assertNull(sessions.list().sessions.first { it.id == "quiet-fox" }.port)
    // The player leaves: its time without players counts from now
    leave()
    leave()
    assertEquals(0, sessions.get("brave-otter")?.players)
    assertEquals(emptyList(), sessions.sweep(30, kept))
    sessions.get("brave-otter")!!.idleSince = TimeSource.Monotonic.markNow() - 31.minutes
    assertEquals(listOf("brave-otter"), sessions.sweep(30, kept))
    // A player wakes it again, on its port; a stop by hand keeps it from waking
    assertTrue(sessions.wake("quiet-fox"))
    assertEquals(GameServerStatus.RUNNING, sessions.get("quiet-fox")?.server?.state?.status)
    sessions.stop("brave-otter")
    assertEquals(GameServerStatus.STOPPED, sessions.get("brave-otter")?.server?.state?.status)
    assertFalse(sessions.wake("brave-otter"))
    assertEquals(GameServerStatus.STOPPED, sessions.get("brave-otter")?.server?.state?.status)
    sessions.stopAll()
  }

  @Test
  fun `a stop or a player between the sweep's look and its sleep is kept`() = runBlocking {
    val (sessions, _) = sessions(listOf("brave-otter", "friday-chaos", "quiet-fox"), limit = 0)
    sessions.sleepAll()
    for (id in listOf("brave-otter", "friday-chaos", "quiet-fox")) assertTrue(sessions.wake(id))
    for (session in sessions.all()) session.idleSince = TimeSource.Monotonic.markNow() - 60.minutes
    // While the sweep asks the database: an Administrator stops one, a player joins another
    var leave: (() -> Unit)? = null
    val meanwhile: suspend (SessionGameServer) -> SleepRules = { s ->
      when (s.id) {
        "brave-otter" -> sessions.stop(s.id)
        "friday-chaos" -> leave = s.joined()
      }
      SleepRules()
    }
    assertEquals(listOf("quiet-fox"), sessions.sweep(30, meanwhile))
    assertEquals(GameServerStatus.STOPPED, sessions.get("brave-otter")?.server?.state?.status)
    assertEquals(GameServerStatus.RUNNING, sessions.get("friday-chaos")?.server?.state?.status)
    // The stopped one doesn't wake for the next player
    assertFalse(sessions.wake("brave-otter"))
    assertEquals(GameServerStatus.STOPPED, sessions.get("brave-otter")?.server?.state?.status)
    leave?.invoke()
    sessions.stopAll()
  }
}
