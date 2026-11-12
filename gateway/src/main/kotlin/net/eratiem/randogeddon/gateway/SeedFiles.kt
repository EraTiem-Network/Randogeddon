package net.eratiem.randogeddon.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Seed names are digits as Archipelago makes them; letters, `-` and `_` for seeds named otherwise. */
private val SEED_NAME = Regex("[A-Za-z0-9_-]{1,64}")

/** The seed's name, from its file name (`AP_<seed>.zip` or `AP_<seed>.archipelago`). */
fun seedNameOf(file: Path): String = file.name.removePrefix("AP_").removeSuffix(".zip").removeSuffix(".archipelago")

/**
 * The files of the data directory's seed: the seed, its save, room.json and session.yaml. A deleted session's go to
 * `old/`; a finished one's to `sessions/<seed>/`, from where it can be reopened (moved back) or deleted for good.
 */
class SeedFiles(private val dataDir: Path) {
  private val archive = dataDir.resolve("sessions")

  /** The seed the data directory holds, if any. */
  fun currentSeed(): String? = files(dataDir).firstOrNull(::isSeed)?.let(::seedNameOf)

  /** Moves the seed's files to `old/` (deleting a session, a new seed); the names moved. */
  fun retire(): List<String> = moveAll(dataDir, dataDir.resolve("old"))

  /** Moves the finished seed's files to `sessions/<seed>/`; the seed's name, or `null` if there was none. */
  fun archive(): String? {
    val seed = currentSeed() ?: return null
    moveAll(dataDir, archive.resolve(seed))
    return seed
  }

  /**
   * Moves an archived seed back into the data directory (it must hold none); `false` if it is neither archived nor in
   * `old/` (where a seed deployed with the CLI goes when the next one comes).
   */
  fun activate(seed: String): Boolean {
    if (!SEED_NAME.matches(seed)) return false
    val dir = archiveDir(seed)
    val old = files(dataDir.resolve("old")).filter { it.name.startsWith("AP_$seed.") }
    if (dir == null && old.none(::isSeed)) return false
    check(currentSeed() == null) { "The data directory still has a seed" }
    if (dir != null) {
      moveAll(dir, dataDir)
      Files.deleteIfExists(dir)
    } else {
      old.forEach { Files.move(it, dataDir.resolve(it.name), StandardCopyOption.REPLACE_EXISTING) }
    }
    return true
  }

  /** Deletes an archived seed for good; `false` if there is none. */
  fun delete(seed: String): Boolean {
    val dir = archiveDir(seed) ?: return false
    return dir.toFile().deleteRecursively()
  }

  /** The spoiler log of [seed] from its zip: in the data directory, the archive or `old/`; `null` if there is none. */
  fun spoiler(seed: String): String? {
    if (!SEED_NAME.matches(seed)) return null
    val zip = listOf(dataDir, archive.resolve(seed), dataDir.resolve("old"))
      .map { it.resolve("AP_$seed.zip") }.firstOrNull { it.exists() } ?: return null
    return ZipFile(zip.toFile()).use { file ->
      file.entries().asSequence().firstOrNull { it.name.endsWith("_Spoiler.txt") }
        ?.let { entry -> file.getInputStream(entry).bufferedReader().readText() }
    }
  }

  private fun archiveDir(seed: String): Path? =
    archive.resolve(seed).takeIf { SEED_NAME.matches(seed) && it.isDirectory() }

  private fun files(dir: Path): List<Path> = if (dir.isDirectory()) dir.listDirectoryEntries() else emptyList()

  private fun isSeed(f: Path) = f.name.endsWith(".archipelago") || (f.name.startsWith("AP_") && f.name.endsWith(".zip"))

  private fun moveAll(from: Path, to: Path): List<String> {
    val moved = files(from).filter { f ->
      isSeed(f) || f.name.endsWith(".apsave") || f.name == "room.json" || f.name == "session.yaml"
    }
    if (moved.isNotEmpty()) to.createDirectories()
    moved.forEach { Files.move(it, to.resolve(it.name), StandardCopyOption.REPLACE_EXISTING) }
    return moved.map { it.name }
  }
}
