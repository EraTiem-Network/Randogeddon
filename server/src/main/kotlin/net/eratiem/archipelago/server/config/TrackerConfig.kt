package net.eratiem.archipelago.server.config

import io.ktor.server.config.ApplicationConfig
import java.nio.file.Path

/** The `archipelago` section of `application.yaml`; every value can be set by an environment variable. */
private fun flag(value: String) = value.trim().lowercase() !in setOf("0", "false", "no", "off")

data class TrackerConfig(
  val port: Int,
  val title: String,
  val webDir: Path,
  val statePath: Path,
  val sessionYaml: Path,
  val serverUrl: String,
  val password: String,
  val roomJson: Path,
  /** Connect with this slot first; otherwise the first player in [roomJson]. */
  val bootstrapSlot: String?,
  val pointHints: Boolean,
  val autoHints: Boolean,
) {
  companion object {
    fun from(config: ApplicationConfig): TrackerConfig {
      fun value(key: String) = config.property("archipelago.$key").getString()
      return TrackerConfig(
        port = value("tracker.port").toInt(),
        title = value("tracker.title"),
        webDir = Path.of(value("tracker.webDir")),
        statePath = Path.of(value("tracker.statePath")),
        sessionYaml = Path.of(value("tracker.sessionYaml")),
        serverUrl = value("server.url"),
        password = value("server.password"),
        roomJson = Path.of(value("server.roomJson")),
        bootstrapSlot = value("server.bootstrapSlot").ifBlank { null },
        pointHints = flag(value("server.pointHints")),
        autoHints = flag(value("server.autoHints")),
      )
    }
  }
}
