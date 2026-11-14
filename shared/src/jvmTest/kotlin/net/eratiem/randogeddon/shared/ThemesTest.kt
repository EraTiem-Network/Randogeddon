package net.eratiem.randogeddon.shared

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every theme stays readable: at least as the default one (which meets WCAG for text, and keeps the coral and amber
 * accents a little below it where they are dots and pills).
 */
class ThemesTest {
  private fun luminance(argb: Long): Double {
    fun channel(shift: Int): Double {
      val v = ((argb shr shift) and 0xFF) / 255.0
      return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
  }

  private fun contrast(a: Long, b: Long): Double {
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
  }

  private fun distance(a: Long, b: Long): Double {
    fun c(v: Long, shift: Int) = ((v shr shift) and 0xFF).toDouble()
    return sqrt((0..2).sumOf { (c(a, it * 8) - c(b, it * 8)).pow(2) })
  }

  private val all = Theme.entries.flatMap { theme ->
    palettes(theme).let { (light, dark) -> listOf("$theme light" to light, "$theme dark" to dark) }
  }

  @Test
  fun `every theme has a light and a dark palette`() {
    for (theme in Theme.entries) {
      val (light, dark) = palettes(theme)
      assertEquals(false to true, light.dark to dark.dark, "$theme")
      assertTrue(light.hues.size >= 6 && dark.hues.size >= 6, "$theme: at least 6 hues")
    }
  }

  @Test
  fun `text, muted text and the text on the brand are readable`() {
    for ((name, p) in all) {
      for ((what, surface) in listOf("background" to p.background, "panel" to p.panel)) {
        assertTrue(contrast(p.text, surface) >= 7.0, "$name: text on $what ${contrast(p.text, surface)}")
        assertTrue(contrast(p.muted, surface) >= 4.5, "$name: muted on $what ${contrast(p.muted, surface)}")
      }
      assertTrue(contrast(p.onBrand, p.brandEnd) >= 3.0, "$name: on the accent ${contrast(p.onBrand, p.brandEnd)}")
      // The top card's text spans the whole gradient (the default's coral start: 2.6)
      assertTrue(contrast(p.onBrand, p.brandStart) >= 2.5, "$name: on the gradient's start ${contrast(p.onBrand, p.brandStart)}")
    }
  }

  @Test
  fun `the item and status colours show on the panel and stay apart`() {
    for ((name, p) in all) {
      val items = mapOf("progression" to p.progression, "useful" to p.useful, "trap" to p.trap)
      items.forEach { (what, c) -> assertTrue(contrast(c, p.panel) >= 3.0, "$name: $what ${contrast(c, p.panel)}") }
      val states = mapOf("ok" to p.ok, "warn" to p.warn, "key" to p.key, "locked" to p.locked)
      states.forEach { (what, c) -> assertTrue(contrast(c, p.panel) >= 2.2, "$name: $what ${contrast(c, p.panel)}") }
      // Told apart at a glance: no two of them nearly the same colour (the default's closest: warn and key, 25 in the dark)
      val colours = (items + states).entries.toList()
      for (i in colours.indices) for (j in i + 1 until colours.size) {
        val d = distance(colours[i].value, colours[j].value)
        assertTrue(d >= 20.0, "$name: ${colours[i].key} and ${colours[j].key} too close ($d)")
      }
    }
  }

  @Test
  fun `themes and modes are found by their codes`() {
    assertEquals(Theme.RANDOGEDDON, Theme.of("randogeddon"))
    assertEquals(null, Theme.of("nope"))
    assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.next)
    assertEquals(ThemeMode.AUTO, ThemeMode.DARK.next)
    assertEquals(ThemeMode.LIGHT, ThemeMode.AUTO.next)
  }
}
