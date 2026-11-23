package net.eratiem.randogeddon.server.util

/**
 * Characters that change the direction of the text after them: the Arabic letter mark, the left-to-right and
 * right-to-left marks, the embeddings and overrides (U+202A to U+202E) and the isolates (U+2066 to U+2069).
 */
private fun Char.directionControl(): Boolean =
  this == '؜' || this == '‎' || this == '‏' || this in '‪'..'‮' || this in '⁦'..'⁩'

/** A line break (or tab, or another separator of lines): a space in [playerText]. */
private fun Char.lineBreak(): Boolean = this in "\n\r\t\u000B\u000C\u0085  "

/** Whether [text] has a character [playerText] takes out or turns into a space. */
fun unsafeText(text: String): Boolean = text.any { it.isISOControl() || it.directionControl() || it.lineBreak() }

/**
 * A text a player wrote (a chat line, an alias, what their client says of a death or a trap) as the feed shows it:
 * one line (line breaks become a space), without control or direction-changing characters, so it can't look like
 * another line or like another slot's words; trimmed and cut to [max] characters. `null`: nothing is left.
 */
fun playerText(text: String, max: Int): String? {
  val line = StringBuilder(minOf(text.length, max + 1))
  for (c in text) {
    if (line.length > max) break
    when {
      c.lineBreak() -> if (line.isNotEmpty() && line.last() != ' ') line.append(' ')
      c.isISOControl() || c.directionControl() -> Unit
      // Nothing in front: the cut counts from the first character shown
      c.isWhitespace() && line.isEmpty() -> Unit
      else -> line.append(c)
    }
  }
  var cut = line.toString().trimEnd().take(max)
  // Not half of a character outside the BMP (an emoji)
  if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
  return cut.trimEnd().ifEmpty { null }
}
