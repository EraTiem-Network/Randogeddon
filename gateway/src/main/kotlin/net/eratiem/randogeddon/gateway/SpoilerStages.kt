package net.eratiem.randogeddon.gateway

import kotlinx.serialization.Serializable
import net.eratiem.randogeddon.shared.ApiJson
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

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

  private fun stampOf(file: Path): Stamp? = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }
    .getOrNull()?.takeIf { it.isRegularFile }?.let { Stamp(it.fileKey(), it.lastModifiedTime().toMillis(), it.size()) }

  /** Session [id]'s state; `null`: it has none (generated before 0.19.3: its spoiler log is complete). */
  fun read(id: String): State? {
    val file = dirs.generationDir(id).resolve(FILE)
    val stamp = stampOf(file) ?: return null.also { seen.remove(id) }
    seen[id]?.takeIf { it.first == stamp }?.let { return it.second }
    val state = runCatching { ApiJson.decodeFromString(State.serializer(), file.readText()) }
      .onFailure { logger.warn("{}'s {}: {}", id, FILE, it.message) }.getOrNull()
    seen[id] = stamp to state
    return state
  }

  fun save(id: String, state: State) {
    val dir = dirs.generationDir(id).createDirectories()
    val written = dir.resolve("$FILE.new")
    written.writeText(ApiJson.encodeToString(State.serializer(), state))
    val file = Files.move(written, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    // Kept as written: the next read needn't parse it again
    val stamp = stampOf(file)
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
