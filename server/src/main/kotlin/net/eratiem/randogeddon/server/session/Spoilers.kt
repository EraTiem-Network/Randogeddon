package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.shared.Sphere
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.Spoiler
import net.eratiem.randogeddon.shared.SpoilerFindings
import net.eratiem.randogeddon.shared.SpoilerLines
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

private val SPHERE_START = Regex("^(\\d+): \\{$")

/**
 * The spoiler log's playthrough, sphere by sphere: the shortest way through the seed, what each step makes reachable
 * and needs. Archipelago writes it as `Playthrough:`, then `<n>: {`, one `Location (Slot): Item (Slot)` per line, `}`.
 * Without that section (an older format, or generated without a playthrough) only the text is there.
 */
internal fun parseSpoiler(seed: String, text: String): Spoiler {
  val lines = text.lines()
  val start = lines.indexOfFirst { it.trim() == "Playthrough:" }
  if (start < 0) return Spoiler(seed, lines = lines.size)
  val spheres = mutableListOf<Sphere>()
  var number: Int? = null
  var entries = mutableListOf<String>()
  for (line in lines.drop(start + 1)) {
    val t = line.trim()
    val open = number
    if (open == null) {
      val match = SPHERE_START.find(t)
      when {
        match != null -> {
          number = match.groupValues[1].toInt()
          entries = mutableListOf()
        }
        // The next section ("Paths:", "Unreachable Items:")
        t.isNotEmpty() -> break
      }
    } else if (t == "}") {
      spheres += Sphere(open, entries)
      number = null
    } else if (t.isNotEmpty()) {
      entries += t
    }
  }
  return Spoiler(seed, spheres, lines.size)
}

/** A session's spoiler log as it was read: its [text], line by line ([lines]), and its playthrough ([spoiler]). */
class SpoilerLog(seed: String, val text: String) {
  val lines: List<String> = text.lines()
  val spoiler: Spoiler = parseSpoiler(seed, text)

  /** Up to [count] lines from line [from] (from 0). */
  fun page(from: Int, count: Int): SpoilerLines {
    val start = from.coerceIn(0, lines.size)
    return SpoilerLines(start, lines.size, lines.subList(start, (start + count.coerceIn(1, SpoilerLines.MAX)).coerceAtMost(lines.size)))
  }

  /** The lines that contain [query], any case: the first [SpoilerFindings.MAX]. */
  fun search(query: String): SpoilerFindings {
    val found = ArrayList<Int>()
    for ((n, line) in lines.withIndex()) {
      if (!line.contains(query, ignoreCase = true)) continue
      if (found.size == SpoilerFindings.MAX) return SpoilerFindings(found, more = true)
      found += n
    }
    return SpoilerFindings(found)
  }
}

private val PLAYER = Regex("^Player (\\d+): (.+)$")
private val GAME = Regex("^Game:\\s+(.+)$")

/** A slot of the seed by its number, as the spoiler log names it: `Player <n>: <name>`, then `Game: <game>`. */
internal data class SeedSlot(val name: String, val game: String?)

/** The slots of the seed by their numbers, from the spoiler log's options part. */
internal fun seedSlots(text: String): Map<Int, SeedSlot> {
  val slots = LinkedHashMap<Int, SeedSlot>()
  var last: Int? = null
  for (line in text.lineSequence().map { it.trimEnd() }) {
    // The options part ends where the placements begin
    if (line == "Locations:" || line == "Playthrough:") break
    val player = PLAYER.matchEntire(line)
    if (player != null) {
      val number = player.groupValues[1].toInt()
      slots[number] = SeedSlot(player.groupValues[2].trim(), null)
      last = number
      continue
    }
    val number = last ?: continue
    val game = GAME.matchEntire(line) ?: continue
    slots[number] = slots.getValue(number).copy(game = game.groupValues[1].trim())
    last = null
  }
  return slots
}

/**
 * The location and its world of a playthrough step, `Location (Slot): Item (Slot)` (the slot is a name's last
 * bracket, locations may have their own); `null` for sphere 0's `Item (Slot)`.
 */
internal fun stepLocation(entry: String): Pair<String, String>? {
  val split = entry.lastIndexOf("): ")
  if (split < 0) return null
  val part = entry.substring(0, split + 1).trim()
  val open = part.lastIndexOf(" (")
  if (open < 0) return null
  return part.substring(0, open) to part.substring(open + 2, part.length - 1)
}

/**
 * [spoiler] with each step marked that is checked: its location is among its world's checked ones ([checked], by the
 * world's name). What the tracker doesn't know (yet: a new session's slots, before it has read them) isn't checked.
 * Sphere 0 has no locations.
 */
internal fun markChecked(spoiler: Spoiler, checked: Map<String, Set<String>>): Spoiler = spoiler.copy(
  spheres = spoiler.spheres.map { sphere ->
    sphere.copy(checked = sphere.entries.map { entry ->
      stepLocation(entry)?.let { (location, world) -> checked[world]?.contains(location) } == true
    })
  },
)

/** Where an item is: [location] of the world [finder] holds [item] for the world [receiver]. */
internal data class Placement(val location: String, val finder: String, val item: String, val receiver: String)

/**
 * The seed's placements from the spoiler log's `Locations:` section, one `Location (Finder): Item (Receiver)` per line
 * (a multiworld's; the names in brackets are the seed's slots, [slots], so a bracket in a location's name doesn't
 * mislead). Empty without that section (a spoiler level without placements).
 */
internal fun placements(text: String, slots: Collection<String>): List<Placement> {
  val lines = text.lines()
  val start = lines.indexOfFirst { it.trim() == "Locations:" }
  if (start < 0) return emptyList()
  val names = slots.sortedByDescending { it.length }
  val out = mutableListOf<Placement>()
  var seen = false
  for (raw in lines.drop(start + 1)) {
    val line = raw.trim()
    if (line.isEmpty()) {
      if (seen) break else continue
    }
    seen = true
    val finder = names.firstOrNull { line.indexOf(" ($it): ") > 0 } ?: continue
    val cut = line.indexOf(" ($finder): ")
    val rest = line.substring(cut + finder.length + 5)
    val receiver = names.firstOrNull { rest.endsWith(" ($it)") } ?: continue
    out += Placement(line.substring(0, cut), finder, rest.dropLast(receiver.length + 3), receiver)
  }
  return out
}

/**
 * Searching a spoiler log costs the server a pass over all of it: per user one search at a time, and no sooner than
 * [SPACING_MS] after their last one (it waits for that). A search asked for while one runs is refused.
 */
@Single
class SpoilerSearches {
  private val running = ConcurrentHashMap.newKeySet<String>()
  private val last = ConcurrentHashMap<String, Long>()

  /** [search] for [user], paced; `null`: one of theirs is still running. */
  suspend fun <T> paced(user: String, search: () -> T): T? {
    if (!running.add(user)) return null
    try {
      val wait = SPACING_MS - (System.currentTimeMillis() - (last[user] ?: 0))
      if (wait > 0) delay(wait)
      return search()
    } finally {
      last[user] = System.currentTimeMillis()
      running.remove(user)
    }
  }

  companion object {
    const val SPACING_MS = 500L
  }
}

/** The spoiler logs of the sessions, from the gateway (it reads them from the seeds' zips), kept once read. */
@Single
class Spoilers(private val gameServer: GameServerClient) {
  private val cache = ConcurrentHashMap<String, SpoilerLog>()

  /** Session [id]'s spoiler log; [seed]: its seed's name. */
  suspend fun of(id: String, seed: String): SpoilerLog? =
    cache[id] ?: gameServer.spoiler(id)?.let { SpoilerLog(seed, it) }?.also { cache[id] = it }

  fun forget(id: String) {
    cache.remove(id)
  }
}
