package net.eratiem.randogeddon.gateway

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import net.eratiem.randogeddon.shared.SessionIds
import net.eratiem.randogeddon.shared.SlotFile
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.SeedFiles")

/** Seed names are digits as Archipelago makes them; letters, `-` and `_` for seeds named otherwise. */
private val SEED_NAME = Regex("[A-Za-z0-9_-]{1,64}")

/** The seed's name, from its file name (`AP_<seed>.zip` or `AP_<seed>.archipelago`). */
fun seedNameOf(file: Path): String = file.name.removePrefix("AP_").removeSuffix(".zip").removeSuffix(".archipelago")

internal fun isSeed(f: Path) = f.name.endsWith(".archipelago") || (f.name.startsWith("AP_") && f.name.endsWith(".zip"))

/**
 * The newest seed file in [dir] (should there be more), or `null`. Only a regular file: a link there (Archipelago's user
 * writes into a session's directory) is none.
 */
internal fun newestSeed(dir: Path): Path? {
  if (!dir.isDirectory()) return null
  return SharedFolder(dir).files().filterKeys { isSeed(Path.of(it)) }.maxByOrNull { it.value.lastModifiedTime().toMillis() }
    ?.let { dir.resolve(it.key) }
}

/**
 * A session's file to hand out as it is: on its own, or the entry [entry] of a seed zip. Read in pieces ([copyTo]), so
 * a large one is never whole in memory. Its directory is Archipelago's user's to write too: a file on its own is read
 * without following a link ([SharedFolder]); a seed zip only if it is a regular file as it is opened.
 */
class SessionFile(private val file: Path, private val entry: String? = null) {
  fun copyTo(out: OutputStream) {
    if (entry == null) {
      SharedFolder(file.parent).copyTo(file.name, out, MAX_SPOILER_FILE)
      return
    }
    if (SharedFolder(file.parent).file(file.name) == null) throw NoSuchFileException("$file")
    ZipFile(file.toFile()).use { zip ->
      val found = zip.getEntry(entry) ?: throw NoSuchFileException("$file: $entry")
      zip.getInputStream(found).use { it.copyTo(out) }
    }
  }

  /** All of it as text (for what has to compare all of it). */
  fun text(): String = ByteArrayOutputStream().also { copyTo(it) }.toString(Charsets.UTF_8)
}

/**
 * A copy serve.py keeps of a save it couldn't read (`<seed>.apsave.broken-<time>`): the game server doesn't start over
 * it, the save stays as it is, and the copy for whoever looks into it.
 */
private val BROKEN_SAVE = Regex(""".+\.apsave\.broken-[^/\\]+""")

/** How many of a session's [BROKEN_SAVE] copies are kept: the newest. */
internal const val BROKEN_SAVES_KEPT = 3

/**
 * A file of MultiServer's save: the save (`<seed>.apsave`), the copy of its last readable one serve.py makes at each
 * start (`<seed>.apsave.bak`, one), a save it couldn't read ([BROKEN_SAVE], a few: serve.py keeps the newest 3, and
 * the gateway too), and a save cut off while it was written (`<seed>.apsave.tmp`, left by a kill). They go with their
 * session.
 */
internal fun isSaveFile(name: String): Boolean =
  name.endsWith(".apsave") || name.endsWith(".apsave.bak") || name.endsWith(".apsave.tmp") || BROKEN_SAVE.matches(name)

/** The [BROKEN_SAVE] copies in a session's directory [dir], by name (only regular files). */
internal fun brokenSaves(dir: Path): Set<String> = SharedFolder(dir).files().keys.filterTo(mutableSetOf()) { BROKEN_SAVE.matches(it) }

/** Of the [BROKEN_SAVE] copies in [dir], all but the newest [keep] deleted (nothing through a link). */
internal fun pruneBrokenSaves(dir: Path, keep: Int = BROKEN_SAVES_KEPT) {
  val folder = SharedFolder(dir)
  folder.files().filterKeys { BROKEN_SAVE.matches(it) }.entries
    .sortedWith(compareByDescending<Map.Entry<String, BasicFileAttributes>> { it.value.lastModifiedTime() }.thenByDescending { it.key })
    .drop(keep)
    .forEach { (name, _) -> runCatching { folder.delete(name) }.onFailure { logger.warn("Could not delete {}: {}", name, it.message) } }
}

/** A slot's output in the seed: `AP_<seed>_P<n>_<name>.<ext>`, or with `-` (Factorio: `AP-<seed>-P<n>-<name>_<version>.zip`). */
private val SLOT_FILE = Regex("^AP[_-][A-Za-z0-9]+[_-]P(\\d+)[_-].+\\.[A-Za-z0-9]+$")

/** Readable text could show the way through the seed (a spoiler, a log): never handed out. */
private val TEXT = setOf("txt", "log", "html", "htm", "md", "csv")

/**
 * The slot number [name] belongs to, if players may download it: a flat file name (no directory, no `..`) of a slot's
 * output, not the multidata, nothing named spoiler and no readable text.
 */
internal fun slotOf(name: String): Int? {
  if ('/' in name || '\\' in name || ".." in name) return null
  val lower = name.lowercase()
  if ("spoiler" in lower || lower.endsWith(".archipelago") || lower.substringAfterLast('.') in TEXT) return null
  return SLOT_FILE.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
}

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
    return newestSeed(dir(id))
  }

  /** The session's spoiler log, to hand out; `null` if there is none. */
  fun spoilerLog(id: String): SessionFile? {
    // Completed in the background after the seed (SpoilerStages): it replaces the seed's. Only a regular file, not a link
    val completed = spoilerFile(id)
    if (SharedFolder(dir(id)).file(completed.name) != null) return SessionFile(completed)
    val zip = seed(id)?.takeIf { it.name.endsWith(".zip") } ?: return null
    return ZipFile(zip.toFile()).use { file ->
      file.entries().asSequence().firstOrNull { it.name.endsWith("_Spoiler.txt") }?.let { SessionFile(zip, it.name) }
    }
  }

  /** The spoiler log from the session's seed zip as text; `null` if there is none. */
  fun spoiler(id: String): String? = spoilerLog(id)?.text()

  /** The spoiler log completed after the seed (a higher level than the seed's own), if there is one. */
  fun spoilerFile(id: String): Path = dir(id).resolve("spoiler.txt")

  /** What the seed was generated from, to complete its spoiler log later: its YAMLs and `stages.json`. */
  fun generationDir(id: String): Path = dir(id).resolve("generation")

  /** The files of the session's seed that belong to a slot (patches, mods), as players may download them. */
  fun slotFiles(id: String): List<SlotFile> {
    val zip = seed(id)?.takeIf { it.name.endsWith(".zip") } ?: return emptyList()
    return ZipFile(zip.toFile()).use { file ->
      file.entries().asSequence().filter { !it.isDirectory }
        .mapNotNull { entry -> slotOf(entry.name)?.let { SlotFile(entry.name, it, entry.size.coerceAtLeast(0)) } }
        .sortedWith(compareBy({ it.slot }, { it.name })).toList()
    }
  }

  /** One of [slotFiles], to hand out; `null` if it isn't one. */
  fun slotFile(id: String, name: String): SessionFile? {
    if (slotOf(name) == null) return null
    val zip = seed(id)?.takeIf { it.name.endsWith(".zip") } ?: return null
    return ZipFile(zip.toFile()).use { file ->
      file.getEntry(name)?.takeIf { !it.isDirectory }?.let { SessionFile(zip, it.name) }
    }
  }

  /**
   * Deletes the session's files for good ([purge]), or moves them to `old/<id>-<time>/`; `false` if there are none.
   * All of them: its seed, its save and the save's copies ([isSaveFile]), what it was generated from, its room. Deleted
   * without following a link Archipelago's user put there ([deleteTree]).
   */
  fun remove(id: String, purge: Boolean): Boolean {
    if (!SessionIds.valid(id)) return false
    val dir = dir(id).takeIf { it.isDirectory() } ?: return false
    if (purge) return runCatching { deleteTree(dir) }.isSuccess && !dir.exists()
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
      val seed = readSeedName(dataDir.resolve("room.json")) ?: seedNameOf(newestSeed(dataDir)!!)
      val target = dir(idOf(seed))
      if (target.exists()) {
        logger.warn("Not moving the seed {} into {}: it exists already", seed, target)
      } else {
        target.createDirectories()
        files(dataDir).filter { isSeed(it) || isSaveFile(it.name) || it.name == "room.json" }
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
