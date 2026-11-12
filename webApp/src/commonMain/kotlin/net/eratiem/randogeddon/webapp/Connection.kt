package net.eratiem.randogeddon.webapp

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
import net.eratiem.randogeddon.shared.Summary

/**
 * How the logged-in player joins the game: the server address and, for one of their worlds, the slot name and its
 * password. With several worlds, a searchable dropdown picks the world (by its short name, without the player's; the
 * full slot name is below it, to copy); it starts on the one that is online now, or
 * else the one played last. The password is hidden until shown; copying works either way. [fillHeight]: the card
 * fills the height the section is given (next to the overview).
 */
@Composable
fun ConnectionSection(
  name: String,
  connection: Connection?,
  /** [name] plays in this session (an admin may not). */
  mine: Boolean,
  summary: Summary,
  modifier: Modifier = Modifier,
  fillHeight: Boolean = false,
) {
  val p = LocalPalette.current
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
  Section("Your connection", modifier, subtitle = "Logged in as $name") {
    Column(
      Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier).card(p).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Field("Server", connection?.server ?: "…")
      when {
        !mine -> Txt("You don't play in this session.", size = 13.sp, color = p.muted)
        slot == null -> Txt("Your slots appear here once the seed is running.", size = 13.sp, color = p.muted)
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
        Field("Slot", slot.name)
        PasswordField(slot)
      }
    }
  }
}

@Composable
private fun PasswordField(slot: MySlot) {
  var shown by remember(slot.name) { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Label("Password")
    Mono(if (shown) slot.password else "•".repeat(12), Modifier.weight(1f))
    SmallButton(if (shown) "Hide" else "Show", { shown = !shown })
    SmallButton("Copy", { Platform.copy(slot.password) })
  }
}

@Composable
private fun Field(label: String, value: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Label(label)
    Mono(value, Modifier.weight(1f))
    SmallButton("Copy", { Platform.copy(value) })
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
