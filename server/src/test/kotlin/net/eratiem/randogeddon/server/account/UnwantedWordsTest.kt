package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.shared.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnwantedWordsTest {
  @Test
  fun `the list is in the build`() {
    assertTrue(UnwantedWords.size > 1000, "${UnwantedWords.size} words")
  }

  @Test
  fun `a word is found as a part, written together, inside and behind digits`() {
    for (name in listOf(
      "shit", "Shit", "Big_Shit", "bigShit", "shit-show", "sh1t", "SH1T_HEAD", "s_h_i_t",
      "xXfuckerXx", "motherfucker99", "fuckyou", "Arschloch", "arschl0ch", "hurensohn_1", "Hitler", "h1tler_fan",
      "nazi", "Nazi_Hunter", "NIGGER",
    )) assertTrue(UnwantedWords.hit(name), name)
  }

  @Test
  fun `ordinary names and words that only contain a short word pass`() {
    for (name in listOf(
      "Alice", "Bob_42", "Scunthorpe", "Cassandra", "Dickens", "Hancock", "Shitake_Fan", "Therapist", "Marsch",
      "Benazir", "Peter", "Regina", "Anita", "Mama_Bear", "Sega_Fan", "hard-mode", "brave-otter", "friday-chaos",
      "Michelle", "Assassin", "Analyst", "Grape", "Cummings", "Essex", "Matsushita",
    )) assertFalse(UnwantedWords.hit(name), name)
  }

  @Test
  fun `a name's form comes first, then the words`() {
    assertEquals(Problem.NAME_CHARACTERS, nameProblem("f u c k")?.problem)
    assertEquals(Problem.NAME_UNWANTED, nameProblem("fuck_this")?.problem)
    assertEquals(Problem.NAME_RESERVED, nameProblem("Admin")?.problem)
    assertEquals(null, nameProblem("Alice_HK"))
  }
}
