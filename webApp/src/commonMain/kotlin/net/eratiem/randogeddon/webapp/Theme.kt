package net.eratiem.randogeddon.webapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import net.eratiem.randogeddon.shared.ItemKind
import net.eratiem.randogeddon.shared.itemKind

/** The dashboard's colours: calm surfaces, vivid accents (coral → violet brand gradient). */
data class Palette(
  val dark: Boolean,
  val background: Color,
  val panel: Color,
  val panelAlt: Color,
  val border: Color,
  val text: Color,
  val muted: Color,
  val brandStart: Color,
  val brandEnd: Color,
  val progression: Color,
  val useful: Color,
  val trap: Color,
  val ok: Color,
  val warn: Color,
  val key: Color,
  val locked: Color,
  val barBackground: Color,
  val shadow: Color,
  /** Avatar and game chip colours; picked by a stable hash of the name. */
  val hues: List<Color>,
) {
  val brand: Brush get() = Brush.horizontalGradient(listOf(brandStart, brandEnd))
  val accent: Color get() = brandEnd

  fun item(flags: Int): Color = when (itemKind(flags)) {
    ItemKind.PROGRESSION -> progression
    ItemKind.USEFUL -> useful
    ItemKind.TRAP -> trap
    ItemKind.FILLER -> muted
  }

  /** The same colour for the same name, on every load and for every viewer. */
  fun hue(name: String): Color = hues[(name.fold(7) { h, c -> h * 31 + c.code } and Int.MAX_VALUE) % hues.size]
}

val LightPalette = Palette(
  dark = false,
  background = Color(0xFFF5F4FA), panel = Color(0xFFFFFFFF), panelAlt = Color(0xFFF7F6FC), border = Color(0xFFE7E4F0),
  text = Color(0xFF1B1A2B), muted = Color(0xFF6C6A80),
  brandStart = Color(0xFFFF7A45), brandEnd = Color(0xFF7C5CFF),
  progression = Color(0xFF7C5CFF), useful = Color(0xFF2E90FA), trap = Color(0xFFF04438),
  ok = Color(0xFF12B76A), warn = Color(0xFFF79009), key = Color(0xFFDC9A06), locked = Color(0xFF8A8AA3),
  barBackground = Color(0xFFECE9F5), shadow = Color(0x1A2B2260),
  hues = listOf(
    Color(0xFFFF7A45), Color(0xFF7C5CFF), Color(0xFF12B76A), Color(0xFF2E90FA),
    Color(0xFFE0409A), Color(0xFF0BA5EC), Color(0xFFF79009), Color(0xFF6172F3),
  ),
)

/** Near-black surfaces; the accents stay vivid. */
val DarkPalette = Palette(
  dark = true,
  background = Color(0xFF07070C), panel = Color(0xFF101118), panelAlt = Color(0xFF171821), border = Color(0xFF22232F),
  text = Color(0xFFE9E8F2), muted = Color(0xFF8D8BA3),
  brandStart = Color(0xFFFF7A4D), brandEnd = Color(0xFF8E72FF),
  progression = Color(0xFFA48FFF), useful = Color(0xFF53B1FD), trap = Color(0xFFF97066),
  ok = Color(0xFF32D583), warn = Color(0xFFFDB022), key = Color(0xFFFAC515), locked = Color(0xFF8E8CA8),
  barBackground = Color(0xFF22232F), shadow = Color(0x66000000),
  hues = listOf(
    Color(0xFFFF8A5C), Color(0xFF9B83FF), Color(0xFF32D583), Color(0xFF53B1FD),
    Color(0xFFF670C7), Color(0xFF36BFFA), Color(0xFFFDB022), Color(0xFF8098F9),
  ),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun RandogeddonTheme(dark: Boolean, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette, content = content)
}
