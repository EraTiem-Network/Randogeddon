package net.eratiem.randogeddon.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressesTest {
  @Test
  fun `addresses and ranges are read as literals`() {
    val docker = assertNotNull(AddressRange.parse("172.16.0.0/12"))
    assertTrue("172.18.0.3" in docker)
    assertTrue("172.31.255.255" in docker)
    assertFalse("172.32.0.1" in docker)
    assertFalse("10.0.0.1" in docker)
    // An IPv4 address written as IPv6 is that address
    assertTrue("::ffff:172.18.0.3" in docker)
    val one = assertNotNull(AddressRange.parse(" 203.0.113.7 "))
    assertTrue("203.0.113.7" in one)
    assertFalse("203.0.113.8" in one)
    val v6 = assertNotNull(AddressRange.parse("fd00::/8"))
    assertTrue("fd12:3456::1" in v6)
    assertTrue("[fd12:3456::1]" in v6)
    assertFalse("fe80::1" in v6)
    assertFalse("172.18.0.3" in v6)
    assertTrue("anything" !in docker)
    // Names are never looked up, and nothing else is a range
    for (text in listOf("traefik", "localhost", "300.1.2.3", "1.2.3", "172.16.0.0/33", "fd00::/129", "10.0.0.0/x", "")) {
      assertNull(AddressRange.parse(text), text)
    }
  }

  @Test
  fun `the setting lists the proxies, and one that is no address stops the start`() {
    assertEquals(emptyList(), trustedProxies(""))
    assertEquals(emptyList(), trustedProxies(" , "))
    val two = trustedProxies("172.16.0.0/12, 10.0.0.5")
    assertEquals(2, two.size)
    assertTrue("10.0.0.5" in two[1])
    assertFailsWith<IllegalArgumentException> { trustedProxies("172.16.0.0/12,traefik") }
  }

  @Test
  fun `the forwarded address is believed only from a trusted proxy`() {
    val traefik = trustedProxies("172.16.0.0/12")
    // Through Traefik: the address it saw (the last; earlier ones the client wrote itself)
    assertEquals("203.0.113.7", clientAddress("172.18.0.2", listOf("198.51.100.1, 203.0.113.7"), traefik))
    assertEquals("203.0.113.7", clientAddress("172.18.0.2", listOf("198.51.100.1", " 203.0.113.7 "), traefik))
    assertEquals("172.18.0.2", clientAddress("172.18.0.2", emptyList(), traefik))
    // Past Traefik, straight to the port: whatever the header says, the peer counts
    assertEquals("192.0.2.50", clientAddress("192.0.2.50", listOf("203.0.113.99"), traefik))
    // Nothing set: every peer is believed, as before the setting
    assertEquals("203.0.113.99", clientAddress("192.0.2.50", listOf("203.0.113.99"), emptyList()))
    assertEquals("192.0.2.50", clientAddress("192.0.2.50", listOf(" , "), emptyList()))
  }

  @Test
  fun `an IPv6 network counts as one address`() {
    assertEquals(addressKey("2001:db8:1:2::1"), addressKey("2001:db8:1:2:ffff:ffff:ffff:ffff"))
    assertEquals(addressKey("2001:db8:1:2::1"), addressKey("[2001:DB8:1:2:0:0:0:9]"))
    assertFalse(addressKey("2001:db8:1:2::1") == addressKey("2001:db8:1:3::1"))
    assertEquals("203.0.113.7", addressKey("203.0.113.7"))
    // An IPv4 address written as IPv6 is that address
    assertEquals("203.0.113.7", addressKey("::ffff:203.0.113.7"))
    // Not a literal: as it is, never looked up
    assertEquals("some-proxy", addressKey("some-proxy"))
  }
}
