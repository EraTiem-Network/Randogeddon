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
 * (`background`, `text`, `brandStart`, …), their mode and language, and the [font] they read it in (`null`: the
 * original; the app's page then keeps the system's). [name]: the account's name (as `/bridge/me` gives it), so the
 * bridge's regular check of its key is this one call.
 */
@Serializable
data class BridgeInfo(
  val version: String? = null,
  val downloads: List<BridgeDownload> = emptyList(),
  val light: Map<String, String> = emptyMap(),
  val dark: Map<String, String> = emptyMap(),
  val mode: ThemeMode = ThemeMode.AUTO,
  val language: String? = null,
  val font: BridgeFont? = null,
  val name: String = "",
)

/**
 * The font of the user's choice for the bridge's page: its files among the dashboard's static files ([regular], and
 * [bold] if it has one), whether it is for the headings only (by the font and their choice, [FontScope]), and
 * how large it is drawn against the system's font at the same size.
 */
@Serializable
data class BridgeFont(val regular: String, val bold: String? = null, val headingsOnly: Boolean = false, val scale: Float = 1f)

/** [choice] (as stored with the account) with [theme], as the bridge gets it. */
fun bridgeFont(theme: Theme, choice: String?, scope: FontScope): BridgeFont? =
  FontChoice.font(theme, choice)?.let { BridgeFont(it.regularFile, it.boldFile, scope.headingsOnly(it), it.scale) }

/**
 * The fingerprint of the key that signs the bridge's checksums (`bridge/signing-key.pub`), as `ssh-keygen -l` shows it:
 * the web app shows it from its own build, not from the tracker that serves the downloads and the key beside them.
 */
const val BRIDGE_SIGNING_KEY_FINGERPRINT = "SHA256:Y/hZRRTeTgoizXJ9CVzWCpIcr3RISfCy+STg3hp+v6w"

/** `GET /downloads/bridge` (no login): what the dashboard offers, for *Your connection*. */
@Serializable
data class BridgeDownloads(
  val version: String? = null,
  val downloads: List<BridgeDownload> = emptyList(),
  /** The builds' checksums are signed (`SHA256SUMS.sig`, checked against `signing-key.pub`). */
  val signed: Boolean = false,
)
