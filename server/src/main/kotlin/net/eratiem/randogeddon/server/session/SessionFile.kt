package net.eratiem.randogeddon.server.session

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.util.logger
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * `session.yaml`: who plays which worlds.
 *
 * ```yaml
 * people:
 *   Alice: [Alice-HK, Alice-Celeste]
 *   Bob: [Bob-Timespinner]
 * ```
 * The worlds are slot names (the `name:` of the player YAMLs).
 */
@Serializable
data class SessionFile(val people: Map<String, List<String>> = emptyMap())

/** Reads [TrackerConfig.sessionYaml] and reads it again whenever it changes; no file means no grouping. */
@Single
class SessionFileWatcher(config: TrackerConfig) {
  private val path: Path = config.sessionYaml
  private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
  private var modified: Long? = null
  private var current = SessionFile()

  /** Person -> slot names, in the order of the file. */
  @Synchronized
  fun people(): Map<String, List<String>> {
    val stamp = if (path.exists()) path.getLastModifiedTime().toMillis() else null
    if (stamp != modified) {
      modified = stamp
      current = if (stamp == null) SessionFile() else runCatching {
        yaml.decodeFromString(SessionFile.serializer(), path.readText())
      }.onFailure { logger.warn("Could not read {}: {}", path, it.message) }.getOrDefault(SessionFile())
      logger.info("Session file {}: {} people", path, current.people.size)
    }
    return current.people
  }
}
