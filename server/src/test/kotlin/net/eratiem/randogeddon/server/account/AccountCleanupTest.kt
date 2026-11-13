package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.database.Account
import net.eratiem.randogeddon.database.AccountActivity
import java.time.Instant
import java.time.temporal.ChronoUnit.DAYS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AccountCleanupTest {
  private val signedUp = Instant.parse("2026-01-01T12:00:00Z")

  private fun activity(
    period: String,
    lastEnd: Instant? = null,
    playing: Boolean = false,
    warnedAt: Instant? = null,
    email: String? = "alice@example.org",
  ) = AccountActivity(Account(Uuid.random(), "Alice", email, false, signedUp, signedUp, period), lastEnd, playing, warnedAt)

  @Test
  fun `the period counts from the last session's end, or from the sign-up without one`() {
    assertEquals(signedUp.plus(91, DAYS), deletesAt(activity("3m"), signedUp))
    val end = signedUp.plus(40, DAYS)
    assertEquals(end.plus(365, DAYS), deletesAt(activity("1y", lastEnd = end), end))
  }

  @Test
  fun `never, and a session that runs, mean no deletion`() {
    assertNull(deletesAt(activity("never")))
    assertNull(deletesAt(activity("2w", lastEnd = signedUp, playing = true)))
  }

  @Test
  fun `warned 14 days before, deleted when due, and never right after the warning`() {
    val a = activity("4w")
    val due = signedUp.plus(28, DAYS)
    assertEquals(CleanupStep.NOTHING, cleanupStep(a, due.minus(15, DAYS)))
    assertEquals(CleanupStep.WARN, cleanupStep(a, due.minus(13, DAYS)))
    // Warned only now, at the due date: two more days
    val late = a.copy(warnedAt = due)
    assertEquals(CleanupStep.NOTHING, cleanupStep(late, due.plus(1, DAYS)))
    assertEquals(CleanupStep.DELETE, cleanupStep(late, due.plus(2, DAYS)))
  }

  @Test
  fun `the 2-week period is warned right away`() {
    assertEquals(CleanupStep.WARN, cleanupStep(activity("2w"), signedUp.plus(1, DAYS)))
  }

  @Test
  fun `a warning is taken back when the deletion moved later`() {
    // A new session ended since the warning: the deletion is a year away again
    val a = activity("1y", lastEnd = signedUp.plus(300, DAYS), warnedAt = signedUp.plus(360, DAYS))
    assertEquals(CleanupStep.UNWARN, cleanupStep(a, signedUp.plus(361, DAYS)))
    assertEquals(CleanupStep.UNWARN, cleanupStep(activity("1y", playing = true, warnedAt = signedUp), signedUp.plus(1, DAYS)))
  }

  @Test
  fun `without an address there is no warning, only the deletion`() {
    val a = activity("4w", email = null)
    val due = signedUp.plus(28, DAYS)
    assertEquals(CleanupStep.NOTHING, cleanupStep(a, due.minus(1, DAYS)))
    assertEquals(CleanupStep.DELETE, cleanupStep(a, due))
  }
}
