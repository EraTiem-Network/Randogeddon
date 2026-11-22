package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.MapView
import net.eratiem.randogeddon.shared.SentItemView
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.app.LocalFonts
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.manage.PageButton
import net.eratiem.randogeddon.webapp.ui.Dot
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Txt
import kotlin.math.ceil
import kotlin.math.max

/** A location on the map: checked, open, or open and reached by the logic now. */
internal enum class MapState { CHECKED, OPEN, AVAILABLE }

/** What the map shows: every location, or only the open ones, or only those available now. */
internal enum class MapFilter { ALL, OPEN, AVAILABLE }

/** A box of the drawn map: a region, or a group of a big region's locations by their names; [x], [y] in dp. */
internal class MapNode(val name: String, val locations: List<String>) {
  var column = 0
  var x = 0f
  var y = 0f
  val rows get() = ceil(locations.size / DOTS_PER_ROW.toFloat()).toInt()
  val height get() = HEADER + rows * DOT_STEP + if (locations.isEmpty()) 0f else PADDING
}

/** A map laid out: its boxes, which connect (pairs of their indices), and its size in dp. */
internal class MapLayout(val nodes: List<MapNode>, val links: List<Pair<Int, Int>>, val width: Float, val height: Float)

private const val DOTS_PER_ROW = 10
private const val DOT = 10f
private const val DOT_STEP = 14f
private const val HEADER = 28f
private const val PADDING = 10f
private const val NODE_WIDTH = 2 * 12f + DOTS_PER_ROW * DOT_STEP - (DOT_STEP - DOT)
private const val COLUMN_GAP = 56f
private const val ROW_GAP = 14f
/** A column taller than this wraps into narrower ones beside it (a hub reaches many regions at once). */
private const val COLUMN_HEIGHT = 560f
private const val WRAP_GAP = 14f

/** A region with this many locations or more is split into groups by their names, where they have groups. */
private const val SPLIT_FROM = 16

/**
 * What separates a location's group from the rest of its name, the first one found of these in their order:
 * `Lake Desolation (Lower): Chicken chest`, `Grub-Crossroads_Acid`.
 */
private val SEPARATORS = listOf(": ", " - ", " – ", "-")

/**
 * A big region's locations in groups by their names: first by the part before a separator ([SEPARATORS]; groups of
 * two or more), then what is left by a first word, then by a last word they share (`Unbreakable_Heart`,
 * `Dirtmouth_Stag`; groups of three or more). Only where that makes two groups or more; the rest stay with the
 * region. `null`: it isn't split.
 */
internal fun nameGroups(locations: List<String>): Pair<List<String>, List<Pair<String, List<String>>>>? {
  if (locations.size < SPLIT_FROM) return null
  val groups = mutableListOf<Pair<String, List<String>>>()
  var rest = locations
  fun take(least: Int, key: (String) -> String?) {
    val found = rest.groupBy(key).filterKeys { it != null }.filterValues { it.size >= least }
    found.forEach { (name, names) -> groups += name!! to names }
    val grouped = found.values.flatten().toSet()
    rest = rest.filter { it !in grouped }
  }
  take(2) { name -> SEPARATORS.firstNotNullOfOrNull { separator -> name.indexOf(separator).takeIf { it > 0 } }?.let { name.substring(0, it) } }
  take(3) { name -> name.split(WORD).takeIf { it.size > 1 }?.first() }
  take(3) { name -> name.split(WORD).takeIf { it.size > 1 }?.last() }
  return if (groups.size < 2) null else rest to groups
}

/** What separates a name's words. */
private val WORD = Regex("[ _]+")

/**
 * [map] laid out: its boxes (a big region's name groups as boxes of their own, linked to it) in columns by how far
 * they are from its start (what can't be reached from it after), each column ordered by its links to the columns
 * before it, so that they cross less.
 */
internal fun layoutMap(map: MapView): MapLayout {
  val nodes = mutableListOf<MapNode>()
  val regionNode = IntArray(map.regions.size)
  val parentOf = mutableMapOf<Int, Int>()
  // The boxes; a group's column follows its region's, below
  val pending = mutableListOf<Pair<String, List<String>>>()
  val pendingParent = mutableListOf<Int>()
  map.regions.forEachIndexed { i, region ->
    val split = nameGroups(region.locations)
    regionNode[i] = nodes.size
    nodes += MapNode(region.name, split?.first ?: region.locations)
    split?.second?.forEach { group -> pending += group; pendingParent += regionNode[i] }
  }
  pending.forEachIndexed { i, (name, locations) ->
    parentOf[nodes.size] = pendingParent[i]
    nodes += MapNode(name, locations)
  }
  val links = map.links.mapNotNull { pair ->
    val a = pair.getOrNull(0)?.let { regionNode.getOrNull(it) } ?: return@mapNotNull null
    val b = pair.getOrNull(1)?.let { regionNode.getOrNull(it) } ?: return@mapNotNull null
    a to b
  } + parentOf.map { (child, parent) -> parent to child }
  val neighbours = Array(nodes.size) { mutableListOf<Int>() }
  links.forEach { (a, b) -> neighbours[a] += b; neighbours[b] += a }

  // Columns: breadth first from the start, then from each part that can't be reached from it
  val column = IntArray(nodes.size) { -1 }
  var offset = 0
  for (start in nodes.indices) {
    if (column[start] >= 0) continue
    var deepest = offset
    val queue = ArrayDeque(listOf(start))
    column[start] = offset
    while (queue.isNotEmpty()) {
      val at = queue.removeFirst()
      deepest = max(deepest, column[at])
      for (next in neighbours[at]) if (column[next] < 0) { column[next] = column[at] + 1; queue += next }
    }
    offset = deepest + 1
  }

  // Each column ordered by where its neighbours in the columns before it are, then stacked; a tall one wraps
  val columns = nodes.indices.groupBy { column[it] }.entries.sortedBy { it.key }
  val rank = DoubleArray(nodes.size)
  var height = 0f
  var x = 8f
  for ((c, members) in columns.map { it.key to it.value }) {
    val ordered = members.sortedBy { n ->
      val before = neighbours[n].filter { column[it] < c }
      if (before.isEmpty()) n.toDouble() else before.map { rank[it] }.average()
    }
    var y = 8f
    var wrapped = 0
    ordered.forEachIndexed { i, n ->
      if (y > 8f && y + nodes[n].height > COLUMN_HEIGHT) { wrapped++; y = 8f }
      rank[n] = i.toDouble()
      nodes[n].column = c
      nodes[n].x = x + wrapped * (NODE_WIDTH + WRAP_GAP)
      nodes[n].y = y
      y += nodes[n].height + ROW_GAP
      height = max(height, y)
    }
    x += (wrapped + 1) * (NODE_WIDTH + WRAP_GAP) - WRAP_GAP + COLUMN_GAP
  }
  return MapLayout(nodes, links, x - COLUMN_GAP + 8f, height)
}

/** What the pointer is over: a location (its name, its state) or a box (its name). */
private data class Hovered(val name: String, val state: MapState?, val at: Offset, val node: MapNode)

/**
 * A slot's map: its regions as boxes in columns from its start, linked as its logic connects them, each location a
 * dot in its box (checked, open, available now), its name on hover; filtered by state. Drawn on one canvas: a slot can
 * have a thousand locations.
 */
@Composable
internal fun WorldMap(map: MapView, missing: Set<String>, available: Set<String>, sent: List<SentItemView>) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val fonts = LocalFonts.current
  val layout = remember(map) { layoutMap(map) }
  val found = remember(sent) { sent.associateBy { it.location } }
  fun stateOf(location: String) = when {
    location !in missing -> MapState.CHECKED
    location in available -> MapState.AVAILABLE
    else -> MapState.OPEN
  }
  var filter by remember(map) { mutableStateOf(MapFilter.ALL) }
  var hovered by remember(map) { mutableStateOf<Hovered?>(null) }
  fun shown(state: MapState) = when (filter) {
    MapFilter.ALL -> true
    MapFilter.OPEN -> state != MapState.CHECKED
    MapFilter.AVAILABLE -> state == MapState.AVAILABLE
  }
  val counts = remember(layout, missing, available) {
    layout.nodes.flatMap { it.locations }.groupingBy { stateOf(it) }.eachCount()
  }
  val stateText = { state: MapState ->
    when (state) {
      MapState.CHECKED -> t.dialogs.mapChecked
      MapState.OPEN -> t.dialogs.mapOpen
      MapState.AVAILABLE -> t.dialogs.mapAvailable
    }
  }
  val colour = { state: MapState ->
    when (state) {
      MapState.CHECKED -> p.muted
      MapState.OPEN -> p.text.copy(alpha = 0.45f)
      MapState.AVAILABLE -> p.ok
    }
  }
  val measurer = rememberTextMeasurer()
  val nameStyle = TextStyle(color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = fonts.text)
  val countStyle = TextStyle(color = p.muted, fontSize = 11.sp, fontFamily = fonts.text)

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
      MapFilter.entries.forEach { f ->
        val label = when (f) {
          MapFilter.ALL -> t.dialogs.mapAll
          MapFilter.OPEN -> t.dialogs.mapOpen
          MapFilter.AVAILABLE -> t.dialogs.mapAvailable
        }
        PageButton(label, f == filter) { filter = f }
      }
      // What the colours mean, with how many there are
      MapState.entries.forEach { state ->
        Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
          Dot(colour(state), 9.dp)
          Txt("${stateText(state)} (${t.number(counts[state] ?: 0)})", size = 12.sp, color = p.muted)
        }
      }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.panelAlt).horizontalScroll(rememberScrollState())) {
      Box(Modifier.size(layout.width.dp, layout.height.dp)) {
        Canvas(
          Modifier.size(layout.width.dp, layout.height.dp).pointerInput(layout) {
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Exit) { hovered = null; continue }
                val at = event.changes.firstOrNull()?.position ?: continue
                val x = at.x / density
                val y = at.y / density
                val node = layout.nodes.firstOrNull { x in it.x..(it.x + NODE_WIDTH) && y in it.y..(it.y + it.height) }
                hovered = node?.let { n ->
                  val i = ((y - n.y - HEADER) / DOT_STEP).toInt() * DOTS_PER_ROW + ((x - n.x - 12f) / DOT_STEP).toInt()
                  val inDots = y >= n.y + HEADER && x >= n.x + 12f && x < n.x + 12f + DOTS_PER_ROW * DOT_STEP
                  val location = n.locations.getOrNull(i)?.takeIf { inDots }
                  if (location != null) Hovered(location, stateOf(location), Offset(x, y), n) else Hovered(n.name, null, Offset(x, y), n)
                }
              }
            }
          },
        ) {
          val d = density
          // The links behind the boxes: from a box's right edge to the other's left (or between their middles)
          for ((a, b) in layout.links) {
            val from = layout.nodes[a]
            val to = layout.nodes[b]
            val (left, right) = if (from.x <= to.x) from to to else to to from
            val start = Offset((left.x + if (left.x < right.x) NODE_WIDTH else NODE_WIDTH / 2) * d, (left.y + HEADER / 2) * d)
            val end = Offset((right.x + if (left.x < right.x) 0f else NODE_WIDTH / 2) * d, (right.y + HEADER / 2) * d)
            drawLine(p.border, start, end, strokeWidth = 1.5f * d)
          }
          for (node in layout.nodes) {
            val states = node.locations.map { stateOf(it) }
            val anyShown = states.any { shown(it) } || filter == MapFilter.ALL
            val dim = if (anyShown) 1f else 0.35f
            val topLeft = Offset(node.x * d, node.y * d)
            val size = Size(NODE_WIDTH * d, node.height * d)
            drawRoundRect(p.panel.copy(alpha = dim), topLeft, size, CornerRadius(10 * d))
            // Something available in it: its edge says so
            val edge = if (MapState.AVAILABLE in states) p.ok else p.border
            drawRoundRect(edge.copy(alpha = dim), topLeft, size, CornerRadius(10 * d), style = Stroke(1.2f * d))
            val done = states.count { it == MapState.CHECKED }
            val count = if (node.locations.isEmpty()) "" else "$done/${node.locations.size}"
            val countWidth = if (count.isEmpty()) 0f else measurer.measure(count, countStyle).size.width.toFloat()
            drawText(
              measurer, node.name, Offset((node.x + 10f) * d, (node.y + 6f) * d),
              nameStyle.copy(color = p.text.copy(alpha = dim)), TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
              size = Size(max(1f, (NODE_WIDTH - 20f) * d - countWidth - 6 * d), 18 * d),
            )
            if (count.isNotEmpty()) drawText(measurer, count, Offset((node.x + NODE_WIDTH - 10f) * d - countWidth, (node.y + 7f) * d), countStyle)
            states.forEachIndexed { i, state ->
              val cx = node.x + 12f + (i % DOTS_PER_ROW) * DOT_STEP + DOT / 2
              val cy = node.y + HEADER + (i / DOTS_PER_ROW) * DOT_STEP + DOT / 2
              val c = colour(state).copy(alpha = if (shown(state)) 1f else 0.15f)
              if (state == MapState.OPEN) drawCircle(c, DOT / 2 * d - 0.8f * d, Offset(cx * d, cy * d), style = Stroke(1.6f * d))
              else drawCircle(c, DOT / 2 * d, Offset(cx * d, cy * d))
            }
          }
        }
        // The name on hover, beside the pointer (kept inside the map)
        hovered?.let { h ->
          val x = (h.at.x + 14f).coerceAtMost(layout.width - 260f).coerceAtLeast(4f)
          Column(
            Modifier.offset { IntOffset((x * density).toInt(), ((h.at.y + 14f) * density).toInt()) }
              .widthIn(max = 260.dp).clip(RoundedCornerShape(8.dp)).background(p.panel).border(1.dp, p.border, RoundedCornerShape(8.dp))
              .padding(horizontal = 10.dp, vertical = 6.dp),
          ) {
            Txt(h.name, size = 12.5.sp, weight = FontWeight.SemiBold)
            val detail = when (h.state) {
              null -> t.dialogs.mapRegion(h.node.locations.count { it !in missing }, h.node.locations.size)
              MapState.CHECKED -> found[h.name]?.let { t.dialogs.mapFound(it.item, it.to) } ?: stateText(MapState.CHECKED)
              else -> stateText(h.state)
            }
            Txt(detail, size = 12.sp, color = if (h.state == MapState.AVAILABLE) p.ok else p.muted)
          }
        }
      }
    }
  }
}
