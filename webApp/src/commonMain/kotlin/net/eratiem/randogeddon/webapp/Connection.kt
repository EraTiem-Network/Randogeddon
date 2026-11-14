package net.eratiem.randogeddon.webapp

import net.eratiem.randogeddon.shared.i18n.fileSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.Connection
import net.eratiem.randogeddon.shared.MySlot
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SlotFile
import net.eratiem.randogeddon.shared.Summary

/**
 * How the logged-in player joins the game: the server address and, for one of their worlds, the slot name and its
 * password. With several worlds, a searchable dropdown picks the world (by its short name, without the player's; the
 * full slot name is below it, to copy); it starts on the one that is online now, or
 * else the one played last. The password is hidden until shown; copying works either way. Below them the slot's files
 * from the seed ([files]: patches, mods), to download. [fillHeight]: the card fills the height the section is given
 * (next to the overview).
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
) {
  val p = LocalPalette.current
  val c = LocalTexts.current.connection
  var picked by remember { mutableStateOf<String?>(null) }
  val slots = connection?.slots.orEmpty()
  val worlds = summary.players.associateBy { it.name }
  val lastPlayed = slots.maxByOrNull { slot ->
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
    Column(
      Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier).card(p).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Field(c.server, connection?.server ?: "…")
      when {
        !mine -> Txt(
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
        Field(c.slot, slot.name)
        PasswordField(slot)
        files.filter { it.slotName == slot.name }.forEach { file -> FileField(sessionId, file) }
      }
    }
  }
}

@Composable
private fun PasswordField(slot: MySlot) {
  var shown by remember(slot.name) { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Label(LocalTexts.current.connection.password)
    Mono(if (shown) slot.password else "•".repeat(12), Modifier.weight(1f))
    SmallButton(if (shown) LocalTexts.current.connection.hide else LocalTexts.current.connection.show, { shown = !shown })
    SmallButton(LocalTexts.current.common.copy, { Platform.copy(slot.password) })
  }
}

/** How a slot's file is used: on the download's tip. */
/** One of the slot's files from the seed: its name and size, and the download. */
@Composable
private fun FileField(sessionId: String, file: SlotFile) {
  val t = LocalTexts.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Label(LocalTexts.current.connection.file)
    Mono(file.name, Modifier.weight(1f))
    Txt(t.fileSize(file.size), size = 12.sp, color = LocalPalette.current.muted, maxLines = 1)
    InfoTip(LocalTexts.current.connection.patchHowTo)
    SmallButton(LocalTexts.current.connection.download, { Platform.download(slotFilePath(sessionId, file.name)) })
  }
}

@Composable
private fun Field(label: String, value: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Label(label)
    Mono(value, Modifier.weight(1f))
    SmallButton(LocalTexts.current.common.copy, { Platform.copy(value) })
  }
}

/** Same width for every label, so the values line up. */
@Composable
private fun Label(text: String) {
  Txt(text, size = 12.sp, color = LocalPalette.current.muted, maxLines = 1, modifier = Modifier.width(62.dp))
}

/** Monospaced and selectable, so similar characters (l, 1, I) are told apart and can be copied by hand too. */
@Composable
private fun Mono(text: String, modifier: Modifier = Modifier) {
  SelectionContainer(modifier.padding(end = 2.dp)) {
    BasicText(text, style = TextStyle(color = LocalPalette.current.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace))
  }
}
