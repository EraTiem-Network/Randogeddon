package net.eratiem.randogeddon.server.config

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import net.eratiem.randogeddon.server.account.BridgeKeys
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.util.hex
import net.eratiem.randogeddon.shared.BridgeDownload
import net.eratiem.randogeddon.shared.BridgeDownloads
import net.eratiem.randogeddon.shared.BridgeInfo
import net.eratiem.randogeddon.shared.FontScope
import net.eratiem.randogeddon.shared.bridgeFont
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.ThemeColors
import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.shared.palettes

/**
 * A build of the bridge: `randogeddon-bridge-<version>-<os>-<arch>`, `.exe` on Windows (the version since 0.19.7, so
 * a downloaded file says which one it is; `dev` for a build without one). Without the version it is the name a
 * bridge from before asks for when it updates: the build for that system is served for it.
 */
private val BRIDGE_FILE = Regex("randogeddon-bridge-(?:(?:[0-9][0-9A-Za-z.]*(?:-SNAPSHOT)?|dev)-)?(windows|linux|macos)-(amd64|arm64)(\\.exe)?")

/** The builds in [dir] and their version (its `VERSION` file); none if it isn't there (a build without the bridge). */
fun bridgeDownloads(dir: Path): BridgeDownloads {
  val files = runCatching { dir.listDirectoryEntries() }.getOrDefault(emptyList())
    .filter { it.isRegularFile() }
    .mapNotNull { path -> BRIDGE_FILE.matchEntire(path.name)?.let { BridgeDownload(it.groupValues[1], it.groupValues[2], path.name, path.fileSize()) } }
    .sortedWith(compareBy({ it.os }, { it.arch }))
  val version = runCatching { dir.resolve("VERSION").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
  return BridgeDownloads(version.takeIf { files.isNotEmpty() }, files, signed = dir.resolve("SHA256SUMS.sig").isRegularFile())
}

/**
 * The build served for the file name [asked]: the build of that name, or for one asked for by another name than it has
 * here (without its version, or with another one: a bridge from before that updates) the build for the same system;
 * `null`: not a build's name, or none for its system.
 */
fun BridgeDownloads.buildFor(asked: String): BridgeDownload? {
  val match = BRIDGE_FILE.matchEntire(asked) ?: return null
  return downloads.firstOrNull { it.file == asked }
    ?: downloads.firstOrNull { it.os == match.groupValues[1] && it.arch == match.groupValues[2] }
}

/** What is served beside the builds to check them: the checksums, their signature and the key it is checked with. */
private val CHECK_FILES = setOf("SHA256SUMS", "SHA256SUMS.sig", "signing-key.pub")

/**
 * The checksums of the builds in [dir], as `sha256sum` writes and checks them (`<hash>  <file>` per line): the build's
 * own file (the one its signature is of), or made here for a build without one.
 */
fun bridgeChecksums(dir: Path): String = runCatching { dir.resolve("SHA256SUMS").readText() }.getOrNull()?.takeIf { it.isNotBlank() }
  ?: bridgeDownloads(dir).downloads.joinToString("") { build ->
  val hash = MessageDigest.getInstance("SHA-256").digest(dir.resolve(build.file).readBytes()).hex()
  "$hash  ${build.file}\n"
}

/** A palette as CSS colours by name, for the bridge's page: `#rrggbb`, or `#rrggbbaa` where it is translucent. */
fun ThemeColors.css(): Map<String, String> {
  fun hex(argb: Long): String {
    val a = (argb shr 24) and 0xFF
    val rgb = "%06x".format(argb and 0xFFFFFF)
    return if (a == 0xFFL) "#$rgb" else "#$rgb${"%02x".format(a)}"
  }
  return mapOf(
    "background" to background, "panel" to panel, "panelAlt" to panelAlt, "border" to border, "text" to text,
    "muted" to muted, "brandStart" to brandStart, "brandEnd" to brandEnd, "onBrand" to onBrand, "ok" to ok,
    "warn" to warn, "trap" to trap, "shadow" to shadow, "liveDot" to liveDot, "offlineDot" to offlineDot,
  ).mapValues { (_, v) -> hex(v) }
}

/**
 * Randogeddon Bridge, outside `/api` (whose cookie login doesn't take a key): `GET /downloads/bridge` (the builds, no
 * login) and `.../{file}`; with a key ([RequireBridgeKey]) `GET /bridge/info` (the version offered, the user's name,
 * colours, mode and language: the bridge's regular check), `/bridge/me`, `/bridge/sessions`,
 * `/bridge/sessions/{id}/connection`, and `DELETE /bridge/key` (the app's logout).
 */
fun Route.bridgeRoutes(
  config: TrackerConfig,
  keys: BridgeKeys,
  logins: LoginCheck,
  sessions: LiveSessions,
  list: SessionList,
  slotPasswords: SlotPasswords,
) {
  // The builds don't change while the tracker runs: listed and hashed once, when first asked for
  val downloads = lazy { bridgeDownloads(config.bridgeDir) }
  val checksums = lazy { bridgeChecksums(config.bridgeDir) }
  route("/downloads/bridge") {
    get { call.respond(downloads.value) }
    // To check a download against: sha256sum -c SHA256SUMS; and that the checksums are ours: their signature
    // (SHA256SUMS.sig, if the build was signed) against signing-key.pub, with ssh-keygen -Y verify
    get("/SHA256SUMS") {
      call.attachment("SHA256SUMS")
      call.respondText(checksums.value, ContentType.Text.Plain)
    }
    get("/{file}") {
      val asked = call.parameters["file"].orEmpty()
      val name = downloads.value.buildFor(asked)?.file ?: asked.takeIf { it in CHECK_FILES }
      val file = name?.let { config.bridgeDir.resolve(it) }?.takeIf { it.isRegularFile() }
      if (name == null || file == null) return@get call.refuse(HttpStatusCode.NotFound, Refusal(Problem.NO_FILE))
      call.attachment(name)
      // The signature and the key are text; by their endings they would be something else (.pub: a Publisher file)
      if (name in CHECK_FILES) call.respondText(file.readText(), ContentType.Text.Plain) else call.respondFile(file.toFile())
    }
  }
  route("/bridge") {
    install(RequireBridgeKey) { userOf = keys::userOf }
    get("/info") {
      val user = call.loggedIn.user
      val theme = Theme.of(user.theme) ?: Theme.DEFAULT
      val (light, dark) = palettes(theme)
      val offered = downloads.value
      call.respond(
        BridgeInfo(
          offered.version, offered.downloads, light.css(), dark.css(), ThemeMode.of(user.themeMode) ?: ThemeMode.AUTO,
          Language.of(user.language)?.code, bridgeFont(theme, user.font, FontScope.of(user.fontScope)), name = user.name,
        )
      )
    }
    meRoute(list)
    // A key never acts as an Administrator: the sessions its user has a part in
    get("/sessions") { call.respond(list.visibleTo(call.actor)) }
    route("/sessions/{id}") {
      install(RequireSessionMember) { this.logins = logins }
      connectionRoute(config, sessions, slotPasswords)
    }
    delete("/key") {
      call.bridgeKey?.let { keys.logout(call.loggedIn.user, it) }
      call.respond(HttpStatusCode.NoContent)
    }
  }
}
