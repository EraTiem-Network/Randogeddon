package net.eratiem.randogeddon.server.config

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
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
import net.eratiem.randogeddon.shared.BridgeDownload
import net.eratiem.randogeddon.shared.BridgeDownloads
import net.eratiem.randogeddon.shared.BridgeInfo
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.ThemeColors
import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.shared.palettes

/** A build of the bridge: `randogeddon-bridge-<os>-<arch>`, `.exe` on Windows. */
private val BRIDGE_FILE = Regex("randogeddon-bridge-(windows|linux|macos)-(amd64|arm64)(\\.exe)?")

/** The builds in [dir] and their version (its `VERSION` file); none if it isn't there (a build without the bridge). */
fun bridgeDownloads(dir: Path): BridgeDownloads {
  val files = runCatching { dir.listDirectoryEntries() }.getOrDefault(emptyList())
    .filter { it.isRegularFile() }
    .mapNotNull { path -> BRIDGE_FILE.matchEntire(path.name)?.let { BridgeDownload(it.groupValues[1], it.groupValues[2], path.name, path.fileSize()) } }
    .sortedWith(compareBy({ it.os }, { it.arch }))
  val version = runCatching { dir.resolve("VERSION").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
  return BridgeDownloads(version.takeIf { files.isNotEmpty() }, files, signed = dir.resolve("SHA256SUMS.sig").isRegularFile())
}

/** What is served beside the builds to check them: the checksums, their signature and the key it is checked with. */
private val CHECK_FILES = setOf("SHA256SUMS", "SHA256SUMS.sig", "signing-key.pub")

/**
 * The checksums of the builds in [dir], as `sha256sum` writes and checks them (`<hash>  <file>` per line): the build's
 * own file (the one its signature is of), or made here for a build without one.
 */
fun bridgeChecksums(dir: Path): String = runCatching { dir.resolve("SHA256SUMS").readText() }.getOrNull()?.takeIf { it.isNotBlank() }
  ?: bridgeDownloads(dir).downloads.joinToString("") { build ->
  val hash = MessageDigest.getInstance("SHA-256").digest(dir.resolve(build.file).readBytes()).joinToString("") { "%02x".format(it) }
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
 * login) and `.../{file}`; with a key ([RequireBridgeKey]) `GET /bridge/info` (the version offered, the user's colours,
 * mode and language), `/bridge/me`, `/bridge/sessions`, `/bridge/sessions/{id}/connection`, and `DELETE /bridge/key`
 * (the app's logout).
 */
fun Route.bridgeRoutes(
  config: TrackerConfig,
  keys: BridgeKeys,
  logins: LoginCheck,
  sessions: LiveSessions,
  list: SessionList,
  slotPasswords: SlotPasswords,
) {
  // The builds don't change while the tracker runs: hashed once, when first asked for
  val checksums = lazy { bridgeChecksums(config.bridgeDir) }
  route("/downloads/bridge") {
    get { call.respond(bridgeDownloads(config.bridgeDir)) }
    // To check a download against: sha256sum -c SHA256SUMS; and that the checksums are ours: their signature
    // (SHA256SUMS.sig, if the build was signed) against signing-key.pub, with ssh-keygen -Y verify
    get("/SHA256SUMS") {
      call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "SHA256SUMS").toString())
      call.respondText(checksums.value, ContentType.Text.Plain)
    }
    get("/{file}") {
      val name = call.parameters["file"].orEmpty()
      val file = config.bridgeDir.resolve(name)
      if ((!BRIDGE_FILE.matches(name) && name !in CHECK_FILES) || !file.isRegularFile()) return@get call.refuse(HttpStatusCode.NotFound, Refusal(Problem.NO_FILE))
      call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
      // The signature and the key are text; by their endings they would be something else (.pub: a Publisher file)
      if (name in CHECK_FILES) call.respondText(file.readText(), ContentType.Text.Plain) else call.respondFile(file.toFile())
    }
  }
  route("/bridge") {
    install(RequireBridgeKey) { userOf = keys::userOf }
    get("/info") {
      val user = call.loggedIn.user
      val (light, dark) = palettes(Theme.of(user.theme) ?: Theme.DEFAULT)
      val offered = bridgeDownloads(config.bridgeDir)
      call.respond(
        BridgeInfo(
          offered.version, offered.downloads, light.css(), dark.css(), ThemeMode.of(user.themeMode) ?: ThemeMode.AUTO,
          Language.of(user.language)?.code,
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
