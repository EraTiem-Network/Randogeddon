package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.BridgeDownloads
import net.eratiem.randogeddon.shared.BridgeKey
import net.eratiem.randogeddon.shared.Connection
import net.eratiem.randogeddon.shared.MySlot
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SlotFile
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.i18n.fileSize
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.date
import net.eratiem.randogeddon.webapp.ui.AppPopup
import net.eratiem.randogeddon.webapp.ui.GameChip
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Mono
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.SearchableSelect
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.SmallHeading
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.hand

/**
 * How the logged-in player joins the game: the server address and, for one of their worlds, the slot name and its
 * password. With several worlds, a searchable dropdown picks the world (by its short name, without the player's; the
 * full slot name is below it, to copy); it starts on the one that is online now, or
 * else the one played last. The password is hidden until shown; copying works either way. For a player then the slot's
 * files from the seed ([files]: patches, mods), to download (or that it has none), and Randogeddon Bridge, for games
 * without TLS (a key for it, its downloads): beside the rest once the card is wide enough, else below it.
 * [fillHeight]: the card fills the height the section is given (next to the overview).
 */
@Composable
fun ConnectionSection(
  name: String,
  connection: Connection?,
  /** The session, for the downloads. */
  sessionId: String,
  /** The seed's files of [name]'s slots. */
  files: List<SlotFile>,
  /** [name] plays in this session (an Administrator, support or a watcher doesn't). */
  mine: Boolean,
  /** What [name] is in it; `null`: an Administrator without a part. */
  role: SessionRole?,
  summary: Summary,
  modifier: Modifier = Modifier,
  fillHeight: Boolean = false,
  /** The slot's files and the bridge beside the rest (the dashboard knows the width: no measuring here, as the row of
   * the tiles measures its children's height, which a layout that depends on its width can't answer). */
  twoColumns: Boolean = false,
) {
  val p = LocalPalette.current
  val c = LocalTexts.current.connection
  var picked by remember { mutableStateOf<String?>(null) }
  val slots = connection?.slots.orEmpty()
  val worlds = summary.players.associateBy { it.name }
  // The one played last of one's own slots (a hosted one only if there is nothing else)
  val lastPlayed = (slots.filter { !it.hosted }.ifEmpty { slots }).maxByOrNull { slot ->
    val world = worlds[slot.name]
    when {
      world == null -> -1
      world.online -> Long.MAX_VALUE
      else -> world.lastActivity ?: 0
    }
  }
  val slot = slots.firstOrNull { it.name == picked } ?: lastPlayed
  Section(
    c.title, modifier, subtitle = c.loggedInAs(name),
    actions = {
      when (role) {
        SessionRole.WATCHER -> Pill(c.watching, p.muted)
        SessionRole.SUPPORT -> Pill(c.support, p.useful)
        SessionRole.MANAGER -> Pill(c.sessionManager, p.accent)
        else -> {}
      }
    },
  ) {
    // The server and the slot; for a player also the slot's files and the bridge, beside them where there is room
    val connect: @Composable () -> Unit = {
      ServerField(c.server, connection?.server ?: "…")
      when {
        // Someone who doesn't play still gets the hosted slots (a Session-Manager sets their servers up)
        !mine && slots.none { it.hosted } -> Txt(
          when (role) {
            SessionRole.WATCHER -> c.watcherNote
            SessionRole.SUPPORT -> c.supportNote
            else -> c.notPlaying
          },
          size = 13.sp, color = p.muted,
        )
        slot == null -> Txt(c.slotsAppear, size = 13.sp, color = p.muted)
        slots.size > 1 -> SearchableSelect(
          slots.map { Triple(it.name, shortSlotName(it.name, name), it.game) }, slot.name, { picked = it },
          Modifier.fillMaxWidth(),
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          GameChip(slot.game, size = 24.dp)
          Txt(slot.game, size = 13.sp, color = p.muted, maxLines = 1)
        }
      }
      if (slot != null) {
        // A hosted slot: its players see its patch files; its Session-Managers (with its password) all of it
        when {
          slot.locked -> Txt(c.lockedNote, size = 12.5.sp, color = p.warn)
          slot.hosted -> Txt(if (slot.password.isEmpty()) c.hostedNote else c.hostedManagerNote, size = 12.5.sp, color = p.muted)
        }
        if (!slot.locked && (!slot.hosted || slot.password.isNotEmpty())) {
          Field(c.slot, slot.name)
          PasswordField(slot)
        }
        // The slot's files belong to it; a locked slot's wait for its key, like its password
        if ((mine || slot.hosted) && !slot.locked) {
          SmallHeading(c.patchFiles, Modifier.padding(top = 2.dp))
          val own = files.filter { it.slotName == slot.name }
          if (own.isEmpty()) Txt(c.noPatchFiles, size = 12.5.sp, color = p.muted)
          own.forEach { file -> FileField(sessionId, file) }
        }
      }
    }
    Box(Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier).card(p).padding(16.dp)) {
      if (mine && twoColumns && BridgeFold.open) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { connect() }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { BridgePart() }
        }
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          connect()
          if (mine) BridgePart()
        }
      }
    }
  }
}

/** From this width on, *Your connection* puts the bridge beside the server and the slot. */
internal val CONNECTION_TWO_COLUMNS = 640.dp

private const val BRIDGE_KEY = "ap-bridge"

/**
 * Whether the bridge is open in *Your connection* (remembered in this browser): folded, it is just its heading, the card
 * is one narrow column again, and the dashboard gives the tiles beside it the room.
 */
internal object BridgeFold {
  var open: Boolean by mutableStateOf(Platform.loadSetting(BRIDGE_KEY) != "folded")
    private set

  fun toggle() {
    open = !open
    Platform.saveSetting(BRIDGE_KEY, if (open) null else "folded")
  }

  /** Back to the default, open (without a login). */
  fun reset() {
    open = true
    Platform.saveSetting(BRIDGE_KEY, null)
  }
}

/**
 * Randogeddon Bridge: its heading with its ⓘ (what it is, what it does, how to use it, starting it on each system),
 * *Create key* and the fold button; open, the new key (shown once, like a password, until it is closed) and the
 * downloads (once the dashboard offers them), a row per system.
 */
@Composable
private fun BridgePart() {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val c = t.connection
  val scope = rememberCoroutineScope()
  var offered by remember { mutableStateOf<BridgeDownloads?>(null) }
  var key by remember { mutableStateOf<BridgeKey?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    (call("GET", "downloads/bridge", null, BridgeDownloads.serializer()) as? Answer.Ok)
      ?.let { offered = it.value }
  }
  val open = BridgeFold.open
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Txt(c.bridge, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
    InfoTip(
      "",
      sections = listOf(
        c.bridgeWhatTitle to c.bridgeWhat,
        c.bridgeDoesTitle to c.bridgeDoes,
        c.bridgeHowTitle to c.bridgeHow,
        "Windows" to c.bridgeWindows,
        "macOS" to c.bridgeMacos,
        "Linux" to c.bridgeLinux,
      ),
    )
    Spacer(Modifier.weight(1f))
    // Folded or open: a chevron, pointing where the part goes
    Tip(if (open) c.foldBridge else c.openBridge) {
      IconButton({ BridgeFold.toggle() }, Icon.CHEVRON, Modifier.size(28.dp).rotate(if (open) 180f else 0f))
    }
  }
  if (!open) return
  // A key: a row of its own
  Row(verticalAlignment = Alignment.CenterVertically) {
    SmallButton(c.makeKey, {
      scope.launch {
        when (val r = call("POST", "api/account/bridge-keys", null, BridgeKey.serializer())) {
          is Answer.Ok -> { key = r.value; problem = null }
          is Answer.Failed -> problem = r.message
        }
      }
    })
  }
  key?.let { made -> KeyField(made) { key = null } }
  problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
  offered?.downloads?.takeIf { it.isNotEmpty() }?.let { downloads ->
    SmallHeading(c.downloads(offered?.version.orEmpty()), Modifier.padding(top = 2.dp))
    // Windows, macOS, Linux; each with its architectures, x86 first
    SYSTEMS.forEach { os ->
      val builds = downloads.filter { it.os == os }.sortedBy { it.arch != "amd64" }
      if (builds.isEmpty()) return@forEach
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(systemName(os))
        builds.forEach { d -> SmallButton(archName(d.os, d.arch), { Platform.download("downloads/bridge/${d.file}") }) }
      }
    }
    // To check a download: the builds' checksums, and (if this build was signed) their signature and its key
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      SmallButton(c.checksums, { Platform.download("downloads/bridge/SHA256SUMS") })
      if (offered?.signed == true) {
        SmallButton(c.signature, { Platform.download("downloads/bridge/SHA256SUMS.sig") })
        SmallButton(c.signingKey, { Platform.download("downloads/bridge/signing-key.pub") })
      }
    }
  }
}

/** A new bridge key, shown only this once: hidden like a password until shown, to copy; closed by its ×. */
@Composable
private fun KeyField(made: BridgeKey, onClose: () -> Unit) {
  val t = LocalTexts.current
  val c = t.connection
  var shown by remember(made.key) { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    ReadOnlyField(if (shown) made.key else "•".repeat(12), Modifier.weight(1f)) {
      SmallButton(if (shown) c.hide else c.show, { shown = !shown })
      SmallButton(t.common.copy, { Platform.copy(made.key) })
    }
    IconButton(onClose, Icon.CLOSE, Modifier.size(26.dp))
  }
  Txt(c.keyMade(t.date(made.expiresAt)), size = 12.5.sp, color = LocalPalette.current.muted)
}

@Composable
private fun PasswordField(slot: MySlot) {
  var shown by remember(slot.name) { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Label(LocalTexts.current.connection.password)
    ReadOnlyField(if (shown) slot.password else "•".repeat(12), Modifier.weight(1f)) {
      SmallButton(if (shown) LocalTexts.current.connection.hide else LocalTexts.current.connection.show, { shown = !shown })
      SmallButton(LocalTexts.current.common.copy, { Platform.copy(slot.password) })
    }
  }
}

/** How a slot's file is used: on the download's tip. */
/** One of the slot's files from the seed: its name and size, and the download. */
@Composable
private fun FileField(sessionId: String, file: SlotFile) {
  val t = LocalTexts.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Mono(file.name, Modifier.weight(1f).padding(end = 2.dp), size = 13.sp)
    Txt(t.fileSize(file.size), size = 12.sp, color = LocalPalette.current.muted, maxLines = 1)
    InfoTip(LocalTexts.current.connection.patchHowTo)
    SmallButton(LocalTexts.current.connection.download, { Platform.download(slotFilePath(sessionId, file.name)) })
  }
}

/** A download's system, the same in every language: `Windows`, `macOS (Apple)`, `Linux (ARM)`, … */
private val SYSTEMS = listOf("windows", "macos", "linux")

private fun systemName(os: String) = when (os) {
  "windows" -> "Windows"
  "macos" -> "macOS"
  else -> "Linux"
}

/** macOS by what Apple calls its processors; the others by their architecture. */
private fun archName(os: String, arch: String) = when {
  os == "macos" -> if (arch == "arm64") "Apple Silicon" else "Intel"
  arch == "arm64" -> "ARM64"
  else -> "x86_64"
}

@Composable
private fun Field(label: String, value: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Label(label)
    ReadOnlyField(value, Modifier.weight(1f)) { SmallButton(LocalTexts.current.common.copy, { Platform.copy(value) }) }
  }
}

/**
 * The server: copied as shown (`host:port`), its host, its port, or as a `wss://` address, picked from the menu of its
 * copy button. A local address with its scheme and path (`ws://localhost:38281/<id>`) is taken apart the same way.
 */
@Composable
private fun ServerField(label: String, server: String) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val c = t.connection
  var menu by remember { mutableStateOf(false) }
  val parts = serverParts(server)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Label(label)
    ReadOnlyField(server, Modifier.weight(1f)) {
      Box {
        SmallButton(t.common.copy + " ▾", { menu = !menu })
        if (menu) AppPopup(offset = IntOffset(0, with(LocalDensity.current) { 26.dp.roundToPx() }), onDismissRequest = { menu = false }) {
          Column(Modifier.card(p).padding(4.dp)) {
            listOf(c.copyAsShown to server, c.copyHost to parts.host, c.copyPort to parts.port, c.copyWss to parts.wss).forEach { (what, text) ->
              Row(
                Modifier.clip(RoundedCornerShape(6.dp)).hand().clickable { Platform.copy(text); menu = false }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Txt(what, size = 12.5.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.width(110.dp))
                Mono(text, size = 12.sp)
              }
            }
          }
        }
      }
    }
  }
}

/** A server's address in its parts: its host, its port (443 for `wss`), and as a `wss://` (or its own `ws://`) address. */
internal data class ServerParts(val host: String, val port: String, val wss: String)

internal fun serverParts(server: String): ServerParts {
  val scheme = server.substringBefore("://", "").takeIf { "://" in server }
  val rest = server.substringAfter("://")
  val hostPort = rest.substringBefore('/')
  val host = hostPort.substringBefore(':')
  val port = hostPort.substringAfter(':', "").ifEmpty { if (scheme == "ws") "80" else "443" }
  return ServerParts(host, port, if (scheme != null) server else "wss://$server")
}

/** A value shown like a text field that can't be changed, its buttons inside it at the right. */
@Composable
private fun ReadOnlyField(value: String, modifier: Modifier = Modifier, buttons: @Composable RowScope.() -> Unit) {
  val p = LocalPalette.current
  Row(
    modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(10.dp)).background(p.panelAlt).border(1.dp, p.border, RoundedCornerShape(10.dp))
      .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Mono(value, Modifier.weight(1f), size = 13.sp)
    buttons()
  }
}

/** Same width for every label, so the values line up. */
@Composable
private fun Label(text: String) {
  Txt(text, size = 12.sp, color = LocalPalette.current.muted, maxLines = 1, modifier = Modifier.width(62.dp))
}

