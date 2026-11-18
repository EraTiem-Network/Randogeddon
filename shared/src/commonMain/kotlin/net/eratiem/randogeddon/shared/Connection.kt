package net.eratiem.randogeddon.shared

import kotlinx.serialization.Serializable

/** `GET /api/sessions/{id}/connection`: where game clients connect to the session, and the person's slots in it. */
@Serializable
data class Connection(
  /** E.g. `friday-chaos.randogeddon.eratiem.net:443`, or `ws://localhost:38281/friday-chaos` locally. */
  val server: String,
  /** The person's slots with their passwords; empty until the tracker knows the seed. */
  val slots: List<MySlot> = emptyList(),
)

@Serializable
data class MySlot(
  val name: String,
  val game: String,
  val password: String,
  /** A hosted slot of the session: its patch files for its players (no password); all of it for its Session-Managers. */
  val hosted: Boolean = false,
  /** Still locked: its key isn't found yet, so no one connects it (no password until then). */
  val locked: Boolean = false,
)

/**
 * A file of the seed that belongs to a slot, for its players to download: a ROM patch (`.apsm`, `.aplttp`, …), a mod
 * (Factorio's zip), other output of the slot's game. Never the multidata, a spoiler or readable text.
 */
@Serializable
data class SlotFile(
  /** As in the seed, e.g. `AP_7213_P3_Alice-SM.apsm`. */
  val name: String,
  /** The slot's number in the seed. */
  val slot: Int,
  /** Bytes. */
  val size: Long = 0,
  /** The slot's name and game; filled in by the tracker. */
  val slotName: String? = null,
  val game: String? = null,
)

/** `GET /api/sessions/{id}/files`: the files the user may download (gateway → tracker: every slot's). */
@Serializable
data class SlotFiles(val files: List<SlotFile> = emptyList())
