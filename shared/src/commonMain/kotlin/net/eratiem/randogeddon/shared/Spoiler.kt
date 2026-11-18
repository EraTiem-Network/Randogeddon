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

/** `GET /api/sessions/{id}/spoiler`: the seed's spoiler log, and its playthrough sphere by sphere (empty if not found in it). */
@Serializable
data class Spoiler(
  val seedName: String,
  val spheres: List<Sphere> = emptyList(),
  /** How many lines the whole log has ([SpoilerLines] reads them). */
  val lines: Int = 0,
)
