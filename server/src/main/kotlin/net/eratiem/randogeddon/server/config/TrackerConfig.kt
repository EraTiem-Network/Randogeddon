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
  /** Where the dashboard is reached, for the links in mails (invitations, address confirmations). */
  val publicUrl: String = "http://localhost:8000",
  val mail: MailConfig = MailConfig(),
  /** Encrypts the TOTP secrets: base64 of 32 bytes; empty: a key file in [stateDir], made once. */
  val totpKey: String = "",
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
        publicUrl = value("tracker.publicUrl").trimEnd('/'),
        totpKey = value("tracker.totpKey"),
        mail = MailConfig(
          host = value("mail.host").trim(),
          address = value("mail.address").trim(),
          port = value("mail.port").toInt(),
          security = MailSecurity.of(value("mail.security")),
          user = value("mail.user"),
          password = value("mail.password"),
          from = value("mail.from"),
        ),
      )
    }
  }
}

/** How the connection to the mail server is secured. */
enum class MailSecurity {
  /** Plain connection upgraded with STARTTLS (port 587); refused if the server doesn't offer it. */
  STARTTLS,
  /** TLS from the start (port 465). */
  TLS,
  /** Unencrypted: only for a local test server (Mailpit). */
  NONE;

  companion object {
    fun of(value: String): MailSecurity = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: STARTTLS
  }
}

/**
 * The SMTP server the mails go out through; without a [host], they are only written to the log. [address]: where to
 * connect instead (e.g. its internal IP), the certificate is still checked against [host].
 */
data class MailConfig(
  val host: String = "",
  val address: String = "",
  val port: Int = 587,
  val security: MailSecurity = MailSecurity.STARTTLS,
  val user: String = "",
  val password: String = "",
  val from: String = "randogeddon@example.net",
)
