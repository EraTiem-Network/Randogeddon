package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.database.Block
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.i18n.English
import net.eratiem.randogeddon.shared.i18n.German
import net.eratiem.randogeddon.shared.i18n.text
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class KeptOutTest {
  private val now = Instant.parse("2026-11-17T10:00:00Z")
  private val bob = User(Uuid.random(), "bob", "hash")

  @Test
  fun `someone timed out is told until when and why, someone suspended that they are`() {
    assertNull(keptOutRefusal(bob, now))
    val timedOut = keptOutRefusal(bob.copy(block = Block(now, now.plusSeconds(86400), "Spam in the chat", "admin")), now)!!
    assertEquals(Problem.ACCOUNT_TIMED_OUT, timedOut.problem)
    assertEquals("An Administrator has timed your account out until 2026-11-18 10:00 UTC. Reason: Spam in the chat", English.problems.text(timedOut))
    assertTrue("bis 2026-11-18 10:00 UTC" in German.problems.text(timedOut) && "Grund: Spam in the chat" in German.problems.text(timedOut))
    // Without a reason nothing stands in its place
    val suspended = keptOutRefusal(bob.copy(block = Block(now, null, null, "admin")), now)!!
    assertEquals(Problem.ACCOUNT_SUSPENDED, suspended.problem)
    assertEquals("An Administrator has suspended your account.", English.problems.text(suspended))
    // A time-out that ran out keeps nobody out
    assertNull(keptOutRefusal(bob.copy(block = Block(now, now.plusSeconds(60), null, "admin")), now.plusSeconds(61)))
  }

  @Test
  fun `the mails say until when and the reason, or leave them out`() {
    for (texts in listOf(English.mails, German.mails)) {
      val timedOut = Mails.keptOut("bob@example.org", "bob", "2026-11-18 10:00 UTC", "Spam", texts)
      assertEquals(texts.timedOutSubject, timedOut.subject)
      assertTrue("bob" in timedOut.text && "2026-11-18 10:00 UTC" in timedOut.text && "Spam" in timedOut.text, timedOut.text)
      val suspended = Mails.keptOut("bob@example.org", "bob", null, null, texts)
      assertEquals(texts.suspendedSubject, suspended.subject)
      for (mail in listOf(timedOut, suspended, Mails.letIn("bob@example.org", "bob", texts), Mails.removed("bob@example.org", "bob", null, texts))) {
        // No margin mark or "null" left in the text, no line of blanks
        assertFalse("|" in mail.text || "null" in mail.text, mail.text)
        assertTrue(mail.text.lines().none { it.isNotEmpty() && it.isBlank() }, mail.text)
      }
      assertTrue("Spam" in Mails.removed("bob@example.org", "bob", "Spam", texts).text)
    }
  }
}
