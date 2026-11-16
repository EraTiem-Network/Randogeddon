package net.eratiem.randogeddon.webapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.fontOf
import net.eratiem.randogeddon.shared.letterTurn

/**
 * The fonts the page draws with: [text] for everything, [heading] for the large lines ([Txt] from 16 sp up, the
 * name on top). `null`: Randogeddon's original (the one the app comes with). A display font (a pixel font) is only
 * the [heading] one, unless the account chose otherwise (`FontScope`).
 */
data class PageFonts(val text: FontFamily? = null, val heading: FontFamily? = null, val textScale: Float = 1f, val headingScale: Float = 1f)

val LocalFonts = staticCompositionLocalOf { PageFonts() }

/** In how many turns the letters take a new font, and how long a turn lasts. */
const val FONT_CHANGE_TURNS = 14
const val FONT_CHANGE_TURN_MS = 45L

/**
 * A font change under way: the page's letters go from [from] to [to] one by one, in no order, each at its own turn
 * ([letterTurn]); [turn] counts up to [FONT_CHANGE_TURNS]. Only the texts read it, so only they are drawn again.
 */
class FontChange(val from: PageFonts, val to: PageFonts) {
  var turn by mutableIntStateOf(0)
}

/** The font change that runs now; `null` (nearly always): none, every letter is in [LocalFonts]. */
val LocalFontChange = staticCompositionLocalOf<FontChange?> { null }

/**
 * [this] with every letter in the font it shows at this moment of [change]: the old one, or the new one once its turn
 * has come. For a text drawn at [size], a [heading] or not (a font may be drawn smaller than another at the same size).
 */
fun AnnotatedString.inChange(change: FontChange, heading: Boolean, size: TextUnit): AnnotatedString {
  if (isEmpty()) return this
  val turn = change.turn
  fun style(fonts: PageFonts): SpanStyle {
    val scale = if (heading) fonts.headingScale else fonts.textScale
    return SpanStyle(
      fontFamily = (if (heading) fonts.heading else fonts.text) ?: FontFamily.Default,
      fontSize = if (scale == 1f) TextUnit.Unspecified else size * scale,
    )
  }
  val old = style(change.from)
  val new = style(change.to)
  val seed = text.hashCode()
  val builder = AnnotatedString.Builder(this)
  // Runs of letters in the same font get one style
  var start = 0
  var changed = letterTurn(seed, 0, FONT_CHANGE_TURNS) < turn
  for (i in 1..length) {
    val next = i < length && letterTurn(seed, i, FONT_CHANGE_TURNS) < turn
    if (i == length || next != changed) {
      builder.addStyle(if (changed) new else old, start, i)
      start = i
      changed = next
    }
  }
  return builder.toAnnotatedString()
}

/** From this size up a line of text is a heading. */
const val HEADING_FROM_SP = 16f

/**
 * The themes' fonts ([fontOf]), each fetched from the app's static files when it is first used and kept for as long
 * as the page is open. Until one is there (and if it can't be loaded) the original shows.
 */
object Fonts {
  private val loaded = mutableStateMapOf<Theme, FontFamily>()
  private val asked = mutableSetOf<Theme>()
  private val scope = MainScope()

  /** [theme]'s font if it is there already; never fetches (the theme cards: 25 fonts aren't loaded for a look). */
  fun there(theme: Theme): FontFamily? = loaded[theme]

  /** [theme]'s font; `null` while it loads (the caller is drawn again once it is there) or if it has none. */
  fun of(theme: Theme): FontFamily? {
    loaded[theme]?.let { return it }
    val font = fontOf(theme) ?: return null
    if (asked.add(theme)) {
      scope.launch {
        val regular = Platform.bytes(font.regularFile)
        if (regular == null) {
          // Asked again the next time it is wanted (offline for a moment)
          asked.remove(theme)
          return@launch
        }
        val bold = font.boldFile?.let { Platform.bytes(it) }
        loaded[theme] = FontFamily(
          listOfNotNull(
            Platform.font("${theme.code}-regular", regular, bold = false),
            bold?.let { Platform.font("${theme.code}-bold", it, bold = true) },
          ),
        )
      }
    }
    return null
  }
}
