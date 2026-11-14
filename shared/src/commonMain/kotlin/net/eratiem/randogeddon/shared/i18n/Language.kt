package net.eratiem.randogeddon.shared.i18n

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A language Randogeddon speaks: its [code] (stored with the account, in the API and the browser), its [tag] for
 * formatting dates, whether its dates name the month in digits ([numericDates]: British English `14 Nov 2026, 08:17`,
 * German `14.11.2026, 08:17`) and its name in itself ([label], for the choice).
 */
@Serializable
enum class Language(val code: String, val tag: String, val numericDates: Boolean, val label: String) {
  @SerialName("en") EN("en", "en-GB", false, "English"),
  @SerialName("de") DE("de", "de-DE", true, "Deutsch"),
  ;

  companion object {
    /** The default, and what a text no other language has yet falls back to. */
    val DEFAULT = EN

    /** By its [code] (or a browser's tag like `de-AT`: its first part); `null` for none or one we don't speak. */
    fun of(code: String?): Language? {
      val main = code?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
      return entries.firstOrNull { it.code == main }
    }
  }
}

/** The texts of [language]. */
fun texts(language: Language): Texts = when (language) {
  Language.EN -> English
  Language.DE -> German
}
