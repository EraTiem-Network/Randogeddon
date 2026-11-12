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
  /** The tracker's own files: each session's history and, once it finished, its archive (`sessions/<id>/`). */
  val stateDir: Path,
  /** The gateway's data directory (read-only): each session's room.json in `sessions/<id>/`. */
  val dataDir: Path,
  /**
   * Where game clients connect to a session, shown to its players with their slot passwords; `{id}` is the session's
   * ID, e.g. `{id}.randogeddon.eratiem.net:443`.
   */
  val publicAddress: String,
  /** The host the sessions' MultiServers are reached on (the gateway runs them, each on its own port). */
  val gatewayHost: String,
  val database: DatabaseConfig,
  /** The gateway's internal API, which runs the game servers (admin panel). */
  val gameServerApi: String,
  val internalToken: String,
  /** Past sessions are deleted this many days after they finished (unless kept); 0: never. */
  val sessionRetentionDays: Int = 90,
  /** The login cookie is sent over HTTPS only; off just for a demo over plain HTTP in the local network. */
  val cookieSecure: Boolean = true,
) {
  /** Where the players of session [id] connect. */
  fun addressOf(id: String): String = publicAddress.replace("{id}", id)

  companion object {
    fun from(config: ApplicationConfig): TrackerConfig {
      fun value(key: String) = config.property("randogeddon.$key").getString()
      return TrackerConfig(
        port = value("tracker.port").toInt(),
        title = value("tracker.title"),
        webDir = Path.of(value("tracker.webDir")),
        stateDir = Path.of(value("tracker.stateDir")),
        dataDir = Path.of(value("tracker.dataDir")),
        publicAddress = value("tracker.publicAddress"),
        gatewayHost = value("server.gatewayHost"),
        database = DatabaseConfig(value("database.url"), value("database.user"), value("database.password")),
        gameServerApi = value("server.gameServerApi").trimEnd('/'),
        internalToken = value("server.internalToken"),
        sessionRetentionDays = value("tracker.sessionRetentionDays").toInt().coerceAtLeast(0),
        cookieSecure = flag(value("tracker.cookieSecure")),
      )
    }
  }
}
