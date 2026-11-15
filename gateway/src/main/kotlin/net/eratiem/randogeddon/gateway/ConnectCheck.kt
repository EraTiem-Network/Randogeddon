package net.eratiem.randogeddon.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What happens to a frame a game client sent. */
sealed interface Verdict {
  /** Passes on to MultiServer unchanged. */
  data object Forward : Verdict

  /** A Connect with a wrong password ([REFUSAL]), or of a slot still locked ([REFUSAL_LOCKED]): MultiServer never sees it. */
  data class Refuse(val slot: String?, val locked: Boolean = false) : Verdict

  /** Not the Archipelago protocol (no JSON array): the connection is closed. */
  data class Close(val reason: String) : Verdict
}

/** What Archipelago clients show for a wrong room password; the same field now carries the slot's password. */
const val REFUSAL = """[{"cmd":"ConnectionRefused","errors":["InvalidPassword"]}]"""

/** A slot still locked (its key isn't found yet): clients show the error as it is. */
const val REFUSAL_LOCKED = """[{"cmd":"ConnectionRefused","errors":["SlotLocked"]}]"""

// Strict (not lenient), and like Python's json.loads it keeps the last of duplicate keys, so both read the same packet
private val json = Json

/**
 * Checks one text frame of a client. Every frame is parsed completely (not searched for "Connect"): MultiServer's
 * parser also decodes escapes like `Connect`, so a text search could be got around.
 *
 * Each `Connect` packet must carry the password of its `name` in [passwords] (slot name -> password of the current
 * seed; only asked for when the frame has a Connect). A name without a password is passed on: MultiServer doesn't know
 * that slot either and refuses it with InvalidSlot, which tells the player about a typo. While no passwords are known
 * at all (no seed yet, database unreachable), every Connect is refused. A slot still [locked] is refused even with its
 * password, until its key is found.
 */
suspend fun checkClientFrame(
  text: String,
  passwords: suspend () -> Map<String, String>,
  locked: suspend () -> Set<String> = { emptySet() },
): Verdict {
  val packets = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray
    ?: return Verdict.Close("not a JSON array of packets")
  for (packet in packets) {
    if (packet !is JsonObject || packet.string("cmd") != "Connect") continue
    val slot = packet.string("name")
    val known = passwords()
    if (known.isEmpty()) return Verdict.Refuse(slot)
    val expected = known[slot] ?: continue
    if (packet.string("password") != expected) return Verdict.Refuse(slot)
    if (slot != null && slot in locked()) return Verdict.Refuse(slot, locked = true)
  }
  return Verdict.Forward
}

/** A string field; `null` if missing or not a string (a number as password is never right). */
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
