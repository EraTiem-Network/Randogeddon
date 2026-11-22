package net.eratiem.randogeddon.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionLimitsTest {
  private val limits = Limits(waiting = 3)

  private fun ConnectionLimits.ticket(address: String = "203.0.113.7") = assertIs<ConnectionLimits.Ticket>(enter(address))

  @Test
  fun `only so many wait for their Connect at once`() {
    val connections = ConnectionLimits(limits)
    val waiting = (1..3).map { connections.ticket("203.0.113.$it") }
    assertEquals(ConnectionLimits.Full.ALL, connections.enter("198.51.100.1"))
    // One gone: its place is free again, once (a second leave gives nothing back)
    waiting.first().leave()
    waiting.first().leave()
    connections.ticket("198.51.100.1")
    assertEquals(ConnectionLimits.Full.ALL, connections.enter("198.51.100.2"))
  }

  @Test
  fun `the ones in a slot aren't limited`() {
    val connections = ConnectionLimits(limits)
    val joined = (1..50).map { connections.ticket().also { it.joined() } }
    assertTrue(joined.all { it.joined })
    val next = connections.ticket()
    assertFalse(next.joined)
    // Leaving after joining doesn't free a waiting place twice
    joined.forEach { it.leave() }
    connections.ticket()
    connections.ticket()
    assertEquals(ConnectionLimits.Full.ALL, connections.enter("203.0.113.7"))
  }

  @Test
  fun `one address holds at most 32 places, an IPv6 network as one`() {
    val connections = ConnectionLimits()
    val one = (1..32).map { connections.ticket("203.0.113.7") }
    // The 33rd isn't let in; another address is
    assertEquals(ConnectionLimits.Full.ADDRESS, connections.enter("203.0.113.7"))
    connections.ticket("203.0.113.8")
    // In a slot or gone: room for the next one from there
    one.first().joined()
    connections.ticket("203.0.113.7")
    assertEquals(ConnectionLimits.Full.ADDRESS, connections.enter("203.0.113.7"))
    one.last().leave()
    connections.ticket("203.0.113.7")
    // Any address of a /64 counts for the whole network; the next /64 is another
    (1..32).forEach { connections.ticket("2001:db8:1:2::$it") }
    assertEquals(ConnectionLimits.Full.ADDRESS, connections.enter("2001:db8:1:2:ffff::1"))
    assertEquals(32, connections.waitingFrom("[2001:db8:1:2::abcd]"))
    connections.ticket("2001:db8:1:3::1")
  }

  @Test
  fun `a name for the log has no control characters`() {
    assertEquals("Alice?2026 INFO fake", printable("Alice\n2026 INFO fake"))
    assertEquals("", printable(null))
    assertEquals(80, printable("x".repeat(500)).length)
    assertEquals("a b  c", withoutControls("a\nb\r\tc", ' '))
  }
}
