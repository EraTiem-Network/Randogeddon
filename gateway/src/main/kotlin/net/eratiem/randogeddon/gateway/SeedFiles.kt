package net.eratiem.randogeddon.gateway

import net.eratiem.randogeddon.shared.SessionIds
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.SeedFiles")

/** Seed names are digits as Archipelago makes them; letters, `-` and `_` for seeds named otherwise. */
private val SEED_NAME = Regex("[A-Za-z0-9_-]{1,64}")

/** The seed's name, from its file name (`AP_<seed>.zip` or `AP_<seed>.archipelago`). */
fun seedNameOf(file: Path): String = file.name.removePrefix("AP_").removeSuffix(".zip").removeSuffix(".archipelago")

internal fun isSeed(f: Path) = f.name.endsWith(".archipelago") || (f.name.startsWith("AP_") && f.name.endsWith(".zip"))

/**
 * The sessions' files: `sessions/<id>/` holds a session's seed, its save and room.json for as long as the session
 * exists, finished ones too. A deleted session's directory goes to `old/<id>-<time>/`, or is deleted for good.
 */
class SessionDirs(private val dataDir: Path) {
  private val root = dataDir.resolve("sessions")

  fun dir(id: String): Path {
    require(SessionIds.valid(id)) { "Not a session ID: $id" }
    return root.resolve(id)
  }

  /** The sessions that have a directory. */
  fun ids(): List<String> = files(root).filter { it.isDirectory() && SessionIds.valid(it.name) }.map { it.name }.sorted()

  /** The session's seed file (the newest, should there be more), or `null`. */
  fun seed(id: String): Path? {
    if (!SessionIds.valid(id)) return null
    return files(dir(id)).filter(::isSeed).maxByOrNull { it.getLastModifiedTime().toMillis() }
  }

  /** The spoiler log from the session's seed zip; `null` if there is none. */
  fun spoiler(id: String): String? {
    val zip = seed(id)?.takeIf { it.name.endsWith(".zip") } ?: return null
    return ZipFile(zip.toFile()).use { file ->
      file.entries().asSequence().firstOrNull { it.name.endsWith("_Spoiler.txt") }
        ?.let { entry -> file.getInputStream(entry).bufferedReader().readText() }
    }
  }

  /** Deletes the session's files for good ([purge]), or moves them to `old/<id>-<time>/`; `false` if there are none. */
  fun remove(id: String, purge: Boolean): Boolean {
    if (!SessionIds.valid(id)) return false
    val dir = dir(id).takeIf { it.isDirectory() } ?: return false
    if (purge) return dir.toFile().deleteRecursively()
    val old = dataDir.resolve("old").createDirectories()
    Files.move(dir, old.resolve("$id-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}"))
    return true
  }

  /**
   * Brings the files of before (one session at a time) into the per-session layout, once: the data directory's own
   * seed with its save and room.json, and every finished session's `sessions/<seed>/`, go to `sessions/<id>/`.
   * [idOf]: a seed's session ID, from the database. Nothing that is there already is overwritten.
   */
  fun migrate(idOf: (seed: String) -> String) {
    val seeds = files(dataDir).filter(::isSeed)
    if (seeds.isNotEmpty()) {
      val seed = readSeedName(dataDir.resolve("room.json")) ?: seedNameOf(seeds.maxBy { it.getLastModifiedTime().toMillis() })
      val target = dir(idOf(seed))
      if (target.exists()) {
        logger.warn("Not moving the seed {} into {}: it exists already", seed, target)
      } else {
        target.createDirectories()
        files(dataDir).filter { isSeed(it) || it.name.endsWith(".apsave") || it.name == "room.json" }
          .forEach { Files.move(it, target.resolve(it.name), StandardCopyOption.REPLACE_EXISTING) }
        logger.info("Moved the seed {} into {}", seed, target)
      }
    }
    // Who plays which slot comes from the admin panel now
    dataDir.resolve("session.yaml").takeIf { it.exists() }?.let { yaml ->
      Files.move(yaml, dataDir.resolve("old").createDirectories().resolve("session.yaml"), StandardCopyOption.REPLACE_EXISTING)
    }
    for (dir in files(root).filter { it.isDirectory() && !SessionIds.valid(it.name) && SEED_NAME.matches(it.name) }) {
      val target = dir(idOf(dir.name))
      if (target.exists()) {
        logger.warn("Not moving {} to {}: it exists already", dir, target)
        continue
      }
      Files.move(dir, target)
      logger.info("Moved the finished session {} to {}", dir.name, target)
    }
  }

  private fun files(dir: Path): List<Path> = if (dir.isDirectory()) dir.listDirectoryEntries() else emptyList()
}
