package net.eratiem.randogeddon.webapp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.FontChoice
import net.eratiem.randogeddon.shared.FontScope
import net.eratiem.randogeddon.shared.ItemKind
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.ThemeColors
import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.itemKind
import net.eratiem.randogeddon.shared.palettes
import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.app.FONT_CHANGE_TURNS
import net.eratiem.randogeddon.webapp.app.FONT_CHANGE_TURN_MS
import net.eratiem.randogeddon.webapp.app.FontChange
import net.eratiem.randogeddon.webapp.app.Fonts
import net.eratiem.randogeddon.webapp.app.LocalFontChange
import net.eratiem.randogeddon.webapp.app.LocalFonts
import net.eratiem.randogeddon.webapp.app.PageFonts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.hash

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
private const val FONT_KEY = "ap-font"

/** How long after a change by hand a font still counts as its result (loading its files takes a moment). */
private const val FONT_ANIMATION_WINDOW_MS = 10_000L

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

  /** The font choice ([FontChoice]; `null`: the theme's), and where it shows. */
  var font: String? by mutableStateOf(null)
    private set
  var fontScope: FontScope by mutableStateOf(FontScope.AUTO)
    private set

  init {
    // `<choice>:<scope>`, the choice empty for the theme's (an older `true` or `false` for the scope: as the font is made)
    Platform.loadSetting(FONT_KEY)?.split(':')?.let { parts ->
      font = parts.getOrNull(0)?.takeIf { it.isNotEmpty() && FontChoice.valid(it) }
      fontScope = FontScope.of(parts.getOrNull(1))
    }
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

  /** The fonts that show now: the chosen one (or the theme's), once it is loaded. */
  val fonts: PageFonts?
    get() {
      val chosen = FontChoice.font(theme, font) ?: return PageFonts()
      // Still loading: nothing new to show yet (the page keeps what it shows)
      val family = Fonts.of(chosen.theme) ?: return null
      return if (fontScope.headingsOnly(chosen)) PageFonts(heading = family, headingScale = chosen.scale)
      else PageFonts(family, family, chosen.scale, chosen.scale)
    }

  // When someone last changed the theme or the font settings by hand (epoch milliseconds)
  private var changedByHandAt = 0L

  /** Someone picks a theme or changes the font settings now: the font that comes of it is animated in. */
  fun changedByHand() {
    changedByHandAt = Platform.now()
  }

  /**
   * Whether a font that shows up now is animated in: only shortly after a change by hand (its files may still have
   * to load). Not when the page loads or the account's choice arrives after the login: the font is just there then.
   */
  fun animatesFont(): Boolean = Platform.now() - changedByHandAt < FONT_ANIMATION_WINDOW_MS

  /** Applies the font [choice] (`null`: the theme's) and where it shows. */
  fun applyFont(choice: String?, scope: FontScope) {
    font = choice?.takeIf(FontChoice::valid)
    fontScope = scope
    Platform.saveSetting(FONT_KEY, "${font.orEmpty()}:${scope.code}")
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

/** How long a new theme or mode takes to fade in. */
private const val THEME_FADE_MS = 300

@Composable
fun RandogeddonTheme(palette: Palette, content: @Composable () -> Unit) {
  // A font that comes of a change by hand (another theme, the font settings) doesn't jump in: the letters take it one
  // by one, in no order ([FontChange]); the colours fade by themselves meanwhile. When the page loads, or the
  // account's choice arrives, the font is just there. While a font's files load, the page keeps what it shows.
  val wanted = Themes.fonts
  var shown by remember { mutableStateOf(wanted ?: PageFonts()) }
  val fonts = wanted ?: shown
  var change by remember { mutableStateOf<FontChange?>(null) }
  LaunchedEffect(fonts) {
    if (fonts == shown || !Themes.animatesFont()) {
      shown = fonts
      change = null
      return@LaunchedEffect
    }
    val running = FontChange(shown, fonts)
    change = running
    repeat(FONT_CHANGE_TURNS) {
      delay(FONT_CHANGE_TURN_MS)
      running.turn = it + 1
    }
    shown = fonts
    change = null
  }
  CompositionLocalProvider(
    LocalPalette provides palette.fading(),
    LocalFonts provides (if (change != null) fonts else shown),
    LocalFontChange provides change,
    content = content,
  )
}

/** [this], with every colour fading from the one before when the theme or the mode changes. */
@Composable
private fun Palette.fading(): Palette {
  @Composable
  fun Color.fade() = animateColorAsState(this, tween(THEME_FADE_MS)).value
  return copy(
    background = background.fade(), panel = panel.fade(), panelAlt = panelAlt.fade(), border = border.fade(),
    text = text.fade(), muted = muted.fade(), brandStart = brandStart.fade(), brandEnd = brandEnd.fade(),
    onBrand = onBrand.fade(), progression = progression.fade(), useful = useful.fade(), trap = trap.fade(),
    ok = ok.fade(), warn = warn.fade(), key = key.fade(), locked = locked.fade(), barBackground = barBackground.fade(),
    shadow = shadow.fade(), liveDot = liveDot.fade(), offlineDot = offlineDot.fade(),
    // A theme may have more or fewer of them: each by its place
    hues = hues.mapIndexed { i, hue -> key(i) { hue.fade() } },
  )
}
