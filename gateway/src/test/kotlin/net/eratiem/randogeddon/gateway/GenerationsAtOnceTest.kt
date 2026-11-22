package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.GenerationStage
import net.eratiem.randogeddon.shared.UploadedYaml
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Several Generate.py at once, with a shell script in its place (needs sh, so skipped on Windows). */
class GenerationsAtOnceTest {
  private lateinit var root: Path
  private lateinit var data: Path
  private lateinit var home: Path
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @BeforeTest
  fun setUp() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    root = Files.createTempDirectory("archipelago")
    data = Files.createTempDirectory("data")
    home = root.resolve("0.6.8").createDirectories()
    home.resolve("MultiServer.py").createFile()
    home.resolve("list_games.py").writeText("""echo '{"games":[{"game":"Hollow Knight","module":"worlds.hk"}]}' > "${'$'}1"""")
  }

  @AfterTest
  fun tearDown() {
    scope.cancel()
  }

  private val request = GenerateRequest(listOf(UploadedYaml("a.yaml", "name: A")), apVersion = "0.6.8")

  private fun generator(atOnce: Int, lookEvery: Duration = 5.seconds): Generator {
    val archipelago = Archipelago(root, "sh")
    return Generator(archipelago, SessionDirs(data), Apworlds(archipelago, data.resolve("apworlds"), null), atOnce = atOnce, lookEvery = lookEvery)
  }

  private suspend fun await(within: Duration = 20.seconds, what: () -> Boolean) = withTimeout(within) {
    while (!what()) delay(50.milliseconds)
  }

  @Test
  fun `as many generate at once as there are places, the others wait`() = runBlocking {
    // Each run counts the runs going while it does
    home.resolve("Generate.py").writeText(
      """
      out="${'$'}4"
      mkdir "$root/run-${'$'}${'$'}"
      ls -d "$root"/run-* | wc -l >> "$root/most.txt"
      sleep 1
      rmdir "$root/run-${'$'}${'$'}"
      touch "${'$'}out/AP_${'$'}${'$'}.zip"
      """.trimIndent()
    )
    val generator = generator(atOnce = 2)
    val results = listOf("brave-otter", "quiet-fox", "sunny-owl").map { id -> scope.async { generator.generate(id, request) } }.awaitAll()
    assertTrue(results.all { it.seedName != null }, results.toString())
    val counted = root.resolve("most.txt").readText().lines().filter { it.isNotBlank() }.map { it.trim().toInt() }
    assertEquals(3, counted.size)
    assertEquals(2, counted.max(), "at once: $counted")
  }

  @Test
  fun `one waiting for its place ends at once when cancelled, and a session generates once at a time`() = runBlocking<Unit> {
    home.resolve("Generate.py").writeText("out=\"${'$'}4\"\nsleep 3\ntouch \"${'$'}out/AP_${'$'}${'$'}.zip\"\n")
    val generator = generator(atOnce = 1)
    val first = scope.async { generator.generate("brave-otter", request) }
    await { generator.log("brave-otter", 0, 100)?.lines.orEmpty().any { it.text.startsWith("$ cd ") } }
    // A doubled request: refused while the first runs
    assertFailsWith<IllegalStateException> { generator.generate("brave-otter", request) }

    val second = scope.async { generator.generate("quiet-fox", request) }
    await { generator.progress("quiet-fox")?.stage == GenerationStage.WAITING }
    assertTrue(generator.cancel("quiet-fox"))
    assertTrue(withTimeout(1.seconds) { second.await() }.cancelled)
    assertNull(generator.progress("quiet-fox"))
    assertNotNull(first.await().seedName)
  }

  @Test
  fun `a session deleted while it is generated ends its generation and leaves no log to a new one of its ID`() = runBlocking {
    home.resolve("Generate.py").writeText("out=\"${'$'}4\"\nsleep 30\ntouch \"${'$'}out/AP_${'$'}${'$'}.zip\"\n")
    val generator = generator(atOnce = 1)
    val run = scope.async { generator.generate("brave-otter", request) }
    await { generator.log("brave-otter", 0, 100)?.lines.orEmpty().any { it.text.startsWith("$ cd ") } }
    generator.dropSpoiler("brave-otter")
    assertTrue(withTimeout(5.seconds) { run.await() }.cancelled)
    assertNull(generator.log("brave-otter", 0, 100))
    assertNull(SessionDirs(data).seed("brave-otter"))
  }

  @Test
  fun `the machine's cores less one generate at once, or as many as set`() {
    assertEquals(3, generationsAtOnce(cores = 4))
    assertEquals(1, generationsAtOnce(cores = 1))
    assertEquals(1, generationsAtOnce(cores = 2))
    assertEquals(2, generationsAtOnce(configured = 2, cores = 8))
    assertEquals(32, generationsAtOnce(configured = 500, cores = 8))
  }

  @Test
  fun `a spoiler's level-3 run is paused for a new session and goes on after, where it was`() = runBlocking {
    val spoiler = "Archipelago Version 0.6.8  -  Seed: 1\n\nLocations:\n\nLake (A): Claw (A)\n\n"
    ZipOutputStream(Files.newOutputStream(root.resolve("seed.zip"))).use { zip ->
      zip.putNextEntry(ZipEntry("AP_1_Spoiler.txt"))
      zip.write(spoiler.encodeToByteArray())
      zip.closeEntry()
    }
    root.resolve("spoiler-2.txt").writeText(spoiler + "Playthrough:\n\n0: {\n}\n")
    root.resolve("spoiler-3.txt").writeText(spoiler + "Playthrough:\n\n0: {\n}\n\nPaths:\n\n")
    // The seed at once (a second after `slow` is there); level 2 at once; level 3 ticks for two seconds
    home.resolve("Generate.py").writeText(
      """
      out="${'$'}4"; level="${'$'}8"
      if [ "${'$'}9" = "--spoiler_only" ]; then
        if [ "${'$'}level" = "3" ]; then
          i=0; while [ ${'$'}i -lt 20 ]; do echo "tick ${'$'}i"; sleep 0.1; i=${'$'}((i+1)); done
        fi
        cp "$root/spoiler-${'$'}level.txt" "${'$'}out/AP_1_Spoiler.txt"
      else
        [ -f "$root/slow" ] && sleep 1
        cp "$root/seed.zip" "${'$'}out/AP_${'$'}6.zip"
      fi
      """.trimIndent()
    )
    // One place: a new session can only have the level-3 run's
    val generator = generator(atOnce = 1, lookEvery = 100.milliseconds)
    scope.launch { generator.completeSpoilers() }
    fun ticks() = generator.spoilerLog("brave-otter", 0, 1000)?.lines.orEmpty().count { it.text.startsWith("tick") }

    assertNotNull(generator.generate("brave-otter", request).seedName)
    assertEquals(1, generator.spoiler("brave-otter")?.level)
    await { generator.spoiler("brave-otter")?.running == 3 && ticks() >= 2 }

    root.resolve("slow").createFile()
    val before = ticks()
    assertNotNull(generator.generate("quiet-fox", request).seedName)
    // It stood still meanwhile (a second: ten ticks if it hadn't)
    val during = ticks() - before
    assertTrue(during <= 3, "ticks while paused: $during")
    val log = generator.spoilerLog("brave-otter", 0, 1000)!!.lines.map { it.text }
    assertTrue(log.any { "Paused" in it }, log.toString())

    // It goes on where it was, and ends: complete (no progress to tell any more)
    await { generator.spoiler("brave-otter") == null }
    val whole = generator.spoilerLog("brave-otter", 0, 1000)!!.lines.map { it.text }
    assertTrue(whole.any { "Goes on" in it }, whole.toString())
    assertEquals((0 until 20).map { "tick $it" }, whole.filter { it.startsWith("tick") })
    assertTrue("Paths:" in SessionDirs(data).spoiler("brave-otter").orEmpty())
    // The new session's spoiler log follows
    await { generator.spoiler("quiet-fox") == null }
    assertNull(generator.spoiler("quiet-fox"))
  }
}
