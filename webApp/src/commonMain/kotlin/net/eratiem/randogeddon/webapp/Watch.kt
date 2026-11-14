package net.eratiem.randogeddon.webapp

import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.number
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.Summary

/**
 * A session by its share link ([token]), without a login: its summary over the live stream, and a slot's details.
 * Read-only: the server answers no passwords, spoiler or accounts here.
 */
class WatchRepository(private val scope: CoroutineScope, private val token: String) : SessionSource {
  private val _summary = MutableStateFlow<Summary?>(null)
  val summary: StateFlow<Summary?> = _summary.asStateFlow()

  private val _streamOpen = MutableStateFlow(false)
  val streamOpen: StateFlow<Boolean> = _streamOpen.asStateFlow()

  /** The link is off, replaced or made up. */
  private val _gone = MutableStateFlow(false)
  val gone: StateFlow<Boolean> = _gone.asStateFlow()

  private var close: (() -> Unit)? = null

  fun start() {
    if (close != null) return
    scope.launch {
      val result = Platform.request("GET", "api/watch/$token/state")
      when (result?.status) {
        200 -> accept(result.body)
        404 -> { _gone.value = true; return@launch }
      }
      open()
    }
  }

  fun stop() {
    close?.invoke()
    close = null
    _streamOpen.value = false
  }

  private fun open() {
    close = Platform.stream("api/watch/$token/stream", onState = ::accept, onConnection = { open ->
      _streamOpen.value = open
      // The browser gives up on a refused stream: tried again while the link works
      if (!open) {
        stop()
        scope.launch {
          delay(3_000)
          if (Platform.request("GET", "api/watch/$token/state")?.status == 404) _gone.value = true else open()
        }
      }
    })
  }

  override suspend fun player(slot: Int): PlayerDetail? =
    Platform.request("GET", "api/watch/$token/player/$slot")?.takeIf { it.status == 200 }
      ?.let { runCatching { ApiJson.decodeFromString(PlayerDetail.serializer(), it.body) }.getOrNull() }

  override val pointHints: Boolean get() = summary.value?.hintSettings?.pointHints != false

  private fun accept(json: String) {
    val summary = runCatching { ApiJson.decodeFromString(Summary.serializer(), json) }.getOrNull() ?: return
    val previous = _summary.value
    if (summary.version >= (previous?.version ?: -1) || summary.finishedAt != previous?.finishedAt) _summary.value = summary
  }
}

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
  val dark = Platform.loadSetting("ap-theme")?.let { it == "dark" } ?: Platform.systemDarkMode()
  var now by remember { mutableLongStateOf(Platform.now()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(30_000)
      now = Platform.now()
    }
  }
  LaunchedEffect(summary?.version) { now = Platform.now() }
  var detailSlot by remember { mutableStateOf<Int?>(null) }

  RandogeddonTheme(dark) {
    val p = LocalPalette.current
  val t = LocalTexts.current
    BoxWithConstraints(
      Modifier.fillMaxSize().background(p.background).background(
        Brush.verticalGradient(listOf(p.brandEnd.copy(alpha = if (p.dark) 0.06f else 0.07f), p.background, p.background))
      )
    ) {
      val contentWidth = minOf(maxWidth, 1432.dp) - 32.dp
      Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        SelectionContainer {
          Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Headline()
            val s = summary
            when {
              gone -> Txt(
                "This link doesn't work (any more): it was turned off or replaced by a new one.",
                color = p.muted, modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally),
              )
              s == null -> Txt("Loading…", color = p.muted, modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally))
              else -> {
                Row(
                  Modifier.fillMaxWidth().card(p).padding(horizontal = 20.dp, vertical = 16.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Column(Modifier.weight(1f)) {
                    Txt(s.sessionId ?: s.title, size = 22.sp, weight = FontWeight.Bold)
                    Txt(
                      "${t.decimal(s.totals.percent)} % of ${t.number(s.totals.total)} checks done · watching, read-only",
                      size = 12.5.sp, color = p.muted,
                    )
                  }
                  Pill(
                    when {
                      !streamOpen -> "reconnecting…"
                      s.finishedAt != null -> "finished"
                      s.connected -> "live"
                      else -> "server offline"
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
