package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What an admin can cheat. */
@Serializable
enum class CheatAction {
  /** Gives the slot an item (`/send`). */
  @SerialName("item") ITEM,
  /** Checks a location of the slot (`/send_location`): its item goes to whoever it belongs to. */
  @SerialName("location") LOCATION,
  /** Sends the slot's remaining items to their owners (`/release`). */
  @SerialName("release") RELEASE,
  /** Gives the slot its items that are still in other slots (`/collect`). */
  @SerialName("collect") COLLECT,
  /** A free hint for one of the slot's items (`/hint`), if the session allows cheat hints. */
  @SerialName("hint") HINT,
  /** A free hint for what is at one of the slot's locations (`/hint_location`), likewise. */
  @SerialName("hint_location") HINT_LOCATION,
}

/**
 * `POST /api/admin/cheat`; [value]: the item or location, for the actions that need one. [from]: a location checked
 * *to* the slot: the other world it is in (one of [CheatNames.incoming]); `null`: the slot's own location.
 */
@Serializable
data class CheatRequest(
  val action: CheatAction,
  val slot: String,
  val value: String? = null,
  val from: String? = null,
  /** An item hint: which of its copies it names. */
  val pick: HintPick = HintPick.RANDOM,
)

/** Which copy of an item a hint names: one by chance, or the one the playthrough needs first. */
@Serializable
enum class HintPick {
  @SerialName("random") RANDOM,
  /** The copy in the earliest sphere of the playthrough; a copy the playthrough doesn't need comes after those. */
  @SerialName("next") NEXT,
}

/** One cheat, as logged. */
@Serializable
data class CheatLogEntry(val ts: Long, val admin: String, val action: CheatAction, val slot: String, val value: String? = null)

/**
 * `GET /api/admin/cheat`: the slots to cheat for (those with locations left to check), whether hints are allowed, and
 * the session's cheats so far.
 */
@Serializable
data class CheatView(
  val slots: List<String> = emptyList(),
  /** Every slot has checked all its locations (100 %): nothing is left to cheat. */
  val done: Boolean = false,
  val cheatHints: Boolean = false,
  /** The game server runs and the session isn't finished. */
  val available: Boolean = false,
  val log: List<CheatLogEntry> = emptyList(),
)

/**
 * `GET /api/admin/cheat/names?slot=`: the items of the slot's game, and its locations not checked yet. [incoming]:
 * the locations of other worlds, not checked yet, that hold one of the slot's items (from the spoiler log's
 * placements; [placementsKnown] `false` without them). For hints: [hintLocations], its open locations not hinted yet;
 * [hintItems], with the placements known, the items of which a copy for the slot is neither checked nor hinted yet
 * (a hint names one of them).
 */
@Serializable
data class CheatNames(
  val items: List<String> = emptyList(),
  val locations: List<String> = emptyList(),
  val incoming: List<IncomingCheck> = emptyList(),
  val placementsKnown: Boolean = false,
  val hintLocations: List<String> = emptyList(),
  val hintItems: List<String> = emptyList(),
)

/** A location of [world], not checked yet, that holds [item] for the slot asked about. */
@Serializable
data class IncomingCheck(val location: String, val world: String, val item: String)
