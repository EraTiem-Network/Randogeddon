package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.api.WatchRepository
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.RandogeddonTheme
import net.eratiem.randogeddon.webapp.ui.Themes
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.scrolling

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
  var now by remember { mutableLongStateOf(Platform.now()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(30_000)
      now = Platform.now()
    }
  }
  LaunchedEffect(summary?.version) { now = Platform.now() }
  var detailSlot by remember { mutableStateOf<Int?>(null) }

  // No account here: the theme last used in this browser
  RandogeddonTheme(Themes.palette) {
    val p = LocalPalette.current
  val t = LocalTexts.current
    BoxWithConstraints(
      Modifier.fillMaxSize().background(p.background).background(
        Brush.verticalGradient(listOf(p.brandEnd.copy(alpha = if (p.dark) 0.06f else 0.07f), p.background, p.background))
      )
    ) {
      val contentWidth = minOf(maxWidth, 1432.dp) - 32.dp
      Column(
        Modifier.fillMaxSize().scrolling().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        SelectionContainer {
          Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
        }
      }
    }
    detailSlot?.let { slot ->
      PlayerDialog(slot, summary?.version ?: 0, repository, onClose = { detailSlot = null })
    }
  }
}
