package net.eratiem.randogeddon.server.account

/**
 * The words no user name and no session ID may contain: swear words and slurs of 15 languages, from
 * `unwanted-words.txt` (made by `scripts/unwanted_words.py`; its sources and licence are in its head).
 *
 * A name is taken apart at `_`, `-`, digits and where a capital follows a small letter (`Dark_Lord`, `darkLord`), and
 * read once more with digits as the letters they stand in for (`sh1t`). It is refused if a part, or the whole
 * without its separators, is one of the words; or if a word stands inside it: one of 7 letters or more, or one
 * marked `*` (shorter ones are inside too many ordinary words: Scunthorpe). A word marked `=` only counts as a whole
 * part. Nobody is told which word it was.
 */
object UnwantedWords {
  private val whole: Set<String>
  private val inside: List<String>

  init {
    val lines = UnwantedWords::class.java.getResourceAsStream("/unwanted-words.txt")?.bufferedReader()?.readLines().orEmpty()
      .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    whole = lines.mapTo(HashSet()) { it.trimStart('*', '=') }
    inside = lines.filter { it.startsWith("*") || (!it.startsWith("=") && it.length >= INSIDE_FROM) }.map { it.trimStart('*') }
  }

  /** How many words there are (0: the list is missing from the build). */
  val size: Int get() = whole.size

  /** [text] (a name, a session ID) has one of the words. */
  fun hit(text: String): Boolean = reads(text) || reads(text.map { LETTERS[it] ?: it }.joinToString(""))

  private fun reads(text: String): Boolean {
    val joined = text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    if (joined in whole || inside.any { it in joined }) return true
    // Its parts: by capitals (darkLord), and by what isn't a letter alone (DARK_LORD, or capitals that came of digits)
    return PART.findAll(text).any { it.value.lowercase() in whole } || LETTERS_ONLY.findAll(text).any { it.value.lowercase() in whole }
  }

  private const val INSIDE_FROM = 7

  /** A part of a name: a word in small letters (maybe with its capital), or capitals alone. */
  private val PART = Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])")

  private val LETTERS_ONLY = Regex("[A-Za-z]+")

  /** Digits (and signs) written for letters. */
  private val LETTERS = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b')
}
