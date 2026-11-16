package net.eratiem.randogeddon.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontsTest {
  /** The web app's static fonts (`scripts/fonts.py` makes them), from this module's directory. */
  private val files = File("../webApp/src/wasmJsMain/resources")

  @Test
  fun `every theme but the default brings a font, with its files and its licence`() {
    assertNull(fontOf(Theme.DEFAULT))
    for (theme in Theme.entries - Theme.DEFAULT) {
      val font = fontOf(theme) ?: error("$theme has no font")
      assertEquals(theme, font.theme)
      assertTrue(File(files, font.regularFile).length() > 10_000, "$theme: ${font.regularFile}")
      font.boldFile?.let { assertTrue(File(files, it).length() > 10_000, "$theme: $it") }
      // No bold file lying around that nobody loads, and none named that isn't there
      assertEquals(font.bold, File(files, "fonts/${theme.code}-bold.ttf").exists(), "$theme: bold")
      assertTrue(File(files, "fonts/licences/${font.family}.txt").length() > 1_000, "$theme: the licence of ${font.family}")
    }
    // One font per theme: no file without a theme
    val known = Theme.entries.mapNotNull(::fontOf).flatMap { listOfNotNull(it.regularFile, it.boldFile) }.map { it.substringAfter('/') }.toSet()
    assertEquals(known, File(files, "fonts").list { _, name -> name.endsWith(".ttf") }!!.toSet())
  }

  @Test
  fun `the font that shows is the theme's, the original, or another theme's`() {
    assertEquals("Press Start 2P", FontChoice.font(Theme.NES, null)?.family)
    assertNull(FontChoice.font(Theme.RANDOGEDDON, null))
    assertNull(FontChoice.font(Theme.NES, FontChoice.ORIGINAL))
    assertEquals("Nunito", FontChoice.font(Theme.NES, "wii")?.family)
    assertEquals("Nunito", FontChoice.font(Theme.RANDOGEDDON, "wii")?.family)
    // What isn't a choice counts as none: the theme's
    assertEquals("Press Start 2P", FontChoice.font(Theme.NES, "nope")?.family)
    assertEquals("Press Start 2P", FontChoice.font(Theme.NES, "randogeddon")?.family)
  }

  @Test
  fun `only nothing, the original and a theme with a font can be stored`() {
    assertTrue(FontChoice.valid(null) && FontChoice.valid(FontChoice.ORIGINAL) && FontChoice.valid("xbox-series"))
    assertTrue(!FontChoice.valid("randogeddon") && !FontChoice.valid("nope") && !FontChoice.valid(""))
    assertTrue(FontChoice.themes.none { it == Theme.DEFAULT } && FontChoice.themes.size == Theme.entries.size - 1)
    // The pixel fonts and the wide one are for headings
    assertEquals(setOf(Theme.NES, Theme.SNES, Theme.PS2), Theme.entries.filter { fontOf(it)?.display == true }.toSet())
  }

  @Test
  fun `the bridge gets the font that shows, a display font for the headings unless wanted everywhere`() {
    assertNull(bridgeFont(Theme.RANDOGEDDON, null, everywhere = false))
    assertNull(bridgeFont(Theme.NES, FontChoice.ORIGINAL, everywhere = true))
    assertEquals(BridgeFont("fonts/nes-regular.ttf", null, headingsOnly = true, scale = 0.75f), bridgeFont(Theme.NES, null, everywhere = false))
    assertEquals(false, bridgeFont(Theme.NES, null, everywhere = true)?.headingsOnly)
    assertEquals(BridgeFont("fonts/wii-regular.ttf", "fonts/wii-bold.ttf"), bridgeFont(Theme.PS2, "wii", everywhere = false))
  }
}
