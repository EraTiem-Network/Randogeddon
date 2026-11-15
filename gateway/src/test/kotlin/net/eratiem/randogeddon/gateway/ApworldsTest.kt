package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.shared.ApworldConflict
import net.eratiem.randogeddon.shared.ApworldFile
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.GameInfo
import net.eratiem.randogeddon.shared.GameSource
import net.eratiem.randogeddon.shared.ReleaseInfo
import org.junit.Assume.assumeFalse
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
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

  /** An apworld of [world] whose manifest names [game]. */
  private fun world(world: String, game: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      zip.putNextEntry(ZipEntry("$world/__init__.py")); zip.closeEntry()
      zip.putNextEntry(ZipEntry("$world/archipelago.json")); zip.write("""{"game": "$game", "world_version": "1.0.0"}""".toByteArray()); zip.closeEntry()
    }
    return bytes.toByteArray()
  }

  @Test
  fun `an apworld's world and game come from inside it`() {
    assertEquals("celeste", worldOf(zip("celeste/__init__.py", "celeste/Items.py")))
    assertEquals("Celeste", manifestGame(world("celeste", "Celeste")))
    assertNull(manifestGame(zip("celeste/__init__.py")))
    assertNotNull(checkApworld(zip("a/__init__.py", "b/__init__.py")))
    assertNotNull(checkApworld(zip("my-game/__init__.py")))
  }

  @Test
  fun `an apworld is a zip with one world folder`() {
    assertNull(checkApworld(zip("celeste/__init__.py", "celeste/Items.py", "celeste/data/a.json")))
    assertNotNull(checkApworld(zip("__init__.py")))
    assertNotNull(checkApworld(zip("celeste/sub/__init__.py")))
    assertNotNull(checkApworld("not a zip".toByteArray()))
  }

  @Test
  fun `no entry may lead out of it`() {
    assertNotNull(checkApworld(zip("celeste/__init__.py", "../evil.py")))
    assertNotNull(checkApworld(zip("celeste/__init__.py", "celeste/../../evil.py")))
    assertNotNull(checkApworld(zip("celeste/__init__.py", "/etc/evil")))
    assertNotNull(checkApworld(zip("celeste/__init__.py", "C:/evil")))
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

    fun upload(release: String, name: String, bytes: ByteArray) =
      runBlocking { apworlds.upload(ApworldUpload(release, name, Base64.getEncoder().encodeToString(bytes))) }

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
    assertFailsWith<ApworldProblem> { runBlocking { lib.apworlds.remove("0.6.8", "broken.apworld") } }
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
    val replaced = runBlocking {
      lib.apworlds.upload(ApworldUpload("0.6.8", "celeste_new.apworld", Base64.getEncoder().encodeToString(world("celeste_new", "Celeste")), replace = true))
    }
    assertNull(replaced.conflict)
    assertNull(replaced.error)
    assertEquals(listOf("celeste_new.apworld"), lib.customWorlds("0.6.8"))
  }

  @Test
  fun `a game the release brings itself is refused`() {
    val lib = Library()
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    assertFailsWith<ApworldProblem> { lib.upload("0.6.8", "hk.apworld", world("hk", "Hollow Knight")) }
  }

  @Test
  fun `only releases the gateway has, and only real apworlds`() {
    val lib = Library()
    assertFailsWith<ApworldProblem> { lib.upload("../0.6.8", "x.apworld", zip("x/__init__.py")) }
    assertFailsWith<ApworldProblem> { lib.upload("0.6.8", "x.apworld", zip("../x/__init__.py")) }
    assertFailsWith<ApworldProblem> { runBlocking { lib.apworlds.remove("0.6.8", "missing.apworld") } }
  }
}
