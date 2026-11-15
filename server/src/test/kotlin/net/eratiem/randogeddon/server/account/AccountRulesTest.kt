package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.PasswordRules
import net.eratiem.randogeddon.shared.UserNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules the web app and the server share (shared's Accounts.kt). */
class AccountRulesTest {
  @Test
  fun `names have 3 to 32 characters, no @ and no spaces around`() {
    assertTrue(UserNames.valid("Bob"))
    assertTrue(UserNames.valid("Dr. Ünicode 42"))
    assertFalse(UserNames.valid("Al"))
    assertFalse(UserNames.valid("x".repeat(33)))
    assertFalse(UserNames.valid("alice@example.org"))
    assertFalse(UserNames.valid(" Alice"))
    // Reserved for hosted slots, in any case
    assertFalse(UserNames.valid("Hosted"))
    assertFalse(UserNames.valid("hosted"))
    assertTrue(UserNames.valid("Hostess"))
    assertFalse(UserNames.valid("Al\nice"))
  }

  @Test
  fun `addresses are checked roughly and their domain lower-cased`() {
    assertTrue(EmailAddresses.valid("alice@example.org"))
    assertFalse(EmailAddresses.valid("alice@localhost"))
    assertFalse(EmailAddresses.valid("alice example.org"))
    assertEquals("Alice.B@example.org", EmailAddresses.normalize("  Alice.B@Example.ORG "))
  }

  @Test
  fun `passwords need 12 characters`() {
    assertNull(PasswordRules.problem("correct horse"))
    assertEquals(Problem.PASSWORD_TOO_SHORT, PasswordRules.problem("short")?.problem)
  }

  @Test
  fun `deletion periods are stored by their code`() {
    assertEquals(listOf("2w", "4w", "3m", "6m", "1y", "never"), AutoDelete.entries.map { it.code })
    assertEquals(AutoDelete.MONTHS_6, AutoDelete.of("6m"))
    assertEquals(AutoDelete.NEVER, AutoDelete.of("something else"))
  }
}
