package net.eratiem.randogeddon.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TunnelKeysTest {
  @Test
  fun `a tunnel key is the same for a slot and its password, and another for anything else`() {
    val key = minecraftTunnelKey("s3cret", "Erin-Dig")
    assertEquals(key, minecraftTunnelKey("s3cret", "Erin-Dig"))
    assertTrue(key.matches(Regex("[0-9a-f]{64}")))
    assertNotEquals(key, minecraftTunnelKey("s3cret", "Erin-HK"))
    assertNotEquals(key, minecraftTunnelKey("other", "Erin-Dig"))
    assertFalse("s3cret" in key)
  }
}
