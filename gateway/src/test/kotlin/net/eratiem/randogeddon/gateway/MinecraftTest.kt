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

  @Test
  fun `a patch whose data json is too long is no slot, and isn't read whole`() {
    val seed = Files.createTempFile("AP_123", ".zip")
    seed.writeBytes(
      zip(
        "AP_123_P1_Huge.apmcdig" to zip("data.json" to ByteArray(MAX_DIG_DATA + 1) { 'x'.code.toByte() }),
        "AP_123_P2_Fits.apmcdig" to zip("data.json" to ByteArray(MAX_DIG_DATA) { 'y'.code.toByte() }),
      ),
    )
    assertEquals(listOf("Fits"), digSlots(seed).map { it.name })
    Files.delete(seed)
  }

  @Test
  fun `a deleted session's servers are its own, not those of a session whose ID starts the same`() {
    val keys = listOf("abc-p1", "abc-p12", "abc-party-p2", "abc-p1-p1", "abcd-p1", "abc-p", "xabc-p1")
    assertEquals(listOf("abc-p1", "abc-p12"), serverKeysOf("abc", keys))
    assertEquals(listOf("abc-p1-p1"), serverKeysOf("abc-p1", keys))
    assertEquals(listOf("abc-party-p2"), serverKeysOf("abc-party", keys))
    assertEquals(emptyList(), serverKeysOf("ab", keys))
  }
}
