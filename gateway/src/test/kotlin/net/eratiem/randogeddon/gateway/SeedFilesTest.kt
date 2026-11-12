package net.eratiem.randogeddon.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeedFilesTest {
  private val data: Path = Files.createTempDirectory("data")
  private val files = SeedFiles(data)

  private fun seed(name: String, spoiler: String? = "Playthrough:\n") {
    ZipOutputStream(data.resolve("AP_$name.zip").outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("AP_$name.archipelago")); zip.closeEntry()
      if (spoiler != null) {
        zip.putNextEntry(ZipEntry("AP_${name}_Spoiler.txt")); zip.write(spoiler.toByteArray()); zip.closeEntry()
      }
    }
    data.resolve("AP_$name.apsave").createFile()
    data.resolve("room.json").writeText("{}")
  }

  @Test
  fun `a finished seed is archived under its name, and comes back when reopened`() {
    seed("111")
    data.resolve("apworlds").createDirectories()
    assertEquals("111", files.currentSeed())
    assertEquals("111", files.archive())
    assertNull(files.currentSeed())
    assertTrue(data.resolve("sessions/111/AP_111.zip").exists() && data.resolve("sessions/111/AP_111.apsave").exists())
    // Only the seed's files: the apworld library stays
    assertTrue(data.resolve("apworlds").exists())

    assertTrue(files.activate("111"))
    assertEquals("111", files.currentSeed())
    assertTrue(data.resolve("AP_111.apsave").exists() && data.resolve("room.json").exists())
    assertFalse(data.resolve("sessions/111").exists())
  }

  @Test
  fun `a seed that went to old (the next one was deployed with the CLI) can come back too`() {
    seed("111")
    files.retire()
    seed("222")
    files.retire()
    assertTrue(files.activate("111"))
    assertEquals("111", files.currentSeed())
    assertTrue(data.resolve("AP_111.apsave").exists())
    // The other seed's files stay in old
    assertTrue(data.resolve("old/AP_222.zip").exists())
  }

  @Test
  fun `reopening needs an empty data directory and an archived seed`() {
    seed("111")
    files.archive()
    seed("222")
    assertFailsWith<IllegalStateException> { files.activate("111") }
    assertFalse(files.activate("999"))
    assertFalse(files.activate("../111"))
  }

  @Test
  fun `the spoiler log comes from the seed's zip, wherever it is`() {
    seed("111", spoiler = "Playthrough:\n\n0: {\n}\n")
    assertEquals("Playthrough:\n\n0: {\n}\n", files.spoiler("111"))
    files.archive()
    assertTrue(files.spoiler("111")!!.startsWith("Playthrough"))
    files.retire()
    seed("222", spoiler = null)
    assertNull(files.spoiler("222"))
    assertNull(files.spoiler("../111"))
    assertNull(files.spoiler("333"))
  }

  @Test
  fun `a deleted archive is gone for good`() {
    seed("111")
    files.archive()
    assertTrue(files.delete("111"))
    assertFalse(data.resolve("sessions/111").exists())
    assertFalse(files.delete("111"))
    assertFalse(files.delete(".."))
  }
}
