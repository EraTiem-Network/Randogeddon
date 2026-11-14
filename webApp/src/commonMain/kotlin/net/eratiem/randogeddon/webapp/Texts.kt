package net.eratiem.randogeddon.webapp

import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ApiError
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
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

/** A refusal of the server as this language words it (by its code), else as the server worded it. */
fun Texts.word(error: ApiError): String = error.code?.let { problems.text(it, error.args) } ?: error.message

/** The refusal in an answer's [body], worded; `null` if it isn't one. */
fun Texts.wordError(body: String): String? =
  runCatching { ApiJson.decodeFromString(ApiError.serializer(), body) }.getOrNull()?.let { word(it) }

/** `08:17`. */
fun Texts.clock(epochMillis: Long): String = Platform.clock(epochMillis, language.tag)

private val MARK = Regex("""\{(\w+)\}""")

/**
 * Appends [template] (a text with `{name}` marks, e.g. `{sender} sent {item} to {receiver}`): each mark as its text
 * from [parts] in its style, the words between them in [rest] (or as they are).
 */
fun AnnotatedString.Builder.appendTemplate(template: String, parts: Map<String, Pair<String, SpanStyle?>>, rest: SpanStyle? = null) {
  var at = 0
  fun words(until: Int) {
    if (until <= at) return
    val text = template.substring(at, until)
    if (rest != null) withStyle(rest) { append(text) } else append(text)
  }
  MARK.findAll(template).forEach { mark ->
    words(mark.range.first)
    val (text, style) = parts[mark.groupValues[1]] ?: ("" to null)
    if (style != null) withStyle(style) { append(text) } else append(text)
    at = mark.range.last + 1
  }
  words(template.length)
}

/** [template] with its marks filled in, without styles. */
fun fill(template: String, vararg parts: Pair<String, String>): String {
  val values = parts.toMap()
  return MARK.replace(template) { values[it.groupValues[1]].orEmpty() }
}
