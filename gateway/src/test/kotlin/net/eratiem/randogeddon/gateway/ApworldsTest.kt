package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.shared.ApworldConflict
import net.eratiem.randogeddon.shared.ApworldFile
import net.eratiem.randogeddon.shared.ApworldRemoval
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.ApworldUploadFile
import net.eratiem.randogeddon.shared.GameInfo
import net.eratiem.randogeddon.shared.GameSource
import net.eratiem.randogeddon.shared.ReleaseInfo
import org.junit.Assume.assumeFalse
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApworldsTest {
  private fun zip(vararg entries: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      entries.forEach { zip.putNextEntry(ZipEntry(it)); zip.write("x".toByteArray()); zip.closeEntry() }
    }
    return bytes.toByteArray()
  }

  /** An apworld of [world] whose manifest names [game]; the manifest in the world's folder, or at the [top]. */
  private fun world(world: String, game: String, top: Boolean = false): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      zip.putNextEntry(ZipEntry("$world/__init__.py")); zip.closeEntry()
      zip.putNextEntry(ZipEntry(if (top) "archipelago.json" else "$world/archipelago.json"))
      zip.write("""{"game": "$game", "world_version": "1.0.0"}""".toByteArray()); zip.closeEntry()
    }
    return bytes.toByteArray()
  }

  private fun problem(bytes: ByteArray) = lookAt(bytes).problem

  @Test
  fun `an apworld's world and game come from inside it`() {
    assertEquals("celeste", lookAt(zip("celeste/__init__.py", "celeste/Items.py")).world)
    assertEquals("Celeste", lookAt(world("celeste", "Celeste")).manifest?.game)
    assertNull(lookAt(zip("celeste/__init__.py")).manifest)
    assertNotNull(problem(zip("a/__init__.py", "b/__init__.py")))
    assertNotNull(problem(zip("my-game/__init__.py")))
  }

  @Test
  fun `an apworld is a zip with one world folder`() {
    assertNull(problem(zip("celeste/__init__.py", "celeste/Items.py", "celeste/data/a.json")))
    assertNotNull(problem(zip("__init__.py")))
    assertNotNull(problem(zip("celeste/sub/__init__.py")))
    assertNotNull(problem("not a zip".toByteArray()))
  }

  @Test
  fun `no entry may lead out of it`() {
    assertNotNull(problem(zip("celeste/__init__.py", "../evil.py")))
    assertNotNull(problem(zip("celeste/__init__.py", "celeste/../../evil.py")))
    assertNotNull(problem(zip("celeste/__init__.py", "/etc/evil")))
    assertNotNull(problem(zip("celeste/__init__.py", "C:/evil")))
  }

  @Test
  fun `an apworld that unpacks to more than a world can is refused`() {
    // 1 GB and a little of zeros, a few MB packed
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      zip.putNextEntry(ZipEntry("big/__init__.py")); zip.closeEntry()
      zip.putNextEntry(ZipEntry("big/data.bin"))
      val chunk = ByteArray(1024 * 1024)
      repeat(1025) { zip.write(chunk) }
      zip.closeEntry()
    }
    assertEquals("it unpacks to more than 1 GB", problem(bytes.toByteArray()))
  }

  @Test
  fun `a manifest too long for one isn't read`() {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      zip.putNextEntry(ZipEntry("w/__init__.py")); zip.closeEntry()
      zip.putNextEntry(ZipEntry("w/archipelago.json"))
      zip.write("""{"game": "W", "x": "${"a".repeat(70_000)}"}""".toByteArray())
      zip.closeEntry()
    }
    val look = lookAt(bytes.toByteArray())
    assertNull(look.problem)
    assertNull(look.manifest)
  }

  @Test
  fun `file names lose their path and odd characters`() {
    assertEquals("celeste.apworld", apworldName("celeste.apworld"))
    assertEquals("celeste.apworld", apworldName("../../celeste.apworld"))
    assertEquals("celeste.apworld", apworldName("C:\\Downloads\\celeste.zip"))
    assertEquals("my_game.apworld", apworldName("my game"))
    assertEquals("x.apworld", apworldName("..x.apworld"))
    assertFailsWith<ApworldProblem> { apworldName("../") }
    assertFailsWith<ApworldProblem> { apworldName(".apworld") }
  }

  private val release = ReleaseInfo(
    "0.6.8",
    listOf(
      GameInfo("Hollow Knight", GameSource.BUNDLED, module = "worlds.hk"),
      GameInfo("Celeste", GameSource.UPLOADED, "celeste.apworld", "worlds.celeste"),
      GameInfo("Yacht Dice", GameSource.UPLOADED, "yacht.apworld", "worlds.yacht"),
    ),
  )
  private val files = listOf(
    ApworldFile("celeste.apworld", "0.6.8", GameSource.UPLOADED),
    ApworldFile("yacht.apworld", "0.6.8", GameSource.UPLOADED, from = "0.6.7"),
  )

  @Test
  fun `a missing game comes first, then the one the error names, then untested apworlds`() {
    val log = listOf(
      "Generating for 4 players", "Hollow Knight: Alice-HK",
      "Traceback (most recent call last):",
      "  File \"/opt/archipelago/0.6.8/custom_worlds/celeste.apworld/celeste/Rules.py\", line 3",
      "KeyError: 'Dash'",
    )
    val found = suspects(listOf("Hollow Knight", "Celeste", "Yacht Dice", "Timespinner"), log, release, files)
    assertEquals(listOf("Timespinner", "Celeste", "Yacht Dice"), found.map { it.game })
    assertEquals("celeste.apworld", found[1].apworld)
    assertEquals("0.6.7", found[2].from)
  }

  @Test
  fun `a module path names a bundled game`() {
    val log = listOf("Traceback (most recent call last):", "  File \"worlds/hk/Rules.py\", line 1", "Exception")
    assertEquals(listOf("Hollow Knight"), suspects(listOf("Hollow Knight", "Celeste"), log, release, files.take(1)).map { it.game })
    // "worlds/hkx" is another module
    val other = listOf("Traceback (most recent call last):", "  File \"worlds/hkx/Rules.py\"", "Exception")
    assertEquals(emptyList(), suspects(listOf("Hollow Knight"), other, release, files))
  }

  @Test
  fun `without the release's game list, a missing game isn't assumed`() {
    val unknown = ReleaseInfo("0.6.8", error = "Listing the games failed")
    assertEquals(emptyList(), suspects(listOf("Timespinner"), listOf("Exception"), unknown, emptyList()))
  }

  /** The library with `sh` as Python and a list_games.py that lists one game per apworld in custom_worlds/. */
  private class Library {
    val root: Path = Files.createTempDirectory("archipelago")
    val data: Path = Files.createTempDirectory("data")
    val manual: Path = Files.createTempDirectory("custom")
    val archipelago = Archipelago(root, "sh")
    val apworlds = Apworlds(archipelago, data.resolve("apworlds"), manual)

    init {
      for (version in listOf("0.6.8", "0.6.7")) {
        val home = root.resolve(version).createDirectories()
        home.resolve("MultiServer.py").createFile()
        home.resolve("list_games.py").writeText(
          """
          games='{"game":"Hollow Knight","module":"worlds.hk"}'; failed=''
          for f in custom_worlds/*.apworld; do
            [ -e "${'$'}f" ] || continue; n=${'$'}(basename "${'$'}f")
            case "${'$'}n" in broken*) failed='{"name":"broken","reason":"cannot import"}';;
              *) games="${'$'}games,{\"game\":\"${'$'}{n%.apworld}\",\"apworld\":\"${'$'}n\"}";; esac
          done
          echo "{\"games\":[${'$'}games],\"failed\":[${'$'}failed]}" > "${'$'}1"
          """.trimIndent()
        )
      }
    }

    /** Several files in one upload: one answer each. */
    fun uploadAll(release: String, vararg files: Pair<String, ByteArray>, replace: Boolean = false) = runBlocking {
      apworlds.upload(ApworldUpload(release, files.map { (name, bytes) -> ApworldUploadFile(name, Base64.getEncoder().encodeToString(bytes)) }, replace))
    }.results

    fun upload(release: String, name: String, bytes: ByteArray, replace: Boolean = false) =
      uploadAll(release, name to bytes, replace = replace).single()

    fun remove(release: String, vararg files: String) = runBlocking { apworlds.remove(ApworldRemoval(release, files.toList())) }

    fun customWorlds(release: String) = root.resolve("$release/custom_worlds").listDirectoryEntries().map { it.name }.sorted()
  }

  @Test
  fun `an upload lands in the release's library and custom_worlds, and brings its game`() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val lib = Library()
    lib.manual.resolve("manual.apworld").writeBytes(zip("manual/__init__.py"))
    val result = lib.upload("0.6.8", "celeste.apworld", zip("celeste/__init__.py"))
    assertEquals(listOf("celeste"), result.games)
    assertNull(result.error)
    assertEquals(listOf("celeste.apworld", "manual.apworld"), lib.customWorlds("0.6.8"))

    val release = runBlocking { lib.apworlds.release("0.6.8") }
    assertEquals(GameSource.UPLOADED, release.games.single { it.game == "celeste" }.source)
    assertEquals(GameSource.MANUAL, release.games.single { it.game == "manual" }.source)
    assertEquals(GameSource.BUNDLED, release.games.single { it.game == "Hollow Knight" }.source)
    assertEquals(
      setOf("celeste.apworld@0.6.8" to GameSource.UPLOADED, "manual.apworld@0.6.8" to GameSource.MANUAL, "manual.apworld@0.6.7" to GameSource.MANUAL),
      lib.apworlds.files().map { "${it.file}@${it.release}" to it.source }.toSet(),
    )
  }

  @Test
  fun `an apworld that doesn't load is refused, and says why`() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val lib = Library()
    lib.upload("0.6.8", "celeste.apworld", zip("celeste/__init__.py"))
    val result = lib.upload("0.6.8", "broken.apworld", zip("broken/__init__.py"))
    assertEquals("cannot import", result.error)
    assertEquals(emptyList(), result.games)
    // Not kept: neither in the library nor in custom_worlds; the release lists its games without it
    assertTrue(!lib.data.resolve("apworlds/0.6.8/broken.apworld").exists())
    assertEquals(listOf("celeste.apworld"), lib.customWorlds("0.6.8"))
    assertEquals(emptyList(), runBlocking { lib.apworlds.release("0.6.8") }.failed)
    assertFailsWith<ApworldProblem> { lib.remove("0.6.8", "broken.apworld") }
  }

  @Test
  fun `an apworld of another release can be tried, marked as from there`() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val lib = Library()
    lib.upload("0.6.7", "yacht.apworld", zip("yacht/__init__.py"))
    val result = runBlocking { lib.apworlds.tryWith(ApworldTry("0.6.8", "0.6.7", "yacht.apworld")) }
    assertEquals(listOf("yacht"), result.games)
    assertEquals("0.6.7", lib.apworlds.files().single { it.release == "0.6.8" }.from)
    assertFailsWith<ApworldProblem> { runBlocking { lib.apworlds.tryWith(ApworldTry("0.6.8", "0.6.7", "nope.apworld")) } }
  }

  @Test
  fun `an upload is named after its world, whatever the file was called`() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val lib = Library()
    val result = lib.upload("0.6.8", "Celeste (1).apworld", zip("celeste/__init__.py"))
    assertEquals("celeste.apworld", result.file)
    assertEquals(listOf("celeste.apworld"), lib.customWorlds("0.6.8"))
  }

  @Test
  fun `an upload that would replace something asks first`() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val lib = Library()
    lib.upload("0.6.8", "celeste.apworld", world("celeste", "Celeste"))
    // The same file again
    val again = lib.upload("0.6.8", "celeste.apworld", world("celeste", "Celeste"))
    assertEquals(ApworldConflict(sameFile = true), again.conflict)
    // Another file of the same game: nothing changes until it is sent with replace
    val other = lib.upload("0.6.8", "celeste_new.apworld", world("celeste_new", "Celeste"))
    assertEquals(ApworldConflict(game = "Celeste", otherFile = "celeste.apworld"), other.conflict)
    assertEquals(listOf("celeste.apworld"), lib.customWorlds("0.6.8"))
    val replaced = lib.upload("0.6.8", "celeste_new.apworld", world("celeste_new", "Celeste"), replace = true)
    assertNull(replaced.conflict)
    assertNull(replaced.error)
    assertEquals(listOf("celeste_new.apworld"), lib.customWorlds("0.6.8"))
  }

  @Test
  fun `an upload's game is known by its manifest at its top too`() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val lib = Library()
    lib.upload("0.6.8", "celeste.apworld", world("celeste", "Celeste"))
    val other = lib.upload("0.6.8", "celeste_top.apworld", world("celeste_top", "Celeste", top = true))
    assertEquals(ApworldConflict(game = "Celeste", otherFile = "celeste.apworld"), other.conflict)
    assertEquals(listOf("celeste.apworld"), lib.customWorlds("0.6.8"))
  }

  @Test
  fun `custom_worlds gets only what changed, and loses what is gone`() {
    val lib = Library()
    val source = lib.manual.resolve("manual.apworld").also { it.writeBytes(zip("manual/__init__.py")) }
    val placed = lib.root.resolve("0.6.8/custom_worlds/manual.apworld")
    fun hold() = runBlocking { lib.apworlds.holding("0.6.8") {} }
    hold()
    assertContentEquals(source.readBytes(), placed.readBytes())

    // Of the same size and time: left as it is (a process may import from it), so the mark stays
    val time = source.getLastModifiedTime()
    val marked = placed.readBytes().also { it[it.size - 1] = (it.last() + 1).toByte() }
    placed.writeBytes(marked)
    placed.setLastModifiedTime(time)
    hold()
    assertContentEquals(marked, placed.readBytes())

    // Changed: copied, and nothing half-copied left beside it
    source.writeBytes(zip("manual/__init__.py", "manual/Items.py"))
    source.setLastModifiedTime(FileTime.fromMillis(time.toMillis() + 10_000))
    hold()
    assertContentEquals(source.readBytes(), placed.readBytes())
    assertEquals(listOf("manual.apworld"), lib.customWorlds("0.6.8"))

    source.deleteIfExists()
    hold()
    assertEquals(emptyList(), lib.customWorlds("0.6.8"))
  }

  @Test
  fun `a game the release brings itself is refused`() {
    val lib = Library()
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    assertTrue(lib.upload("0.6.8", "hk.apworld", world("hk", "Hollow Knight")).error.orEmpty().contains("brings Hollow Knight itself"))
  }

  @Test
  fun `only releases the gateway has, and only real apworlds`() {
    val lib = Library()
    assertFailsWith<ApworldProblem> { lib.upload("../0.6.8", "x.apworld", zip("x/__init__.py")) }
    assertNotNull(lib.upload("0.6.8", "x.apworld", zip("../x/__init__.py")).error)
    assertFailsWith<ApworldProblem> { lib.remove("0.6.8", "missing.apworld") }
    // A name that leads to a release's directory, or merely looks like a release, is none
    lib.data.resolve("apworlds/0.6.8").createDirectories().resolve("y.apworld").writeBytes(zip("y/__init__.py"))
    for (name in listOf("0.6.8/../0.6.8", "./0.6.8", "..", "0.6.9")) {
      assertFailsWith<ApworldProblem>(name) { runBlocking { lib.apworlds.holding(name) {} } }
      assertFailsWith<ApworldProblem>(name) { runBlocking { lib.apworlds.release(name) } }
      assertNull(lib.apworlds.fileOf(name, "y.apworld"), name)
    }
    assertNotNull(lib.apworlds.fileOf("0.6.8", "y.apworld"))
  }

  @Test
  fun `several files go in together, each with its own answer, and a refused one isn't kept`() {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val lib = Library()
    val results = lib.uploadAll(
      "0.6.8",
      "celeste.apworld" to zip("celeste/__init__.py"),
      "broken.apworld" to zip("broken/__init__.py"),
      "not a zip.apworld" to "nope".toByteArray(),
      "yacht.apworld" to zip("yacht/__init__.py"),
    )
    assertEquals(listOf("celeste.apworld", "broken.apworld", "not_a_zip.apworld", "yacht.apworld"), results.map { it.file })
    assertEquals(listOf(listOf("celeste"), emptyList(), emptyList(), listOf("yacht")), results.map { it.games })
    assertEquals("cannot import", results[1].error)
    assertNotNull(results[2].error)
    assertEquals(listOf("celeste.apworld", "yacht.apworld"), lib.customWorlds("0.6.8"))

    // Two at once go out together too
    lib.remove("0.6.8", "celeste.apworld", "yacht.apworld")
    assertEquals(emptyList(), lib.customWorlds("0.6.8"))
  }
}
