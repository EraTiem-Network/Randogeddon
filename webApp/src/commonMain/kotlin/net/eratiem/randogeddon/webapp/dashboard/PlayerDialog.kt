package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.duration
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.api.SessionSource
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.clock
import net.eratiem.randogeddon.webapp.ui.DialogCard
import net.eratiem.randogeddon.webapp.ui.DialogSection
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.Dot
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.GameChip
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.ProgressBar
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.TwoColumns
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.scrolling

/** One world as a dialog; see [WorldDetail]. */
@Composable
fun PlayerDialog(slot: Int, version: Long, repository: SessionSource, onClose: () -> Unit) {
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    // A dialog is its own window: its text needs its own selection
    SelectionContainer { WorldDetail(slot, version, repository, onClose, back = false) }
  }
}

/**
 * Received items, remaining locations, play time and connection log of one world; reloaded when the session changes
 * while it is shown. [back]: shown inside another view (a person), so the button goes back instead of closing.
 */
@Composable
fun WorldDetail(slot: Int, version: Long, repository: SessionSource, onClose: () -> Unit, back: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var detail by remember(slot) { mutableStateOf<PlayerDetail?>(null) }
  var query by remember(slot) { mutableStateOf("") }
  var missingQuery by remember(slot) { mutableStateOf("") }
  LaunchedEffect(slot, version) {
    repository.player(slot)?.let { detail = it }
  }

  val d = detail
  val percent = if (d == null || d.total == 0) 0.0 else 100.0 * d.checked / d.total
  DialogCard(900.dp, header = {
    if (d != null) GameChip(d.game, size = 40.dp)
    Column(Modifier.weight(1f)) {
      Txt(d?.name ?: "…", size = 17.sp, weight = FontWeight.SemiBold)
      if (d != null) Txt(t.dialogs.slotHeading(d.game, d.checked, d.total, t.decimal(percent)), size = 12.5.sp, color = p.muted)
    }
    IconButton(onClose, if (back) Icon.BACK else Icon.CLOSE)
  }) { available ->
    val twoColumns = available >= 700.dp
    if (d == null) {
      Txt(t.dialogs.loading, color = p.muted, modifier = Modifier.padding(16.dp))
      return@DialogCard
    }
    val now = Platform.now()
    val open = d.sessions.lastOrNull()?.takeIf { it.end == null }
    val played = d.sessions.sumOf { (it.end ?: now) - it.start }
    Column(Modifier.scrolling().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Bars(Progress.of(d), height = 8.dp)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProgressPills(Progress.of(d))
      }
      // Time facts of this world
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Fact(t.dialogs.played, t.duration(played))
        Fact(t.dialogs.sessions, "${d.sessions.size}")
        Fact(t.dialogs.status, if (open != null) t.dialogs.online else t.dialogs.offline, if (open != null) p.ok else p.muted)
        if (repository.pointHints) d.hintPoints?.let { Fact(t.dialogs.hintPoints, "$it") }
      }
      val items: @Composable ColumnScope.() -> Unit = {
        DialogSection(t.dialogs.receivedItems(d.items.size))
        FilterField(query, { query = it }, t.dialogs.filter, Modifier.fillMaxWidth().padding(bottom = 8.dp))
        val shown = d.items.asReversed().filter {
          query.isBlank() || "${it.item} ${it.from} ${it.location}".contains(query, ignoreCase = true)
        }
        if (shown.isEmpty()) Txt(t.dialogs.nothingYet, size = 13.sp, color = p.muted)
        Paged(shown, 50, reset = query) { i ->
          ListRow {
            Txt(buildAnnotatedString {
              withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.item(i.flags))) { append(i.item) }
              withStyle(SpanStyle(color = p.muted)) {
                append(t.dialogs.from(i.from, i.location.takeIf { it.isNotEmpty() }))
              }
            }, size = 13.sp)
          }
        }
      }
      val missing: @Composable ColumnScope.() -> Unit = {
        DialogSection(t.dialogs.connections(d.sessions.size))
        if (d.sessions.isEmpty()) Txt(t.dialogs.noClientYet, size = 13.sp, color = p.muted)
        Paged(d.sessions.asReversed(), 20) { c ->
          ListRow {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Dot(if (c.end == null) p.ok else p.muted, 7.dp)
              Txt(
                t.dialogs.connection(c.implicit, t.clock(c.start), c.end?.let { t.clock(it) }),
                size = 13.sp, modifier = Modifier.weight(1f),
              )
              Txt(t.duration((c.end ?: now) - c.start), size = 12.5.sp, color = p.muted)
            }
          }
        }
        Spacer(Modifier.height(14.dp))
        DialogSection(t.dialogs.remainingChecks(d.missing.size))
        if (d.missing.isEmpty()) Txt(t.dialogs.allDone, size = 13.sp, color = p.ok)
        else {
          FilterField(missingQuery, { missingQuery = it }, t.dialogs.filter, Modifier.fillMaxWidth().padding(bottom = 8.dp))
          val missing = d.missing.filter { missingQuery.isBlank() || it.contains(missingQuery, ignoreCase = true) }
          Paged(missing, 50, reset = missingQuery) { ListRow { Txt(it, size = 13.sp) } }
        }
      }
      TwoColumns(twoColumns, 16.dp, items, missing)
    }
  }
}


/**
 * [items] [pageSize] at a time, each as [row], with which of how many they are and arrows to the others below them
 * (none for a list that fits). A slot can have thousands of locations: drawn all at once, they stopped the browser.
 * [reset]: back to the first page when it changes (a filter's text).
 */
@Composable
internal fun <T> Paged(items: List<T>, pageSize: Int, reset: Any? = null, row: @Composable (T) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var page by remember(reset) { mutableIntStateOf(0) }
  val pages = (items.size + pageSize - 1) / pageSize
  // A list that got shorter keeps a page that exists
  val shown = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
  val from = shown * pageSize
  items.subList(from, minOf(from + pageSize, items.size)).forEach { row(it) }
  if (pages > 1) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(
        t.dialogs.rangeOf(t.number(from + 1), t.number(minOf(from + pageSize, items.size)), t.number(items.size)),
        size = 12.5.sp, color = p.muted, modifier = Modifier.weight(1f),
      )
      if (shown > 0) Tip(t.dialogs.previousPage) { IconButton({ page = shown - 1 }, Icon.CHEVRON, Modifier.size(30.dp).rotate(90f)) }
      if (shown < pages - 1) Tip(t.dialogs.nextPage) { IconButton({ page = shown + 1 }, Icon.CHEVRON, Modifier.size(30.dp).rotate(-90f)) }
    }
  }
}

@Composable
private fun ListRow(content: @Composable () -> Unit) {
  Column(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(vertical = 4.dp)) { content() }
    Divider()
  }
}
