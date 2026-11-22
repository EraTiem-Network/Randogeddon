package net.eratiem.randogeddon.server.session

import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
    assertEquals(sample.lines().size, spoiler.lines)
  }

  @Test
  fun `a playthrough that couldn't be reduced says so, by the log's last line`() {
    assertEquals(false, parseSpoiler("123", sample).unreduced)
    val unreduced = parseSpoiler("123", sample.trimEnd() + "\n\n" + PLAYTHROUGH_NOT_REDUCED + "\n")
    assertTrue(unreduced.unreduced)
    assertEquals(listOf(0, 1, 2), unreduced.spheres.map { it.number })
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
    // The same from a log as it was read
    assertEquals(seedSlots(text), SpoilerLog("1", text).slots)
  }

  @Test
  fun `the section after the playthrough ends it`() {
    val text = "Playthrough:\n\n0: {\n  A (X)\n}\n1: {\n  L (X): I (Y)\n}\n\nPaths:\n\n  I (Y): [...]\n"
    val spoiler = parseSpoiler("1", text)
    assertEquals(listOf(listOf("A (X)"), listOf("L (X): I (Y)")), spoiler.spheres.map { it.entries })
    // Where the paths start: their part of the log has a tab of its own
    assertEquals(9, spoiler.pathsFrom)
    assertEquals(null, parseSpoiler("1", "Playthrough:\n\n0: {\n  A (X)\n}\n").pathsFrom)
  }

  @Test
  fun `the settings are the seed's, each slot's options, and what worlds wrote besides`() {
    val text = listOf(
      "\uFEFFArchipelago Version 0.6.8  -  Seed: 1", "", "Filling Algorithm:               balanced", "Players:                         2", "",
      "Player 1: Dave-Timespinner", "Game:                            Timespinner", "Death Link:                      No",
      "Excluded Locations:              ", "", "Player 2: Erin-HK", "Game:                            Hollow Knight", "", "",
      "Shop Prices:", "Erin-HK", "", "Sly_1 (Erin-HK): King's_Idol (Erin-HK) costing 3 Geo", "", "Locations:", "", "L (Erin-HK): I (Erin-HK)",
    ).joinToString("\n")
    val settings = SpoilerLog("1", text).settings
    assertEquals(listOf("Filling Algorithm" to "balanced", "Players" to "2"), settings.general.map { it.name to it.value })
    assertEquals(listOf("Dave-Timespinner" to "Timespinner", "Erin-HK" to "Hollow Knight"), settings.slots.map { it.name to it.game })
    // An option without a value is one still (it is padded behind its colon), not a heading
    assertEquals(listOf("Death Link" to "No", "Excluded Locations" to ""), settings.slots[0].options.map { it.name to it.value })
    assertEquals(listOf("Shop Prices"), settings.sections.map { it.title })
    assertEquals(listOf("Erin-HK", "", "Sly_1 (Erin-HK): King's_Idol (Erin-HK) costing 3 Geo"), settings.sections[0].lines)
  }

  @Test
  fun `the paths are each location's regions from the start`() {
    val text = listOf(
      "Player 1: Bob (2)", "Game:   G", "", "Player 2: X", "Game:   G", "",
      "Playthrough:", "", "0: {", "  A (X)", "}", "", "Paths:", "",
      "Castle Basement: Just an egg (Bob (2))", "        Menu -> Tutorial", "   =>   Tutorial -> Start Game",
      "   =>   Lake desolation -> Lake desolation -> Lower lake desolation", "   =>   Lower lake desolation",
      "Start (X)", "        Menu", "", PLAYTHROUGH_NOT_REDUCED,
    ).joinToString("\n")
    val log = SpoilerLog("1", text)
    assertEquals(
      listOf(
        PathEntry("Castle Basement: Just an egg", "Bob (2)", listOf("Menu", "Tutorial", "Lake desolation", "Lower lake desolation")),
        PathEntry("Start", "X", listOf("Menu")),
      ),
      log.paths,
    )
    assertEquals(emptyList(), SpoilerLog("1", "Playthrough:\n\n0: {\n  A (X)\n}\n").paths)
  }

  @Test
  fun `without a playthrough only the text is there`() {
    val spoiler = parseSpoiler("1", "Archipelago Version 0.6.8\n\nLocations:\n\n  L (X): I (Y)\n")
    assertTrue(spoiler.spheres.isEmpty())
    assertEquals(null, spoiler.pathsFrom)
    assertEquals(6, spoiler.lines)
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
    // A log as it was read: the same, read once for the same slots, again for others
    val log = SpoilerLog("1", text)
    val placed = log.placements(listOf("Alice-HK", "Bob-SM64"))
    assertEquals(placements(text, listOf("Alice-HK", "Bob-SM64")), placed)
    assertSame(placed, log.placements(setOf("Alice-HK", "Bob-SM64")))
    assertEquals(listOf(Placement("Lake", "Alice-HK", "Geo", "Alice-HK")), log.placements(listOf("Alice-HK")))
  }

  @Test
  fun `the playthrough's checked steps are marked`() {
    val spoiler = net.eratiem.randogeddon.shared.Spoiler(
      "1",
      listOf(
        net.eratiem.randogeddon.shared.Sphere(0, listOf("Sword (Alice-HK)")),
        net.eratiem.randogeddon.shared.Sphere(1, listOf("Chest (1F) (Alice-HK): Star (Bob-SM64)", "Crossroads (Bob-SM64): Claw (Alice-HK)", "Far (Eve-Celeste): Key (Bob-SM64)")),
      ),
    )
    val marked = markChecked(spoiler, mapOf("Alice-HK" to setOf("Chest (1F)", "Lake"), "Bob-SM64" to emptySet()))
    assertEquals(listOf(false), marked.spheres[0].checked)
    // Chest (1F) is checked; Crossroads isn't; Eve-Celeste isn't known
    assertEquals(listOf(true, false, false), marked.spheres[1].checked)
    // A new session, before the tracker has read its slots: nothing is checked
    assertEquals(listOf(false, false, false), markChecked(spoiler, emptyMap()).spheres[1].checked)
    assertEquals(listOf(false, false, false), markChecked(spoiler, mapOf("Alice-HK" to emptySet())).spheres[1].checked)
  }

  @Test
  fun `the log is read by pages and searched by line`() {
    val log = SpoilerLog("1", (0 until 1000).joinToString("\n") { "line $it" + if (it % 100 == 7) " Master Sword" else "" })
    assertEquals(1000, log.spoiler.lines)
    val page = log.page(300, 50)
    assertEquals(300, page.from)
    assertEquals(1000, page.total)
    assertEquals(50, page.lines.size)
    assertEquals("line 300", page.lines.first())
    // Never more than the most, never past the end
    assertEquals(net.eratiem.randogeddon.shared.SpoilerLines.MAX, log.page(0, 100_000).lines.size)
    assertEquals(listOf("line 999"), log.page(999, 50).lines)
    assertEquals(emptyList(), log.page(5000, 50).lines)
    // Any case; the lines' numbers
    assertEquals(listOf(7, 107, 207, 307, 407, 507, 607, 707, 807, 907), log.search("master sw").lines)
    assertEquals(false, log.search("master sw").more)
    val many = log.search("line")
    assertEquals(net.eratiem.randogeddon.shared.SpoilerFindings.MAX, many.lines.size)
    assertTrue(many.more)
  }

  @Test
  fun `searches of one user are spaced and don't run side by side`() = kotlinx.coroutines.runBlocking {
    val searches = SpoilerSearches()
    val started = System.nanoTime()
    assertEquals(1, searches.paced("u") { 1 })
    assertEquals(2, searches.paced("u") { 2 })
    assertTrue((System.nanoTime() - started) / 1_000_000 >= SpoilerSearches.SPACING_MS - 50)
    // While one waits for its turn, another of the same user is refused; another user's isn't
    val waiting = async { searches.paced("u") { 3 } }
    kotlinx.coroutines.delay(100)
    assertEquals(null, searches.paced("u") { 4 })
    assertEquals(5, searches.paced("other") { 5 })
    assertEquals(3, waiting.await())
  }
}
