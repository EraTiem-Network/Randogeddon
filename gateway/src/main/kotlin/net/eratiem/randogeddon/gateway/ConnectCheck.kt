package net.eratiem.randogeddon.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What happens to a frame a game client sent. */
sealed interface Verdict {
  /** Passes on to MultiServer unchanged. */
  data object Forward : Verdict

  /** A frame with a Connect that passed its checks: passes on unchanged, and the client is in [slot] from here. */
  data class Join(val slot: String) : Verdict

  /** A Connect that doesn't pass ([why]): answered here, MultiServer never sees it. */
  data class Refuse(val slot: String?, val why: Why = Why.PASSWORD) : Verdict

  /** Why a Connect is refused, and what the client is told (the errors Archipelago clients know, and `SlotLocked`). */
  enum class Why(val answer: String, val logged: String) {
    PASSWORD(REFUSAL, "wrong password"),
    LOCKED(REFUSAL_LOCKED, "still locked"),
    UNKNOWN(REFUSAL_UNKNOWN, "no such slot"),
  }

  /** Not the Archipelago protocol (no JSON array): the connection is closed. */
  data class Close(val reason: String) : Verdict
}

/** What Archipelago clients show for a wrong room password; the same field now carries the slot's password. */
const val REFUSAL = """[{"cmd":"ConnectionRefused","errors":["InvalidPassword"]}]"""

/** A name that is no slot a client connects as: what MultiServer answers a typo with. */
const val REFUSAL_UNKNOWN = """[{"cmd":"ConnectionRefused","errors":["InvalidSlot"]}]"""

/** A slot still locked (its key isn't found yet): clients show the error as it is. */
const val REFUSAL_LOCKED = """[{"cmd":"ConnectionRefused","errors":["SlotLocked"]}]"""

// Strict (not lenient), and like Python's json.loads it keeps the last of duplicate keys, so both read the same packet
private val json = Json

/**
 * Checks one text frame of a client. Every frame is parsed completely (not searched for "Connect"): MultiServer's
 * parser also decodes escapes like `Connect`, so a text search could be got around.
 *
 * Each `Connect` packet must carry the password of its `name` in [passwords] (slot name -> password of the current
 * seed; only asked for when the frame has a Connect). A name without a password is refused here with InvalidSlot (a
 * typo, as MultiServer would answer it): MultiServer knows more names than the slots, an item link's group among them,
 * and takes a client in under those without any password. While no passwords are known at all (no seed yet, database
 * unreachable), every Connect is refused. A slot still [locked] is refused even with its password, until its key is
 * found.
 */
suspend fun checkClientFrame(
  text: String,
  passwords: suspend () -> Map<String, String>,
  locked: suspend () -> Set<String> = { emptySet() },
): Verdict {
  val packets = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray
    ?: return Verdict.Close("not a JSON array of packets")
  var joins: String? = null
  for (packet in packets) {
    if (packet !is JsonObject || packet.string("cmd") != "Connect") continue
    val slot = packet.string("name")
    val known = passwords()
    if (known.isEmpty()) return Verdict.Refuse(slot)
    if (slot == null) return Verdict.Refuse(null, Verdict.Why.UNKNOWN)
    val expected = known[slot] ?: return Verdict.Refuse(slot, Verdict.Why.UNKNOWN)
    if (packet.string("password") != expected) return Verdict.Refuse(slot)
    if (slot in locked()) return Verdict.Refuse(slot, Verdict.Why.LOCKED)
    joins = slot
  }
  return joins?.let(Verdict::Join) ?: Verdict.Forward
}

/**
 * A text frame of MultiServer for a client, its `RoomInfo` saying that a password is needed: MultiServer has none of
 * its own here (`password: false`), but every slot has one, checked above. Some clients send theirs only when the
 * room asks for it and would be refused otherwise. Anything else passes as it is.
 */
fun askingForPassword(text: String): String {
  if ("RoomInfo" !in text) return text
  val packets = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return text
  if (packets.none { it is JsonObject && it.string("cmd") == "RoomInfo" }) return text
  return JsonArray(
    packets.map { if (it is JsonObject && it.string("cmd") == "RoomInfo") JsonObject(it + ("password" to JsonPrimitive(true))) else it },
  ).toString()
}

/** A string field; `null` if missing or not a string (a number as password is never right). */
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
