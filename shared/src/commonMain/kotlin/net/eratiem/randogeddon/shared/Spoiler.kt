package net.eratiem.randogeddon.shared

import kotlinx.serialization.Serializable

/** One sphere of the spoiler's playthrough: what is reachable (and needed) at that step, "Location (Slot): Item (Slot)". */
@Serializable
data class Sphere(
  val number: Int,
  val entries: List<String>,
  /** Per entry: its location is checked already (as the tracker sees it now); empty if not known. */
  val checked: List<Boolean> = emptyList(),
)

/**
 * `GET /api/sessions/{id}/spoiler/log?from=&count=`: some lines of the spoiler log ([from]: the first one's number,
 * from 0), of [total]. The log is read page by page: a big seed's is too long for a browser to show at once.
 */
@Serializable
data class SpoilerLines(val from: Int, val total: Int, val lines: List<String>) {
  companion object {
    /** The most lines one answer has. */
    const val MAX = 400
  }
}

/**
 * `GET /api/sessions/{id}/spoiler/search?q=`: the numbers of the lines that contain the text (any case), the first
 * [MAX] of them; [more]: there are more.
 */
@Serializable
data class SpoilerFindings(val lines: List<Int>, val more: Boolean = false) {
  companion object {
    const val MAX = 500
    const val MIN_QUERY = 2
    const val MAX_QUERY = 100
  }
}

/** An option as the spoiler log has it: its name and what it is set to (as text). */
@Serializable
data class SpoilerOption(val name: String, val value: String)

/** A slot of the seed as the spoiler log has it: its number, name and game, and its options. */
@Serializable
data class SpoilerSlot(val number: Int, val name: String, val game: String? = null, val options: List<SpoilerOption> = emptyList())

/**
 * What a world wrote into the spoiler log besides (a game's prices, the starting items), under its own heading, as
 * it wrote it; [more]: it has more lines than these (the log's text has them all).
 */
@Serializable
data class SpoilerSection(val title: String, val lines: List<String>, val more: Boolean = false) {
  companion object {
    /** The most lines of a section in an answer. */
    const val MAX = 1000
  }
}

/** `GET /api/sessions/{id}/spoiler/settings`: what the seed was made with: its own settings, each slot's, the rest. */
@Serializable
data class SpoilerSettings(
  val general: List<SpoilerOption> = emptyList(),
  val slots: List<SpoilerSlot> = emptyList(),
  val sections: List<SpoilerSection> = emptyList(),
)

/** Where an item lies: [location] of [finder]'s world holds [item] for [receiver]; [checked]: found already. */
@Serializable
data class SpoilerPlacement(val location: String, val finder: String, val item: String, val receiver: String, val checked: Boolean = false)

/**
 * `GET /api/sessions/{id}/spoiler/placements?slot=&q=&from=&count=`: the seed's placements a page at a time ([from]:
 * the first one's place among the [total] that are for or in `slot`'s world and contain `q`, any case).
 */
@Serializable
data class SpoilerPlacements(val total: Int, val from: Int, val placements: List<SpoilerPlacement>) {
  companion object {
    /** The most placements, or paths, one answer has. */
    const val MAX = 200
  }
}

/** The way to an item of the playthrough: [location] of [slot]'s world, and the [regions] passed from the start to it. */
@Serializable
data class SpoilerPath(val location: String, val slot: String, val regions: List<String>)

/** `GET /api/sessions/{id}/spoiler/paths?slot=&q=&from=&count=`: the playthrough's paths a page at a time, as the placements. */
@Serializable
data class SpoilerPaths(val total: Int, val from: Int, val paths: List<SpoilerPath>)

/** `GET /api/sessions/{id}/spoiler`: the seed's spoiler log, and its playthrough sphere by sphere (empty if not found in it). */
@Serializable
data class Spoiler(
  val seedName: String,
  val spheres: List<Sphere> = emptyList(),
  /** How many lines the whole log has ([SpoilerLines] reads them). */
  val lines: Int = 0,
  /**
   * The playthrough lists every progression item in the order it can be collected, not only the required ones:
   * Archipelago couldn't reduce it (a world's logic contradicts itself).
   */
  val unreduced: Boolean = false,
  /** The line its paths start at (`Paths:`, from the log's third level on; they run to its end); `null`: it has none. */
  val pathsFrom: Int? = null,
  /** The seed's slots by name (the views' filter), and how many placements the log has (none: it lists none). */
  val slots: List<String> = emptyList(),
  val placements: Int = 0,
)
