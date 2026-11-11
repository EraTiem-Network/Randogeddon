package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/**
 * One person at a glance: totals, their worlds (each opens the world view), open hints for their worlds, a combined
 * connection log and their recent activity.
 */
@Composable
fun PersonDialog(
  name: String,
  s: Summary,
  now: Long,
  repository: TrackerRepository,
  onClose: () -> Unit,
) {
  val p = LocalPalette.current
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
      WorldDetail(slot, s.version, repository, onClose = { openWorld = null }, back = true)
      return@Dialog
    }
    BoxWithConstraints(Modifier.padding(16.dp)) {
      val twoColumns = maxWidth >= 760.dp
      Column(Modifier.widthIn(max = 960.dp).fillMaxWidth().heightIn(max = maxHeight).card(p, RoundedCornerShape(20.dp))) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Avatar(person.name, 44.dp)
          Column(Modifier.weight(1f)) {
            Txt(person.name, size = 18.sp, weight = FontWeight.Bold)
            Txt(
              "${worlds.size} slots · ${person.checked} / ${person.total} checks" +
                if (person.online) " · online" else "",
              size = 12.5.sp, color = if (person.online) p.ok else p.muted,
            )
          }
          Txt("${oneDecimal(person.percent)}%", size = 22.sp, weight = FontWeight.Bold, brush = p.brand)
          IconButton(onClose, Icon.CLOSE)
        }
        Divider()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          ProgressBar((person.percent / 100).toFloat())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact("Played", duration(played))
            Fact("Goals", "${person.goals} / ${worlds.size}", if (person.goals > 0) p.ok else p.text)
            Fact("Locked", "${worlds.count { it.status == PlayerStatus.LOCKED }}")
            if (s.hintSettings.pointHints) Fact("Hint points", "${worlds.sumOf { it.hintPoints ?: 0 }}")
          }

          val left: @Composable ColumnScope.() -> Unit = {
            Section("Slots")
            worlds.forEach { w ->
              HoverRow(onClick = { openWorld = w.slot }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  GameChip(w.game, dimmed = w.status == PlayerStatus.LOCKED)
                  Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                      Txt(w.game, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                      Pill(statusLabel(w), statusColor(w.status))
                    }
                    Txt("${w.checked} / ${w.total} · ${duration(playTime(w, now))} played" +
                      (w.goalAt?.let { " · done ${Platform.clock(it)}" } ?: ""), size = 11.5.sp, color = p.muted, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    ProgressBar((w.percent / 100).toFloat(), height = 4.dp, dimmed = w.status == PlayerStatus.LOCKED)
                  }
                }
              }
            }
            if (s.hintSettings.enabled || openHints.isNotEmpty()) {
              Spacer(Modifier.height(10.dp))
              Section("Open hints for ${person.name} (${openHints.size})")
              if (openHints.isEmpty()) Txt("None.", size = 13.sp, color = p.muted)
              openHints.forEach { h ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f)) { ItemText(h.itemName, h.itemFlags, key = h.itemName.startsWith("Key to ")) }
                    HintStatus(h)
                  }
                  Txt("${h.locationName} · in ${h.finderName} · for ${h.receiverName}", size = 12.sp, color = p.muted)
                }
                Divider()
              }
            }
          }
          val right: @Composable ColumnScope.() -> Unit = {
            Section("Connections (${sessions.size})")
            if (sessions.isEmpty()) Txt("No game client seen yet.", size = 13.sp, color = p.muted)
            sessions.take(20).forEach { (world, c) ->
              Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(if (c.end == null) p.ok else p.muted, 7.dp)
                Column(Modifier.weight(1f)) {
                  Txt(world, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
                  Txt(
                    (if (c.implicit) "seen playing " else "") + Platform.clock(c.start) +
                      (c.end?.let { " – ${Platform.clock(it)}" } ?: " – now"),
                    size = 12.sp, color = p.muted,
                  )
                }
                Txt(duration((c.end ?: now) - c.start), size = 12.5.sp, color = p.muted)
              }
              Divider()
            }
            Spacer(Modifier.height(10.dp))
            Section("Recent activity")
            if (activity.isEmpty()) Txt("Nothing yet.", size = 13.sp, color = p.muted)
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
              activity.forEach { FeedRow(it) }
            }
          }
          if (twoColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
              Column(Modifier.weight(1f), content = left)
              Column(Modifier.weight(1f), content = right)
            }
          } else {
            Column(content = left)
            Column(content = right)
          }
        }
      }
    }
  }
}

@Composable
private fun RowScope.Fact(label: String, value: String, color: Color = LocalPalette.current.text) {
  val p = LocalPalette.current
  Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(p.panelAlt).padding(horizontal = 12.dp, vertical = 8.dp)) {
    Txt(label, size = 11.5.sp, color = p.muted, maxLines = 1)
    Txt(value, size = 16.sp, weight = FontWeight.Bold, color = color, maxLines = 1)
  }
}

@Composable
private fun Section(title: String) {
  Txt(title.uppercase(), size = 12.sp, color = LocalPalette.current.muted, weight = FontWeight.SemiBold,
    modifier = Modifier.padding(bottom = 8.dp))
}
