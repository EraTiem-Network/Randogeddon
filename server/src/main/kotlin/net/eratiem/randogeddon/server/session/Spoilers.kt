package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.shared.Sphere
import net.eratiem.randogeddon.shared.Spoiler
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
  if (start < 0) return Spoiler(seed, text = text)
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
  return Spoiler(seed, spheres, text)
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
 * [spoiler] with each step marked that is checked: its world is known ([open]: each world's locations not checked
 * yet) and the location isn't open there any more. Sphere 0 has no locations.
 */
internal fun markChecked(spoiler: Spoiler, open: Map<String, Set<String>>): Spoiler = spoiler.copy(
  spheres = spoiler.spheres.map { sphere ->
    sphere.copy(checked = sphere.entries.map { entry ->
      stepLocation(entry)?.let { (location, world) -> open[world]?.let { location !in it } } == true
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

/** The spoiler logs of the sessions, from the gateway (it reads them from the seeds' zips), kept once read. */
@Single
class Spoilers(private val gameServer: GameServerClient) {
  private val cache = ConcurrentHashMap<String, Spoiler>()

  /** Session [id]'s spoiler log; [seed]: its seed's name. */
  suspend fun of(id: String, seed: String): Spoiler? =
    cache[id] ?: gameServer.spoiler(id)?.let { parseSpoiler(seed, it) }?.also { cache[id] = it }

  fun forget(id: String) {
    cache.remove(id)
  }
}
