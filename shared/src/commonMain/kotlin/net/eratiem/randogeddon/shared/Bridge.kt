package net.eratiem.randogeddon.shared

import kotlinx.serialization.Serializable

/**
 * `POST /api/account/bridge-keys`: a new key for Randogeddon Bridge, shown once. It is a login of its own kind
 * (30 days, like the others) that reaches only `/bridge/…`: who the user is, their sessions, a session's connection.
 */
@Serializable
data class BridgeKey(val key: String, val expiresAt: Long)

/** `GET /api/account/bridge-keys`: one of the user's keys, by [id] (for ending it); the times in epoch milliseconds. */
@Serializable
data class BridgeKeyView(val id: String, val createdAt: Long, val expiresAt: Long, val lastUsedAt: Long? = null)

/** One bridge download: [os] `windows`, `linux` or `macos`, [arch] `amd64` or `arm64`, at `/downloads/bridge/<file>`. */
@Serializable
data class BridgeDownload(val os: String, val arch: String, val file: String, val bytes: Long)

/**
 * `GET /bridge/info` (with a key): the bridge the dashboard offers ([version], `null`: none; [downloads]), and how the
 * user sees Randogeddon, so the app's page looks the same: their theme's two palettes as CSS colours by name
 * (`background`, `text`, `brandStart`, …), their mode and language.
 */
@Serializable
data class BridgeInfo(
  val version: String? = null,
  val downloads: List<BridgeDownload> = emptyList(),
  val light: Map<String, String> = emptyMap(),
  val dark: Map<String, String> = emptyMap(),
  val mode: ThemeMode = ThemeMode.AUTO,
  val language: String? = null,
)

/** `GET /downloads/bridge` (no login): what the dashboard offers, for *Your connection*. */
@Serializable
data class BridgeDownloads(
  val version: String? = null,
  val downloads: List<BridgeDownload> = emptyList(),
  /** The builds' checksums are signed (`SHA256SUMS.sig`, checked against `signing-key.pub`). */
  val signed: Boolean = false,
)
