package net.eratiem.randogeddon.webapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import net.eratiem.randogeddon.shared.ItemKind
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.ThemeColors
import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.itemKind
import net.eratiem.randogeddon.shared.palettes

/** The page's colours: one palette of a theme (`:shared`'s [ThemeColors], as Compose colours). */
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
  /** Text and icons on the brand gradient and the accent. */
  val onBrand: Color,
  val progression: Color,
  val useful: Color,
  val trap: Color,
  val ok: Color,
  val warn: Color,
  val key: Color,
  val locked: Color,
  val barBackground: Color,
  val shadow: Color,
  /** The top card's state: connected, or not. */
  val liveDot: Color,
  val offlineDot: Color,
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

private fun c(argb: Long) = Color(argb.toInt())

fun ThemeColors.palette() = Palette(
  dark = dark, background = c(background), panel = c(panel), panelAlt = c(panelAlt), border = c(border),
  text = c(text), muted = c(muted), brandStart = c(brandStart), brandEnd = c(brandEnd), onBrand = c(onBrand),
  progression = c(progression), useful = c(useful), trap = c(trap), ok = c(ok), warn = c(warn), key = c(key),
  locked = c(locked), barBackground = c(barBackground), shadow = c(shadow), liveDot = c(liveDot),
  offlineDot = c(offlineDot), hues = hues.map(::c),
)

val LocalPalette = staticCompositionLocalOf { palettes(Theme.DEFAULT).first.palette() }

private const val THEME_KEY = "ap-theme"

/**
 * Which theme and mode the app is in: the account's once someone is logged in ([apply]), else the one last used in
 * this browser (`ap-theme`, `<theme>:<mode>`; the old `light` or `dark` is the default theme in it), else the
 * default theme, automatic. Automatic follows the device's light/dark setting and switches with it. Whatever applies
 * is remembered in the browser, and index.html's login page takes its colours (`ap-theme-css`).
 */
object Themes {
  var theme: Theme by mutableStateOf(Theme.DEFAULT)
    private set
  var mode: ThemeMode by mutableStateOf(ThemeMode.DEFAULT)
    private set
  private var systemDark by mutableStateOf(Platform.systemDarkMode())

  init {
    val stored = Platform.loadSetting(THEME_KEY)
    when (stored) {
      "light" -> mode = ThemeMode.LIGHT
      "dark" -> mode = ThemeMode.DARK
      else -> stored?.split(':')?.let { parts ->
        theme = Theme.of(parts.getOrNull(0)) ?: Theme.DEFAULT
        mode = ThemeMode.of(parts.getOrNull(1)) ?: ThemeMode.DEFAULT
      }
    }
    Platform.onSystemDarkModeChange { systemDark = it; tellPage() }
  }

  /** The palette that shows now. */
  val palette: Palette
    get() {
      val (light, dark) = palettes(theme)
      val isDark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> systemDark
      }
      return (if (isDark) dark else light).palette()
    }

  /** Applies [theme] and [mode] (`null`: the default theme, automatic). */
  fun apply(theme: Theme?, mode: ThemeMode?) {
    this.theme = theme ?: Theme.DEFAULT
    this.mode = mode ?: ThemeMode.DEFAULT
    Platform.saveSetting(THEME_KEY, "${this.theme.code}:${this.mode.code}")
    tellPage()
  }

  /** The page around the app (index.html's login form, the browser's bar colour) in the theme's colours. */
  fun tellPage() {
    val (light, dark) = palettes(theme)
    fun css(p: ThemeColors) = listOf(
      "bg" to p.background, "panel" to p.panel, "border" to p.border, "text" to p.text, "muted" to p.muted,
      "accent" to p.brandEnd, "brand" to p.brandStart, "error" to p.trap, "on-brand" to p.onBrand,
    ).joinToString(",") { (name, v) -> "\"$name\":\"#${(v and 0xFFFFFF).toString(16).padStart(6, '0')}\"" }
    Platform.setPageTheme("{\"mode\":\"${mode.code}\",\"light\":{${css(light)}},\"dark\":{${css(dark)}}}")
  }
}

@Composable
fun RandogeddonTheme(palette: Palette, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalPalette provides palette, content = content)
}
