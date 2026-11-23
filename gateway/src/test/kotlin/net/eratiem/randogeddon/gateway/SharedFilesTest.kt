package net.eratiem.randogeddon.gateway

import org.junit.Assume.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Archipelago's user can put into the folders it writes too (a session's, a temporary one): symbolic links to what
 * only the gateway may read or write. The gateway reads, writes and deletes there without ever following one. Skipped
 * where the file system can't make a link (Windows without the right).
 */
class SharedFilesTest {
  private lateinit var base: Path
  /** A session's directory: Archipelago's user writes into it. */
  private lateinit var shared: Path
  /** What is the gateway's alone (its environment, the APWorld library). */
  private lateinit var secret: Path
  private lateinit var library: Path

  @BeforeTest
  fun setUp() {
    base = Files.createTempDirectory("shared")
    shared = base.resolve("sessions/brave-otter").createDirectories()
    secret = base.resolve("environ").also { it.writeText("INTERNAL_TOKEN=hunter2") }
    library = base.resolve("apworlds").createDirectories().also { it.resolve("hk.apworld").writeText("world") }
    val canLink = runCatching { Files.createSymbolicLink(base.resolve("probe"), secret) }.isSuccess
    assumeTrue("needs symbolic links", canLink)
  }

  @AfterTest
  fun tearDown() {
    if (::base.isInitialized) base.toFile().deleteRecursively()
  }

  private fun link(at: Path, to: Path) = Files.createSymbolicLink(at, to)

  @Test
  fun `a link where a file is read is refused, not followed`() {
    link(shared.resolve("spoiler.txt"), secret)
    val folder = SharedFolder(shared)
    assertFailsWith<IOException> { folder.readText("spoiler.txt", 1024) }
    assertNull(folder.file("spoiler.txt"))
    // Not among the files either, nor a seed
    link(shared.resolve("AP_1.zip"), secret)
    assertEquals(emptySet(), folder.files().keys)
    assertNull(newestSeed(shared))
    // A link to a folder on the way, too
    link(shared.resolve("generation"), library)
    assertFailsWith<IOException> { folder.readText("generation/hk.apworld", 1024) }
    // A regular file is read, up to its bound
    shared.resolve("room.json").writeText("{}")
    assertEquals("{}", folder.readText("room.json", 1024))
    assertFailsWith<IOException> { folder.readText("room.json", 1) }
  }

  @Test
  fun `a file is written anew beside a link put in its way, and moved over the link, its target stays`() {
    // Both the file and its temporary name are links to the gateway's own files
    link(shared.resolve("stages.json"), secret)
    link(shared.resolve("stages.json.new"), library.resolve("hk.apworld"))
    SharedFolder(shared).writeText("stages.json", "{\"seed\": 1}")
    assertEquals("INTERNAL_TOKEN=hunter2", secret.readText())
    assertEquals("world", library.resolve("hk.apworld").readText())
    assertFalse(shared.resolve("stages.json").isSymbolicLink())
    assertEquals("{\"seed\": 1}", shared.resolve("stages.json").readText())
    assertFalse(shared.resolve("stages.json.new").exists())
    // Into a folder that is a link: refused, nothing written there
    link(shared.resolve("generation"), library)
    assertFailsWith<IOException> { SharedFolder(shared).writeText("generation/stages.json", "{}") }
    assertFailsWith<IOException> { SharedFolder(shared).folder("generation") }
    assertFalse(library.resolve("stages.json").exists() || library.resolve("stages.json.new").exists())
  }

  @Test
  fun `a tree is deleted without entering a link`() {
    val kept = shared.resolve("kept").createDirectories()
    kept.resolve("game-0.json").writeText("{}")
    link(kept.resolve("library"), library)
    link(kept.resolve("environ"), secret)
    deleteTree(shared)
    assertFalse(shared.exists())
    // What the links led to is all there
    assertEquals("world", library.resolve("hk.apworld").readText())
    assertTrue(secret.exists())
  }

  @Test
  fun `a session's directory is removed for good without following its links`() {
    val data = base.resolve("data")
    val session = data.resolve("sessions/quiet-fox").createDirectories()
    session.resolve("AP_1.zip").writeText("seed")
    link(session.resolve("library"), library)
    assertTrue(SessionDirs(data).remove("quiet-fox", purge = true))
    assertFalse(session.exists())
    assertTrue(library.resolve("hk.apworld").exists())
  }

  @Test
  fun `room json is never written from a link room_info's folder had in its place`() {
    val root = base.resolve("ap").createDirectories()
    val home = root.resolve("0.6.8").createDirectories()
    home.resolve("MultiServer.py").writeText("")
    // As Archipelago's code could: the answer is a link to the gateway's own secrets
    home.resolve("room_info.py").writeText("""ln -s "$secret" "${'$'}2"""")
    assumeTrue("needs sh", !System.getProperty("os.name").startsWith("Windows"))
    val roomJson = shared.resolve("room.json")
    assertFailsWith<IOException> { Launcher(Archipelago(root, "sh")) { null }.prepare(shared.resolve("AP_1.zip"), roomJson) }
    assertFalse(roomJson.exists())
  }
}
