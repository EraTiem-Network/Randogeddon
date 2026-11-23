package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.ui.LogView
import net.eratiem.randogeddon.webapp.ui.PageButton

/** One log of the Management panel's *Logs*: its tab's [title], its [lines] (kept by its section), its colours. */
internal class PanelLog(val title: String, val order: Int, val lines: List<LogLine>, val color: ((LogLine) -> Color?)? = null)

/**
 * The logs of a session's sections in the Management panel, by key: each section puts its own here (only one its
 * viewer may read), and *Logs* shows them as tabs instead of a log in every section.
 */
internal class PanelLogs {
  private val logs = mutableStateMapOf<String, PanelLog>()

  fun put(key: String, log: PanelLog) {
    logs[key] = log
  }

  fun get(key: String): PanelLog? = logs[key]

  fun remove(key: String) {
    logs.remove(key)
  }

  /** In their order (the game server's first), then by title. */
  val all: List<Pair<String, PanelLog>> get() = logs.entries.map { it.key to it.value }.sortedWith(compareBy({ it.second.order }, { it.second.title }))
}

/** The panel's logs while its *Logs* shows them; `null` elsewhere (a section shows its log itself there). */
internal val LocalPanelLogs = staticCompositionLocalOf<PanelLogs?> { null }

/** The order of the tabs: the game server's, the spoiler log's run, the logic's, the Minecraft servers'. */
internal object LogOrder {
  const val GAME_SERVER = 0
  const val SPOILER = 1
  const val LOGIC = 2
  const val MINECRAFT = 10
}

/**
 * A section's log: a tab of the panel's *Logs* ([LocalPanelLogs]), or else shown here. Called only while its viewer
 * may read it, so nobody gets a tab they can't open.
 */
@Composable
internal fun SectionLog(key: String, title: String, order: Int, lines: List<LogLine>, height: Dp = 260.dp, color: ((LogLine) -> Color?)? = null) {
  val logs = LocalPanelLogs.current
  if (logs == null) {
    if (lines.isNotEmpty()) LogView(lines, LocalTexts.current.panel.nothingYet, height = height, color = color ?: { null })
    return
  }
  // The lines as they are now (a section may keep them in a new list each time), and gone with the section
  SideEffect { if (logs.get(key)?.let { it.lines !== lines || it.title != title } != false) logs.put(key, PanelLog(title, order, lines, color)) }
  DisposableEffect(logs, key) { onDispose { logs.remove(key) } }
}

/** *Logs*: one tab per log (the game server's first), the chosen one below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogTabs(logs: PanelLogs) {
  val t = LocalTexts.current
  val all = logs.all
  var chosen by remember { mutableStateOf<String?>(null) }
  // The one chosen, while it is there; else the first
  val shown = all.firstOrNull { it.first == chosen } ?: all.firstOrNull() ?: return
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (all.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for ((key, log) in all) PageButton(log.title, key == shown.first) { chosen = key }
    }
    LogView(shown.second.lines, t.panel.nothingYet, color = shown.second.color ?: { null })
  }
}
