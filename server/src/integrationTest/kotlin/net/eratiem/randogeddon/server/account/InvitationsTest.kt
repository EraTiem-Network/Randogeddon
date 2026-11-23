package net.eratiem.randogeddon.server.account

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.TrackerHarness
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.i18n.Language
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Invitations and new addresses on the real stores: what each try counts towards, an invitation whose mail failed. */
class InvitationsTest {
  private lateinit var tracker: TrackerHarness

  @BeforeTest
  fun start() {
    tracker = TrackerHarness()
  }

  @AfterTest
  fun stop() {
    tracker.close()
  }

  /** What a session's people are told for an address with an account (they don't learn whose it is). */
  private val hasAccount = Refusal(Problem.ADDRESS_HAS_ACCOUNT)

  /**
   * [by]'s invitation of [email] as a session's people invite
   * ([net.eratiem.randogeddon.server.admin.SessionPeopleService.inviteNew]).
   */
  private suspend fun invite(email: String, by: User, suggestedName: String? = null) =
    tracker.accounts.invite(email, suggestedName, by, Language.DEFAULT, existing = hasAccount)

  /** [user] asks for [email] as their address. */
  private suspend fun requestEmail(user: User, email: String) =
    tracker.accounts.requestEmail(user, email, Language.DEFAULT)

  @Test
  fun `a refused invitation counts towards the mails, by the account, not its name`() = runBlocking<Unit> {
    tracker.user("Tess", email = "tess@example.org")
    val ann = tracker.user("Ann")
    repeat(MailLimits.SOON) {
      val refused = assertFailsWith<AccountProblem> { invite("tess@example.org", ann) }
      assertEquals(HttpStatusCode.Conflict, refused.status)
      assertEquals(hasAccount, refused.refusal)
    }
    // Counted by the account, not by its name
    tracker.accounts.rename(ann, "Anna")
    val renamed = tracker.users.findActive(ann.id)!!
    assertEquals("Anna", renamed.name)
    for (address in listOf("tess@example.org", "nobody@example.org")) {
      val limited = assertFailsWith<AccountProblem> { invite(address, renamed) }
      assertEquals(HttpStatusCode.TooManyRequests, limited.status)
      assertEquals(Problem.TOO_MANY_MAILS, limited.refusal.problem)
    }
    // Nothing went out, nobody was invited
    assertNull(tracker.users.accountByEmail("nobody@example.org"))
    assertEquals(emptyList(), tracker.mailer.sent)
    // Another account's mails are its own
    val ben = tracker.user("Ben")
    assertEquals("nobody@example.org", invite("nobody@example.org", ben).email)
    assertEquals(listOf("nobody@example.org"), tracker.mailer.sent.map { it.to })
  }

  @Test
  fun `a refused address change counts too, with the invitations`() = runBlocking<Unit> {
    tracker.user("Tess", email = "tess@example.org")
    val carl = tracker.user("Carl", email = "carl@example.org")
    repeat(MailLimits.SOON) {
      val refused = assertFailsWith<AccountProblem> { requestEmail(carl, "tess@example.org") }
      assertEquals(HttpStatusCode.Conflict, refused.status)
      assertEquals(Problem.ADDRESS_OTHER_ACCOUNT, refused.refusal.problem)
    }
    val limited = assertFailsWith<AccountProblem> { requestEmail(carl, "carl.new@example.org") }
    assertEquals(HttpStatusCode.TooManyRequests, limited.status)
    assertNull(tracker.emailChanges.pending(carl.id))
    assertEquals(emptyList(), tracker.mailer.sent)
    // One count for every kind of mail of the account
    val invitation = assertFailsWith<AccountProblem> { invite("someone@example.org", carl) }
    assertEquals(Problem.TOO_MANY_MAILS, invitation.refusal.problem)
  }

  @Test
  fun `a Session-Manager's invitation whose mail failed leaves no account behind, so the retry works`() = runBlocking<Unit> {
    val ann = tracker.user("Ann")
    tracker.mailer.failing = true
    val failed = assertFailsWith<AccountProblem> { invite("newt@example.org", ann, suggestedName = "Newt") }
    assertEquals(HttpStatusCode.BadGateway, failed.status)
    assertEquals(Problem.INVITATION_FAILED, failed.refusal.problem)
    assertNull(tracker.users.accountByEmail("newt@example.org"))

    tracker.mailer.failing = false
    val invited = invite("newt@example.org", ann, suggestedName = "Newt")
    assertNull(invited.signedUpAt)
    assertEquals(invited.id, tracker.users.accountByEmail("newt@example.org")?.id)
    assertEquals("Newt", tracker.invitations.of(invited.id)?.suggestedName)
    assertEquals(listOf("newt@example.org"), tracker.mailer.sent.map { it.to })
    // Invited now: refused as any address with an account
    assertEquals(hasAccount, assertFailsWith<AccountProblem> { invite("newt@example.org", ann) }.refusal)
  }

  /** [by]'s invitation of [email] in the Administrators' user list. */
  private suspend fun inviteAsAdministrator(email: String, by: User) =
    tracker.accounts.invite(email, null, by, Language.DEFAULT)

  @Test
  fun `an Administrator's invitation whose mail failed stays, to be sent again`() = runBlocking<Unit> {
    val root = tracker.user("Root", administrator = true)
    tracker.mailer.failing = true
    val failed = assertFailsWith<AccountProblem> { inviteAsAdministrator("late@example.org", root) }
    assertEquals(Problem.INVITATION_FAILED, failed.refusal.problem)
    val kept = assertNotNull(tracker.users.accountByEmail("late@example.org"))
    assertNull(kept.signedUpAt)
    // Not a second invitation: the first one is sent again
    val again = assertFailsWith<AccountProblem> { inviteAsAdministrator("late@example.org", root) }
    assertEquals(Problem.ADDRESS_INVITED, again.refusal.problem)

    tracker.mailer.failing = false
    tracker.accounts.resend(kept.id, Language.DEFAULT)
    assertEquals(listOf("late@example.org"), tracker.mailer.sent.map { it.to })
    assertNotNull(tracker.invitations.of(kept.id))
  }
}
