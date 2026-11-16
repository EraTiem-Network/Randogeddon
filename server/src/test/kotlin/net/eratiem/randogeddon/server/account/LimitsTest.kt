package net.eratiem.randogeddon.server.account

import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.shared.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class LimitsTest {
  private val minute = 60_000L

  @Test
  fun `ten wrong tries hold an account's login up until the oldest is ten minutes old`() {
    val attempts = LoginAttempts()
    val alice = LoginAttempts.password(" Alice ")
    repeat(LoginAttempts.MAX - 1) { attempts.failed(alice, now = it * 1000L) }
    assertFalse(attempts.blocked(alice, now = 10_000))
    attempts.failed(alice, now = 10_000)
    assertTrue(attempts.blocked(alice, now = 11_000))
    // The name as typed, whatever its case; another account isn't touched, nor are its codes
    assertTrue(attempts.blocked(LoginAttempts.password("ALICE"), now = 11_000))
    assertFalse(attempts.blocked(LoginAttempts.password("bob"), now = 11_000))
    assertFalse(attempts.blocked(LoginAttempts.code("alice"), now = 11_000))
    // Still held just before the first try is ten minutes old, free right after
    assertTrue(attempts.blocked(alice, now = 10 * minute - 1))
    assertFalse(attempts.blocked(alice, now = 10 * minute + 1))
  }

  @Test
  fun `an account sends only so many mails in ten minutes, and in a day`() {
    val mails = MailLimits()
    repeat(MailLimits.SOON) { mails.count("alice", now = it * 1000L) }
    val refused = assertFailsWith<AccountProblem> { mails.count("alice", now = 60_000) }
    assertEquals(HttpStatusCode.TooManyRequests, refused.status)
    assertEquals(Problem.TOO_MANY_MAILS, refused.refusal.problem)
    // Someone else still sends; Alice again after ten minutes
    mails.count("bob", now = 60_000)
    mails.count("alice", now = 11 * minute)
    // Over the day: 10 every 11 minutes, until the day's limit
    var sent = MailLimits.SOON + 1
    var now = 22 * minute
    while (sent < MailLimits.DAILY) {
      mails.count("alice", now)
      sent++
      now += 2 * minute
    }
    assertFailsWith<AccountProblem> { mails.count("alice", now + 11 * minute) }
    // The next day she sends again
    mails.count("alice", now + 1.days.inWholeMilliseconds + minute)
  }

  @Test
  fun `counts forget what is older than their window`() {
    val counts = Counts(10.minutes)
    counts.add("k", now = 0)
    counts.add("k", now = 5 * minute)
    assertEquals(2, counts.of("k", now = 9 * minute))
    assertEquals(1, counts.of("k", now = 12 * minute))
    assertEquals(0, counts.of("k", now = 16 * minute))
    assertEquals(0, counts.of("other", now = 0))
  }

  @Test
  fun `a login through the provider lasts a day, a password login a month`() {
    assertEquals(1.days, loginDuration(LoginMethods.OIDC))
    assertEquals(1.days, loginDuration(LoginMethods.LINK))
    assertEquals(30.days, loginDuration(LoginMethods.PASSWORD))
    assertEquals(30.days, loginDuration(LoginMethods.BRIDGE))
  }
}
