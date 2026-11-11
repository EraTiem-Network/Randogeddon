package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.GenerateResult
import net.eratiem.randogeddon.shared.SessionOptions
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Archipelago")

/**
 * The Archipelago releases this gateway has, one directory each under [root] (e.g. `/opt/archipelago/0.6.8`). A
 * session runs on the release it was generated with; one the gateway doesn't have can't be started.
 */
class Archipelago(private val root: Path, val python: String) {
  /** Newest first. */
  fun versions(): List<String> {
    if (!root.isDirectory()) return emptyList()
    return root.listDirectoryEntries().filter { it.resolve("MultiServer.py").exists() }.map { it.name }
      .sortedWith { a, b -> compareVersions(b, a) }
  }

  val newest: String get() = versions().firstOrNull() ?: error("No Archipelago release in $root")

  fun home(version: String): Path {
    val home = root.resolve(version)
    require(home.resolve("MultiServer.py").exists()) {
      "Archipelago $version is not installed in this gateway (it has ${versions().joinToString().ifEmpty { "none" }})"
    }
    return home
  }
}

/** 0.6.10 after 0.6.9: compares the numbers, not the text. */
internal fun compareVersions(a: String, b: String): Int {
  val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
  val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
  for (i in 0 until maxOf(x.size, y.size)) {
    val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
    if (c != 0) return c
  }
  return 0
}

/**
 * Starts MultiServer for a seed with its session's Archipelago version and options (from the database; the session
 * was created in the admin panel). Seeds deployed with the CLI have no session there: they run on the newest release
 * with [defaults] (the `AP_*` environment variables).
 */
class Launcher(
  private val archipelago: Archipelago,
  private val setups: SessionSetupStore,
  private val roomJson: Path,
  private val port: Int,
  private val defaults: SessionOptions,
  /** For `!admin` remote commands; empty: disabled. */
  private val adminPassword: String,
) {
  /** The slot list for the tracker and the slot passwords (room_info.py, next to serve.py). */
  fun prepare(seed: Path) {
    val proc = ProcessBuilder(archipelago.python, "room_info.py", seed.absolutePathString(), roomJson.absolutePathString())
      .directory(archipelago.home(archipelago.newest).toFile()).redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader().readText()
    check(proc.waitFor() == 0) { "room_info.py failed: ${output.trim().lines().lastOrNull()}" }
  }

  /** serve.py = MultiServer plus the hint switches (AP_POINT_HINTS, AP_AUTO_HINTS in its environment). */
  fun launch(seed: Path): Launch {
    val setup = readSeedName(roomJson)?.let { name -> runBlocking { setups.of(name) } }
    val version = setup?.apVersion ?: archipelago.newest
    val options = setup?.options?.let { ApiJson.decodeFromString(SessionOptions.serializer(), it) } ?: defaults
    val command = buildList {
      addAll(listOf(archipelago.python, "serve.py", seed.absolutePathString(), "--host", "0.0.0.0", "--port", port.toString()))
      addAll(listOf("--hint_cost", options.hintCost.toString(), "--location_check_points", options.locationCheckPoints.toString()))
      addAll(listOf("--release_mode", options.releaseMode, "--collect_mode", options.collectMode))
      addAll(listOf("--remaining_mode", options.remainingMode, "--countdown_mode", options.countdownMode))
      addAll(listOf("--auto_shutdown", "0", "--logtime"))
      if (adminPassword.isNotBlank()) addAll(listOf("--server_password", adminPassword))
    }
    val environment = mapOf("AP_POINT_HINTS" to options.pointHints.toString(), "AP_AUTO_HINTS" to options.autoHints.toString())
    return Launch(command, environment, archipelago.home(version))
  }
}

/**
 * Generates seeds with Archipelago's Generate.py: the uploaded YAMLs, plus a World Locks slot for the worlds that
 * start locked. The new seed becomes the data directory's seed; the previous one goes to `old/` first, as
 * scripts/deploy-seed.sh does. One generation at a time.
 */
class Generator(private val archipelago: Archipelago, private val dataDir: Path) {
  private val mutex = Mutex()

  suspend fun generate(request: GenerateRequest): GenerateResult = mutex.withLock {
    withContext(Dispatchers.IO) { run(request) }
  }

  private fun run(request: GenerateRequest): GenerateResult {
    val home = runCatching { archipelago.home(request.apVersion) }.getOrElse {
      return GenerateResult(log = listOf(it.message ?: "Archipelago ${request.apVersion} is missing"))
    }
    val work = createTempDirectory("generate")
    try {
      val players = work.resolve("players").createDirectories()
      val output = work.resolve("output").createDirectories()
      request.yamls.forEachIndexed { i, yaml -> players.resolve(fileName(yaml.fileName, i)).writeText(yaml.content) }
      if (request.locked.isNotEmpty()) players.resolve("zz-world-locks.yaml").writeText(locksYaml(request.locked, request.earlyKeys))

      logger.info("Generating a seed from {} YAMLs with Archipelago {}", request.yamls.size, request.apVersion)
      val proc = ProcessBuilder(
        archipelago.python, "Generate.py", "--player_files_path", players.absolutePathString(),
        "--outputpath", output.absolutePathString(),
      ).directory(home.toFile()).redirectErrorStream(true).start()
      val lines = proc.inputStream.bufferedReader().readLines()
      if (!proc.waitFor(10, TimeUnit.MINUTES)) proc.destroyForcibly()
      val zip = output.listDirectoryEntries("AP_*.zip").firstOrNull()
        ?: return GenerateResult(log = lines.takeLast(40).ifEmpty { listOf("Generate.py ended without a seed") })

      retire()
      val target = dataDir.resolve(zip.name)
      Files.move(zip, target, StandardCopyOption.REPLACE_EXISTING)
      val seedName = zip.name.removePrefix("AP_").removeSuffix(".zip")
      logger.info("Generated seed {}", seedName)
      return GenerateResult(seedName = seedName, seedFile = zip.name, log = lines.takeLast(10))
    } finally {
      work.toFile().deleteRecursively()
    }
  }

  /** The data directory's seed, its save, room.json and session.yaml go to `old/` (deleting a session, a new seed). */
  fun retire(): List<String> {
    val old = dataDir.resolve("old").createDirectories()
    val moved = dataDir.listDirectoryEntries().filter { f ->
      f.name.endsWith(".archipelago") || (f.name.startsWith("AP_") && f.name.endsWith(".zip")) ||
        f.name.endsWith(".apsave") || f.name == "room.json" || f.name == "session.yaml"
    }
    moved.forEach { Files.move(it, old.resolve(it.name), StandardCopyOption.REPLACE_EXISTING) }
    return moved.map { it.name }
  }

  /**
   * Without any path, only letters, digits, dots, dashes and underscores; every file a `.yaml`, numbered so names
   * can't collide.
   */
  private fun fileName(given: String, index: Int): String {
    val base = given.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
      .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "player" }
    return "%03d-%s.yaml".format(index, base)
  }

  /** The World Locks slot (apworlds/world_locks): nobody plays it; it holds the keys. Names as JSON strings. */
  private fun locksYaml(locked: List<String>, earlyKeys: Boolean): String = """
    |name: Locks
    |game: World Locks
    |World Locks:
    |  locked_worlds: [${locked.joinToString { JsonPrimitive(it).toString() }}]
    |  early_keys: $earlyKeys
    |""".trimMargin()
}
