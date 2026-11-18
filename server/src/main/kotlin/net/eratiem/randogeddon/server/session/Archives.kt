package net.eratiem.randogeddon.server.session

import kotlinx.serialization.json.jsonObject
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.util.string
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ArchivedSession
import net.eratiem.randogeddon.shared.SessionIds
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * The tracker's files of each session, `sessions/<id>/` in its state directory: the history of the live tracker
 * (`tracker.json`), and once the session finished, what the dashboard showed then (`archive.json`: the summary with
 * every slot's details). Reopening drops the archive; the history goes on from where it was.
 */
@Single
class Archives(private val config: TrackerConfig) {
  private val cache = ConcurrentHashMap<String, ArchivedSession>()

  fun dir(id: String): Path {
    require(SessionIds.valid(id)) { "Not a session ID: $id" }
    return config.stateDir.resolve("sessions").resolve(id)
  }

  /** The live tracker's history of session [id]. */
  fun history(id: String): Path = dir(id).resolve("tracker.json")

  fun write(id: String, archived: ArchivedSession) {
    val file = dir(id).createDirectories().resolve("archive.json")
    val tmp = Files.createTempFile(file.parent, ".archive-", ".json")
    tmp.writeText(ApiJson.encodeToString(ArchivedSession.serializer(), archived))
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    cache[id] = archived
  }

  fun read(id: String): ArchivedSession? {
    if (!SessionIds.valid(id)) return null
    cache[id]?.let { return it }
    val file = dir(id).resolve("archive.json").takeIf { it.exists() } ?: return null
    return runCatching { ApiJson.decodeFromString(ArchivedSession.serializer(), file.readText()) }.getOrNull()
      ?.also { cache[id] = it }
  }

  /** The session is reopened: it is live again. */
  fun forget(id: String) {
    cache.remove(id)
    if (SessionIds.valid(id)) dir(id).resolve("archive.json").deleteIfExists()
  }

  /** The session is deleted: its history and archive go. */
  fun delete(id: String) {
    cache.remove(id)
    if (SessionIds.valid(id)) dir(id).toFile().deleteRecursively()
  }

  /**
   * Brings the files of one session at a time into `sessions/<id>/`, once: the tracker's history (`tracker.json` next
   * to `sessions/`) and each finished session's `sessions/<seed>.json` and `<seed>.tracker.json`. [idOf]: a seed's
   * session ID. Nothing that is there already is overwritten.
   */
  fun migrate(idOf: (seed: String) -> String) {
    val root = config.stateDir.resolve("sessions")
    fun move(from: Path, seed: String, name: String) {
      val target = dir(idOf(seed)).resolve(name)
      if (target.exists()) return logger.warn("Not moving {} to {}: it exists already", from, target)
      target.parent.createDirectories()
      Files.move(from, target)
      logger.info("Moved {} to {}", from, target)
    }
    val current = config.stateDir.resolve("tracker.json")
    if (current.exists()) {
      val seed = runCatching { ApiJson.parseToJsonElement(current.readText()).jsonObject.string("seedName") }.getOrNull()
      if (seed != null) move(current, seed, "tracker.json")
    }
    if (!root.isDirectory()) return
    for (file in root.listDirectoryEntries().filter { !it.isDirectory() }) {
      when {
        file.name.endsWith(".tracker.json") -> move(file, file.name.removeSuffix(".tracker.json"), "tracker.json")
        file.name.endsWith(".json") -> move(file, file.name.removeSuffix(".json"), "archive.json")
      }
    }
  }
}
