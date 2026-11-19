package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.eratiem.randogeddon.shared.ConnectionSession
import net.eratiem.randogeddon.shared.PlayerStatus
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.duration
import net.eratiem.randogeddon.webapp.api.SessionSource
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.clock
import net.eratiem.randogeddon.webapp.app.fill
import net.eratiem.randogeddon.webapp.ui.Avatar
import net.eratiem.randogeddon.webapp.ui.DialogCard
import net.eratiem.randogeddon.webapp.ui.DialogSection
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.Dot
import net.eratiem.randogeddon.webapp.ui.GameChip
import net.eratiem.randogeddon.webapp.ui.HoverRow
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.ProgressBar
import net.eratiem.randogeddon.webapp.ui.TwoColumns
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.scrolling

/**
 * One person at a glance: totals, their worlds (each opens the world view), open hints for their worlds, a combined
 * connection log and their recent activity.
 */
@Composable
fun PersonDialog(
  name: String,
  s: Summary,
  now: Long,
  repository: SessionSource,
  onClose: () -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val person = s.people.firstOrNull { it.name == name }
  if (person == null) {
    // e.g. session.yaml changed while it was open
    LaunchedEffect(name) { onClose() }
    return
  }
  val worlds = person.slots.mapNotNull { slot -> s.players.firstOrNull { it.slot == slot } }
  val worldNames = worlds.map { it.name }.toSet()

  // Connection logs come with the world details; reloaded when the session changes
  var sessions by remember(name) { mutableStateOf<List<Pair<String, ConnectionSession>>>(emptyList()) }
  LaunchedEffect(name, s.version) {
    sessions = worlds.flatMap { w -> repository.player(w.slot)?.sessions.orEmpty().map { w.name to it } }
      .sortedByDescending { it.second.start }
  }

  val played = worlds.sumOf { playTime(it, now) }
  val openHints = s.hints.filter { !it.found && it.receiverName in worldNames }
  val activity = s.events.asReversed().filter { e ->
    listOfNotNull(e.playerName, e.senderName, e.receiverName, e.finderName).any { it in worldNames }
  }.take(40)

  // A world opens inside this dialog (with a back button): two dialogs on top of each other don't work on the web
  var openWorld by remember(name) { mutableStateOf<Int?>(null) }
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    openWorld?.let { slot ->
      SelectionContainer { WorldDetail(slot, s.version, repository, onClose = { openWorld = null }, back = true) }
      return@Dialog
    }
    SelectionContainer {
      DialogCard(960.dp, header = {
        Avatar(person.name, 44.dp)
        Column(Modifier.weight(1f)) {
          Txt(person.name, size = 18.sp, weight = FontWeight.Bold)
          Txt(
            t.dialogs.personLine(worlds.size, person.checked, person.total, person.online),
            size = 12.5.sp, color = if (person.online) p.ok else p.muted,
          )
        }
        Txt("${t.decimal(person.percent)}%", size = 22.sp, weight = FontWeight.Bold, brush = p.brand)
        IconButton(onClose, Icon.CLOSE)
      }) { available ->
        val twoColumns = available >= 760.dp
        Column(Modifier.scrolling().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          ProgressBar((person.percent / 100).toFloat())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact(t.dialogs.played, t.duration(played))
            Fact(t.dialogs.goals, "${person.goals} / ${worlds.size}", if (person.goals > 0) p.ok else p.text)
            Fact(t.dialogs.locked, "${worlds.count { it.status == PlayerStatus.LOCKED }}")
            if (s.hintSettings.pointHints) Fact(t.dialogs.hintPoints, "${worlds.sumOf { it.hintPoints ?: 0 }}")
          }

          val left: @Composable ColumnScope.() -> Unit = {
            DialogSection(t.dialogs.slots)
            worlds.forEach { w ->
              HoverRow(onClick = { openWorld = w.slot }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  GameChip(w.game, dimmed = w.status == PlayerStatus.LOCKED)
                  Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                      Txt(w.game, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                      Pill(statusLabel(t, w), statusColor(w.status))
                    }
                    Txt(
                      t.dialogs.slotLine(w.checked, w.total, t.duration(playTime(w, now)), w.goalAt?.let { t.clock(it) }),
                      size = 11.5.sp, color = p.muted, maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    ProgressBar((w.percent / 100).toFloat(), height = 4.dp, dimmed = w.status == PlayerStatus.LOCKED)
                  }
                }
              }
            }
            if (s.hintSettings.enabled || openHints.isNotEmpty()) {
              Spacer(Modifier.height(10.dp))
              DialogSection(t.dialogs.openHints(person.name, openHints.size))
              if (openHints.isEmpty()) Txt(t.dialogs.none, size = 13.sp, color = p.muted)
              openHints.forEach { h ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) { ItemText(h.itemName, h.itemFlags, key = h.itemName.startsWith("Key to ")) }
                    HintStatus(h)
                  }
                  Txt(fill(t.dialogs.hintWhereFor, "location" to h.locationName, "finder" to h.finderName, "receiver" to h.receiverName), size = 12.sp, color = p.muted)
                }
                Divider()
              }
            }
          }
          val right: @Composable ColumnScope.() -> Unit = {
            DialogSection(t.dialogs.connections(sessions.size))
            if (sessions.isEmpty()) Txt(t.dialogs.noClientYet, size = 13.sp, color = p.muted)
            sessions.take(20).forEach { (world, c) ->
              Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(if (c.end == null) p.ok else p.muted, 7.dp)
                Column(Modifier.weight(1f)) {
                  Txt(world, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
                  Txt(
                    t.dialogs.connection(c.implicit, t.clock(c.start), c.end?.let { t.clock(it) }),
                    size = 12.sp, color = p.muted,
                  )
                }
                Txt(t.duration((c.end ?: now) - c.start), size = 12.5.sp, color = p.muted)
              }
              Divider()
            }
            Spacer(Modifier.height(10.dp))
            DialogSection(t.dialogs.recentActivity)
            if (activity.isEmpty()) Txt(t.dialogs.nothingYet, size = 13.sp, color = p.muted)
            Column(Modifier.heightIn(max = 520.dp).scrolling()) {
              activity.forEach { FeedRow(it) }
            }
          }
          TwoColumns(twoColumns, 18.dp, left, right)
        }
      }
    }
  }
}

@Composable
internal fun RowScope.Fact(label: String, value: String, color: Color = LocalPalette.current.text) {
  val p = LocalPalette.current
  Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(p.panelAlt).padding(horizontal = 12.dp, vertical = 8.dp)) {
    Txt(label, size = 11.5.sp, color = p.muted, maxLines = 1)
    Txt(value, size = 16.sp, weight = FontWeight.Bold, color = color, maxLines = 1)
  }
}

