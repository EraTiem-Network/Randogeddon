package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
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
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.Sphere
import net.eratiem.randogeddon.shared.Spoiler

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
      if (s.spheres.isNotEmpty()) Playthrough(s.spheres, narrow)
      FullLog(s.text, narrow)
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
  val search: @Composable (Modifier) -> Unit = { modifier -> FilterField(query, { query = it }, t.spoiler.searchSteps, modifier) }
  val controls: @Composable () -> Unit = {
    Select(listOf<Pair<String?, String>>(null to t.spoiler.everySlot) + slots.map { it to it }, slot, { slot = it })
    SmallButton(if (open.size == spheres.size) t.spoiler.foldAll else t.spoiler.unfoldAll, {
      open = if (open.size == spheres.size) emptySet() else spheres.map { it.number }.toSet()
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
      val visible = spheres.filter { shown(it.number).isNotEmpty() }
      if (visible.isEmpty()) {
        Txt(
          if (needle.isNotEmpty()) t.spoiler.nothingMatches(query.trim()) else t.spoiler.nothingFor(slot.orEmpty()),
          color = p.muted, modifier = Modifier.padding(16.dp),
        )
      }
      visible.forEachIndexed { i, sphere ->
        if (i > 0) Divider()
        val entries = shown(sphere.number)
        val unfolded = sphere.number in open || slot != null || needle.isNotEmpty()
        Row(
          Modifier.fillMaxWidth().hand().clickable { open = if (sphere.number in open) open - sphere.number else open + sphere.number }
            .padding(horizontal = if (narrow) 12.dp else 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Txt(if (unfolded) "▾" else "▸", size = 13.sp, color = p.muted, modifier = Modifier.width(12.dp))
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

/** A step whose location is checked already: a ✓ in the status colour (the column stays for the others). */
@Composable
private fun CheckedMark(checked: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  Box(Modifier.width(CHECKED_COLUMN), contentAlignment = Alignment.CenterEnd) {
    if (checked) Tip(t.spoiler.checkedTip) { Txt("✓", size = 13.sp, weight = FontWeight.Bold, color = p.ok) }
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

/** At most this many findings are marked; the count says when there are more. */
private const val MAX_FINDINGS = 2000

/** Where [query] occurs in [text] (ignoring case), at most [MAX_FINDINGS]; nothing for less than 2 characters. */
internal fun findings(text: String, query: String): List<Int> {
  if (query.length < 2) return emptyList()
  return buildList {
    var at = text.indexOf(query, 0, ignoreCase = true)
    while (at >= 0 && size < MAX_FINDINGS) {
      add(at)
      at = text.indexOf(query, at + query.length, ignoreCase = true)
    }
  }
}

/**
 * The whole log, monospace. The search marks every finding (the current one stronger) and counts them; the arrows,
 * or Enter and Shift+Enter, jump from one to the next.
 */
@Composable
private fun FullLog(text: String, narrow: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var query by remember { mutableStateOf("") }
  val q = query.trim()
  val found = remember(text, q) { findings(text, q) }
  var current by remember(text, q) { mutableStateOf(0) }
  val mark = p.warn.copy(alpha = 0.35f)
  val currentMark = p.accent.copy(alpha = 0.55f)
  val marked = remember(text, found, current, mark, currentMark) {
    buildAnnotatedString {
      append(text)
      found.forEachIndexed { n, start ->
        addStyle(SpanStyle(background = if (n == current) currentMark else mark), start, start + q.length)
      }
    }
  }
  val scroll = rememberScrollState()
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  val density = LocalDensity.current
  // The current finding a few lines below the top of the box
  LaunchedEffect(found, current, layout) {
    val l = layout ?: return@LaunchedEffect
    val at = found.getOrNull(current) ?: return@LaunchedEffect
    val top = l.getLineTop(l.getLineForOffset(at)) - with(density) { 48.dp.toPx() }
    scroll.animateScrollTo(top.toInt().coerceAtLeast(0))
  }
  fun step(by: Int) {
    if (found.isNotEmpty()) current = (current + by).mod(found.size)
  }
  val search: @Composable (Modifier) -> Unit = { modifier ->
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      FilterField(
        query, { query = it }, t.common.search,
        (if (narrow) Modifier.weight(1f) else Modifier.width(200.dp)).onPreviewKeyEvent { event ->
          if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
            step(if (event.isShiftPressed) -1 else 1)
            true
          } else false
        },
      )
      if (q.length >= 2) {
        Txt(
          when {
            found.isEmpty() -> t.spoiler.none
            found.size >= MAX_FINDINGS -> "${current + 1} / ${MAX_FINDINGS}+"
            else -> "${current + 1} / ${found.size}"
          },
          size = 12.5.sp, color = if (found.isEmpty()) p.trap else p.muted, maxLines = 1,
        )
        IconButton({ step(-1) }, Icon.CHEVRON, Modifier.size(30.dp).rotate(180f))
        IconButton({ step(1) }, Icon.CHEVRON, Modifier.size(30.dp))
      }
    }
  }
  Section(t.spoiler.fullLog, subtitle = t.spoiler.fullLogSubtitle, actions = {
    if (!narrow) search(Modifier)
  }) {
    if (narrow) search(Modifier.fillMaxWidth())
    Box(Modifier.fillMaxWidth().card(p).heightIn(max = 560.dp)) {
      SelectionContainer {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(12.dp)) {
          BasicText(
            marked, style = TextStyle(color = p.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            onTextLayout = { layout = it },
          )
        }
      }
    }
  }
}
