package net.eratiem.randogeddon.gateway

import kotlinx.serialization.Serializable
import net.eratiem.randogeddon.shared.ApiJson
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
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
  )

  /** Session [id]'s state; `null`: it has none (generated before 0.19.3: its spoiler log is complete). */
  fun read(id: String): State? {
    val file = dirs.generationDir(id).resolve(FILE)
    if (!file.isRegularFile()) return null
    return runCatching { ApiJson.decodeFromString(State.serializer(), file.readText()) }
      .onFailure { logger.warn("{}'s {}: {}", id, FILE, it.message) }.getOrNull()
  }

  fun save(id: String, state: State) {
    val dir = dirs.generationDir(id).createDirectories()
    val written = dir.resolve("$FILE.new")
    written.writeText(ApiJson.encodeToString(State.serializer(), state))
    Files.move(written, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  /** The next spoiler log to complete ([running]: not those): one without its playthrough first, then one without its paths. */
  fun next(running: Set<String> = emptySet()): Pair<String, State>? = dirs.ids().filter { it !in running && dirs.seed(it) != null }
    .mapNotNull { id -> read(id)?.let { id to it } }
    .filter { (_, state) -> state.level < COMPLETE && state.stopped == null }
    .minByOrNull { (id, state) -> "${state.level} $id" }

  companion object {
    const val FILE = "stages.json"
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
