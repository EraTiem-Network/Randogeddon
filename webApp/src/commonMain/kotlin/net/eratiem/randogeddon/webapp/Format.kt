package net.eratiem.randogeddon.webapp

/** For a URL's path or query: letters and digits as they are, everything else percent-encoded (UTF-8). */
fun String.percentEncoded(): String = encodeToByteArray().joinToString("") { b ->
  val c = b.toInt() and 0xff
  if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.~")) c.toChar().toString()
  else "%" + c.toString(16).uppercase().padStart(2, '0')
}

/** The API path to download one of session [id]'s slot files. */
fun slotFilePath(id: String, name: String): String = "api/sessions/$id/files/${name.percentEncoded()}"

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
