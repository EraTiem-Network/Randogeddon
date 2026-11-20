package net.eratiem.randogeddon.gateway

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UploadedSeedsTest {
  private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip ->
      files.forEach { (name, content) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
  }.toByteArray()

  private fun refused(bytes: ByteArray): String {
    val out = Files.createTempFile("seed", ".zip")
    try {
      return assertFailsWith<SeedProblem> { cleanSeedZip(bytes, out) }.message.orEmpty()
    } finally {
      out.deleteIfExists()
    }
  }

  @Test
  fun `a seed zip is written anew with the files it has`() {
    val out = Files.createTempFile("seed", ".zip")
    val entries = cleanSeedZip(
      zip(
        "AP_123.archipelago" to "multidata", "AP_123_Spoiler.txt" to "spoiler",
        "AP_123_P1_Alice.aplttp" to "patch", "AP_123_P2_Bob (1).apz5" to "patch two",
      ),
      out,
    )
    assertEquals(2, entries.files)
    assertEquals("ap_123_spoiler.txt", entries.spoiler)
    ZipFile(out.toFile()).use { written ->
      assertEquals(
        listOf("AP_123.archipelago", "AP_123_P1_Alice.aplttp", "AP_123_P2_Bob (1).apz5", "AP_123_Spoiler.txt"),
        written.entries().toList().map { it.name }.sorted(),
      )
      assertEquals("patch two", written.getInputStream(written.getEntry("AP_123_P2_Bob (1).apz5")).readBytes().decodeToString())
    }
    // Without a spoiler log and without files for players
    assertEquals(SeedEntries("ap_9.archipelago", null, 0), cleanSeedZip(zip("AP_9.archipelago" to "m"), out))
    out.deleteIfExists()
  }

  @Test
  fun `what isn't a seed's file is refused, with the reason`() {
    val seed = "AP_1.archipelago" to "m"
    // Paths out of it, folders, and names Archipelago doesn't give
    for (name in listOf("../AP_1.txt", "AP_dir/AP_1.txt", "/AP_1.txt", "AP_a\\b.txt", "notes.txt", "AP_x:stream", "AP_1.", ".AP_hidden")) {
      assertTrue("isn't a file of a seed" in refused(zip(seed, name to "x")), name)
    }
    assertTrue("isn't a file of a seed" in refused(zip(seed, "AP_folder/" to "")))
    // The same name twice (also for a file system that doesn't tell upper and lower case apart)
    assertTrue("twice" in refused(zip(seed, "AP_1_P1.apz5" to "a", "AP_1_p1.APZ5" to "b")))
    assertTrue("no .archipelago" in refused(zip("AP_1_Spoiler.txt" to "s")))
    assertTrue("more than one .archipelago" in refused(zip(seed, "AP_2.archipelago" to "m")))
    assertTrue("more than one spoiler" in refused(zip(seed, "AP_1_Spoiler.txt" to "s", "AP_2_Spoiler.txt" to "s")))
    assertTrue("isn't a zip file" in refused("not a zip at all".toByteArray()))
    assertTrue("isn't a zip file" in refused(ByteArray(0)))
    assertTrue("more than $MAX_SEED_FILES files" in refused(zip(seed, *(1..MAX_SEED_FILES).map { "AP_1_P$it.txt" to "" }.toTypedArray())))
  }
}
