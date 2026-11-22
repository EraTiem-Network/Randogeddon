package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.shared.Sphere
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.Spoiler
import net.eratiem.randogeddon.shared.SpoilerFindings
import net.eratiem.randogeddon.shared.SpoilerLines
import net.eratiem.randogeddon.shared.SpoilerOption
import net.eratiem.randogeddon.shared.SpoilerSection
import net.eratiem.randogeddon.shared.SpoilerSettings
import net.eratiem.randogeddon.shared.SpoilerSlot
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

private val SPHERE_START = Regex("^(\\d+): \\{$")

/** The gateway's last line of a log whose playthrough Archipelago couldn't reduce to the required items. */
internal const val PLAYTHROUGH_NOT_REDUCED = "Randogeddon: the playthrough is not reduced to the required items"

/**
 * The spoiler log's playthrough, sphere by sphere: the shortest way through the seed, what each step makes reachable
 * and needs. Archipelago writes it as `Playthrough:`, then `<n>: {`, one `Location (Slot): Item (Slot)` per line, `}`.
 * Without that section (the seed's own log, before its playthrough is worked out; an older format) only the text is
 * there. `Paths:` after it starts the paths to every item (the log's third level).
 */
internal fun parseSpoiler(seed: String, text: String): Spoiler = parseSpoiler(seed, text.lines())

/** [parseSpoiler] of the log's [lines]. */
internal fun parseSpoiler(seed: String, lines: List<String>): Spoiler {
  val start = lines.indexOfFirst { it.trim() == "Playthrough:" }
  if (start < 0) return Spoiler(seed, lines = lines.size)
  // The third level's part, after the playthrough to the log's end
  val paths = lines.withIndex().indexOfFirst { (n, line) -> n > start && line.trimEnd() == "Paths:" }.takeIf { it >= 0 }
  // The gateway's spoiler_run.py says so in the log's last line when Archipelago couldn't reduce the playthrough
  val unreduced = lines.takeLast(3).any { it.trim() == PLAYTHROUGH_NOT_REDUCED }
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
  return Spoiler(seed, spheres, lines.size, unreduced, paths)
}

/**
 * A session's spoiler log as it was read: its text line by line ([lines]; the text itself isn't kept twice), and its
 * playthrough ([spoiler]); the seed's [slots] and its [placements] once asked for.
 */
class SpoilerLog(seed: String, text: String) {
  val lines: List<String> = text.lines()
  val spoiler: Spoiler = parseSpoiler(seed, lines)

  /** The seed's slots by their numbers ([seedSlots]). */
  internal val slots: Map<Int, SeedSlot> by lazy { seedSlots(lines) }

  /** What the seed was made with ([spoilerSettings]). */
  val settings: SpoilerSettings by lazy { spoilerSettings(lines) }

  /** Every placement of the seed, told apart by its slots; and the playthrough's paths, if the log has them. */
  internal val allPlacements: List<Placement> by lazy { placements(slots.values.map { it.name }) }
  internal val paths: List<PathEntry> by lazy {
    spoiler.pathsFrom?.let { paths(lines, it, slots.values.map { slot -> slot.name }) }.orEmpty()
  }

  @Volatile
  private var placed: Pair<Set<String>, List<Placement>>? = null

  /** The seed's placements ([placements]) told apart by [slots]; read again only for other slots than last time. */
  internal fun placements(slots: Collection<String>): List<Placement> {
    val names = slots.toSet()
    placed?.let { (from, placements) -> if (from == names) return placements }
    return placements(lines, names).also { placed = names to it }
  }

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

/** An option's line: its name, a colon, what it is set to (padded; nothing for an empty one). */
private val OPTION = Regex("^([^:]+):\\s*(.*)$")

/**
 * What the seed was made with, from the spoiler log's part before its placements: the seed's own settings (the
 * lines before the first slot), each slot's options (`Player <n>: <name>`, then a line an option), and what worlds
 * wrote besides: a heading of its own (a line ending in a colon after an empty one; an option without a value is
 * padded behind its colon), then its lines as they are.
 */
internal fun spoilerSettings(lines: List<String>): SpoilerSettings {
  val general = mutableListOf<SpoilerOption>()
  val slots = mutableListOf<SpoilerSlot>()
  val sections = mutableListOf<SpoilerSection>()
  var slot: SpoilerSlot? = null
  var options = mutableListOf<SpoilerOption>()
  var title: String? = null
  var text = mutableListOf<String>()
  fun endSlot() {
    slot?.let { slots += it.copy(options = options.toList()) }
    slot = null
    options = mutableListOf()
  }
  fun endSection() {
    title?.let { name ->
      val kept = text.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
      sections += SpoilerSection(name, kept.take(SpoilerSection.MAX), more = kept.size > SpoilerSection.MAX)
    }
    title = null
    text = mutableListOf()
  }
  var afterBlank = false
  for ((n, raw) in lines.withIndex()) {
    val line = raw.removePrefix("\uFEFF").trimEnd('\r')
    val trimmed = line.trimEnd()
    if (trimmed == "Locations:" || trimmed == "Playthrough:") break
    // The first line names Archipelago's version and the seed
    if (n == 0) continue
    val player = PLAYER.matchEntire(trimmed)
    val heading = afterBlank && player == null && line.isNotEmpty() && !line[0].isWhitespace() && line.endsWith(":")
    when {
      heading -> {
        endSlot()
        endSection()
        title = line.dropLast(1)
      }
      title != null -> text += trimmed
      player != null -> {
        endSlot()
        slot = SpoilerSlot(player.groupValues[1].toInt(), player.groupValues[2].trim())
      }
      else -> OPTION.matchEntire(trimmed)?.let { match ->
        val option = SpoilerOption(match.groupValues[1].trim(), match.groupValues[2].trim())
        val of = slot
        when {
          of == null -> general += option
          option.name == "Game" && of.game == null -> slot = of.copy(game = option.value)
          else -> options += option
        }
      }
    }
    afterBlank = trimmed.isEmpty()
  }
  endSlot()
  endSection()
  return SpoilerSettings(general, slots, sections)
}

/** The way to an item of the playthrough: [location] of [slot]'s world, the [regions] passed to it from the start. */
internal data class PathEntry(val location: String, val slot: String, val regions: List<String>)

/**
 * The playthrough's paths, from the log's `Paths:` line ([from]) on: `Location (Slot)`, then a line a step, indented,
 * `Region -> Entrance` (`=>` before all but the first), and at last the region it lies in. Each step's region, in
 * order. The slot is one of the seed's ([slots]: a name may have brackets of its own), else what the last bracket
 * holds. The paths end with the log, or at a line that isn't one of theirs.
 */
internal fun paths(lines: List<String>, from: Int, slots: Collection<String> = emptyList()): List<PathEntry> {
  val names = slots.sortedByDescending { it.length }
  val out = mutableListOf<PathEntry>()
  var head: Pair<String, String>? = null
  var regions = mutableListOf<String>()
  fun end() {
    head?.let { (location, slot) -> out += PathEntry(location, slot, regions.toList()) }
    head = null
    regions = mutableListOf()
  }
  for (raw in lines.drop(from + 1)) {
    val line = raw.trimEnd()
    if (line.isEmpty()) continue
    if (line[0].isWhitespace()) {
      if (head != null) regions += line.trim().removePrefix("=>").trim().substringBefore(" -> ")
      continue
    }
    end()
    if (!line.endsWith(")")) break
    val open = names.firstOrNull { line.endsWith(" ($it)") }?.let { line.length - it.length - 3 } ?: line.lastIndexOf(" (")
    if (open <= 0) break
    head = line.substring(0, open) to line.substring(open + 2, line.length - 1)
  }
  end()
  return out
}

private val PLAYER = Regex("^Player (\\d+): (.+)$")
private val GAME = Regex("^Game:\\s+(.+)$")

/** A slot of the seed by its number, as the spoiler log names it: `Player <n>: <name>`, then `Game: <game>`. */
internal data class SeedSlot(val name: String, val game: String?)

/** The slots of the seed by their numbers, from the spoiler log's options part. */
internal fun seedSlots(text: String): Map<Int, SeedSlot> = seedSlots(text.lineSequence().asIterable())

/** [seedSlots] of the log's [lines]. */
internal fun seedSlots(lines: Iterable<String>): Map<Int, SeedSlot> {
  val slots = LinkedHashMap<Int, SeedSlot>()
  var last: Int? = null
  for (raw in lines) {
    val line = raw.trimEnd()
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
internal fun placements(text: String, slots: Collection<String>): List<Placement> = placements(text.lines(), slots)

/** [placements] of the log's [lines]. */
internal fun placements(lines: List<String>, slots: Collection<String>): List<Placement> {
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

/**
 * The spoiler logs of the sessions, from the gateway (the seed's, or the one completed after it), kept once read: read
 * again when the gateway has completed a higher level of it.
 */
@Single
class Spoilers(private val gameServer: GameServerClient, private val sessions: LiveSessions) {
  private val cache = ConcurrentHashMap<String, Pair<Int, SpoilerLog>>()

  /** Session [id]'s spoiler log; [seed]: its seed's name. */
  suspend fun of(id: String, seed: String): SpoilerLog? {
    // Complete (3), or how far the gateway is with it
    val level = sessions.get(id)?.server?.spoiler?.level ?: 3
    cache[id]?.takeIf { it.first == level }?.let { return it.second }
    return gameServer.spoiler(id)?.let { SpoilerLog(seed, it) }?.also { cache[id] = level to it }
  }

  fun forget(id: String) {
    cache.remove(id)
  }
}
