package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.eratiem.randogeddon.shared.AvailableRequest
import net.eratiem.randogeddon.shared.AvailableSlot
import net.eratiem.randogeddon.shared.LocksFound
import net.eratiem.randogeddon.shared.LogicStatus
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Each session's logic with a shell script as `logic_run.py` (and `sh` as the interpreter): a session whose name says
 * so is never built, the others are and answer each question with its number, the first one late. Needs sh, so it is
 * skipped on Windows.
 */
class LogicTest {
  private lateinit var root: Path
  private lateinit var data: Path
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var logic: Logic
  private lateinit var apworlds: Apworlds

  @BeforeTest
  fun setUp() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    root = Files.createTempDirectory("archipelago")
    data = Files.createTempDirectory("data")
    val home = root.resolve("0.6.8").createDirectories()
    home.resolve("MultiServer.py").createFile()
    home.resolve(LOGIC_RUN).writeText(
      """
      case "${'$'}1" in *never*) sleep 600;; esac
      case "${'$'}1" in *glued*)
        echo '[randogeddon-logic] {"ready": true, "slots": [1, 2], "mismatches": {"2": "Chest B: not reachable in sphere 1"}}'
        while read -r question; do
          echo "${'$'}question" >> "${'$'}(dirname "${'$'}1")/questions"
          echo
          printf 'a world prints dots...'
          echo '[randogeddon-logic] {"available": {"1": [7], "2": [8]}}'
        done
        exit 0;;
      esac
      echo '[randogeddon-logic] {"ready": true}'
      n=0
      while read -r question; do
        n=${'$'}((n+1))
        [ ${'$'}n = 1 ] && sleep 1.5
        echo "[randogeddon-logic] {\"available\": {\"1\": [${'$'}n]}}"
      done
      """.trimIndent()
    )
    val archipelago = Archipelago(root, "sh")
    val dirs = SessionDirs(data)
    val stages = SpoilerStages(dirs)
    for (id in listOf("never-built", "quick-fox", "calm-heron", "glued-fox")) {
      data.resolve("sessions/$id").createDirectories().resolve("AP_1.zip").createFile()
      stages.save(id, SpoilerStages.State(1, "0.6.8"))
    }
    apworlds = Apworlds(archipelago, data.resolve("apworlds"), null)
    logic = Logic(archipelago, dirs, apworlds, scope, atOnce = 1, stages = stages, answerTime = 1.seconds)
  }

  @AfterTest
  fun tearDown() {
    logic.stopAll()
    scope.cancel()
  }

  private suspend fun await(within: Duration = 5.seconds, what: () -> Boolean) = withTimeout(within) {
    while (!what()) delay(20.milliseconds)
  }

  private fun logged(id: String, text: String) = logic.log(id, 0)?.lines.orEmpty().any { text in it.text }

  @Test
  fun `ending a logic while it is built frees its build place and the library at once`() = runBlocking {
    logic.serverRunning("never-built")
    await { logged("never-built", "Building") }
    logic.stop("never-built")
    // A change of the library waits for no build any more
    withTimeout(2.seconds) { apworlds.syncAll() }
    // The one build place is free: the next session's logic is built
    logic.serverRunning("quick-fox")
    await { logic.progress("quick-fox")?.status == LogicStatus.RUNNING }
  }

  @Test
  fun `after a late answer, each question gets its own`() = runBlocking {
    logic.serverRunning("calm-heron")
    await { logic.progress("calm-heron")?.status == LogicStatus.RUNNING }
    val question = AvailableRequest(listOf(AvailableSlot(1, emptyList(), emptyList())))
    // The first answer comes after the wait
    assertNull(logic.available("calm-heron", question))
    // The late one is dropped: the second question's answer is the second
    assertEquals(mapOf(1 to listOf(2L)), logic.available("calm-heron", question)?.locations)
    assertEquals(mapOf(1 to listOf(3L)), logic.available("calm-heron", question)?.locations)
  }

  @Test
  fun `an answer after what a world printed is taken, the mismatches come with every answer, said once`() = runBlocking {
    logic.serverRunning("glued-fox")
    await { logic.progress("glued-fox")?.status == LogicStatus.RUNNING }
    val slots = listOf(AvailableSlot(1, emptyList(), emptyList()), AvailableSlot(2, emptyList(), emptyList()))
    val answer = logic.available("glued-fox", AvailableRequest(slots, locks = LocksFound(9, listOf(77L))))
    assertEquals(mapOf(1 to listOf(7L), 2 to listOf(8L)), answer?.locations)
    assertEquals(mapOf(2 to "Chest B: not reachable in sphere 1"), answer?.mismatches)
    // Only the asked slots'
    assertEquals(emptyMap(), logic.available("glued-fox", AvailableRequest(slots.take(1)))?.mismatches)
    val log = logic.log("glued-fox", 0)!!.lines.map { it.text }
    assertEquals(1, log.count { "Chest B" in it }, log.toString())
    assertTrue("a world prints dots..." in log, log.toString())
    assertTrue(log.none { it.isBlank() }, log.toString())
    // The question as the script reads it: the keys found, and its number
    val asked = data.resolve("sessions/glued-fox/questions").readLines().first()
    assertTrue(""""locks":{"slot":9,"items":[77]}""" in asked && """"id":1""" in asked, asked)
  }
}
