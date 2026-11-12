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
