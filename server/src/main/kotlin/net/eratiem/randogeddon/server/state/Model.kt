package net.eratiem.randogeddon.server.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.eratiem.randogeddon.shared.ConnectionSession
import net.eratiem.randogeddon.server.util.int
import net.eratiem.randogeddon.server.util.long

/** `NetUtils.ClientStatus` */
object ClientStatus {
  const val UNKNOWN = 0
  const val READY = 10
  const val GOAL = 30
}

/** `NetUtils.SlotType` */
object SlotType {
  const val PLAYER = 1
  const val GROUP = 2
}

/** The game of the World Locks apworld (apworlds/world_locks); its slot holds the keys of the locked worlds. */
const val WORLD_LOCKS_GAME = "World Locks"

const val MAX_SESSIONS = 200

/** A session cut by a tracker restart continues if the world is seen again within this time. */
const val SESSION_MERGE_GAP_MS = 5 * 60_000L

/** The location of an item in the starting inventory, as MultiServer sends it. */
const val STARTING_INVENTORY = -2L

/** One `NetworkItem`: the item, the location it was found at, the player it came from (or went to) and its flags. */
data class NetworkItem(val item: Long, val location: Long, val player: Int, val flags: Int)

class Player(val slot: Int, val name: String, val game: String) {
  var alias: String = name
  var checked: MutableSet<Long> = mutableSetOf()
  var missing: MutableSet<Long> = mutableSetOf()

  /** Received items; [NetworkItem.player] is the sender. */
  var items: MutableList<NetworkItem> = mutableListOf()
  var status: Int = ClientStatus.UNKNOWN

  /** Game clients connected to this slot, counted from Join/Part since the tracker connected. */
  var online: Int = 0
  /** When it was released ([Release] message); with [goalAt] it tells the automatic release at the goal. */
  var releasedAt: Long? = null
  /** Its newest check: the newest `ItemSend` it found. */
  var lastCheckAt: Long? = null
  var lastActivity: Long? = null
  var hintPoints: Int? = null
  var sessions: MutableList<ConnectionSession> = mutableListOf()
  var firstCheckAt: Long? = null
  var goalAt: Long? = null
  /** Its checks available now, as the session's logic last counted them; `null`: not known. */
  var available: Int? = null
  /** Its received items and checks when the logic was last asked about it (asked again once they change). */
  var availableAsked: Pair<Int, Int>? = null

  val openSession: ConnectionSession? get() = sessions.lastOrNull()?.takeIf { it.end == null }

  /** Every location checked (by playing, or by a release). */
  val complete: Boolean get() = total > 0 && missing.isEmpty()

  /** Finished sessions only; the open one is added by whoever knows the current time. */
  val playTimeMs: Long get() = sessions.sumOf { s -> s.end?.let { it - s.start } ?: 0 }

  fun goOnline(now: Long, implicit: Boolean) {
    if (openSession != null) return
    val last = sessions.lastOrNull()
    val lastEnd = last?.end
    if (last != null && last.cut && lastEnd != null && now - lastEnd <= SESSION_MERGE_GAP_MS) {
      // Only the tracker was gone: the same session goes on
      sessions[sessions.lastIndex] = last.copy(end = null, cut = false)
      return
    }
    sessions += ConnectionSession(now, implicit = implicit)
    while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
  }

  /** [cut]: the tracker stops seeing the world (restart, lost server), the client itself may still be there. */
  fun goOffline(now: Long, cut: Boolean = false) {
    openSession?.let { sessions[sessions.lastIndex] = it.copy(end = now, cut = cut) }
  }

  val total: Int get() = (checked + missing).size
}

/** `NetworkItem` arrives as `{"item", "location", "player", "flags", "class"}` or as a list. */
internal fun networkItem(raw: JsonElement): NetworkItem = when (raw) {
  is JsonObject -> NetworkItem(raw.long("item")!!, raw.long("location")!!, raw.int("player")!!, raw.int("flags") ?: 0)
  else -> raw.jsonArray.let {
    NetworkItem(it[0].jsonPrimitive.long, it[1].jsonPrimitive.long, it[2].jsonPrimitive.int, it.getOrNull(3)?.jsonPrimitive?.int ?: 0)
  }
}
