package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.app.LocalTexts

/**
 * Which rows of a list show ([label], e.g. "101–200 of 950") and the arrows to the rows before and after them, each
 * with its tip ([earlierTip], [laterTip]); an arrow whose way is `null` isn't there. [upDown]: the earlier rows are
 * above (newer lines of a log, newer events), else to the left.
 */
@Composable
fun PagerRow(
  label: String,
  earlierTip: String,
  laterTip: String,
  onEarlier: (() -> Unit)?,
  onLater: (() -> Unit)?,
  modifier: Modifier = Modifier,
  upDown: Boolean = false,
) {
  val p = LocalPalette.current
  Row(
    modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Txt(label, size = 12.5.sp, color = p.muted, modifier = Modifier.weight(1f))
    if (onEarlier != null) {
      IconButton(onEarlier, Icon.CHEVRON, Modifier.size(30.dp).rotate(if (upDown) 180f else 90f), label = earlierTip)
    }
    if (onLater != null) {
      IconButton(onLater, Icon.CHEVRON, Modifier.size(30.dp).rotate(if (upDown) 0f else -90f), label = laterTip)
    }
  }
}

/**
 * The pager of a list the tracker pages ([from]: the first row shown, counted from 0; [shown] of them, of [total]),
 * under a divider; [step] rows a page. Nothing while every row fits on one page.
 */
@Composable
fun RangePager(from: Int, shown: Int, total: Int, step: Int, onFrom: (Int) -> Unit) {
  if (from == 0 && shown >= total) return
  val t = LocalTexts.current
  Divider()
  PagerRow(
    t.spoiler.rowsOf(t.number(from + 1), t.number(from + shown), t.number(total)), t.spoiler.earlierRows, t.spoiler.laterRows,
    onEarlier = if (from > 0) ({ onFrom((from - step).coerceAtLeast(0)) }) else null,
    onLater = if (from + shown < total) ({ onFrom(from + shown) }) else null,
    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
  )
}

/**
 * [items] [pageSize] at a time, each as [row], with which of how many they are and arrows to the others below them
 * (none for a list that fits). A slot can have thousands of locations: drawn all at once, they stopped the browser.
 * [reset]: back to the first page when it changes (a filter's text).
 */
@Composable
fun <T> Paged(items: List<T>, pageSize: Int, reset: Any? = null, row: @Composable (T) -> Unit) {
  val t = LocalTexts.current
  var page by remember(reset) { mutableIntStateOf(0) }
  val pages = (items.size + pageSize - 1) / pageSize
  // A list that got shorter keeps a page that exists
  val shown = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
  val from = shown * pageSize
  items.subList(from, minOf(from + pageSize, items.size)).forEach { row(it) }
  if (pages > 1) {
    PagerRow(
      t.dialogs.rangeOf(t.number(from + 1), t.number(minOf(from + pageSize, items.size)), t.number(items.size)),
      t.dialogs.previousPage, t.dialogs.nextPage,
      onEarlier = if (shown > 0) ({ page = shown - 1 }) else null,
      onLater = if (shown < pages - 1) ({ page = shown + 1 }) else null,
      modifier = Modifier.padding(top = 6.dp),
    )
  }
}

/**
 * A list's search and its filter (a slot, a kind), emitted where it is called: in a [Section]'s actions (a wide page,
 * the search [searchModifier] `Modifier.width(220.dp)`), or in a column (a phone: `Modifier.fillMaxWidth()`, the filter
 * below it). [filters]: each value and its label, the one for everything among them ([slotFilters]).
 */
@Composable
fun <F> SearchAndFilter(
  query: String,
  onQuery: (String) -> Unit,
  placeholder: String,
  filter: F,
  filters: List<Pair<F, String>>,
  onFilter: (F) -> Unit,
  searchModifier: Modifier = Modifier,
  onSubmit: (() -> Unit)? = null,
) {
  FilterField(query, onQuery, placeholder, searchModifier, clearable = true, onSubmit = onSubmit)
  Select(filters, filter, onFilter)
}

/** A slot filter's choices: every slot ([all], `null`), then each of [slots] by its name. */
fun slotFilters(slots: List<String>, all: String): List<Pair<String?, String>> =
  listOf<Pair<String?, String>>(null to all) + slots.map { it to it }

/**
 * What a searched, filtered and paged list is narrowed to: the search as typed ([query], at most [maxQuery]
 * characters; [needle] trimmed, for asking), the [filter] and the first row shown ([from]). A new search or filter
 * starts at the first page.
 */
@Stable
class ListingState<F>(initialFilter: F, private val maxQuery: Int) {
  var query by mutableStateOf("")
    private set
  var filter by mutableStateOf(initialFilter)
    private set
  var from by mutableIntStateOf(0)
    private set

  val needle: String get() = query.trim()

  fun search(text: String) {
    val cut = text.take(maxQuery)
    if (cut.trim() != needle) from = 0
    query = cut
  }

  fun pick(value: F) {
    if (value != filter) from = 0
    filter = value
  }

  fun go(row: Int) {
    from = row.coerceAtLeast(0)
  }
}

/** A [ListingState] that starts over (nothing searched, [initialFilter], the first page) when [key] changes. */
@Composable
fun <F> rememberListingState(key: Any?, initialFilter: F, maxQuery: Int = 100): ListingState<F> =
  remember(key) { ListingState(initialFilter, maxQuery) }

/**
 * The line of a row that folds ([open] or not; [onToggle] on a click, Enter or Space): an arrow, then [content] (what
 * it is, and what stands at its right; a `Modifier.weight(1f)` on its title).
 */
@Composable
fun FoldRow(
  open: Boolean,
  onToggle: () -> Unit,
  narrow: Boolean = false,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  val p = LocalPalette.current
  Row(
    // The ring inside: such rows fill their card to its edges
    modifier.fillMaxWidth().focusRing(RoundedCornerShape(8.dp), gap = (-2).dp).hand()
      .toggleable(open, role = Role.Button) { onToggle() }
      .padding(horizontal = if (narrow) 12.dp else 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Drawn: the themes' fonts don't all have arrows
    Box(Modifier.size(14.dp).rotate(if (open) 0f else -90f)) { DrawnIcon(Icon.CHEVRON, p.muted, 14.dp) }
    content()
  }
}
