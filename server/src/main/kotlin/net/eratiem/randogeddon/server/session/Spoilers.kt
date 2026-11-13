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
