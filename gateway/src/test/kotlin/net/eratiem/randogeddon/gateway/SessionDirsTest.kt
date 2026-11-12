package net.eratiem.randogeddon.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionDirsTest {
  private val data: Path = Files.createTempDirectory("data")
  private val dirs = SessionDirs(data)

  private fun seed(dir: Path, name: String, spoiler: String? = "Playthrough:\n") {
    dir.createDirectories()
    ZipOutputStream(dir.resolve("AP_$name.zip").outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("AP_$name.archipelago")); zip.closeEntry()
      if (spoiler != null) {
        zip.putNextEntry(ZipEntry("AP_${name}_Spoiler.txt")); zip.write(spoiler.toByteArray()); zip.closeEntry()
      }
    }
    dir.resolve("AP_$name.apsave").createFile()
    dir.resolve("room.json").writeText("""{"seed_name": "$name"}""")
  }

  @Test
  fun `each session has its directory and seed, named by its ID`() {
    seed(data.resolve("sessions/brave-otter"), "111")
    data.resolve("sessions/friday-chaos").createDirectories()
    // Not a session: no valid ID
    data.resolve("sessions/Not_An_Id").createDirectories()
    assertEquals(listOf("brave-otter", "friday-chaos"), dirs.ids())
    assertEquals("AP_111.zip", dirs.seed("brave-otter")?.name)
    assertNull(dirs.seed("friday-chaos"))
    assertNull(dirs.seed("../brave-otter"))
    assertFailsWith<IllegalArgumentException> { dirs.dir("..") }
  }

  @Test
  fun `the spoiler log comes from the session's seed zip`() {
    seed(data.resolve("sessions/brave-otter"), "111", spoiler = "Playthrough:\n\n0: {\n}\n")
    seed(data.resolve("sessions/quiet-fox"), "222", spoiler = null)
    assertEquals("Playthrough:\n\n0: {\n}\n", dirs.spoiler("brave-otter"))
    assertNull(dirs.spoiler("quiet-fox"))
    assertNull(dirs.spoiler("nobody-here"))
  }

  @Test
  fun `a removed session goes to old, or for good`() {
    seed(data.resolve("sessions/brave-otter"), "111")
    seed(data.resolve("sessions/quiet-fox"), "222")
    assertTrue(dirs.remove("brave-otter", purge = false))
    assertFalse(data.resolve("sessions/brave-otter").exists())
    val old = data.resolve("old").listDirectoryEntries().single()
    assertTrue(old.name.startsWith("brave-otter-") && old.resolve("AP_111.apsave").exists())
    assertTrue(dirs.remove("quiet-fox", purge = true))
    assertFalse(data.resolve("sessions/quiet-fox").exists())
    assertFalse(dirs.remove("quiet-fox", purge = true))
    assertFalse(dirs.remove("..", purge = true))
  }

  @Test
  fun `the files of one session at a time move into the session directories, once`() {
    // The current seed in the data directory itself, with session.yaml; a finished one archived by its seed
    seed(data, "111")
    data.resolve("session.yaml").writeText("people: {}")
    seed(data.resolve("sessions/222"), "222")
    data.resolve("apworlds/0.6.8").createDirectories()
    val ids = mapOf("111" to "s-111", "222" to "friday-chaos")
    dirs.migrate { ids.getValue(it) }

    assertEquals(listOf("friday-chaos", "s-111"), dirs.ids())
    assertTrue(data.resolve("sessions/s-111/AP_111.apsave").exists())
    assertEquals("""{"seed_name": "111"}""", data.resolve("sessions/s-111/room.json").readText())
    assertTrue(data.resolve("sessions/friday-chaos/AP_222.zip").exists())
    assertFalse(data.resolve("AP_111.zip").exists() || data.resolve("sessions/222").exists())
    assertTrue(data.resolve("old/session.yaml").exists())
    // The apworld library stays where it is
    assertTrue(data.resolve("apworlds/0.6.8").exists())
    // Nothing left to do the next time
    dirs.migrate { error("not asked") }
  }
}
