package net.eratiem.randogeddon.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionLimitsTest {
  private val limits = Limits(waiting = 3)

  @Test
  fun `only so many wait for their Connect at once`() {
    val connections = ConnectionLimits(limits)
    val waiting = (1..3).map { assertNotNull(connections.enter()) }
    assertNull(connections.enter())
    // One gone: its place is free again, once (a second leave gives nothing back)
    waiting.first().leave()
    waiting.first().leave()
    assertNotNull(connections.enter())
    assertNull(connections.enter())
  }

  @Test
  fun `the ones in a slot aren't limited`() {
    val connections = ConnectionLimits(limits)
    val joined = (1..50).map { assertNotNull(connections.enter()).also { it.joined() } }
    assertTrue(joined.all { it.joined })
    val next = assertNotNull(connections.enter())
    assertFalse(next.joined)
    // Leaving after joining doesn't free a waiting place twice
    joined.forEach { it.leave() }
    assertNotNull(connections.enter())
    assertNotNull(connections.enter())
    assertNull(connections.enter())
  }

  @Test
  fun `a name for the log has no control characters`() {
    assertEquals("Alice?2026 INFO fake", printable("Alice\n2026 INFO fake"))
    assertEquals("", printable(null))
    assertEquals(80, printable("x".repeat(500)).length)
    assertEquals("a b  c", withoutControls("a\nb\r\tc", ' '))
  }
}
