package net.eratiem.randogeddon.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.SeedInfo
import net.eratiem.randogeddon.shared.SeedSlot
import net.eratiem.randogeddon.shared.SeedUpload
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.io.path.readText

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.UploadedSeeds")

/** The largest seed zip taken, and the most it may unpack to; how many files it may have. */
const val MAX_SEED_BYTES = 64L * 1024 * 1024
const val MAX_SEED_UNPACKED = 512L * 1024 * 1024
const val MAX_SEED_FILES = 1000

/** How long an uploaded seed waits for its session before it is deleted, and how many wait at once. */
private const val SEED_KEPT_MS = 60 * 60_000L
private const val MAX_WAITING = 8

/** The game of the World Locks slot: its locks are set up when a seed is generated here, so not for one from elsewhere. */
private const val WORLD_LOCKS_GAME = "World Locks"

/** Why an uploaded seed isn't taken, as the form shows it. */
class SeedProblem(message: String) : Exception(message)

/** What a seed zip has, after [cleanSeedZip]: its multidata, its spoiler log (if any), and how many files for players. */
data class SeedEntries(val multidata: String, val spoiler: String?, val files: Int)

/** A file's name as a seed zip of Archipelago has them: `AP_…`, flat (no directory), nothing a file system reads otherwise. */
private val SEED_FILE = Regex("AP_[^/\\\\:*?\"<>|\\p{Cntrl}]{1,200}")

/**
 * Checks an uploaded seed zip and writes it anew to [out], entry by entry: what is kept is what was read here, whatever
 * else the upload's directory says (two readers of a crafted zip needn't see the same). Only files as Archipelago
 * names a seed's (`AP_…`, no directories), each name once, one `.archipelago`, at most one spoiler log, and no more
 * than [MAX_SEED_FILES] files or [MAX_SEED_UNPACKED] bytes unpacked.
 */
fun cleanSeedZip(bytes: ByteArray, out: Path): SeedEntries {
  if (bytes.size > MAX_SEED_BYTES) throw SeedProblem("it is larger than ${MAX_SEED_BYTES / 1024 / 1024} MB")
  val names = mutableSetOf<String>()
  var unpacked = 0L
  try {
    ZipOutputStream(out.outputStream()).use { target ->
      ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val entry = zip.nextEntry ?: break
          val name = entry.name
          if (entry.isDirectory || !SEED_FILE.matches(name) || name.endsWith(".") || name.endsWith(" ")) {
            throw SeedProblem("it has ${printable(name)}, which isn't a file of a seed (they are all named AP_…, without folders)")
          }
          if (!names.add(name.lowercase())) throw SeedProblem("it has ${printable(name)} twice")
          if (names.size > MAX_SEED_FILES) throw SeedProblem("it has more than $MAX_SEED_FILES files")
          target.putNextEntry(ZipEntry(name))
          while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            unpacked += read
            if (unpacked > MAX_SEED_UNPACKED) throw SeedProblem("it unpacks to more than ${MAX_SEED_UNPACKED / 1024 / 1024} MB")
            target.write(buffer, 0, read)
          }
          target.closeEntry()
        }
      }
    }
  } catch (e: ZipException) {
    throw SeedProblem("it isn't a zip file Archipelago made (${e.message})")
  }
  if (names.isEmpty()) throw SeedProblem("it isn't a zip file (the AP_….zip from Archipelago's output folder is)")
  val multidata = names.filter { it.endsWith(".archipelago") }
  if (multidata.size != 1) throw SeedProblem("it has ${if (multidata.isEmpty()) "no" else "more than one"} .archipelago file")
  val spoilers = names.filter { it.endsWith("_spoiler.txt") }
  if (spoilers.size > 1) throw SeedProblem("it has more than one spoiler log")
  return SeedEntries(multidata.single(), spoilers.singleOrNull(), names.size - 1 - spoilers.size)
}

/** `seed_info.py`'s answer. */
@Serializable
private class SeedRead(
  val problem: String? = null,
  @SerialName("seed_name") val seedName: String = "",
  val version: String = "",
  val slots: List<Slot> = emptyList(),
) {
  @Serializable
  class Slot(val slot: Int, val name: String, val game: String)
}

private val SEED_NAME = Regex("[0-9A-Za-z_-]{1,64}")

/**
 * Seeds generated elsewhere (the Launcher's `AP_<seed>.zip`), uploaded to become a session's: checked and written anew
 * ([cleanSeedZip]), their multidata read with the release's own restricted loader (`seed_info.py`), then kept under a
 * token until the tracker makes the session ([install]) or [SEED_KEPT_MS] have passed.
 */
class UploadedSeeds(
  private val archipelago: Archipelago,
  private val dirs: SessionDirs,
  dataDir: Path,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private class Waiting(val file: Path, val info: SeedInfo, val since: Long)

  private val folder = dataDir.resolve("seed-uploads")
  private val waiting = ConcurrentHashMap<String, Waiting>()
  private val random = SecureRandom()

  init {
    // What a gateway before this one left: nobody has its tokens any more
    runCatching { folder.listDirectoryEntries().forEach { it.deleteIfExists() } }
  }

  /** Checks [upload] and keeps it for its session; throws [SeedProblem] for one that isn't taken. */
  fun take(upload: SeedUpload): SeedInfo {
    forgetOld()
    if (waiting.size >= MAX_WAITING) throw SeedProblem("too many uploaded seeds wait for their session; try again later")
    val release = upload.apVersion ?: archipelago.newest
    val home = runCatching { archipelago.home(release) }.getOrElse { throw SeedProblem("Archipelago $release isn't here") }
    val bytes = runCatching { Base64.getDecoder().decode(upload.base64) }.getOrElse { throw SeedProblem("it isn't base64") }
    val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    val file = folder.createDirectories().resolve("$token.zip")
    try {
      val entries = cleanSeedZip(bytes, file)
      val read = read(home, file)
      read.problem?.let { throw SeedProblem(it) }
      if (!SEED_NAME.matches(read.seedName)) throw SeedProblem("its seed has no name Archipelago gives one")
      if (read.slots.isEmpty()) throw SeedProblem("it has no slot for a player")
      read.slots.firstOrNull { it.game == WORLD_LOCKS_GAME }?.let {
        throw SeedProblem("it has a World Locks slot (${printable(it.name)}): locks only work for a seed generated here")
      }
      val info = SeedInfo(
        token, read.seedName, release, read.version, read.slots.map { SeedSlot(it.name, it.game) },
        spoiler = entries.spoiler != null, files = entries.files,
      )
      waiting[token] = Waiting(file, info, clock())
      logger.info("Seed {} uploaded ({} slots, generated with {}): waits for its session", info.seedName, info.slots.size, info.generatedWith)
      return info
    } catch (e: Throwable) {
      file.deleteIfExists()
      throw e
    }
  }

  /**
   * The seed kept under [token] becomes session [id]'s; `null`: no such one (any more). Throws
   * [IllegalStateException] if the session has a seed already.
   */
  fun install(id: String, token: String): SeedInfo? {
    forgetOld()
    val seed = waiting[token] ?: return null
    check(dirs.seed(id) == null) { "Session $id has a seed already" }
    if (waiting.remove(token) == null) return null
    val target = dirs.dir(id).createDirectories().resolve("AP_${seed.info.seedName}.zip")
    Files.move(seed.file, target, StandardCopyOption.ATOMIC_MOVE)
    logger.info("Uploaded seed {} is session {}'s", seed.info.seedName, id)
    return seed.info
  }

  /** The seed kept under [token] won't become a session: deleted; `false`: no such one. */
  fun drop(token: String): Boolean {
    val seed = waiting.remove(token) ?: return false
    seed.file.deleteIfExists()
    return true
  }

  private fun forgetOld() {
    val now = clock()
    waiting.entries.removeIf { (_, seed) -> (now - seed.since > SEED_KEPT_MS).also { old -> if (old) seed.file.deleteIfExists() } }
  }

  private fun read(home: Path, zip: Path): SeedRead {
    val answer = Files.createTempFile("seed-info", ".json")
    try {
      val proc = ProcessBuilder(archipelago.python, "seed_info.py", zip.absolutePathString(), answer.absolutePathString())
        .withoutSecrets().directory(home.toFile()).redirectErrorStream(true).start()
      proc.outputStream.close()
      val output = StringBuilder()
      val reader = Thread { runCatching { output.append(proc.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
      if (!proc.waitFor(120, TimeUnit.SECONDS)) {
        proc.destroyForcibly()
        throw SeedProblem("reading it took too long")
      }
      reader.join(2000)
      if (proc.exitValue() != 0) {
        logger.info("seed_info.py: {}", output.toString().trim().lines().takeLast(3).joinToString(" | "))
        throw SeedProblem("it can't be read as a seed (${printable(output.toString().trim().lines().lastOrNull())})")
      }
      return ApiJson.decodeFromString(SeedRead.serializer(), answer.readText())
    } finally {
      answer.deleteIfExists()
    }
  }
}
