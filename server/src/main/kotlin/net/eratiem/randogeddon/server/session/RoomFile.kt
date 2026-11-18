package net.eratiem.randogeddon.server.session

import kotlinx.serialization.json.jsonObject
import net.eratiem.randogeddon.server.state.SlotType
import net.eratiem.randogeddon.server.util.array
import net.eratiem.randogeddon.server.util.int
import net.eratiem.randogeddon.server.util.string
import net.eratiem.randogeddon.shared.ApiJson
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** A slot of the room: a player's world, the World Locks slot, or a group. */
data class RoomSlot(val slot: Int, val name: String, val game: String, val type: Int)

/** `room.json`, written by the archipelago container on startup (`archipelago/room_info.py`). */
data class Room(val seedName: String?, val slots: List<RoomSlot>) {
  /** Slots a client can connect as; groups (item links) can't. */
  val players: List<RoomSlot> get() = slots.filter { it.type == SlotType.PLAYER }

  companion object {
    /** `null` while the file doesn't exist (no seed yet) or can't be read. */
    fun read(path: Path): Room? {
      if (!path.exists()) return null
      return runCatching {
        val json = ApiJson.parseToJsonElement(path.readText()).jsonObject
        Room(
          seedName = json.string("seed_name"),
          slots = json.array("slots").orEmpty().map { it.jsonObject }.mapNotNull { slot ->
            RoomSlot(
              slot = slot.int("slot") ?: return@mapNotNull null,
              name = slot.string("name") ?: return@mapNotNull null,
              game = slot.string("game").orEmpty(),
              type = slot.int("type") ?: SlotType.PLAYER,
            )
          },
        )
      }.getOrNull()
    }
  }
}
