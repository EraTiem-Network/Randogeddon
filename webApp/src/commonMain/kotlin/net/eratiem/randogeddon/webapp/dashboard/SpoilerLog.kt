package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.SpoilerFindings
import net.eratiem.randogeddon.shared.SpoilerLines
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.api.pathSegment
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.PagerRow
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Selectable
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.scrolling

/** How many lines of the log show at once. */
private const val PAGE = 300

/** How long the search waits after the last key before it asks the server. */
internal const val SEARCH_DELAY_MS = 500L

/** Where [query] occurs in [text] (ignoring case); nothing for less than 2 characters. */
internal fun findings(text: String, query: String): List<Int> {
  if (query.length < SpoilerFindings.MIN_QUERY) return emptyList()
  return buildList {
    var at = text.indexOf(query, 0, ignoreCase = true)
    while (at >= 0) {
      add(at)
      at = text.indexOf(query, at + query.length, ignoreCase = true)
    }
  }
}

/**
 * The whole log, monospace, [PAGE] lines at a time (a big seed's log is too long for a browser to show at once):
 * the arrows under it go to the lines before and after. The search asks the server (a moment after the last key,
 * and the server spaces searches out): it counts the lines found, and the arrows beside it, or Enter and
 * Shift+Enter, jump from one to the next, to whichever page it is on. What is found is marked on the page shown.
 */
@Composable
internal fun FullLog(id: String, total: Int, narrow: Boolean, title: String, subtitle: String) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // The page shown: its first line's number and its lines; null while the first one loads
  var page by remember(id) { mutableStateOf<SpoilerLines?>(null) }
  var wanted by remember(id) { mutableStateOf(0) }
  var problem by remember(id) { mutableStateOf<String?>(null) }
  LaunchedEffect(id, wanted) {
    when (val answer = call("GET", "api/sessions/${id.pathSegment()}/spoiler/log?from=$wanted&count=$PAGE", null, SpoilerLines.serializer())) {
      is Answer.Ok -> { page = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }

  var query by remember(id) { mutableStateOf("") }
  val q = query.trim()
  // The lines the server found for [q]; null: not asked (yet)
  var found by remember(id) { mutableStateOf<SpoilerFindings?>(null) }
  var current by remember(id) { mutableStateOf(0) }
  var searching by remember(id) { mutableStateOf(false) }
  var searchProblem by remember(id) { mutableStateOf<String?>(null) }
  LaunchedEffect(id, q) {
    found = null
    current = 0
    searchProblem = null
    if (q.length < SpoilerFindings.MIN_QUERY) {
      searching = false
      return@LaunchedEffect
    }
    searching = true
    // Typing on restarts this: only what stays for a moment is asked for
    delay(SEARCH_DELAY_MS)
    val path = "api/sessions/${id.pathSegment()}/spoiler/search?q=${q.take(SpoilerFindings.MAX_QUERY).percentEncoded()}"
    when (val answer = call("GET", path, null, SpoilerFindings.serializer())) {
      is Answer.Ok -> {
        found = answer.value
        // The first finding from the page shown on, so the search doesn't jump back to the top
        current = answer.value.lines.indexOfFirst { it >= (page?.from ?: 0) }.coerceAtLeast(0)
      }
      is Answer.Failed -> searchProblem = answer.message
    }
    searching = false
  }
  val lines = found?.lines.orEmpty()
  val currentLine = lines.getOrNull(current)
  // The page with the current finding
  LaunchedEffect(currentLine) { currentLine?.let { wanted = it - it % PAGE } }
  fun step(by: Int) {
    if (lines.isNotEmpty()) current = (current + by).mod(lines.size)
  }

  val shown = page
  val text = remember(shown) { shown?.lines?.joinToString("\n").orEmpty() }
  // Where each of the page's lines starts in its text
  val starts = remember(shown) {
    val out = IntArray(shown?.lines?.size ?: 0)
    var at = 0
    shown?.lines?.forEachIndexed { i, line -> out[i] = at; at += line.length + 1 }
    out
  }
  val mark = p.warn.copy(alpha = 0.35f)
  val currentMark = p.accent.copy(alpha = 0.55f)
  // The current finding's line on this page, if it is on it
  val currentOnPage = currentLine?.let { it - (shown?.from ?: 0) }?.takeIf { it in starts.indices }
  val marked = remember(text, q, found, currentOnPage, mark, currentMark) {
    buildAnnotatedString {
      append(text)
      if (found != null) {
        val from = currentOnPage?.let { starts[it] }
        val to = currentOnPage?.let { starts.getOrNull(it + 1) ?: (text.length + 1) }
        findings(text, q).forEach { start ->
          val inCurrent = from != null && to != null && start >= from && start < to
          addStyle(SpanStyle(background = if (inCurrent) currentMark else mark), start, start + q.length)
        }
      }
    }
  }
  val scroll = rememberScrollState()
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  val density = LocalDensity.current
  // The current finding a few lines below the top of the box; a page turned by hand starts at its top
  LaunchedEffect(shown, currentOnPage, layout) {
    val l = layout ?: return@LaunchedEffect
    val at = currentOnPage?.let { starts[it] }
    if (at == null || at > l.layoutInput.text.length) {
      if (currentLine == null) scroll.scrollTo(0)
      return@LaunchedEffect
    }
    val top = l.getLineTop(l.getLineForOffset(at)) - with(density) { 48.dp.toPx() }
    scroll.animateScrollTo(top.toInt().coerceAtLeast(0))
  }

  val search: @Composable (Modifier) -> Unit = { modifier ->
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      FilterField(
        query, { query = it.take(SpoilerFindings.MAX_QUERY) }, t.common.search,
        (if (narrow) Modifier.weight(1f) else Modifier.width(220.dp)).onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
            step(if (event.isShiftPressed) -1 else 1)
            true
          } else false
        },
        clearable = true,
      )
      if (q.length >= SpoilerFindings.MIN_QUERY) {
        val result = found
        Txt(
          when {
            searchProblem != null -> searchProblem.orEmpty()
            result == null -> if (searching) "…" else ""
            lines.isEmpty() -> t.spoiler.none
            else -> "${current + 1} / ${lines.size}${if (result.more) "+" else ""}"
          },
          size = 12.5.sp, maxLines = 1,
          color = if (searchProblem != null || (result != null && lines.isEmpty())) p.trap else p.muted,
        )
        if (lines.isNotEmpty()) {
          IconButton({ step(-1) }, Icon.CHEVRON, Modifier.size(30.dp).rotate(180f), label = t.spoiler.previousFinding)
          IconButton({ step(1) }, Icon.CHEVRON, Modifier.size(30.dp), label = t.spoiler.nextFinding)
        }
      }
    }
  }
  Section(title, subtitle = subtitle, actions = {
    if (!narrow) search(Modifier)
  }) {
    if (narrow) search(Modifier.fillMaxWidth())
    problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    Column(Modifier.fillMaxWidth().card(p)) {
      Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        Selectable {
          Column(Modifier.fillMaxWidth().scrolling(scroll).padding(12.dp)) {
            if (shown == null && problem == null) Txt(t.dialogs.loading, size = 12.5.sp, color = p.muted)
            BasicText(
              marked, style = TextStyle(color = p.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
              onTextLayout = { layout = it },
            )
          }
        }
      }
      // Which lines these are, and the pages before and after
      if (shown != null && total > PAGE) {
        val from = shown.from
        val last = shown.from + shown.lines.size
        Divider()
        PagerRow(
          t.spoiler.linesOf(t.number(from + 1), t.number(last), t.number(shown.total)), t.spoiler.earlierLines, t.spoiler.laterLines,
          onEarlier = if (from > 0) ({ wanted = (from - PAGE).coerceAtLeast(0) }) else null,
          onLater = if (last < shown.total) ({ wanted = last }) else null,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
      }
    }
  }
}
