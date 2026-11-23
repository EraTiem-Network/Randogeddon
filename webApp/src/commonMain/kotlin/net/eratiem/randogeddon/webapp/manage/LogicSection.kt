package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.LogicProgress
import net.eratiem.randogeddon.shared.LogicStatus
import net.eratiem.randogeddon.shared.LogicView
import net.eratiem.randogeddon.webapp.api.load
import net.eratiem.randogeddon.webapp.api.post
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.ui.Dot
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.Working
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.rememberAction

/** How often the section asks how its logic is; for a session without one (it can't get one), rarely. */
private const val ASK_MS = 3_000L
private const val ASK_NONE_MS = 60_000L

/** How many lines of its log the page keeps. */
private const val MAX_LINES = 20_000

/**
 * Session [id]'s logic, which counts its slots' available checks: its state and *Build again* (its Session-Managers
 * and Administrators), its log ([administrator] only: pages and a search, newest on top). Not shown for a session that
 * can have none (a seed from elsewhere, a race, from before 0.19.3).
 */
@Composable
fun LogicSection(id: String, administrator: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current.sessionAdmin
  var progress by remember(id) { mutableStateOf<LogicProgress?>(null) }
  val log = remember(id) { mutableStateListOf<LogLine>() }
  var last by remember(id) { mutableStateOf(0L) }
  val rebuild = rememberAction()
  LaunchedEffect(id, administrator) {
    while (true) {
      load("api/sessions/$id/manage/logic", LogicView.serializer())?.let { progress = it.progress }
      // The log is Administrators' (the tracker refuses it to anyone else too)
      if (administrator && progress != null) {
        load("api/sessions/$id/manage/logic/log?since=$last", LogLines.serializer())?.let { part ->
          // Built again, its numbers may start over: its lines from the start then (asked for next time when this
          // asked beyond its end)
          val behind = part.last < last
          if (behind || (part.lines.isNotEmpty() && log.isNotEmpty() && part.lines.first().n <= log.last().n)) log.clear()
          if (behind) {
            last = 0L
            return@let
          }
          log.addAll(part.lines)
          if (log.size > MAX_LINES) log.removeRange(0, log.size - MAX_LINES)
          last = part.last
        }
      }
      delay(if (progress == null) ASK_NONE_MS else ASK_MS)
    }
  }
  val now = progress ?: return
  Section(t.logicSection, subtitle = t.logicSectionSubtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (now.status) {
          LogicStatus.STARTING -> Working(p.ok)
          LogicStatus.RUNNING -> Dot(p.ok)
          LogicStatus.STOPPED -> Dot(p.muted)
          LogicStatus.FAILED -> Dot(p.trap)
        }
        Txt(t.logicState(now.status, now.problem), size = 12.5.sp, color = if (now.status == LogicStatus.FAILED) p.trap else p.text,
          modifier = Modifier.weight(1f))
        // Not while it is being built (nor twice at once)
        if (now.status != LogicStatus.STARTING) SmallButton(t.logicRebuild, {
          rebuild.run {
            post("api/sessions/$id/manage/logic/rebuild").also {
              load("api/sessions/$id/manage/logic", LogicView.serializer())?.let { progress = it.progress }
            }
          }
        })
      }
      rebuild.problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      if (administrator && log.isNotEmpty()) SectionLog("logic", t.logicSection, LogOrder.LOGIC, log)
    }
  }
}
