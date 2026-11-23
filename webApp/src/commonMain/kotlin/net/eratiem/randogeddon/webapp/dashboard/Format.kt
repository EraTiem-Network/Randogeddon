package net.eratiem.randogeddon.webapp.dashboard

import kotlin.math.round
import net.eratiem.randogeddon.webapp.api.pathSegment

/**
 * For a URL's path or query: letters, digits, `-`, `_` and `~` as they are, everything else percent-encoded (UTF-8),
 * dots too ([pathSegment]). A name that is only `.` or `..` is resolved by the browser even so ([dotSegment]).
 */
fun String.percentEncoded(): String = pathSegment()

/** [name] is `.` or `..`: as a path's segment the browser resolves it (encoded too), so it can't be sent as one. */
fun dotSegment(name: String): Boolean = name == "." || name == ".."

/**
 * [checked] of [total] in percent, to a tenth as the tracker gives it: 100 only once every one is checked (at most
 * 99.9 before, where rounding would make it 100).
 */
fun percentOf(checked: Int, total: Int): Double = when {
  total <= 0 -> 0.0
  checked >= total -> 100.0
  else -> (round(1000.0 * checked / total) / 10).coerceAtMost(99.9)
}

/** Every one of [total] is checked: a session, a slot or a person is complete (decided by the counts, not a percentage). */
fun allChecked(checked: Int, total: Int): Boolean = total > 0 && checked >= total

/** The API path to download one of session [id]'s slot files; a name that is a [dotSegment] can't be asked for so. */
fun slotFilePath(id: String, name: String): String = "api/sessions/${id.pathSegment()}/files/${name.percentEncoded()}"

/**
 * A slot name without its person's name in front, where the person is clear anyway: "Alice-HK" of Alice is "HK".
 * Only with a separator after the name (`-`, `_`, space, `.`); otherwise, and if nothing would be left, the full name.
 */
fun shortSlotName(slot: String, person: String?): String {
  if (person.isNullOrEmpty() || !slot.startsWith(person, ignoreCase = true)) return slot
  val rest = slot.substring(person.length)
  if (rest.firstOrNull() !in setOf('-', '_', ' ', '.')) return slot
  return rest.drop(1).trimStart('-', '_', ' ', '.').ifEmpty { slot }
}
