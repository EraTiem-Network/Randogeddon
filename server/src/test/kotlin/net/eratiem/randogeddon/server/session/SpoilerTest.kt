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

  @Test
  fun `the placements are read, with the seed's slots telling the names apart`() {
    val text = """
      |Locations:
      |
      |Crossroads (Bob-SM64): Mantis Claw (Alice-HK)
      |Chest (1F) (Alice-HK): Power Star (Bob-SM64)
      |Lake (Alice-HK): Geo (Alice-HK)
      |
      |Playthrough:
      |""".trimMargin()
    assertEquals(
      listOf(
        Placement("Crossroads", "Bob-SM64", "Mantis Claw", "Alice-HK"),
        Placement("Chest (1F)", "Alice-HK", "Power Star", "Bob-SM64"),
        Placement("Lake", "Alice-HK", "Geo", "Alice-HK"),
      ),
      placements(text, listOf("Alice-HK", "Bob-SM64")),
    )
    // A spoiler log without placements
    assertEquals(emptyList(), placements("Playthrough:\n", listOf("Alice-HK")))
  }

  @Test
  fun `the playthrough's checked steps are marked`() {
    val spoiler = net.eratiem.randogeddon.shared.Spoiler(
      "1",
      listOf(
        net.eratiem.randogeddon.shared.Sphere(0, listOf("Sword (Alice-HK)")),
        net.eratiem.randogeddon.shared.Sphere(1, listOf("Chest (1F) (Alice-HK): Star (Bob-SM64)", "Crossroads (Bob-SM64): Claw (Alice-HK)", "Far (Eve-Celeste): Key (Bob-SM64)")),
      ),
      "",
    )
    val marked = markChecked(spoiler, mapOf("Alice-HK" to setOf("Lake"), "Bob-SM64" to setOf("Crossroads")))
    assertEquals(listOf(false), marked.spheres[0].checked)
    // Chest (1F) isn't open any more; Crossroads still is; Eve-Celeste isn't known
    assertEquals(listOf(true, false, false), marked.spheres[1].checked)
  }
}
