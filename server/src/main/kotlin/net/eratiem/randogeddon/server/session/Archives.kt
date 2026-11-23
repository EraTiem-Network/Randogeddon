package net.eratiem.randogeddon.server.session

import kotlinx.serialization.json.jsonObject
import net.eratiem.randogeddon.database.SessionSnapshotStore
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * What a finished session's dashboard showed when it finished (its archive: the summary with every slot's details),
 * kept in the database as the session's final snapshot ([SessionSnapshotStore]), and all of them in memory, read at
 * the tracker's start ([load]). Reopening makes it live again. Until 0.19.10 they were files in the tracker's state
 * directory (`sessions/<id>/archive.json`, next to the live tracker's history `tracker.json`): [load] brings those in
 * once and leaves them where they are.
 */
@Single
class Archives(private val config: TrackerConfig, private val snapshots: SessionSnapshotStore) {
  private val cache = ConcurrentHashMap<String, ArchivedSession>()

  fun dir(id: String): Path {
    require(SessionIds.valid(id)) { "Not a session ID: $id" }
    return config.stateDir.resolve("sessions").resolve(id)
  }

  /** The live tracker's history file of session [id] from before 0.19.10 (imported once, see `TrackerService`). */
  fun history(id: String): Path = dir(id).resolve("tracker.json")

  /** Every archive, into memory; the files from before into the database first (those it has none of). */
  suspend fun load() {
    val kept = snapshots.finals()
    // Read once: the imported ones are known from their files
    for ((id, json) in kept + importFiles(kept.keys)) {
      runCatching { ApiJson.decodeFromString(ArchivedSession.serializer(), json) }
        .onSuccess { cache[id] = it }
        .onFailure { logger.warn("Could not read {}'s archive: {}", id, it.message) }
    }
    logger.info("{} archived sessions", cache.size)
  }

  /** The archive files of before, of sessions without one in the database, into it; the ones imported (id -> JSON). */
  private suspend fun importFiles(kept: Set<String>): Map<String, String> {
    val root = config.stateDir.resolve("sessions")
    if (!root.isDirectory()) return emptyMap()
    val imported = mutableMapOf<String, String>()
    for (dir in root.listDirectoryEntries().filter { it.isDirectory() && SessionIds.valid(it.name) && it.name !in kept }) {
      val file = dir.resolve("archive.json").takeIf { it.exists() } ?: continue
      runCatching {
        val text = file.readText()
        ApiJson.decodeFromString(ArchivedSession.serializer(), text)
        // Only a session that is there has one (V29): a deleted one's file is refused
        snapshots.saveFinal(dir.name, file.getLastModifiedTime().toMillis(), text)
        imported[dir.name] = text
      }.onSuccess { logger.info("Imported {}'s archive into the database", dir.name) }
        .onFailure { logger.warn("Could not import {}: {}", file, it.message) }
    }
    return imported
  }

  /** [archived] as it is kept in the database (the session's final snapshot). */
  fun encode(archived: ArchivedSession): String = ApiJson.encodeToString(ArchivedSession.serializer(), archived)

  /**
   * Session [id] finished with [archived], written to the database with the finish itself
   * (`SessionFinishStore.finish`): it is read from here from now on.
   */
  fun remember(id: String, archived: ArchivedSession) {
    cache[id] = archived
  }

  fun read(id: String): ArchivedSession? = cache[id]

  /** The session is reopened: it is live again (its archive is its last snapshot until the tracker writes one). */
  suspend fun forget(id: String) {
    snapshots.reopen(id)
    cache.remove(id)
  }

  /** The session is deleted: its snapshot or archive goes, and its files of before. */
  suspend fun delete(id: String) {
    cache.remove(id)
    snapshots.delete(id)
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
