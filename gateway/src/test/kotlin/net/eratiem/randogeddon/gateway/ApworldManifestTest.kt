package net.eratiem.randogeddon.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApworldManifestTest {
  private fun apworld(vararg files: Pair<String, String>): Path {
    val path = Files.createTempFile("world", ".apworld")
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
      for ((name, text) in files) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.encodeToByteArray())
        zip.closeEntry()
      }
    }
    return path
  }

  @Test
  fun `an apworld's game, version and oldest release come from its archipelago json`() {
    val world = apworld(
      "a1800/__init__.py" to "",
      "a1800/archipelago.json" to """{"game": "Anno 1800", "world_version": "1.5.1", "minimum_ap_version": "0.6.7", "version": 7}""",
    )
    assertEquals(ApworldManifest("Anno 1800", "1.5.1", "0.6.7"), apworldManifest(world))
    // At its top too; what isn't text is left out
    assertEquals(
      ApworldManifest("Minecraft Dig", null, null),
      apworldManifest(apworld("archipelago.json" to """{"game": "Minecraft Dig", "world_version": 11}""")),
    )
  }

  @Test
  fun `without one, or with one that isn't JSON or lies deeper, there is nothing to tell`() {
    assertNull(apworldManifest(apworld("adofai/__init__.py" to "")))
    assertNull(apworldManifest(apworld("adofai/archipelago.json" to "not json")))
    assertNull(apworldManifest(apworld("adofai/data/archipelago.json" to """{"game": "X"}""")))
    // Not a zip at all
    assertNull(apworldManifest(Files.createTempFile("broken", ".apworld").also { Files.writeString(it, "PK fake") }))
  }

  @Test
  fun `the upload's check reads the same manifest as the library's list`() {
    val manifest = """{"game": "Anno 1800", "world_version": "1.5.1"}"""
    val worlds = listOf(
      apworld("a1800/__init__.py" to "", "a1800/archipelago.json" to manifest),
      apworld("a1800/__init__.py" to "", "archipelago.json" to manifest),
      // Both: the shorter path wins
      apworld(
        "a1800/__init__.py" to "", "a1800/archipelago.json" to """{"game": "Other"}""", "archipelago.json" to manifest,
      ),
      apworld("a1800/__init__.py" to "", "a1800/data/archipelago.json" to manifest),
      apworld("a1800/__init__.py" to "", "a1800/archipelago.json" to "not json"),
      apworld("a1800/__init__.py" to ""),
    )
    val anno = ApworldManifest("Anno 1800", "1.5.1", null)
    assertEquals(listOf(anno, anno, anno, null, null, null), worlds.map { apworldManifest(it) })
    assertEquals(worlds.map { apworldManifest(it) }, worlds.map { lookAt(Files.readAllBytes(it)).manifest })
  }
}
