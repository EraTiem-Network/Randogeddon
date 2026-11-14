package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A look of the app: a light and a dark [ThemeColors] each ([palettes]). Stored by [code]. */
@Serializable
enum class Theme(val code: String) {
  @SerialName("randogeddon") RANDOGEDDON("randogeddon"),
  ;

  companion object {
    val DEFAULT = RANDOGEDDON

    fun of(code: String?): Theme? = entries.firstOrNull { it.code == code }
  }
}

/** Light, dark, or automatic: the device's light/dark setting, following it when it changes. */
@Serializable
enum class ThemeMode(val code: String) {
  @SerialName("light") LIGHT("light"),
  @SerialName("dark") DARK("dark"),
  @SerialName("auto") AUTO("auto"),
  ;

  /** The next one for the top card's button: light, dark, automatic, light, … */
  val next: ThemeMode get() = entries[(ordinal + 1) % entries.size]

  companion object {
    val DEFAULT = AUTO

    fun of(code: String?): ThemeMode? = entries.firstOrNull { it.code == code }
  }
}

/**
 * One palette, as ARGB numbers (the web app makes Compose colours of them; the JVM tests check their contrast).
 * [onBrand]: text and icons on the brand gradient and the accent; [liveDot]/[offlineDot]: the top card's state.
 */
data class ThemeColors(
  val dark: Boolean,
  val background: Long,
  val panel: Long,
  val panelAlt: Long,
  val border: Long,
  val text: Long,
  val muted: Long,
  val brandStart: Long,
  val brandEnd: Long,
  val onBrand: Long,
  val progression: Long,
  val useful: Long,
  val trap: Long,
  val ok: Long,
  val warn: Long,
  val key: Long,
  val locked: Long,
  val barBackground: Long,
  val shadow: Long,
  val liveDot: Long,
  val offlineDot: Long,
  /** Avatar and game chip colours, picked by a stable hash of the name. */
  val hues: List<Long>,
)

/** The light and the dark palette of [theme]. */
fun palettes(theme: Theme): Pair<ThemeColors, ThemeColors> = when (theme) {
  Theme.RANDOGEDDON -> RANDOGEDDON_LIGHT to RANDOGEDDON_DARK
}

/** Calm surfaces, vivid accents: the coral → violet brand gradient. */
private val RANDOGEDDON_LIGHT = ThemeColors(
  dark = false,
  background = 0xFFF5F4FA, panel = 0xFFFFFFFF, panelAlt = 0xFFF7F6FC, border = 0xFFE7E4F0,
  text = 0xFF1B1A2B, muted = 0xFF6C6A80,
  brandStart = 0xFFFF7A45, brandEnd = 0xFF7C5CFF, onBrand = 0xFFFFFFFF,
  progression = 0xFF7C5CFF, useful = 0xFF2E90FA, trap = 0xFFF04438,
  ok = 0xFF12B76A, warn = 0xFFF79009, key = 0xFFDC9A06, locked = 0xFF8A8AA3,
  barBackground = 0xFFECE9F5, shadow = 0x1A2B2260, liveDot = 0xFF6CF0A9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFFF7A45, 0xFF7C5CFF, 0xFF12B76A, 0xFF2E90FA, 0xFFE0409A, 0xFF0BA5EC, 0xFFF79009, 0xFF6172F3),
)

/** Near-black surfaces; the accents stay vivid. */
private val RANDOGEDDON_DARK = ThemeColors(
  dark = true,
  background = 0xFF07070C, panel = 0xFF101118, panelAlt = 0xFF171821, border = 0xFF22232F,
  text = 0xFFE9E8F2, muted = 0xFF8D8BA3,
  brandStart = 0xFFFF7A4D, brandEnd = 0xFF8E72FF, onBrand = 0xFFFFFFFF,
  progression = 0xFFA48FFF, useful = 0xFF53B1FD, trap = 0xFFF97066,
  ok = 0xFF32D583, warn = 0xFFFDB022, key = 0xFFFAC515, locked = 0xFF8E8CA8,
  barBackground = 0xFF22232F, shadow = 0x66000000, liveDot = 0xFF6CF0A9, offlineDot = 0xFFFFB4A8,
  hues = listOf(0xFFFF8A5C, 0xFF9B83FF, 0xFF32D583, 0xFF53B1FD, 0xFFF670C7, 0xFF36BFFA, 0xFFFDB022, 0xFF8098F9),
)
