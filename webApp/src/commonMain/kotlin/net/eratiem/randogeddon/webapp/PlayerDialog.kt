package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.eratiem.randogeddon.shared.PlayerDetail

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
  var detail by remember(slot) { mutableStateOf<PlayerDetail?>(null) }
  var query by remember(slot) { mutableStateOf("") }
  LaunchedEffect(slot, version) {
    repository.player(slot)?.let { detail = it }
  }

  run {
    BoxWithConstraints(Modifier.padding(16.dp)) {
      val twoColumns = maxWidth >= 700.dp
      Column(
        Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = maxHeight).card(p, RoundedCornerShape(20.dp))
      ) {
        val d = detail
        val percent = if (d == null || d.total == 0) 0.0 else 100.0 * d.checked / d.total
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          if (d != null) GameChip(d.game, size = 40.dp)
          Column(Modifier.weight(1f)) {
            Txt(d?.name ?: "…", size = 17.sp, weight = FontWeight.SemiBold)
            if (d != null) Txt("${d.game} · ${d.checked} / ${d.total} checks (${oneDecimal(percent)}%)", size = 12.5.sp, color = p.muted)
          }
          IconButton(onClose, if (back) Icon.BACK else Icon.CLOSE)
        }
        Divider()
        if (d == null) {
          Txt("Loading…", color = p.muted, modifier = Modifier.padding(16.dp))
          return@Column
        }
        val now = Platform.now()
        val open = d.sessions.lastOrNull()?.takeIf { it.end == null }
        val played = d.sessions.sumOf { (it.end ?: now) - it.start }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          ProgressBar((percent / 100).toFloat())
          // Time facts of this world
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact("Played", duration(played))
            Fact("Sessions", "${d.sessions.size}")
            Fact("Status", if (open != null) "online" else "offline", if (open != null) p.ok else p.muted)
            if (repository.pointHints) d.hintPoints?.let { Fact("Hint points", "$it") }
          }
          val items: @Composable ColumnScope.() -> Unit = {
            Section("Received items (${d.items.size})")
            FilterField(query, { query = it }, "Filter…", Modifier.fillMaxWidth().padding(bottom = 8.dp))
            val shown = d.items.asReversed().filter {
              query.isBlank() || "${it.item} ${it.from} ${it.location}".contains(query, ignoreCase = true)
            }
            if (shown.isEmpty()) Txt("Nothing yet.", size = 13.sp, color = p.muted)
            shown.forEach { i ->
              ListRow {
                Txt(buildAnnotatedString {
                  withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.item(i.flags))) { append(i.item) }
                  withStyle(SpanStyle(color = p.muted)) {
                    append("  from ${i.from}")
                    if (i.location.isNotEmpty()) append(" · ${i.location}")
                  }
                }, size = 13.sp)
              }
            }
          }
          val missing: @Composable ColumnScope.() -> Unit = {
            Section("Connections (${d.sessions.size})")
            if (d.sessions.isEmpty()) Txt("No game client seen yet.", size = 13.sp, color = p.muted)
            d.sessions.asReversed().take(30).forEach { c ->
              ListRow {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Dot(if (c.end == null) p.ok else p.muted, 7.dp)
                  Txt(
                    buildString {
                      append(if (c.implicit) "seen playing " else "")
                      append(Platform.clock(c.start))
                      append(c.end?.let { " – ${Platform.clock(it)}" } ?: " – now")
                    },
                    size = 13.sp, modifier = Modifier.weight(1f),
                  )
                  Txt(duration((c.end ?: now) - c.start), size = 12.5.sp, color = p.muted)
                }
              }
            }
            Spacer(Modifier.height(14.dp))
            Section("Remaining locations (${d.missing.size})")
            if (d.missing.isEmpty()) Txt("All done!", size = 13.sp, color = p.ok)
            d.missing.forEach { ListRow { Txt(it, size = 13.sp) } }
          }
          if (twoColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              Column(Modifier.weight(1f), content = items)
              Column(Modifier.weight(1f), content = missing)
            }
          } else {
            Column(content = items)
            Column(content = missing)
          }
        }
      }
    }
  }
}

@Composable
private fun RowScope.Fact(label: String, value: String, color: Color = LocalPalette.current.text) {
  val p = LocalPalette.current
  Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(p.panelAlt).padding(horizontal = 12.dp, vertical = 8.dp)) {
    Txt(label, size = 11.5.sp, color = p.muted, maxLines = 1)
    Txt(value, size = 16.sp, weight = FontWeight.Bold, color = color, maxLines = 1)
  }
}

@Composable
private fun Section(title: String) {
  Txt(title.uppercase(), size = 12.sp, color = LocalPalette.current.muted, weight = FontWeight.SemiBold,
    modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun ListRow(content: @Composable () -> Unit) {
  Column(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(vertical = 4.dp)) { content() }
    Divider()
  }
}
