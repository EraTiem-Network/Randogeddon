package net.eratiem.randogeddon.webapp

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.fontOf

/**
 * The fonts the page draws with: [text] for everything, [heading] for the large lines ([Txt] from 16 sp up, the
 * name on top). `null`: Randogeddon's original (the one the app comes with). A display font (a pixel font) is only
 * the [heading] one, unless the account wants it everywhere.
 */
data class PageFonts(val text: FontFamily? = null, val heading: FontFamily? = null, val textScale: Float = 1f, val headingScale: Float = 1f)

val LocalFonts = staticCompositionLocalOf { PageFonts() }

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
