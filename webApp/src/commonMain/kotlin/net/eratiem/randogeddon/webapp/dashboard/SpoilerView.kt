package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.Sphere
import net.eratiem.randogeddon.shared.Spoiler
import net.eratiem.randogeddon.shared.SpoilerFindings
import net.eratiem.randogeddon.shared.SpoilerLines
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.wordError
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.NARROW
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Select
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.hand
import net.eratiem.randogeddon.webapp.ui.scrolling

/**
 * A session's spoiler log: the playthrough sphere by sphere (each foldable; the shortest way through the seed, what each
 * step makes reachable), and the full log, searchable. [id]: the session. [onBack]: closes it.
 */
@Composable
fun SpoilerView(id: String, onBack: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var spoiler by remember(id) { mutableStateOf<Spoiler?>(null) }
  var problem by remember(id) { mutableStateOf<String?>(null) }
  LaunchedEffect(id) {
    val result = Platform.request("GET", "api/sessions/$id/spoiler")
    when {
      result == null -> problem = t.common.serverUnreachable
      result.status == 200 -> spoiler = runCatching { ApiJson.decodeFromString(Spoiler.serializer(), result.body) }.getOrNull()
      else -> problem = t.wordError(result.body) ?: t.common.failed(result.status)
    }
  }

  BoxWithConstraints {
    // A phone: the steps as two lines each, the pickers and the search below their headings
    val narrow = maxWidth < NARROW
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconButton(onBack, Icon.BACK)
        Column(Modifier.weight(1f)) {
          Txt(t.spoiler.title, size = 20.sp, weight = FontWeight.Bold)
          Txt(
            listOfNotNull(id, spoiler?.let { t.frame.seed(it.seedName) }, spoiler?.let { t.spoiler.spheres(it.spheres.size) }).joinToString(" · "),
            size = 12.5.sp, color = p.muted,
          )
        }
      }
      problem?.let { Txt(it, size = 13.sp, color = p.trap) }
      val s = spoiler
      if (s == null) {
        if (problem == null) Txt(t.dialogs.loading, color = p.muted)
        return@Column
      }
      if (s.unreduced) Txt(t.spoiler.unreduced, size = 12.5.sp, color = p.warn)
      if (s.spheres.isNotEmpty()) Playthrough(s.spheres, narrow)
      FullLog(id, s.lines, narrow)
    }
  }
}

/**
 * One step of a sphere: an item for [itemSlot], found at [location] in [locationSlot] (none for sphere 0); [checked]:
 * that location is checked already.
 */
private data class Step(
  val item: String,
  val itemSlot: String?,
  val location: String?,
  val locationSlot: String?,
  val checked: Boolean = false,
)

/** "Name (Slot)" into its parts; the slot is the last parenthesis, since names can have their own. */
private fun named(text: String): Pair<String, String?> {
  val t = text.trim()
  val open = t.lastIndexOf(" (")
  if (!t.endsWith(")") || open < 0) return t to null
  return t.substring(0, open) to t.substring(open + 2, t.length - 1)
}

/** "Location (Slot): Item (Slot)", or just "Item (Slot)" in sphere 0. Locations may contain "): " themselves. */
private fun step(entry: String): Step {
  val split = entry.lastIndexOf("): ")
  if (split < 0) {
    val (item, slot) = named(entry)
    return Step(item, slot, null, null)
  }
  val (location, locationSlot) = named(entry.substring(0, split + 1))
  val (item, itemSlot) = named(entry.substring(split + 3))
  return Step(item, itemSlot, location, locationSlot)
}

@Composable
private fun Playthrough(spheres: List<Sphere>, narrow: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val steps = remember(spheres) {
    spheres.associate { sphere ->
      sphere.number to sphere.entries.mapIndexed { i, entry -> step(entry).copy(checked = sphere.checked.getOrNull(i) == true) }
    }
  }
  val slots = remember(steps) {
    steps.values.flatten().flatMap { listOfNotNull(it.itemSlot, it.locationSlot) }.distinct().sortedBy { it.lowercase() }
  }
  // Folded at first: it is the whole way through the seed; a slot filter unfolds what concerns that slot
  var open by remember { mutableStateOf(emptySet<Int>()) }
  var slot by remember { mutableStateOf<String?>(null) }
  // The search: the steps whose item, check or slots contain it (any case); like the slot, it unfolds what it finds
  var query by remember { mutableStateOf("") }
  val needle = query.trim().lowercase()
  fun matches(step: Step) = needle.isEmpty() ||
    listOfNotNull(step.item, step.location, step.itemSlot, step.locationSlot).any { needle in it.lowercase() }
  fun shown(number: Int) = steps[number].orEmpty()
    .filter { (slot == null || it.itemSlot == slot || it.locationSlot == slot) && matches(it) }
  val search: @Composable (Modifier) -> Unit = { modifier ->
    FilterField(query, { query = it }, t.spoiler.searchSteps, modifier, clearable = true)
  }
  // The spheres the slot and the search leave (with a step); they unfold when filtered, fold back when not, and each
  // folds by hand either way
  val visibleNumbers = spheres.filter { shown(it.number).isNotEmpty() }.map { it.number }.toSet()
  val allOpen = visibleNumbers.isNotEmpty() && open.containsAll(visibleNumbers)
  LaunchedEffect(slot, needle) { open = if (slot != null || needle.isNotEmpty()) visibleNumbers else emptySet() }
  val controls: @Composable () -> Unit = {
    Select(listOf<Pair<String?, String>>(null to t.spoiler.everySlot) + slots.map { it to it }, slot, { slot = it })
    // Follows the list: "Fold all" once every sphere shown is unfolded (by hand too), else "Unfold all"
    SmallButton(if (allOpen) t.spoiler.foldAll else t.spoiler.unfoldAll, {
      open = if (allOpen) open - visibleNumbers else open + visibleNumbers
    })
  }
  Section(
    t.spoiler.playthrough, subtitle = t.spoiler.playthroughSubtitle,
    actions = {
      if (!narrow) {
        search(Modifier.width(220.dp))
        controls()
      }
    },
  ) {
    // A phone: the search on a line of its own, the slot and folding below it
    if (narrow) {
      search(Modifier.fillMaxWidth())
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
    }
    Column(Modifier.fillMaxWidth().card(p)) {
      val visible = spheres.filter { it.number in visibleNumbers }
      if (visible.isEmpty()) {
        Txt(
          if (needle.isNotEmpty()) t.spoiler.nothingMatches(query.trim()) else t.spoiler.nothingFor(slot.orEmpty()),
          color = p.muted, modifier = Modifier.padding(16.dp),
        )
      }
      visible.forEachIndexed { i, sphere ->
        if (i > 0) Divider()
        val entries = shown(sphere.number)
        val unfolded = sphere.number in open
        Row(
          Modifier.fillMaxWidth().hand().clickable { open = if (sphere.number in open) open - sphere.number else open + sphere.number }
            .padding(horizontal = if (narrow) 12.dp else 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // Drawn: the themes' fonts don't all have arrows
          Box(Modifier.size(14.dp).rotate(if (unfolded) 0f else -90f)) { DrawnIcon(Icon.CHEVRON, p.muted, 14.dp) }
          Txt(
            if (sphere.number == 0) t.spoiler.sphereZero else t.spoiler.sphere(sphere.number),
            size = 13.5.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
          )
          // How many of its checks are done (sphere 0 has none)
          val withLocation = entries.count { it.location != null }
          val done = entries.count { it.checked }
          if (withLocation > 0 && done > 0) Pill(t.spoiler.checked(done, withLocation), if (done == withLocation) p.ok else p.useful)
          Pill(t.spoiler.steps(entries.size), p.muted)
        }
        if (unfolded) {
          if (narrow) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
              entries.forEachIndexed { j, entry -> StepCard(entry, odd = j % 2 == 1) }
            }
          } else {
            Column(Modifier.fillMaxWidth().padding(start = 38.dp, end = 16.dp, bottom = 12.dp)) {
              StepHeader()
              entries.forEachIndexed { j, entry -> StepRow(entry, odd = j % 2 == 1) }
            }
          }
        }
      }
    }
  }
}

private val SLOT_COLUMN = 150.dp
private val CHECKED_COLUMN = 22.dp

/** A step whose location is checked already: a check mark in the status colour (the column stays for the others). */
@Composable
private fun CheckedMark(checked: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  Box(Modifier.width(CHECKED_COLUMN), contentAlignment = Alignment.CenterEnd) {
    if (checked) Tip(t.spoiler.checkedTip) { DrawnIcon(Icon.CHECK, p.ok, 14.dp) }
  }
}
private val ARROW_COLUMN = 28.dp

/** The table's columns: whose item, the item, then the slot and location it lies at. */
@Composable
private fun StepHeader() {
  val p = LocalPalette.current
  val t = LocalTexts.current
  Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Txt(t.spoiler.slot, size = 11.5.sp, color = p.muted, weight = FontWeight.SemiBold, modifier = Modifier.width(SLOT_COLUMN))
    Txt(t.spoiler.item, size = 11.5.sp, color = p.muted, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(ARROW_COLUMN))
    Txt(t.spoiler.slot, size = 11.5.sp, color = p.muted, weight = FontWeight.SemiBold, modifier = Modifier.width(SLOT_COLUMN))
    Txt(t.spoiler.check, size = 11.5.sp, color = p.muted, weight = FontWeight.SemiBold, modifier = Modifier.weight(1.2f))
    Spacer(Modifier.width(CHECKED_COLUMN))
  }
}

/** Slot · Item @ Slot · Location (the item is at that check); sphere 0 has no location (it is there from the start). */
@Composable
private fun StepRow(step: Step, odd: Boolean) {
  val p = LocalPalette.current
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (odd) p.panelAlt else Color.Transparent)
      .padding(horizontal = 10.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.width(SLOT_COLUMN)) { step.itemSlot?.let { SlotTag(it) } }
    Txt(step.item, size = 13.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 8.dp))
    Txt(if (step.location != null) "@" else "", size = 13.sp, color = p.muted, modifier = Modifier.width(ARROW_COLUMN))
    Box(Modifier.width(SLOT_COLUMN)) { step.locationSlot?.let { SlotTag(it) } }
    Txt(step.location.orEmpty(), size = 12.5.sp, color = p.muted, modifier = Modifier.weight(1.2f))
    CheckedMark(step.checked)
  }
}

/** A step on a phone: Slot · Item, and below it @ Slot · Location. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(step: Step, odd: Boolean) {
  val p = LocalPalette.current
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (odd) p.panelAlt else Color.Transparent)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
      step.itemSlot?.let { SlotTag(it) }
      Txt(step.item, size = 13.sp, weight = FontWeight.SemiBold)
      if (step.checked) CheckedMark(true)
    }
    if (step.location != null) {
      FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt("@ ", size = 12.5.sp, color = p.muted)
        step.locationSlot?.let { SlotTag(it) }
        Txt(step.location, size = 12.5.sp, color = p.muted)
      }
    }
  }
}

/** A slot in its colour. */
@Composable
private fun SlotTag(slot: String) {
  val p = LocalPalette.current
  val hue = p.hue(slot)
  Txt(
    slot, size = 11.5.sp, weight = FontWeight.SemiBold, color = hue, maxLines = 1,
    modifier = Modifier.padding(end = 8.dp).clip(CircleShape).background(hue.copy(alpha = 0.14f))
      .padding(horizontal = 8.dp, vertical = 2.dp),
  )
}

/** How many lines of the log show at once. */
private const val PAGE = 300

/** How long the search waits after the last key before it asks the server. */
private const val SEARCH_DELAY_MS = 500L

/** [text] for a URL's query: everything but letters, digits and `-._~` as its UTF-8 bytes, percent-encoded. */
internal fun urlEncoded(text: String): String = buildString {
  for (byte in text.encodeToByteArray()) {
    val c = byte.toInt() and 0xff
    if (c.toChar().let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-._~" }) append(c.toChar())
    else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
  }
}

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
private fun FullLog(id: String, total: Int, narrow: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // The page shown: its first line's number and its lines; null while the first one loads
  var page by remember(id) { mutableStateOf<SpoilerLines?>(null) }
  var wanted by remember(id) { mutableStateOf(0) }
  var problem by remember(id) { mutableStateOf<String?>(null) }
  LaunchedEffect(id, wanted) {
    val answer = call("GET", "api/sessions/$id/spoiler/log?from=$wanted&count=$PAGE", null) {
      ApiJson.decodeFromString(SpoilerLines.serializer(), it)
    }
    when (answer) {
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
    val answer = call("GET", "api/sessions/$id/spoiler/search?q=${urlEncoded(q.take(SpoilerFindings.MAX_QUERY))}", null) {
      ApiJson.decodeFromString(SpoilerFindings.serializer(), it)
    }
    when (answer) {
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
            result.lines.isEmpty() -> t.spoiler.none
            else -> "${current + 1} / ${result.lines.size}${if (result.more) "+" else ""}"
          },
          size = 12.5.sp, maxLines = 1,
          color = if (searchProblem != null || result?.lines?.isEmpty() == true) p.trap else p.muted,
        )
        if (lines.isNotEmpty()) {
          IconButton({ step(-1) }, Icon.CHEVRON, Modifier.size(30.dp).rotate(180f))
          IconButton({ step(1) }, Icon.CHEVRON, Modifier.size(30.dp))
        }
      }
    }
  }
  Section(t.spoiler.fullLog, subtitle = t.spoiler.fullLogSubtitle, actions = {
    if (!narrow) search(Modifier)
  }) {
    if (narrow) search(Modifier.fillMaxWidth())
    problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    Column(Modifier.fillMaxWidth().card(p)) {
      Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        SelectionContainer {
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
        Divider()
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          val first = shown.from
          val last = shown.from + shown.lines.size
          Txt(
            t.spoiler.linesOf(t.number(first + 1), t.number(last), t.number(shown.total)), size = 12.5.sp, color = p.muted,
            modifier = Modifier.weight(1f),
          )
          if (first > 0) Tip(t.spoiler.earlierLines) {
            IconButton({ wanted = (first - PAGE).coerceAtLeast(0) }, Icon.CHEVRON, Modifier.size(30.dp).rotate(90f))
          }
          if (last < shown.total) Tip(t.spoiler.laterLines) {
            IconButton({ wanted = last }, Icon.CHEVRON, Modifier.size(30.dp).rotate(-90f))
          }
        }
      }
    }
  }
}
