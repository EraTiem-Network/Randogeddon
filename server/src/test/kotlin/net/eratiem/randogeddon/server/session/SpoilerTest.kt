package net.eratiem.randogeddon.server.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpoilerTest {
  /** The start of a real spoiler log of the demo (Archipelago 0.6.8), shortened. */
  private val sample = javaClass.getResource("/spoiler-sample.txt")!!.readText()

  @Test
  fun `the playthrough is read sphere by sphere`() {
    val spoiler = parseSpoiler("123", sample)
    assertEquals(listOf(0, 1, 2), spoiler.spheres.map { it.number })
    // Sphere 0 is what everyone starts with: no location
    assertEquals("Air Dash (Alice-Celeste)", spoiler.spheres[0].entries.first())
    assertEquals(4, spoiler.spheres[1].entries.size)
    assertEquals("Lifeblood_Cocoon-King's_Pass (Alice-HK): World Key 3 (Locks)", spoiler.spheres[1].entries[2])
    // A location name with a colon stays whole
    assertEquals("Lake Desolation: Kitty Boss (Bob-Timespinner): Strawberry (Alice-Celeste)", spoiler.spheres[2].entries[3])
    assertEquals(sample, spoiler.text)
  }

  @Test
  fun `the slots are named by their numbers, with their games`() {
    // As Archipelago 0.6.8 writes the options part (shortened)
    val text = "Archipelago Version 0.6.8  -  Seed: 1\n\nPlayers:                         3\n\n" +
      "Player 1: Dave-Timespinner\nGame:                            Timespinner\nStart With Jewelry Box:          Yes\n\n" +
      "Player 2: Erin Super Metroid\nGame:                            Super Metroid\n\n" +
      "Player 3: Locks\nGame:                            World Locks\n\nLocations:\n\n" +
      "Player 9: Nobody (Alice-HK): Mantis Claw (Alice-HK)\n"
    assertEquals(
      mapOf(
        1 to SeedSlot("Dave-Timespinner", "Timespinner"),
        2 to SeedSlot("Erin Super Metroid", "Super Metroid"),
        3 to SeedSlot("Locks", "World Locks"),
      ),
      seedSlots(text),
    )
  }

  @Test
  fun `the section after the playthrough ends it`() {
    val text = "Playthrough:\n\n0: {\n  A (X)\n}\n1: {\n  L (X): I (Y)\n}\n\nPaths:\n\n  I (Y): [...]\n"
    val spoiler = parseSpoiler("1", text)
    assertEquals(listOf(listOf("A (X)"), listOf("L (X): I (Y)")), spoiler.spheres.map { it.entries })
  }

  @Test
  fun `without a playthrough only the text is there`() {
    val spoiler = parseSpoiler("1", "Archipelago Version 0.6.8\n\nLocations:\n\n  L (X): I (Y)\n")
    assertTrue(spoiler.spheres.isEmpty())
    assertTrue(spoiler.text.startsWith("Archipelago"))
  }
}
