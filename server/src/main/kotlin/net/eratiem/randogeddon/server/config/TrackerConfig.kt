package net.eratiem.randogeddon.server.config

import io.ktor.server.config.ApplicationConfig
import net.eratiem.randogeddon.database.DatabaseConfig
import java.nio.file.Path

/** The `randogeddon` section of `application.yaml`; every value can be set by an environment variable. */
private fun flag(value: String) = value.trim().lowercase() !in setOf("0", "false", "no", "off")

data class TrackerConfig(
  val port: Int,
  val title: String,
  val webDir: Path,
  val statePath: Path,
  val sessionYaml: Path,
  /** Where game clients connect (the gateway), `host:port`; shown to logged-in players. */
  val publicAddress: String,
  val serverUrl: String,
  val roomJson: Path,
  /** Connect with this slot first; otherwise the first player in [roomJson]. */
  val bootstrapSlot: String?,
  val pointHints: Boolean,
  val autoHints: Boolean,
  val database: DatabaseConfig,
  /** The gateway's internal API, which runs the game server (admin panel). */
  val gameServerApi: String,
  val internalToken: String,
  /** Past sessions are deleted this many days after they finished (unless kept); 0: never. */
  val sessionRetentionDays: Int = 90,
  /** The login cookie is sent over HTTPS only; off just for a demo over plain HTTP in the local network. */
  val cookieSecure: Boolean = true,
) {
  companion object {
    fun from(config: ApplicationConfig): TrackerConfig {
      fun value(key: String) = config.property("randogeddon.$key").getString()
      return TrackerConfig(
        port = value("tracker.port").toInt(),
        title = value("tracker.title"),
        webDir = Path.of(value("tracker.webDir")),
        statePath = Path.of(value("tracker.statePath")),
        sessionYaml = Path.of(value("tracker.sessionYaml")),
        publicAddress = value("tracker.publicAddress"),
        serverUrl = value("server.url"),
        roomJson = Path.of(value("server.roomJson")),
        bootstrapSlot = value("server.bootstrapSlot").ifBlank { null },
        pointHints = flag(value("server.pointHints")),
        autoHints = flag(value("server.autoHints")),
        database = DatabaseConfig(value("database.url"), value("database.user"), value("database.password")),
        gameServerApi = value("server.gameServerApi").trimEnd('/'),
        internalToken = value("server.internalToken"),
        sessionRetentionDays = value("tracker.sessionRetentionDays").toInt().coerceAtLeast(0),
        cookieSecure = flag(value("tracker.cookieSecure")),
      )
    }
  }
}
