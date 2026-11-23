package net.eratiem.randogeddon.gateway

import kotlinx.serialization.Serializable
import net.eratiem.randogeddon.shared.ApiJson
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.SpoilerStages")

/**
 * The spoiler logs that are completed after their seeds: a seed is generated with a spoiler without playthrough
 * (level 1) so its session starts at once; the gateway then generates it again with the same seed number and
 * inputs, only its spoiler (`--spoiler_only`), at level 2 (the playthrough) and 3 (the paths). Each session's
 * `generation/stages.json` says how far it is; its YAMLs lie next to it.
 */
class SpoilerStages(private val dirs: SessionDirs) {
  /** [seed]: the seed number; [apworlds]: the APWorlds of its games by their SHA-256; [stopped]: why it stays. */
  @Serializable
  data class State(
    val seed: Long,
    val release: String,
    val level: Int = 1,
    val apworlds: Map<String, String> = emptyMap(),
    val stopped: String? = null,
    /** Generate.py's arguments for the seed's settings (the plando modules, no balancing): the later levels' too. */
    val arguments: List<String> = emptyList(),
  )

  /**
   * What tells a file apart from a later one: its identity (a file written anew and moved over it is another one), its
   * modification time and its size.
   */
  private data class Stamp(val key: Any?, val modified: Long, val size: Long)

  /** Each session's file as last read or written, with its state: read again only when it changed. */
  private val seen = ConcurrentHashMap<String, Pair<Stamp, State?>>()

  /**
   * Its session's directory is Archipelago's user's to write too ([SharedFolder]): the file is read and written there
   * without following a link, and only as a regular file.
   */
  private fun folder(id: String) = SharedFolder(dirs.dir(id))

  private fun stampOf(id: String): Stamp? = folder(id).file(PATH)?.let { Stamp(it.fileKey(), it.lastModifiedTime().toMillis(), it.size()) }

  /** Session [id]'s state; `null`: it has none (generated before 0.19.3: its spoiler log is complete). */
  fun read(id: String): State? {
    val stamp = stampOf(id) ?: return null.also { seen.remove(id) }
    seen[id]?.takeIf { it.first == stamp }?.let { return it.second }
    val state = runCatching { ApiJson.decodeFromString(State.serializer(), folder(id).readText(PATH, MAX_STATE)) }
      .onFailure { logger.warn("{}'s {}: {}", id, FILE, it.message) }.getOrNull()
    seen[id] = stamp to state
    return state
  }

  fun save(id: String, state: State) {
    val folder = folder(id)
    folder.folder(GENERATION)
    folder.writeText(PATH, ApiJson.encodeToString(State.serializer(), state))
    // Kept as written: the next read needn't parse it again
    val stamp = stampOf(id)
    if (stamp != null) seen[id] = stamp to state else seen.remove(id)
  }

  /**
   * The next spoiler log to complete ([running]: not those): one without its playthrough first, then one without its
   * paths. The kept states are looked at first, a seed only for the ones that are to be completed.
   */
  fun next(running: Set<String> = emptySet()): Pair<String, State>? = dirs.ids().asSequence().filter { it !in running }
    .mapNotNull { id -> read(id)?.let { id to it } }
    .filter { (_, state) -> state.level < COMPLETE && state.stopped == null }
    .sortedBy { (id, state) -> "${state.level} $id" }
    .firstOrNull { (id, _) -> dirs.seed(id) != null }

  companion object {
    const val FILE = "stages.json"
    /** Its session's folder of what the seed was generated from ([SessionDirs.generationDir]), and the file in it. */
    private const val GENERATION = "generation"
    private const val PATH = "$GENERATION/$FILE"
    /** The most a state is read of: a few KB (the seed's number, its APWorlds' checksums, its arguments). */
    private const val MAX_STATE = 4L * 1024 * 1024
    /** Generate.py for the later levels, with a playthrough that falls back instead of failing (`archipelago/`). */
    const val SPOILER_RUN = "spoiler_run.py"
    /** Level 3: with the paths, the spoiler log is complete. */
    const val COMPLETE = 3
  }
}

/** A line starting a part of a spoiler log (`Locations:`, `Playthrough:`, `Paths:`): no indent, a colon at its end. */
private val PART = Regex("^\\S.*:\\s*$")

/**
 * Where the spoiler log puts every item (its `Locations:` part, sorted): the same for every level of the same seed,
 * so a later level's log belongs to the seed when they are equal.
 */
fun placementsOf(spoiler: String): List<String> {
  val lines = spoiler.lineSequence().map { it.trimEnd('\r') }.toList()
  val start = lines.indexOfFirst { it.trim() == "Locations:" }
  if (start < 0) return emptyList()
  return lines.drop(start + 1).takeWhile { !PART.matches(it) }.filter { it.isNotBlank() }.sorted()
}
