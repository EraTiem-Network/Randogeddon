package net.eratiem.randogeddon.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionLimitsTest {
  private val limits = Limits(perAddress = 4, waitingPerAddress = 2, waiting = 3)

  @Test
  fun `an address has few connections that aren't in a slot yet`() {
    val connections = ConnectionLimits(limits)
    val first = assertNotNull(connections.enter("203.0.113.7"))
    assertNotNull(connections.enter("203.0.113.7"))
    assertNull(connections.enter("203.0.113.7"))
    // In a slot, it makes room for the next one waiting
    first.joined()
    assertTrue(first.joined)
    assertNotNull(connections.enter("203.0.113.7"))
    assertNull(connections.enter("203.0.113.7"))
  }

  @Test
  fun `an address has a limit in all, and a place given back is free again`() {
    val connections = ConnectionLimits(limits)
    val tickets = (1..4).map { assertNotNull(connections.enter("203.0.113.7")).also { it.joined() } }
    assertNull(connections.enter("203.0.113.7"))
    tickets.first().leave()
    // Given back once only
    tickets.first().leave()
    assertNotNull(connections.enter("203.0.113.7"))
    assertNull(connections.enter("203.0.113.7"))
  }

  @Test
  fun `only so many wait in all, whatever their addresses`() {
    val connections = ConnectionLimits(limits)
    val waiting = (1..3).map { assertNotNull(connections.enter("203.0.113.$it")) }
    assertNull(connections.enter("203.0.113.99"))
    waiting.first().leave()
    val next = assertNotNull(connections.enter("203.0.113.99"))
    assertFalse(next.joined)
    // A place left after joining isn't counted as waiting twice
    next.joined()
    next.leave()
    assertNotNull(connections.enter("203.0.113.100"))
  }

  @Test
  fun `an IPv6 network counts as one address`() {
    assertEquals(addressKey("2001:db8:1:2::1"), addressKey("2001:db8:1:2:ffff:ffff:ffff:ffff"))
    assertEquals(addressKey("2001:db8:1:2::1"), addressKey("[2001:DB8:1:2:0:0:0:9]"))
    assertFalse(addressKey("2001:db8:1:2::1") == addressKey("2001:db8:1:3::1"))
    assertEquals("203.0.113.7", addressKey("203.0.113.7"))
    // An IPv4 address written as IPv6 is that IPv4 address
    assertEquals("203.0.113.7", addressKey("::ffff:203.0.113.7"))
    // Not an address: left as it is, without a lookup
    assertEquals("some-proxy", addressKey("some-proxy"))
    val connections = ConnectionLimits(limits)
    assertNotNull(connections.enter("2001:db8:1:2::1"))
    assertNotNull(connections.enter("2001:db8:1:2::2"))
    assertNull(connections.enter("2001:db8:1:2::3"))
  }

  @Test
  fun `a name for the log has no control characters`() {
    assertEquals("Alice?2026 INFO fake", printable("Alice\n2026 INFO fake"))
    assertEquals("", printable(null))
    assertEquals(80, printable("x".repeat(500)).length)
  }
}
