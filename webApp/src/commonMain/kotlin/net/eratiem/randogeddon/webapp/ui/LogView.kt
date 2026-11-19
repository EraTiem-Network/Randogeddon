package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.app.LocalTexts

/** How many lines of a log show at once: a browser lags with thousands of lines of text at a time. */
private const val LOG_PAGE = 300

/** A search looks for at least this many characters. */
private const val MIN_QUERY = 2

/**
 * A log as it grows ([lines]: oldest first, numbered), newest line on top, [LOG_PAGE] lines at a time: on the first
 * page new lines come in on top; on another one the lines stay put while more come. The arrows under it go to newer
 * and older lines. The search (in what is here) counts the lines found, and its arrows, or Enter and Shift+Enter, go
 * from one to the next, to whichever page it is on; what is found is marked. [color]: a line's own colour, if any.
 */
@Composable
fun LogView(
  lines: List<LogLine>,
  empty: String,
  modifier: Modifier = Modifier,
  height: Dp = 420.dp,
  color: (LogLine) -> Color? = { null },
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // The number of the line on top; null: the newest (it follows them)
  var top by remember { mutableStateOf<Long?>(null) }
  var query by remember { mutableStateOf("") }
  val q = query.trim()
  var current by remember { mutableStateOf(0) }
  val count = lines.size
  // The lines found, newest first (as they are shown), as indices into [lines]
  val found = remember(q, count, lines.firstOrNull()?.n) {
    if (q.length < MIN_QUERY) emptyList()
    else (count - 1 downTo 0).filter { lines[it].text.contains(q, ignoreCase = true) }
  }
  LaunchedEffect(q) { current = 0 }
  fun jump(by: Int) {
    if (found.isEmpty()) return
    current = (current + by).mod(found.size)
    top = lines[found[current]].n
  }
  // The index of the line on top: the newest, or the one numbered [top] (or the next older one kept)
  val topIndex = top?.let { n ->
    var lo = 0
    var hi = count - 1
    var at = -1
    while (lo <= hi) {
      val mid = (lo + hi) / 2
      if (lines[mid].n <= n) { at = mid; lo = mid + 1 } else hi = mid - 1
    }
    at
  } ?: (count - 1)
  val page = if (topIndex < 0) emptyList() else (topIndex downTo maxOf(0, topIndex - LOG_PAGE + 1)).map { lines[it] }
  val currentLine = found.getOrNull(current)?.let { lines[it].n }
  val text = remember(page, q, currentLine, p) {
    buildAnnotatedString {
      page.forEachIndexed { i, line ->
        if (i > 0) append('\n')
        val start = length
        append(line.text)
        color(line)?.let { addStyle(SpanStyle(color = it), start, length) }
        if (q.length >= MIN_QUERY) {
          var at = line.text.indexOf(q, 0, ignoreCase = true)
          while (at >= 0) {
            val mark = if (line.n == currentLine) p.accent.copy(alpha = 0.55f) else p.warn.copy(alpha = 0.35f)
            addStyle(SpanStyle(background = mark), start + at, start + at + q.length)
            at = line.text.indexOf(q, at + q.length, ignoreCase = true)
          }
        }
      }
    }
  }
  val scroll = rememberScrollState()
  // A page turned (or a finding jumped to) starts at its top
  LaunchedEffect(top) { scroll.scrollTo(0) }

  Column(modifier.fillMaxWidth().card(p)) {
    Row(
      Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      FilterField(
        query, { query = it.take(100) }, t.common.search,
        Modifier.width(220.dp).onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
            jump(if (event.isShiftPressed) -1 else 1)
            true
          } else false
        },
        clearable = true,
      )
      if (q.length >= MIN_QUERY) {
        Txt(
          if (found.isEmpty()) t.spoiler.none else "${current + 1} / ${found.size}",
          size = 12.5.sp, maxLines = 1, color = if (found.isEmpty()) p.trap else p.muted,
        )
        if (found.isNotEmpty()) {
          IconButton({ jump(-1) }, Icon.CHEVRON, Modifier.size(30.dp).rotate(180f))
          IconButton({ jump(1) }, Icon.CHEVRON, Modifier.size(30.dp))
        }
      }
    }
    Box(Modifier.fillMaxWidth().height(height)) {
      SelectionContainer {
        Column(Modifier.fillMaxWidth().scrolling(scroll).padding(12.dp)) {
          if (page.isEmpty()) Txt(empty, size = 13.sp, color = p.muted)
          else BasicText(text, style = TextStyle(color = p.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
        }
      }
    }
    // Which lines these are, counted from the newest, and the pages above (newer) and below (older)
    if (count > LOG_PAGE) {
      Divider()
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val first = count - topIndex
        val last = first + page.size - 1
        Txt(
          t.spoiler.linesOf(t.number(first), t.number(last), t.number(count)), size = 12.5.sp, color = p.muted,
          modifier = Modifier.weight(1f),
        )
        if (topIndex < count - 1) Tip(t.panel.newerLines) {
          IconButton(
            { top = (topIndex + LOG_PAGE).let { if (it >= count - 1) null else lines[it].n } },
            Icon.CHEVRON, Modifier.size(30.dp).rotate(180f),
          )
        }
        if (topIndex - LOG_PAGE >= 0) Tip(t.panel.olderLines) {
          IconButton({ top = lines[topIndex - LOG_PAGE].n }, Icon.CHEVRON, Modifier.size(30.dp))
        }
      }
    }
  }
}
