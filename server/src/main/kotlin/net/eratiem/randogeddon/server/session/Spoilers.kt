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

/** The spoiler logs of the seeds, from the gateway (it reads them from the seeds' zips), kept once read. */
@Single
class Spoilers(private val gameServer: GameServerClient) {
  private val cache = ConcurrentHashMap<String, Spoiler>()

  suspend fun of(seed: String): Spoiler? =
    cache[seed] ?: gameServer.spoiler(seed)?.let { parseSpoiler(seed, it) }?.also { cache[seed] = it }

  fun forget(seed: String) {
    cache.remove(seed)
  }
}
