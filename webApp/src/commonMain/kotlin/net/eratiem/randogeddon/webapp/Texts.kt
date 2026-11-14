package net.eratiem.randogeddon.webapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import net.eratiem.randogeddon.shared.i18n.English
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.shared.i18n.Texts

/** The texts of the language the app is in; [App] provides them, so a change of the language redraws everything. */
val LocalTexts = staticCompositionLocalOf<Texts> { English }

private const val LANGUAGE_KEY = "ap-language"

/**
 * Which language the app is in: the account's choice once someone is logged in ([chosen]), else the one last used in
 * this browser, else the browser's, else English. Whatever applies is remembered in the browser (for the login page
 * of the next visit) and told to index.html.
 */
object Languages {
  /** The browser's language, if it is one Randogeddon speaks. */
  val browser: Language? get() = Language.of(Platform.browserLanguage())

  private val stored: Language? get() = Language.of(Platform.loadSetting(LANGUAGE_KEY))

  var current: Language by mutableStateOf(stored ?: browser ?: Language.DEFAULT)
    private set

  /** Applies [language] (or, for `null`, the browser's: the account follows it). */
  fun apply(language: Language?) {
    val next = language ?: browser ?: Language.DEFAULT
    current = next
    Platform.saveSetting(LANGUAGE_KEY, next.code)
    Platform.setPageLanguage(next.code)
  }
}

/** Date and time of [epochMillis] as this language writes them: `14 Nov 2026, 08:17`, `14.11.2026, 08:17`. */
fun Texts.date(epochMillis: Long): String = Platform.date(epochMillis, language.tag, language.numericDates)

/** `08:17`. */
fun Texts.clock(epochMillis: Long): String = Platform.clock(epochMillis, language.tag)
