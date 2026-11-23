package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.YamlWorld
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.ui.AppPopup
import net.eratiem.randogeddon.webapp.ui.Check
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.DropdownSearch
import net.eratiem.randogeddon.webapp.ui.GameChip
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.focusRing
import net.eratiem.randogeddon.webapp.ui.hand
import net.eratiem.randogeddon.webapp.ui.justClosed
import net.eratiem.randogeddon.webapp.ui.scrolling

/**
 * One slot of the new session: its players (chips; type to search the accounts, Enter or a click picks one) and its
 * [lock] control (nothing with random locks, or a single slot). [users] are account IDs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorldRow(
  world: YamlWorld,
  users: List<String>,
  known: List<UserRef>,
  /** Hosted (its checkbox, asked once more): no players, and no picker to add them. */
  hosted: Boolean,
  onHosted: (Boolean) -> Unit,
  hostedLabel: String,
  /** A hosted Minecraft Dig slot's server run here (its checkbox, for Administrators); `null`: not offered. */
  direct: Boolean?,
  onDirect: (Boolean) -> Unit,
  /** Open to all of the session's players (its checkbox): no players named. */
  open: Boolean,
  onOpen: (Boolean) -> Unit,
  /** Why the last invitation from here failed, below the players; `null`: none did. */
  inviteProblem: String?,
  /** Invites an address from here: the new account, or `null` (why: [inviteProblem]). */
  onInvite: suspend (String) -> UserRef?,
  lock: @Composable () -> Unit,
  onUsers: (List<String>) -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var asking by remember { mutableStateOf(false) }
  Divider()
  Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    GameChip(world.game)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
          Txt(world.name, size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
          Txt("${world.game} · ${world.fileName}", size = 11.5.sp, color = p.muted, maxLines = 1)
        }
        lock()
        Check(t.sessionAdmin.openCheck, open, t.sessionAdmin.openTip) { onOpen(it) }
        Check(hostedLabel, hosted, t.sessionAdmin.hostedTip) { yes -> if (yes) asking = true else onHosted(false) }
      }
      if (asking) {
        Confirm(t.sessionAdmin.hostedQuestion, t.sessionAdmin.yesHosted, danger = false,
          onYes = { asking = false; onHosted(true) }, onCancel = { asking = false })
      }
      if (hosted && direct != null) Check(t.sessionAdmin.hostDirectly, direct) { onDirect(it) }
      if (hosted) Txt(t.sessionAdmin.hostedLine, size = 12.sp, color = p.muted)
      if (open) Txt(if (hosted) t.sessionAdmin.openHostedLine else t.sessionAdmin.openLine, size = 12.sp, color = p.muted)
      else if (hosted) Txt(t.sessionAdmin.hostedPlayersLine, size = 12.sp, color = p.muted)
      if (!open) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        users.forEach { id ->
          val user = known.firstOrNull { it.id == id }
          UserChip(user?.label ?: id, invited = user?.name == null, onRemove = { onUsers(users - id) })
        }
        PlayerPicker(
          known.filter { it.id !in users }, if (users.isEmpty()) t.sessionAdmin.addPlayer else t.sessionAdmin.addAnother,
          onPick = { if (it.id !in users) onUsers(users + it.id) }, onInvite = onInvite,
        )
      }
      if (!open) inviteProblem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    }
  }
}

/**
 * Adds a player: a dropdown of the accounts ([choices]) with a search field on top; a typed e-mail address that no
 * account has offers its invitation instead ([onInvite]: the new account, or `null`).
 */
@Composable
internal fun PlayerPicker(
  choices: List<UserRef>,
  placeholder: String,
  onPick: (UserRef) -> Unit,
  onInvite: suspend (String) -> UserRef?,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  var open by remember { mutableStateOf(false) }
  var closedAt by remember { mutableLongStateOf(0L) }
  var query by remember { mutableStateOf("") }
  var inviting by remember { mutableStateOf(false) }
  var anchorHeight by remember { mutableStateOf(0) }
  val shape = RoundedCornerShape(10.dp)
  val rowShape = RoundedCornerShape(8.dp)
  Box(Modifier.width(190.dp).onSizeChanged { anchorHeight = it.height }) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = FIELD_HEIGHT).focusRing(shape).clip(shape).background(p.panelAlt)
        .border(1.dp, p.border, shape)
        .hand().clickable(role = Role.Button) { if (!justClosed(closedAt)) { open = !open; query = "" } }
        .padding(start = 10.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Txt(placeholder, size = 12.5.sp, color = p.muted, maxLines = 1, modifier = Modifier.weight(1f))
      DrawnIcon(Icon.CHEVRON, p.muted, 14.dp)
    }
    if (open) AppPopup(
      offset = IntOffset(0, anchorHeight + 6),
      onDismissRequest = { open = false; closedAt = Platform.now() },
      properties = PopupProperties(focusable = true),
    ) {
      Column(
        Modifier.widthIn(min = 240.dp, max = 320.dp).card(p, RoundedCornerShape(12.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        DropdownSearch(query) { query = it }
        val matches = choices.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }.take(200)
        val address = EmailAddresses.normalize(query)
        Column(Modifier.heightIn(max = 260.dp).scrolling()) {
          matches.forEach { user ->
            Txt(
              user.label, size = 13.sp, maxLines = 1, color = if (user.name == null) p.muted else p.text,
              modifier = Modifier.fillMaxWidth().focusRing(rowShape, gap = (-2).dp).clip(rowShape).hand()
                .clickable(role = Role.Button) { onPick(user); open = false; query = "" }.padding(horizontal = 8.dp, vertical = 7.dp),
            )
          }
          when {
            matches.isNotEmpty() -> {}
            EmailAddresses.valid(address) -> Txt(
              if (inviting) t.sessionAdmin.inviting else t.sessionAdmin.inviteAddress(address), size = 13.sp, color = p.accent,
              modifier = Modifier.fillMaxWidth().focusRing(rowShape, gap = (-2).dp).clip(rowShape).hand().clickable(role = Role.Button) {
                if (!inviting) scope.launch {
                  inviting = true
                  onInvite(address)?.let(onPick)
                  inviting = false
                  open = false
                  query = ""
                }
              }.padding(horizontal = 8.dp, vertical = 7.dp),
            )
            else -> Txt(t.sessionAdmin.noAccountMatches, size = 12.sp, color = p.muted, modifier = Modifier.padding(8.dp))
          }
        }
      }
    }
  }
}

/** A chosen YAML: its file and its game(s), × to remove it. */
@Composable
internal fun YamlChip(fileName: String, games: List<String>, onRemove: () -> Unit) {
  val p = LocalPalette.current
  Row(
    Modifier.clip(CircleShape).background(p.accent.copy(alpha = 0.12f)).padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Txt(fileName, size = 12.5.sp, weight = FontWeight.SemiBold, color = p.accent, maxLines = 1)
    if (games.isNotEmpty()) Txt(games.joinToString(", "), size = 11.5.sp, color = p.accent.copy(alpha = 0.8f), maxLines = 1)
    RemoveCross(LocalTexts.current.sessionAdmin.removeItem(fileName), p.accent, onRemove)
  }
}

@Composable
internal fun UserChip(name: String, invited: Boolean, onRemove: () -> Unit) {
  val p = LocalPalette.current
  Row(
    Modifier.heightIn(min = FIELD_HEIGHT).clip(CircleShape).background(p.hue(name).copy(alpha = 0.15f))
      .padding(start = 12.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Txt(if (invited) LocalTexts.current.sessionAdmin.invitedName(name) else name, size = 12.5.sp, weight = FontWeight.SemiBold, color = p.hue(name), maxLines = 1)
    RemoveCross(LocalTexts.current.sessionAdmin.removeItem(name), p.hue(name), onRemove)
  }
}

/** A chip's ×: a button (Tab, Enter) named [label] for assistive tools; its ring inside the chip, which clips. */
@Composable
private fun RemoveCross(label: String, color: Color, onRemove: () -> Unit) {
  Box(
    Modifier.size(18.dp).focusRing(CircleShape, gap = (-1).dp).clip(CircleShape).hand()
      .clickable(onClickLabel = label, role = Role.Button, onClick = onRemove)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    DrawnIcon(Icon.CLOSE, color, 10.dp)
  }
}
