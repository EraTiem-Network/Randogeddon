package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.GenerationProgress
import net.eratiem.randogeddon.shared.GenerationStage
import net.eratiem.randogeddon.shared.UploadedYaml
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seed generation with a shell script as "Generate.py" (and `sh` as the interpreter): it lists the YAMLs it was given
 * and writes a seed, or fails when a YAML says so (and then waits for Enter, like Generate.py). Skipped on Windows
 * (needs sh).
 */
class GeneratorTest {
  @Test
  fun `Generate py's lines tell its stage and about how far it is`() {
    assertEquals(GenerationProgress(GenerationStage.CREATING, 5), generationProgress("[INFO] Creating MultiWorld."))
    assertEquals(GenerationStage.FILLING, generationProgress("Filling the multiworld with 742 items.")?.stage)
    assertEquals(GenerationProgress(GenerationStage.FILLING, 40), generationProgress("Current fill step (Progression) at 1000/2000 items placed."))
    assertEquals(GenerationStage.BALANCING, generationProgress("Balancing multiworld progression for 6 Players.")?.stage)
    assertEquals(GenerationProgress(GenerationStage.OUTPUT, 62), generationProgress("Generating output files (1/2)."))
    // The playthrough says nothing on its way: it gets the bar's last third
    assertEquals(GenerationProgress(GenerationStage.PLAYTHROUGH, 66), generationProgress("Calculating playthrough."))
    assertEquals(GenerationProgress(GenerationStage.DONE, 100), generationProgress("Done. Enjoy. Total Time: 12.3"))
    assertEquals(null, generationProgress(" Hollow Knight : v0.6.8 | Items: 300"))
  }

  private lateinit var root: Path
  private lateinit var data: Path

  @BeforeTest
  fun setUp() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    root = Files.createTempDirectory("archipelago")
    data = Files.createTempDirectory("data")
    for (version in listOf("0.6.8", "0.6.10", "0.5.1")) {
      val home = root.resolve(version).createDirectories()
      home.resolve("MultiServer.py").createFile()
      home.resolve("Generate.py").writeText(
        """
        players="${'$'}2"; out="${'$'}4"
        ls "${'$'}players" > "$root/files-$version.txt"
        cat "${'$'}players"/* > "$root/seen-$version.txt"
        grep -q "unbeatable" "${'$'}players"/* && { echo "Traceback (most recent call last):"; echo "  File \"worlds/hk/__init__.py\""; echo "Game appears as unbeatable"; read -r _; exit 1; }
        echo "version $version"
        touch "${'$'}out/AP_12345.zip"
        """.trimIndent()
      )
      home.resolve("list_games.py").writeText(
        """echo '{"games":[{"game":"Hollow Knight","module":"worlds.hk"},{"game":"Archipelago","module":"worlds.generic"}]}' > "${'$'}1""""
      )
    }
  }

  private val archipelago get() = Archipelago(root, "sh")
  private val generator get() = Generator(archipelago, SessionDirs(data), Apworlds(archipelago, data.resolve("apworlds"), null))

  @Test
  fun `versions are listed newest first, by number`() {
    assertEquals(listOf("0.6.10", "0.6.8", "0.5.1"), archipelago.versions())
    assertEquals("0.6.10", archipelago.newest)
    assertFailsWith<IllegalArgumentException> { archipelago.home("0.9.9") }
  }

  @Test
  fun `a generated seed goes into its session's directory, next to the other sessions`() = runBlocking {
    data.resolve("sessions/quiet-fox").createDirectories().resolve("AP_999.zip").createFile()

    val result = generator.generate(
      "brave-otter",
      GenerateRequest(
        yamls = listOf(UploadedYaml("Alice HK.yaml", "name: Alice-HK"), UploadedYaml("../evil.yml", "name: Bob")),
        locked = listOf("Bob"),
        earlyKeys = true,
        apVersion = "0.6.8",
      )
    )
    assertEquals("12345", result.seedName)
    assertTrue(data.resolve("sessions/brave-otter/AP_12345.zip").exists())
    assertTrue(data.resolve("sessions/quiet-fox/AP_999.zip").exists())
    assertTrue(result.log.any { "version 0.6.8" in it })
    // A session gets one seed
    assertFailsWith<IllegalStateException> {
      generator.generate("brave-otter", GenerateRequest(listOf(UploadedYaml("a.yaml", "name: A")), apVersion = "0.6.8"))
    }
    Unit
  }

  @Test
  fun `the locked worlds become a World Locks slot, the file names are made safe`() = runBlocking {
    generator.generate(
      "brave-otter",
      GenerateRequest(
        listOf(UploadedYaml("../Alice HK.yaml", "name: Al")), locked = listOf("Al\"ice", "Bob"), apVersion = "0.6.8",
      )
    )
    val seen = root.resolve("seen-0.6.8.txt").readText()
    assertTrue("game: World Locks" in seen, seen)
    assertTrue("""locked_worlds: ["Al\"ice", "Bob"]""" in seen, seen)
    assertTrue("early_keys: false" in seen, seen)
    val files = root.resolve("files-0.6.8.txt").readText().lines().filter { it.isNotBlank() }
    assertEquals(listOf("000-Alice_HK.yaml", "zz-world-locks.yaml"), files)
  }

  @Test
  fun `a failed generation leaves no seed and says why`() = runBlocking {
    val result = generator.generate(
      "brave-otter",
      GenerateRequest(listOf(UploadedYaml("a.yaml", "unbeatable")), apVersion = "0.6.8", games = listOf("Hollow Knight", "Celeste"))
    )
    assertNull(result.seedName)
    assertTrue(result.log.any { "unbeatable" in it })
    assertNull(SessionDirs(data).seed("brave-otter"))
    // Celeste isn't in the release at all; Hollow Knight's module is in the traceback
    assertEquals(listOf("Celeste", "Hollow Knight"), result.suspects.map { it.game })
    assertEquals("named in the error", result.suspects[1].reason)
  }

  @Test
  fun `a version the gateway doesn't have is refused`() = runBlocking {
    val result = generator.generate("brave-otter", GenerateRequest(emptyList(), apVersion = "1.0.0"))
    assertNull(result.seedName)
    assertTrue(result.log.single().contains("not installed"))
  }
}
