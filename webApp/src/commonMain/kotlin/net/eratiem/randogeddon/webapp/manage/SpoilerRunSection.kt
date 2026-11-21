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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.SpoilerProgress
import net.eratiem.randogeddon.shared.SpoilerRunView
import net.eratiem.randogeddon.webapp.api.load
import net.eratiem.randogeddon.webapp.api.post
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.LogView
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.Working
import net.eratiem.randogeddon.webapp.ui.card

/** How often the section asks how far the spoiler log is. */
private const val ASK_MS = 3_000L

/** How many lines of a run's log the page keeps. */
private const val MAX_LINES = 20_000

/**
 * Session [id]'s spoiler log as the gateway completes it after the seed: its level, the run going now (with
 * Generate.py's share of a core) or that it waits for its turn, why it stopped, and that run's log (newest on top).
 * [base]: `manage` (Session-Managers, Administrators) or `support`. Not shown once it is complete (or from before).
 */
@Composable
fun SpoilerRunSection(id: String, base: String) {
  val p = LocalPalette.current
  val t = LocalTexts.current.sessionAdmin
  var progress by remember(id) { mutableStateOf<SpoilerProgress?>(null) }
  var known by remember(id) { mutableStateOf(false) }
  val log = remember(id) { mutableStateListOf<LogLine>() }
  var last by remember(id) { mutableStateOf(0L) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(id) {
    while (true) {
      load("api/sessions/$id/$base/spoiler-run", SpoilerRunView.serializer())?.let { progress = it.progress; known = true }
      if (progress != null) {
        load("api/sessions/$id/$base/spoiler-run/log?since=$last", LogLines.serializer())?.let { part ->
          // A new run's numbers start again
          if (part.lines.isNotEmpty() && log.isNotEmpty() && part.lines.first().n <= log.last().n) log.clear()
          log.addAll(part.lines)
          if (log.size > MAX_LINES) log.removeRange(0, log.size - MAX_LINES)
          last = part.last
        }
      }
      delay(ASK_MS)
    }
  }
  val now = progress
  if (!known || now == null) return
  Section(t.spoilerSection, subtitle = t.spoilerSectionSubtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Txt(t.spoilerLevel(now.level), size = 13.sp)
      val stopped = now.stopped
      when {
        stopped != null -> {
          Txt(t.spoilerStopped(now.level, stopped), size = 12.5.sp, color = p.trap)
          // Support looks on; a Session-Manager or an Administrator tries it again (after a fix)
          if (base == "manage") SmallButton(t.spoilerRetry, {
            scope.launch {
              post("api/sessions/$id/manage/spoiler-run/retry")
              load("api/sessions/$id/$base/spoiler-run", SpoilerRunView.serializer())?.let { progress = it.progress }
            }
          })
        }
        now.paused -> Txt(t.spoilerPaused, size = 12.5.sp, color = p.muted)
        now.running != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Working(p.ok)
          Txt(t.spoilerWorking(now.running!!), size = 12.5.sp)
          Pill(now.cpu?.let(t::generationWorkingCpu) ?: t.generationWorking, p.ok)
        }
        else -> Txt(t.spoilerWaiting, size = 12.5.sp, color = p.muted)
      }
      if (log.isNotEmpty()) LogView(log, LocalTexts.current.panel.nothingYet, height = 260.dp)
    }
  }
}
