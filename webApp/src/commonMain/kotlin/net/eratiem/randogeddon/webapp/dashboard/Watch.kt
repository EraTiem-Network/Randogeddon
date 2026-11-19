package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.api.WatchRepository
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.ui.PageFrame
import net.eratiem.randogeddon.webapp.ui.rememberNow
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.RandogeddonTheme
import net.eratiem.randogeddon.webapp.ui.Themes
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card

/**
 * The page of a share link: the session's dashboard, read-only and without a login (for a stream): its progress,
 * tiles, players, hints and the live feed below them.
 */
@Composable
fun WatchApp(token: String) {
  val scope = rememberCoroutineScope()
  val repository = remember(token) { WatchRepository(scope, token) }
  DisposableEffect(repository) {
    // A login form left open by the tracker doesn't belong here
    Platform.hideLogin()
    repository.start()
    onDispose { repository.stop() }
  }
  val summary by repository.summary.collectAsState()
  val streamOpen by repository.streamOpen.collectAsState()
  val gone by repository.gone.collectAsState()
  val now = rememberNow(summary?.version)
  var detailSlot by remember { mutableStateOf<Int?>(null) }

  // No account here: the theme last used in this browser
  RandogeddonTheme(Themes.palette) {
    val p = LocalPalette.current
  val t = LocalTexts.current
    PageFrame(16.dp) { _, contentWidth ->
      Headline()
      val s = summary
      when {
        gone -> Txt(
          t.watch.linkGone,
          color = p.muted, modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally),
        )
        s == null -> Txt(t.dialogs.loading, color = p.muted, modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally))
        else -> {
          Row(
            Modifier.fillMaxWidth().card(p).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Txt(s.sessionId ?: s.title, size = 22.sp, weight = FontWeight.Bold)
              Txt(
                t.watch.progress(t.decimal(s.totals.percent), t.number(s.totals.total)),
                size = 12.5.sp, color = p.muted,
              )
            }
            Pill(
              when {
                !streamOpen -> t.frame.reconnecting
                s.finishedAt != null -> t.frame.finished
                s.connected -> t.frame.live
                else -> t.frame.serverOffline
              },
              if (streamOpen && s.connected) p.ok else p.muted,
            )
          }
          Stats(s, now, contentWidth)
          People(s, now, contentWidth, onOpen = { detailSlot = it }, onOpenPerson = {})
          if (showHints(s)) Hints(s, compact = contentWidth < 700.dp)
          Feed(s, Modifier.fillMaxWidth(), FeedPosition.BELOW, fillHeight = false, onPosition = null, onHide = null)
        }
      }
    }
    detailSlot?.let { slot ->
      PlayerDialog(slot, summary?.version ?: 0, repository, onClose = { detailSlot = null })
    }
  }
}
