package net.eratiem.randogeddon.database

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BlockTest {
  private val now = Instant.parse("2026-11-17T10:00:00Z")

  @Test
  fun `a time-out holds until its time, a suspension until it is taken back`() {
    val timedOut = Block(now, now.plusSeconds(3600), "spam", "admin")
    assertTrue(timedOut.holds(now) && timedOut.holds(now.plusSeconds(3599)))
    assertFalse(timedOut.holds(now.plusSeconds(3600)))
    val suspended = Block(now, null, null, "admin")
    assertTrue(suspended.holds(now.plusSeconds(10L * 365 * 86400)))
  }

  @Test
  fun `a user is kept out while it holds, and a role's person isn't there for the session`() {
    val user = User(Uuid.random(), "bob", "hash")
    assertFalse(user.keptOut(now))
    val timedOut = user.copy(block = Block(now, now.plusSeconds(60), null, "admin"))
    assertTrue(timedOut.keptOut(now))
    assertFalse(timedOut.keptOut(now.plusSeconds(61)))
    // In a session: a manager who is kept out or deleted can't act for it; one whose time-out ran out can
    assertFalse(SessionRoleEntry("manager", "bob", false).unavailable(now))
    assertTrue(SessionRoleEntry("manager", "bob", true).unavailable(now))
    val entry = SessionRoleEntry("manager", "bob", false, Block(now, now.plusSeconds(60), null, null))
    assertTrue(entry.unavailable(now))
    assertFalse(entry.unavailable(now.plusSeconds(61)))
  }
}
