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
 * A seed's spoiler log: the playthrough sphere by sphere (each foldable; the shortest way through the seed, what each
 * step makes reachable), and the full log, searchable. [onBack]: closes it.
 */
@Composable
fun SpoilerView(seed: String, onBack: () -> Unit) {
  val p = LocalPalette.current
  var spoiler by remember(seed) { mutableStateOf<Spoiler?>(null) }
  var problem by remember(seed) { mutableStateOf<String?>(null) }
  LaunchedEffect(seed) {
    val result = Platform.request("GET", "api/spoiler/$seed")
    when {
      result == null -> problem = "The server can't be reached"
      result.status == 200 -> spoiler = runCatching { ApiJson.decodeFromString(Spoiler.serializer(), result.body) }.getOrNull()
      else -> problem = runCatching { ApiJson.decodeFromString(ApiError.serializer(), result.body).message }
        .getOrDefault("Failed (${result.status})")
    }
  }

  BoxWithConstraints {
    // A phone: the steps as two lines each, the pickers and the search below their headings
    val narrow = maxWidth < NARROW
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconButton(onBack, Icon.BACK)
        Column(Modifier.weight(1f)) {
          Txt("Spoiler log", size = 20.sp, weight = FontWeight.Bold)
          Txt("Seed $seed" + (spoiler?.let { " · ${it.spheres.size} spheres" } ?: ""), size = 12.5.sp, color = p.muted)
        }
      }
      problem?.let { Txt(it, size = 13.sp, color = p.trap) }
      val s = spoiler
      if (s == null) {
        if (problem == null) Txt("Loading…", color = p.muted)
        return@Column
      }
      if (s.spheres.isNotEmpty()) Playthrough(s.spheres, narrow)
      FullLog(s.text, narrow)
    }
  }
}

/** One step of a sphere: an item for [itemSlot], found at [location] in [locationSlot] (none for sphere 0). */
private data class Step(val item: String, val itemSlot: String?, val location: String?, val locationSlot: String?)

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
  val steps = remember(spheres) { spheres.associate { it.number to it.entries.map(::step) } }
  val slots = remember(steps) {
    steps.values.flatten().flatMap { listOfNotNull(it.itemSlot, it.locationSlot) }.distinct().sortedBy { it.lowercase() }
  }
  // Folded at first: it is the whole way through the seed; a slot filter unfolds what concerns that slot
  var open by remember { mutableStateOf(emptySet<Int>()) }
  var slot by remember { mutableStateOf<String?>(null) }
  fun shown(number: Int) = steps[number].orEmpty().filter { slot == null || it.itemSlot == slot || it.locationSlot == slot }
  val controls: @Composable () -> Unit = {
    Select(listOf<Pair<String?, String>>(null to "Every slot") + slots.map { it to it }, slot, { slot = it })
    SmallButton(if (open.size == spheres.size) "Fold all" else "Unfold all", {
      open = if (open.size == spheres.size) emptySet() else spheres.map { it.number }.toSet()
    })
  }
  Section(
    "Playthrough", subtitle = "the way through the seed, sphere by sphere: what becomes reachable, and is needed, at each step",
    actions = { if (!narrow) controls() },
  ) {
    if (narrow) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
    Column(Modifier.fillMaxWidth().card(p)) {
      val visible = spheres.filter { shown(it.number).isNotEmpty() }
      if (visible.isEmpty()) Txt("Nothing for $slot.", color = p.muted, modifier = Modifier.padding(16.dp))
      visible.forEachIndexed { i, sphere ->
        if (i > 0) Divider()
        val entries = shown(sphere.number)
        val unfolded = sphere.number in open || slot != null
        Row(
          Modifier.fillMaxWidth().hand().clickable { open = if (sphere.number in open) open - sphere.number else open + sphere.number }
            .padding(horizontal = if (narrow) 12.dp else 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Txt(if (unfolded) "▾" else "▸", size = 13.sp, color = p.muted, modifier = Modifier.width(12.dp))
          Txt(
            if (sphere.number == 0) "Sphere 0 · what everyone starts with" else "Sphere ${sphere.number}",
            size = 13.5.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
          )
          Pill("${entries.size} ${if (entries.size == 1) "step" else "steps"}", p.muted)
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
private val ARROW_COLUMN = 28.dp

/** The table's columns: whose item, the item, then the slot and location it lies at. */
@Composable
private fun StepHeader() {
  val p = LocalPalette.current
  Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Txt("Slot", size = 11.5.sp, color = p.muted, weight = FontWeight.SemiBold, modifier = Modifier.width(SLOT_COLUMN))
    Txt("Item", size = 11.5.sp, color = p.muted, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(ARROW_COLUMN))
    Txt("Slot", size = 11.5.sp, color = p.muted, weight = FontWeight.SemiBold, modifier = Modifier.width(SLOT_COLUMN))
    Txt("Check", size = 11.5.sp, color = p.muted, weight = FontWeight.SemiBold, modifier = Modifier.weight(1.2f))
  }
}

/** Slot · Item → Slot · Location; sphere 0 has no location (it is there from the start). */
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
    Txt(if (step.location != null) "→" else "", size = 13.sp, color = p.muted, modifier = Modifier.width(ARROW_COLUMN))
    Box(Modifier.width(SLOT_COLUMN)) { step.locationSlot?.let { SlotTag(it) } }
    Txt(step.location.orEmpty(), size = 12.5.sp, color = p.muted, modifier = Modifier.weight(1.2f))
  }
}

/** A step on a phone: Slot · Item, and below it → Slot · Location. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(step: Step, odd: Boolean) {
  val p = LocalPalette.current
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (odd) p.panelAlt else Color.Transparent)
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      step.itemSlot?.let { SlotTag(it) }
      Txt(step.item, size = 13.sp, weight = FontWeight.SemiBold)
    }
    if (step.location != null) {
      FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt("→ ", size = 12.5.sp, color = p.muted)
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
        query, { query = it }, "Search…",
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
            found.isEmpty() -> "none"
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
  Section("Full spoiler log", subtitle = "every check of every slot, the options and the playthrough", actions = {
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
