package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.eratiem.randogeddon.shared.ApiJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.eratiem.randogeddon.shared.ApworldConflict
import net.eratiem.randogeddon.shared.ApworldFile
import net.eratiem.randogeddon.shared.ApworldResult
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.ArchipelagoInfo
import net.eratiem.randogeddon.shared.FailedLoad
import net.eratiem.randogeddon.shared.GameInfo
import net.eratiem.randogeddon.shared.GameSource
import net.eratiem.randogeddon.shared.ReleaseInfo
import net.eratiem.randogeddon.shared.Suspect
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Apworlds")

/** The largest apworld accepted; the big ones (with their own libraries) are a few MB. */
private const val MAX_APWORLD_BYTES = 64L * 1024 * 1024

/** Archipelago's generic world: in every release, not a game anyone plays. */
private const val GENERIC_WORLD = "Archipelago"

/** An upload or a removal that can't be done, with what to tell the admin. */
class ApworldProblem(message: String) : Exception(message)

/**
 * The apworlds of each Archipelago release: the ones uploaded in the admin panel (a library per release, under
 * [library], e.g. `/data/apworlds/0.6.8/`) and the ones put by hand into the server's `custom_worlds/` ([manual],
 * for every release). Before a release lists its games or generates, its own `custom_worlds/` directory is made of
 * exactly these. One change at a time; a generation holds the release meanwhile.
 */
class Apworlds(private val archipelago: Archipelago, private val library: Path, private val manual: Path?) {
  private val mutex = Mutex()
  /** The games per release, listed once and again after a change (listing loads every world: seconds). */
  private val listed = mutableMapOf<String, ReleaseInfo>()

  suspend fun info(): ArchipelagoInfo = ArchipelagoInfo(archipelago.versions().map { release(it) }, files())

  suspend fun release(version: String): ReleaseInfo = mutex.withLock {
    listed[version] ?: withContext(Dispatchers.IO) { list(version) }.also { listed[version] = it }
  }

  /** Runs [block] (Generate.py) with the release's `custom_worlds/` up to date; no change to it meanwhile. */
  suspend fun <T> holding(version: String, block: suspend () -> T): T = mutex.withLock {
    withContext(Dispatchers.IO) { sync(version) }
    block()
  }

  /** Every release's `custom_worlds/` up to date (at the start, for MultiServer). */
  suspend fun syncAll() = mutex.withLock {
    withContext(Dispatchers.IO) {
      archipelago.versions().forEach { version ->
        runCatching { sync(version) }.onFailure { logger.warn("custom_worlds of {}: {}", version, it.message) }
      }
    }
  }

  /** The library of every release, and the hand-placed files (listed under each release). */
  fun files(): List<ApworldFile> = archipelago.versions().flatMap { version ->
    val uploaded = uploads(version).map { file ->
      ApworldFile(file.name, version, GameSource.UPLOADED, from = origin(file), size = file.fileSize())
    }
    val hand = manualFiles().filter { m -> uploaded.none { it.file == m.name } }
      .map { ApworldFile(it.name, version, GameSource.MANUAL, size = it.fileSize()) }
    uploaded + hand
  }

  /**
   * Adds an uploaded apworld to the release's library, named after the world inside it (Archipelago imports it by its
   * file name, which must be the world's folder). A game the release brings itself is refused (its own world always
   * wins); a file of the same name, or another uploaded file of the same game, is only replaced with
   * [ApworldUpload.replace] (else the answer names it, and nothing changes).
   */
  suspend fun upload(upload: ApworldUpload): ApworldResult {
    requireRelease(upload.release)
    val given = apworldName(upload.fileName)
    val bytes = runCatching { Base64.getDecoder().decode(upload.base64) }.getOrElse { throw ApworldProblem("Not base64") }
    if (bytes.size > MAX_APWORLD_BYTES) throw ApworldProblem("$given is larger than ${MAX_APWORLD_BYTES / 1024 / 1024} MB")
    checkApworld(bytes)?.let { throw ApworldProblem("$given: $it") }
    val name = "${worldOf(bytes)}.apworld"
    val game = manifestGame(bytes)
    val owner = game?.let { ownerOf(upload.release, it, name) }
    when (owner?.source) {
      GameSource.BUNDLED -> throw ApworldProblem("$name: Archipelago ${upload.release} brings $game itself, and its own world wins over an APWorld")
      GameSource.MANUAL -> throw ApworldProblem("$name: $game comes from ${owner.apworld}, put on the server by hand (custom_worlds/)")
      else -> {}
    }
    val sameFile = library.resolve(upload.release).resolve(name).exists()
    val other = owner?.apworld
    if (!upload.replace && (sameFile || other != null)) {
      return ApworldResult(name, conflict = ApworldConflict(sameFile, game.takeIf { other != null }, other))
    }
    return change(upload.release, name, replacing = other) { dir ->
      dir.resolve(name).writeBytes(bytes)
      dir.resolve("$name.from").deleteIfExists()
    }
  }

  /**
   * Where [game] comes from in [release], besides [name]: the release itself, an uploaded or a hand-placed file (by
   * their manifests, else by the release's list of games).
   */
  private suspend fun ownerOf(release: String, game: String, name: String): GameInfo? {
    val listed = release(release).games.firstOrNull { it.game == game }
    if (listed?.source == GameSource.BUNDLED) return listed
    val uploaded = uploads(release).filter { it.name != name }
    uploaded.firstOrNull { manifestGame(it.readBytes()) == game }?.let { return GameInfo(game, GameSource.UPLOADED, it.name) }
    manualFiles().filter { m -> m.name != name && uploads(release).none { it.name == m.name } }
      .firstOrNull { manifestGame(it.readBytes()) == game }?.let { return GameInfo(game, GameSource.MANUAL, it.name) }
    return listed?.takeIf { it.apworld != name }
  }

  /** Copies an apworld of another release's library to [ApworldTry.release]'s, marked as from there (untested). */
  suspend fun tryWith(request: ApworldTry): ApworldResult {
    requireRelease(request.release)
    val name = apworldName(request.file)
    val source = library.resolve(request.from).resolve(name)
      .takeIf { request.from != request.release && request.from in archipelago.versions() && it.exists() }
      ?: throw ApworldProblem("$name is not in the library of Archipelago ${request.from}")
    return change(request.release, name) { dir ->
      Files.copy(source, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
      dir.resolve("$name.from").writeText(request.from)
    }
  }

  suspend fun remove(release: String, file: String) {
    requireRelease(release)
    val name = apworldName(file)
    val dir = library.resolve(release)
    if (!dir.resolve(name).exists()) throw ApworldProblem("$name is not in the library of Archipelago $release")
    mutex.withLock {
      withContext(Dispatchers.IO) {
        dir.resolve(name).deleteIfExists()
        dir.resolve("$name.from").deleteIfExists()
        listed.remove(release)
        sync(release)
      }
    }
    logger.info("Removed {} from the library of Archipelago {}", name, release)
  }

  /** Stores the file in the release's library, lists the release again and tells what the file brought. */
  /**
   * Stores [name] with [store] and lists the release's games again. A file that brings no game (it doesn't load, or
   * the release can't list its games with it) is refused: what was there before is put back, and the answer says why.
   */
  private suspend fun change(
    release: String, name: String, replacing: String? = null, store: (Path) -> Unit,
  ): ApworldResult = mutex.withLock {
    withContext(Dispatchers.IO) {
      val dir = library.resolve(release).createDirectories()
      val file = dir.resolve(name)
      val marker = dir.resolve("$name.from")
      val before = file.takeIf { it.exists() }?.readBytes()
      val beforeFrom = marker.takeIf { it.exists() }?.readText()
      // Another file of the same game goes (and comes back if this one is refused)
      val replaced = replacing?.let { dir.resolve(it) }?.takeIf { it.exists() }
      val replacedMarker = replaced?.resolveSibling("${replaced.name}.from")
      val replacedBytes = replaced?.readBytes()
      val replacedFrom = replacedMarker?.takeIf { it.exists() }?.readText()
      replaced?.deleteIfExists()
      replacedMarker?.deleteIfExists()
      store(dir)
      listed.remove(release)
      val info = list(release).also { listed[release] = it }
      val games = info.games.filter { it.apworld == name }.map { it.game }
      val failed = info.failed.firstOrNull { it.name == name.removeSuffix(".apworld") || name in it.reason }
      val error = failed?.reason ?: info.error ?: if (games.isEmpty()) "No game loaded from it" else null
      if (error == null) {
        logger.info("Added {} to the library of Archipelago {}{}", name, release, replaced?.let { ", instead of ${it.name}" }.orEmpty())
        return@withContext ApworldResult(name, games)
      }
      if (before != null) file.writeBytes(before) else file.deleteIfExists()
      if (beforeFrom != null) marker.writeText(beforeFrom) else marker.deleteIfExists()
      if (replaced != null && replacedBytes != null) {
        replaced.writeBytes(replacedBytes)
        replacedFrom?.let { replacedMarker.writeText(it) }
      }
      listed.remove(release)
      listed[release] = list(release)
      logger.warn("Refused {} for Archipelago {}: {}", name, release, error)
      ApworldResult(name, error = error)
    }
  }

  /** Only releases the gateway has: their names become paths. */
  private fun requireRelease(version: String) {
    if (version !in archipelago.versions()) throw ApworldProblem("Archipelago $version is not installed in this gateway")
  }

  private fun uploads(version: String): List<Path> = library.resolve(version).takeIf { it.isDirectory() }
    ?.listDirectoryEntries("*.apworld")?.sortedBy { it.name }.orEmpty()

  private fun manualFiles(): List<Path> = manual?.takeIf { it.isDirectory() }
    ?.listDirectoryEntries("*.apworld")?.sortedBy { it.name }.orEmpty()

  private fun origin(file: Path): String? = file.resolveSibling("${file.name}.from").takeIf { it.exists() }?.readText()?.trim()

  /** The release's `custom_worlds/`: the hand-placed apworlds, then its library (which wins on equal names). */
  private fun sync(version: String) {
    val target = archipelago.home(version).resolve("custom_worlds").createDirectories()
    val wanted = (manualFiles() + uploads(version)).associateBy { it.name }
    target.listDirectoryEntries("*.apworld").filter { it.name !in wanted }.forEach { it.deleteIfExists() }
    wanted.forEach { (name, source) -> Files.copy(source, target.resolve(name), StandardCopyOption.REPLACE_EXISTING) }
  }

  /** Loads the release's worlds with list_games.py (next to serve.py) and sorts out where each game comes from. */
  private fun list(version: String): ReleaseInfo {
    val home = archipelago.home(version)
    sync(version)
    val out = createTempFile("games", ".json")
    try {
      val proc = ProcessBuilder(archipelago.python, "list_games.py", out.absolutePathString())
        .directory(home.toFile()).redirectErrorStream(true).start()
      proc.outputStream.close()
      // Reading blocks until the output ends: the time limit has to end the process from outside
      Thread { if (!proc.waitFor(3, TimeUnit.MINUTES)) proc.destroyForcibly() }.apply { isDaemon = true }.start()
      val output = proc.inputStream.bufferedReader().readText()
      proc.waitFor()
      if (proc.exitValue() != 0 || Files.size(out) == 0L) {
        return ReleaseInfo(version, error = "Listing the games failed: ${output.trim().lines().takeLast(3).joinToString(" ")}")
      }
      val listing = ApiJson.decodeFromString(Listing.serializer(), out.readText())
      val uploaded = uploads(version).map { it.name }.toSet()
      val games = listing.games.filter { it.game != GENERIC_WORLD }.map { game ->
        val source = when (game.apworld) {
          null -> GameSource.BUNDLED
          in uploaded -> GameSource.UPLOADED
          else -> GameSource.MANUAL
        }
        GameInfo(game.game, source, game.apworld, game.module)
      }.sortedBy { it.game.lowercase() }
      return ReleaseInfo(version, games, listing.failed)
    } finally {
      out.deleteIfExists()
    }
  }

  @Serializable
  private data class Listing(val games: List<ListedGame> = emptyList(), val failed: List<FailedLoad> = emptyList())

  @Serializable
  private data class ListedGame(val game: String, val apworld: String? = null, val module: String? = null)
}

/** A file name for the library: no path, only safe characters, ending in `.apworld`. */
internal fun apworldName(given: String): String {
  val base = given.substringAfterLast('/').substringAfterLast('\\').trim()
  val stem = base.removeSuffix(".apworld").removeSuffix(".zip").replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
  if (stem.isEmpty()) throw ApworldProblem("Not a file name: $given")
  return "$stem.apworld"
}

/** An apworld's entries (`/` as the separator), in order. */
private fun zipEntries(bytes: ByteArray): List<String> = runCatching {
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    generateSequence { zip.nextEntry }.map { it.name.replace('\\', '/') }.toList()
  }
}.getOrDefault(emptyList())

private val WORLD_ENTRY = Regex("^([^/]+)/__init__\\.py$")

/** The world folder of an apworld (`<world>/__init__.py`): Archipelago imports it by this name. */
internal fun worldOf(bytes: ByteArray): String? = zipEntries(bytes).firstNotNullOfOrNull { WORLD_ENTRY.find(it)?.groupValues?.get(1) }

/** The game an apworld's manifest (`<world>/archipelago.json`) names, if it has one. */
internal fun manifestGame(bytes: ByteArray): String? = runCatching {
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    generateSequence { zip.nextEntry }.firstOrNull { Regex("^[^/]+/archipelago\\.json$").matches(it.name.replace('\\', '/')) }
      ?.let { ApiJson.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject["game"] as? JsonPrimitive }
      ?.takeIf { it.isString }?.content
  }
}.getOrNull()

/**
 * What's wrong with an apworld, or `null`: it must be a zip with one world folder (`<world>/__init__.py`), named as a
 * Python module can be (Archipelago imports it so), and no entry may lead out of it.
 */
internal fun checkApworld(bytes: ByteArray): String? {
  var entries = 0
  val worlds = mutableSetOf<String>()
  try {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries++
        val name = entry.name.replace('\\', '/')
        if (name.startsWith("/") || name.split('/').any { it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(name)) {
          return "an entry leads out of the APWorld ($name)"
        }
        WORLD_ENTRY.find(name)?.let { worlds += it.groupValues[1] }
      }
    }
  } catch (e: Exception) {
    return "not a valid zip (${e.message})"
  }
  return when {
    entries == 0 -> "not a valid zip"
    worlds.isEmpty() -> "no world inside (an APWorld holds a folder with an __init__.py)"
    worlds.size > 1 -> "more than one world inside (${worlds.sorted().joinToString(", ")})"
    !Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(worlds.single()) -> "its world ${worlds.single()} isn't a name Archipelago can load"
    else -> null
  }
}

/**
 * The games that may have made a generation fail, most likely first: one the release has no apworld for, one the
 * error names (by game, apworld file or module), one whose apworld comes from another release. The error is the
 * output from the last traceback on, or all of it without one.
 */
internal fun suspects(games: List<String>, log: List<String>, release: ReleaseInfo, files: List<ApworldFile>): List<Suspect> {
  val error = log.indexOfLast { it.startsWith("Traceback") }.let { if (it >= 0) log.drop(it) else log }.joinToString("\n")
  val known = release.games.associateBy { it.game }
  fun named(game: String, info: GameInfo?): Boolean {
    if (error.contains(game)) return true
    if (info == null) return false
    if (info.apworld?.let { error.contains(it) } == true) return true
    val module = info.module?.removePrefix("worlds.")?.substringBefore('.') ?: return false
    return Regex("worlds[./]${Regex.escape(module)}\\b").containsMatchIn(error)
  }
  return games.distinct().mapNotNull { game ->
    val info = known[game]
    val file = info?.apworld?.let { name -> files.firstOrNull { it.file == name && it.release == release.version } }
    when {
      info == null && release.error == null ->
        Suspect(game, "Archipelago ${release.version} has no APWorld for it") to 0
      named(game, info) -> Suspect(game, "named in the error", info?.apworld, file?.from) to 1
      file?.from != null -> Suspect(game, "its APWorld is from Archipelago ${file.from}, untested here", file.file, file.from) to 2
      else -> null
    }
  }.sortedBy { it.second }.map { it.first }
}
