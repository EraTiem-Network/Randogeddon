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
    assertTrue(UserNames.valid("Dark_Lord_42"))
    // Letters without accents, digits and _ only
    for (name in listOf("Dr. Ünicode 42", "Ada Lovelace", "ada-l", "Zoë", "a.b.c", "tab\tbed", "名前名前")) assertFalse(UserNames.valid(name), name)
    // Names that would pass for someone with a say here
    for (name in listOf("Admin", "ADMINISTRATOR", "moderator", "Support", "root", "Randogeddon", "system")) assertFalse(UserNames.valid(name), name)
    assertTrue(UserNames.valid("Administrator_Ada"))
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
    assertTrue(EmailAddresses.valid("alice.b+tracker@mail.example.org"))
    // What SMTP or a header would split at, and control characters: such an address never worked
    val unsendable = listOf(
      "a<b@example.org", "alice@example.org>", "a,b@example.org", "a;b@example.org", "\"a\"@example.org",
      "a\u0000b@example.org", "alice@exa\u007Fmple.org", "a\u001Bb@example.org",
    )
    for (address in unsendable) assertFalse(EmailAddresses.valid(address), address)
  }

  @Test
  fun `passwords need 12 characters`() {
    assertNull(PasswordRules.problem("correct horse"))
    assertEquals(Problem.PASSWORD_TOO_SHORT, PasswordRules.problem("short")?.problem)
  }

  /** [password] as Bob (bob.builder@example.org) sets it. */
  private fun bobs(password: String) = PasswordRules.problem(password, "Bobby", "Bob.Builder@example.org")?.problem

  @Test
  fun `without a name or address only the length is checked, the pages' own check`() {
    assertNull(PasswordRules.problem("password1234"))
    assertNull(PasswordRules.problem("aaaaaaaaaaaa"))
    assertEquals(Problem.PASSWORD_TOO_SHORT, PasswordRules.problem("Bobby", "Bobby", null)?.problem)
  }

  @Test
  fun `a new password with the account's name or the address's first part in it is refused, in any case`() {
    for (password in listOf("bobby-likes-trains", "ilove BOBBY so much", "my bob.builder thing", "xxBob.Builderxx")) {
      assertEquals(Problem.PASSWORD_HAS_NAME, bobs(password), password)
    }
    // A part shorter than 4 characters is in too many words to count
    assertNull(PasswordRules.problem("Alfred builds trains", "Al", "al@example.org"))
    // Only what is given: the domain isn't the person's
    assertNull(bobs("example-trains-and-boats"))
  }

  @Test
  fun `a repeat, a run or a common word with digits around it is refused`() {
    val simple = listOf(
      // Repeats: one character, a unit of up to 4, the last time cut short
      "aaaaaaaaaaaa", "abcabcabcabc", "121212121212", "abcdabcdabcdab", "XyXyXyXyXyXy",
      // Runs: digits and letters either way, keyboards' rows (QWERTY, QWERTZ), round past the end
      "123456789012", "987654321098", "abcdefghijkl", "zyxwvutsrqpo", "qwertzuiopas", "qwertyuiopasdf", "poiuytrewqmn",
      "xyzabcdefghi",
      // A common word with digits, symbols, spaces or itself again
      "password1234", "Password1234!", "passwordpassword", "Welcome2026!!", "randogeddon123", "P@ssw0rd2026",
      "123 archipelago 456", "Passw0rd-Passw0rd", "!!sommer2026!!", "L3tm31n123456",
    )
    for (password in simple) assertEquals(Problem.PASSWORD_TOO_SIMPLE, bobs(password), password)
  }

  @Test
  fun `a long random password, a phrase of words, and a common word among other words pass`() {
    val fine = listOf(
      "Kx9#vQ2!mZ7pL4@w", "correct horse battery staple", "my-password-is-very-long-indeed", "sommer regen und tee",
      "password manager forever", "abcabcabcabd", "1234567890ab", "trains2026boats", "qwerty is a keyboard",
    )
    for (password in fine) assertNull(bobs(password), password)
  }

  @Test
  fun `deletion periods are stored by their code`() {
    assertEquals(listOf("2w", "4w", "3m", "6m", "1y", "never"), AutoDelete.entries.map { it.code })
    assertEquals(AutoDelete.MONTHS_6, AutoDelete.of("6m"))
    assertEquals(AutoDelete.NEVER, AutoDelete.of("something else"))
  }
}
