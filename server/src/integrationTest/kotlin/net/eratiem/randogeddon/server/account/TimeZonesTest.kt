package net.eratiem.randogeddon.server.account

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.server.TrackerHarness
import net.eratiem.randogeddon.shared.AccountActionRequest
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.TimeOut
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An account's time zone on the real stores: kept as the browser gives it, and the mails' times in it. */
class TimeZonesTest {
  private lateinit var tracker: TrackerHarness

  @BeforeTest
  fun start() {
    tracker = TrackerHarness()
  }

  @AfterTest
  fun stop() {
    tracker.close()
  }

  @Test
  fun `an account's time zone is kept, a name that is none is refused`() = runBlocking<Unit> {
    val ann = tracker.user("Ann")
    for (wrong in listOf("Mars/Olympus_Mons", "", "Europe/" + "x".repeat(70), "../../etc/passwd")) {
      val refused = assertFailsWith<AccountProblem> { tracker.accounts.setTimeZone(ann, wrong) }
      assertEquals(HttpStatusCode.BadRequest, refused.status)
      assertEquals(Problem.NO_SUCH_TIME_ZONE, refused.refusal.problem)
    }
    assertNull(tracker.users.findActive(ann.id)?.timeZone)
    tracker.accounts.setTimeZone(ann, "Europe/Berlin")
    assertEquals("Europe/Berlin", tracker.users.findActive(ann.id)?.timeZone)
    assertEquals("Europe/Berlin", tracker.users.account(ann.id)?.timeZone)
  }

  @Test
  fun `a time-out's mail gives its end in the account's time zone, without one in UTC`() = runBlocking<Unit> {
    val ada = tracker.user("Ada", administrator = true)
    val ann = tracker.user("Ann", email = "ann@example.org")
    val ben = tracker.user("Ben", email = "ben@example.org")
    tracker.accounts.setTimeZone(ann, "Asia/Kolkata")
    tracker.accounts.keepOut(ann.id, AccountActionRequest(TimeOut.HOUR), ada)
    tracker.accounts.keepOut(ben.id, AccountActionRequest(TimeOut.HOUR), ada)
    assertTrue(Regex("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d Asia/Kolkata").containsMatchIn(tracker.mailer.sent.single { it.to == "ann@example.org" }.text))
    assertTrue(Regex("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d UTC").containsMatchIn(tracker.mailer.sent.single { it.to == "ben@example.org" }.text))
    // And the login form says the same to Ann
    val refusal = tracker.logins.keptOutProblem(tracker.users.findActive(ann.id)!!)!!
    assertTrue(refusal.args.getValue("until").endsWith("Asia/Kolkata"))
  }
}
