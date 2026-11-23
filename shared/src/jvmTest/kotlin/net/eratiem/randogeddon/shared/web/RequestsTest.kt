package net.eratiem.randogeddon.shared.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.Totals

class RequestsTest {
  private fun summary(startedAt: Long, version: Long, finishedAt: Long? = null) = Summary(
    title = "Session", connected = true, startedAt = startedAt, version = version, finishedAt = finishedAt,
    totals = Totals(checked = 0, total = 0, percent = 0.0, players = 0, goals = 0, online = 0),
    players = emptyList(), events = emptyList(), hints = emptyList(),
  )

  @Test
  fun `a summary replaces none, a newer one and one of another run, not an older one of the same run`() {
    val shown = summary(startedAt = 1_000, version = 5)
    assertTrue(shown.replaces(null))
    assertTrue(summary(1_000, 6).replaces(shown))
    // The same state again (the first fetch beside the stream)
    assertTrue(summary(1_000, 5).replaces(shown))
    assertFalse(summary(1_000, 4).replaces(shown))
    // The tracker restarted: its count starts over
    assertTrue(summary(2_000, 0).replaces(shown))
    // Finished meanwhile, whatever its version
    assertTrue(summary(1_000, 4, finishedAt = 3_000).replaces(shown))
  }

  @Test
  fun `the wait before a new attempt doubles from 2 s up to 30 s, and up to 1 s more by chance`() {
    fun within(failures: Int, base: Long) = repeat(50) {
      val wait = retryDelay(failures)
      assertTrue(wait in base until base + 1_000, "failure $failures waits $wait")
    }
    within(1, 2_000)
    within(2, 4_000)
    within(3, 8_000)
    within(4, 16_000)
    within(5, 30_000)
    within(12, 30_000)
    // Never shorter than the first wait
    within(0, 2_000)
    within(-3, 2_000)
  }

  @Test
  fun `a path segment keeps letters, digits and dash, underscore and tilde, and encodes every other byte`() {
    assertEquals("Session_1-a~b", "Session_1-a~b".pathSegment())
    assertEquals("a%2Fb", "a/b".pathSegment())
    assertEquals("%3F%23%25%20", "?#% ".pathSegment())
    assertEquals("%2E%2E", "..".pathSegment())
    // Each byte of UTF-8, also of a letter outside ASCII
    assertEquals("%C3%A4", "ä".pathSegment())
    assertEquals("", "".pathSegment())
  }
}
