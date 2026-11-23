package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.GenerationProgress
import net.eratiem.randogeddon.shared.GenerationStage
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.LogView
import net.eratiem.randogeddon.webapp.ui.Mono
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.Txt

/** Below this share of one core, Generate.py does nothing; that long of it ([QUIET_FOR_MS]) is no response. */
internal const val QUIET_CPU = 5
private const val QUIET_FOR_MS = 60_000L

/** [ms] as a clock: `4:05`, `1:02:03`. */
private fun clock(ms: Long): String {
  val s = (ms / 1000).coerceAtLeast(0)
  val (h, m, sec) = Triple(s / 3600, s / 60 % 60, s % 60)
  fun two(n: Long) = n.toString().padStart(2, '0')
  return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}

/**
 * That it isn't stuck, in two pills under the bar: how long it has run since its start (each second), and what it
 * does: working (with Generate.py's share of one core: a working fill or playthrough keeps one busy), waiting for its
 * turn, or no response once Generate.py has used next to no CPU for a minute.
 */
@Composable
internal fun GenerationLiveness(progress: GenerationProgress, startedAt: Long?, quietSince: Long?) {
  val p = LocalPalette.current
  val t = LocalTexts.current.sessionAdmin
  var now by remember { mutableLongStateOf(Platform.now()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(1000)
      now = Platform.now()
    }
  }
  val quiet = quietSince?.let { now - it }?.takeIf { it >= QUIET_FOR_MS }
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    startedAt?.let { Pill(t.generationTime(clock(now - it)), p.muted) }
    when {
      progress.stage == GenerationStage.WAITING -> Pill(t.generationWaitingTurn, p.muted)
      quiet != null -> Pill(t.generationQuiet((quiet / 60_000).toInt()), p.trap)
      else -> Pill(progress.cpu?.let(t::generationWorkingCpu) ?: t.generationWorking, p.ok)
    }
  }
}

/** How many lines of a generation's output the page keeps (as many as the gateway). */
internal const val MAX_GENERATION_LOG = 20_000

/** Generate.py's output, newest on top, a page at a time. */
@Composable
internal fun GenerationLog(log: List<LogLine>) {
  val t = LocalTexts.current
  val p = LocalPalette.current
  Txt(t.sessionAdmin.generationLog, size = 12.5.sp, weight = FontWeight.SemiBold, color = p.muted)
  // What ran and the gateway's own notes stand out from Generate.py's output
  LogView(log, t.panel.nothingYet, height = 320.dp, color = { line ->
    if (line.text.startsWith("[gateway]") || line.text.startsWith("$ ")) p.accent else null
  })
}

/** A failed generation: why (the suspected games), Generate.py's output ([detail]) below in monospace. */
@Composable
internal fun GenerationError(message: String, detail: String?) {
  val p = LocalPalette.current
  if (detail == null) {
    Mono(message, color = p.trap)
  } else {
    Txt(message, size = 12.5.sp, color = p.trap)
    Mono(detail)
  }
}
