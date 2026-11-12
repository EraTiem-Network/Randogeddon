package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ArchivedSession
import org.koin.core.annotation.Single
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val SEED_NAME = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * The finished sessions as they were when they finished, next to the tracker's history (`/state/sessions/`): the
 * dashboard's summary with every slot's details (`<seed>.json`, what a past session shows), and a copy of the
 * tracker's history file (`<seed>.tracker.json`, brought back when the session is reopened).
 */
@Single
class Archives(private val config: TrackerConfig) {
  private val cache = ConcurrentHashMap<String, ArchivedSession>()

  private val dir: Path get() = config.statePath.toAbsolutePath().parent.resolve("sessions")

  fun write(seed: String, archived: ArchivedSession) {
    val file = file(seed, ".json")
    file.parent.createDirectories()
    val tmp = Files.createTempFile(file.parent, ".archive-", ".json")
    tmp.writeText(ApiJson.encodeToString(ArchivedSession.serializer(), archived))
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    cache[seed] = archived
  }

  fun read(seed: String): ArchivedSession? {
    if (!SEED_NAME.matches(seed)) return null
    cache[seed]?.let { return it }
    val file = file(seed, ".json").takeIf { it.exists() } ?: return null
    return runCatching { ApiJson.decodeFromString(ArchivedSession.serializer(), file.readText()) }.getOrNull()
      ?.also { cache[seed] = it }
  }

  /** The names of the session's players, without regard to case. */
  fun players(seed: String): Set<String> = read(seed)?.summary?.people.orEmpty().map { it.name.lowercase() }.toSet()

  /** Keeps a copy of the tracker's history file for [seed]. */
  fun keepHistory(seed: String) {
    val source = config.statePath
    if (!source.exists()) return
    val target = file(seed, ".tracker.json")
    target.parent.createDirectories()
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
  }

  fun history(seed: String): Path? = file(seed, ".tracker.json").takeIf { it.exists() }

  fun delete(seed: String) {
    cache.remove(seed)
    if (!SEED_NAME.matches(seed)) return
    file(seed, ".json").deleteIfExists()
    file(seed, ".tracker.json").deleteIfExists()
  }

  private fun file(seed: String, suffix: String): Path {
    require(SEED_NAME.matches(seed)) { "Not a seed name: $seed" }
    return dir.resolve(seed + suffix)
  }
}
