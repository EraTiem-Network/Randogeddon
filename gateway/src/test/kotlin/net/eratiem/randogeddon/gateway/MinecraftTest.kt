package net.eratiem.randogeddon.gateway

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class MinecraftTest {
  private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { z -> files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
  }.toByteArray()

  @Test
  fun `a seed's Minecraft Dig slots come from its patches, each with its data json`() {
    val patch = zip("data.json" to """{"player_name":"Erin-Dig","client_version":16}""".toByteArray(), "archipelago.json" to "{}".toByteArray())
    val seed = Files.createTempFile("AP_123", ".zip")
    seed.writeBytes(
      zip(
        "AP_123.archipelago" to byteArrayOf(1),
        "AP_123_P7_Erin-Dig.apmcdig" to patch,
        "AP_123_P2_Alice_HK.apmcdig" to zip("data.json" to "{}".toByteArray()),
        "AP_123_P3_Bob.apz5" to byteArrayOf(2),
        // Without its data.json: not a slot to host
        "AP_123_P4_Broken.apmcdig" to zip("other.txt" to byteArrayOf(3)),
      ),
    )
    val slots = digSlots(seed)
    assertEquals(listOf(2 to "Alice_HK", 7 to "Erin-Dig"), slots.map { it.number to it.name })
    assertEquals("""{"player_name":"Erin-Dig","client_version":16}""", slots.last().data)
    Files.delete(seed)
  }
}
