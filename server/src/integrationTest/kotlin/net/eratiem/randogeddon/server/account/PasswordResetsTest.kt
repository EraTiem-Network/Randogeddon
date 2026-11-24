package net.eratiem.randogeddon.server.account

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.server.TrackerHarness
import net.eratiem.randogeddon.server.config.OidcConfig
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.shared.i18n.texts
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A forgotten password on the real stores: the hints, who gets a link, the link once, the logins that end. */
class PasswordResetsTest {
  private lateinit var tracker: TrackerHarness

  @BeforeTest
  fun start() {
    tracker = TrackerHarness()
  }

  @AfterTest
  fun stop() {
    tracker.close()
  }

  /** The second step's work for [account] with [email]: whether a link went out. */
  private suspend fun send(account: String, email: String, now: Instant = Instant.now()) =
    tracker.resets.send(account, email, Language.DEFAULT, now)

  /** The token of the newest mail's link. */
  private fun lastToken(): String =
    Regex("#/reset/([A-Za-z0-9_-]+)").find(tracker.mailer.sent.last().text)!!.groupValues[1]

  @Test
  fun `the first step shows an address partly hidden, a real one or one made up, and never which`() = runBlocking<Unit> {
    tracker.user("Mia", email = "Mia.Smith@Proton.ME")
    assertEquals("m•••@p•••.me", tracker.resets.hint("Mia"))
    assertEquals("m•••@p•••.me", tracker.resets.hint(" mia "))
    // An address is shown as typed, whether an account has it or not
    assertEquals("m•••@p•••.me", tracker.resets.hint("mia.smith@proton.me"))
    assertEquals("n•••@e•••.org", tracker.resets.hint("nobody@example.org"))
    // A name without an account: made up, the same every time, in the same shape
    val madeUp = tracker.resets.hint("Nobody")
    assertEquals(madeUp, tracker.resets.hint("nobody"))
    assertTrue(Regex("[a-z]•••@[a-z]•••\\.[a-z]+").matches(madeUp), madeUp)
    // An account without an address gets one made up too
    tracker.user("Quiet")
    assertTrue(Regex("[a-z]•••@[a-z]•••\\.[a-z]+").matches(tracker.resets.hint("Quiet")))
    for (input in listOf("", "   ", "x".repeat(255))) {
      val refused = assertFailsWith<AccountProblem> { tracker.resets.hint(input) }
      assertEquals(HttpStatusCode.BadRequest, refused.status)
      assertEquals(Problem.NAME_OR_ADDRESS, refused.refusal.problem)
    }
  }

  @Test
  fun `the link goes only to the account's own address, and sets the password once, ending every login`() = runBlocking<Unit> {
    val mia = tracker.user("Mia", email = "mia@example.org")
    tracker.twoFactorStore.setUp(mia.id, "sealed")
    tracker.twoFactorStore.enable(mia.id, emptyList())
    val later = Instant.now().plus(1, ChronoUnit.DAYS)
    tracker.loginStore.create("browser", mia.id, later)
    tracker.loginStore.create("bridge-key", mia.id, later, method = LoginMethods.BRIDGE)
    // A login begun with the old password that waits for its code, and someone else's
    val waiting = tracker.pending.create(mia.id)
    val others = tracker.pending.create(tracker.user("Noa").id)
    // Another address: nothing goes out
    assertFalse(send("Mia", "mia@example.com"))
    assertEquals(emptyList(), tracker.mailer.sent)
    // By name or by address, the address in any case
    assertTrue(send("mia", "MIA@Example.org"))
    val mail = tracker.mailer.sent.single()
    assertEquals("mia@example.org", mail.to)
    assertTrue("${tracker.config.publicUrl}/#/reset/" in mail.text, mail.text)
    val token = lastToken()
    assertEquals("Mia", tracker.resets.view(token).name)
    // A password that doesn't fit leaves the link as it was
    val short = assertFailsWith<AccountProblem> { tracker.resets.reset(token, "short") }
    assertEquals(HttpStatusCode.BadRequest, short.status)
    assertEquals(Problem.PASSWORD_TOO_SHORT, short.refusal.problem)
    // An obvious one too (a common word with digits), and the link still works
    assertEquals(Problem.PASSWORD_TOO_SIMPLE, assertFailsWith<AccountProblem> { tracker.resets.reset(token, "Password2026!") }.refusal.problem)
    tracker.resets.reset(token, "a new password, long enough")

    val after = tracker.users.findActive(mia.id)!!
    assertTrue(PasswordHasher.verify("a new password, long enough", after.passwordHash))
    // Two-factor login stays, every login (the bridge's too) ended
    assertTrue(after.twoFactor)
    assertNull(tracker.loginStore.userOf("browser"))
    assertNull(tracker.loginStore.userOf("bridge-key"))
    assertNull(tracker.pending.userOf(waiting))
    assertNotNull(tracker.pending.userOf(others))
    // The address is told
    assertEquals(listOf("mia@example.org", "mia@example.org"), tracker.mailer.sent.map { it.to })
    assertEquals(texts(Language.DEFAULT).mails.passwordChangedSubject, tracker.mailer.sent.last().subject)
    // Once
    for (attempt in listOf<suspend () -> Unit>({ tracker.resets.view(token) }, { tracker.resets.reset(token, "yet another password") })) {
      val gone = assertFailsWith<AccountProblem> { attempt() }
      assertEquals(HttpStatusCode.NotFound, gone.status)
      assertEquals(Problem.LINK_GONE, gone.refusal.problem)
    }
  }

  @Test
  fun `a link runs out, a new one makes the old one worthless, and an account gets three an hour`() = runBlocking<Unit> {
    tracker.user("Ned", email = "ned@example.org")
    val t0 = Instant.now()
    assertTrue(send("Ned", "ned@example.org", t0))
    val first = lastToken()
    assertTrue(send("Ned", "ned@example.org", t0.plusSeconds(60)))
    val second = lastToken()
    assertNotEquals(first, second)
    assertEquals(Problem.LINK_GONE, assertFailsWith<AccountProblem> { tracker.resets.view(first, t0.plusSeconds(61)) }.refusal.problem)
    assertEquals("Ned", tracker.resets.view(second, t0.plusSeconds(61)).name)
    // 15 minutes
    val ranOut = t0.plusSeconds(60).plus(PASSWORD_RESET_VALID.inWholeMinutes, ChronoUnit.MINUTES)
    assertEquals(Problem.LINK_GONE, assertFailsWith<AccountProblem> { tracker.resets.view(second, ranOut) }.refusal.problem)
    assertEquals(1, tracker.passwordResets.deleteExpired(ranOut.plusSeconds(1)))
    // The third of the hour goes out, the fourth doesn't
    assertTrue(send("ned@example.org", "ned@example.org", t0.plusSeconds(120)))
    assertFalse(send("Ned", "ned@example.org", t0.plusSeconds(180)))
    assertEquals(PasswordResets.MAILS_PER_HOUR, tracker.mailer.sent.size)
  }

  @Test
  fun `no link for an account kept out, an invitation, or without a mail server`() = runBlocking<Unit> {
    val kept = tracker.user("Kept", email = "kept@example.org")
    tracker.users.block(kept.id, null, null, "Root")
    assertFalse(send("Kept", "kept@example.org"))
    // An invitation has its own link
    tracker.users.invite("new@example.org")
    assertFalse(send("new@example.org", "new@example.org"))
    // A link made before the account was kept out doesn't work either
    val ola = tracker.user("Ola", email = "ola@example.org")
    assertTrue(send("Ola", "ola@example.org"))
    val token = lastToken()
    tracker.users.block(ola.id, null, null, "Root")
    assertEquals(Problem.LINK_GONE, assertFailsWith<AccountProblem> { tracker.resets.reset(token, "a new password, long enough") }.refusal.problem)
    assertEquals(1, tracker.mailer.sent.size)

    // Without a mail server the link would go to the log: none is made
    val unset = PasswordResets(
      tracker.users, tracker.passwordResets, tracker.logins, tracker.twoFactor, tracker.mailer,
      tracker.config.copy(mail = tracker.config.mail.copy(host = "")), tracker.pending,
    )
    assertFalse(unset.offered)
    tracker.user("Pia", email = "pia@example.org")
    assertFalse(unset.send("Pia", "pia@example.org", Language.DEFAULT))
    assertEquals(1, tracker.mailer.sent.size)
  }

  @Test
  fun `with single sign-on an Administrator gets no link, a player does`() = runBlocking<Unit> {
    tracker.close()
    tracker = TrackerHarness(oidc = OidcConfig("https://auth.example.org/application/o/randogeddon/", "randogeddon", "secret"))
    tracker.user("Root", administrator = true, email = "root@example.org")
    tracker.user("Tom", email = "tom@example.org")
    assertFalse(send("Root", "root@example.org"))
    assertTrue(send("Tom", "tom@example.org"))
    assertEquals(listOf("tom@example.org"), tracker.mailer.sent.map { it.to })
  }

  @Test
  fun `a new address takes the link to the old one away`() = runBlocking<Unit> {
    val rex = tracker.user("Rex", email = "rex@example.org")
    assertTrue(send("Rex", "rex@example.org"))
    val token = lastToken()
    tracker.users.setEmail(rex.id, "rex@example.com")
    assertEquals(Problem.LINK_GONE, assertFailsWith<AccountProblem> { tracker.resets.view(token) }.refusal.problem)
  }

  @Test
  fun `the second step is counted per network address, whatever account it names`() = runBlocking<Unit> {
    tracker.user("Sam", email = "sam@example.org")
    val t0 = System.currentTimeMillis()
    repeat(PasswordResets.ADDRESS_TRIES) {
      tracker.resets.ask("someone-$it", "someone@example.org", "203.0.113.7", Language.DEFAULT, t0 + it)
    }
    val limited = assertFailsWith<AccountProblem> {
      tracker.resets.ask("Sam", "sam@example.org", "203.0.113.7", Language.DEFAULT, t0 + 100)
    }
    assertEquals(HttpStatusCode.TooManyRequests, limited.status)
    assertEquals(Problem.TOO_MANY_MAILS, limited.refusal.problem)
    // Another network address isn't held up
    tracker.resets.ask("Nobody", "nobody@example.org", "198.51.100.1", Language.DEFAULT, t0 + 100)
  }
}
