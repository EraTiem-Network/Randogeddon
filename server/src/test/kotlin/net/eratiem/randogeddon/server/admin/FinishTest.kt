package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.server.session.completed
import net.eratiem.randogeddon.server.session.deletesAt
import net.eratiem.randogeddon.server.session.reopenUntil
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinishTest {
  @Test
  fun `a finished session's open invitations go, unless another running session has them`() {
    val (signedUp, invited, invitedElsewhere) = List(3) { Uuid.random() }
    assertEquals(
      setOf(invited),
      invitationsToCancel(
        part = setOf(signedUp, invited, invitedElsewhere),
        elsewhere = setOf(invitedElsewhere),
        invited = setOf(invited, invitedElsewhere),
      ),
    )
  }

  private val now = Instant.parse("2026-11-13T20:00:00Z")

  @Test
  fun `the countdown starts when every slot is done, and finishes when it runs out`() {
    assertEquals(FinishStep.ARM, finishStep(done = true, dueAt = null, cancelled = false, now))
    assertEquals(FinishStep.NONE, finishStep(done = true, dueAt = now.plusSeconds(60), cancelled = false, now))
    assertEquals(FinishStep.FINISH, finishStep(done = true, dueAt = now, cancelled = false, now))
    assertEquals(FinishStep.FINISH, finishStep(done = true, dueAt = now.minusSeconds(5), cancelled = false, now))
  }

  @Test
  fun `a slot that isn't done any more stops it`() {
    assertEquals(FinishStep.DISARM, finishStep(done = false, dueAt = now.plusSeconds(60), cancelled = false, now))
    assertEquals(FinishStep.NONE, finishStep(done = false, dueAt = null, cancelled = false, now))
  }

  @Test
  fun `after an admin cancelled it, it doesn't start again on its own`() {
    assertEquals(FinishStep.NONE, finishStep(done = true, dueAt = null, cancelled = true, now))
  }

  @Test
  fun `reopening within 14 days, unless it was completed`() {
    assertEquals(Instant.parse("2026-11-27T20:00:00Z"), reopenUntil(now, completed = false))
    assertNull(reopenUntil(now, completed = true))
    assertEquals(true, completed(100.0))
    assertEquals(false, completed(99.9))
    assertEquals(false, completed(null))
  }

  @Test
  fun `a past session is deleted 90 days after it finished, unless kept or turned off`() {
    assertEquals(Instant.parse("2027-02-11T20:00:00Z"), deletesAt(now, keep = false, days = 90))
    assertNull(deletesAt(now, keep = true, days = 90))
    assertNull(deletesAt(now, keep = false, days = 0))
    // Auto-deletion enabled again 10 days later: 90 days from then; a date before the finish doesn't count
    assertEquals(Instant.parse("2027-02-21T20:00:00Z"), deletesAt(now, keep = false, days = 90, since = now.plus(java.time.Duration.ofDays(10))))
    assertEquals(Instant.parse("2027-02-11T20:00:00Z"), deletesAt(now, keep = false, days = 90, since = now.minusSeconds(60)))
  }
}
