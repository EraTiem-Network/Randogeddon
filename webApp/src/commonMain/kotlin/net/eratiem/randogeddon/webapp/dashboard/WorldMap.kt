package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import net.eratiem.randogeddon.webapp.ui.scrollingBoth
import kotlin.math.ceil
import kotlin.math.max

/** A location on the map: checked, open, or open and reached by the logic now. */
internal enum class MapState { CHECKED, OPEN, AVAILABLE }

/** What the map shows: every location, or only the open ones, or only those available now. */
internal enum class MapFilter { ALL, OPEN, AVAILABLE }

/**
 * A box of the drawn map: a region, or a group of a big region's locations by their names; [x], [y] in dp. The map
 * is a tree from its start: [children] are the boxes first reached from this one (a line goes to each), [also] the
 * others it connects to (said on hover, without a line: lines for them would cross).
 */
internal class MapNode(val name: String, val locations: List<String>) {
  var column = 0
  var x = 0f
  var y = 0f
  val children = mutableListOf<Int>()
  val also = mutableListOf<Int>()
  val rows get() = ceil(locations.size / DOTS_PER_ROW.toFloat()).toInt()
  val height get() = HEADER + rows * DOT_STEP + if (locations.isEmpty()) 0f else PADDING
}

/** A map laid out: its boxes (each with the ones it leads to) and its size in dp. */
internal class MapLayout(val nodes: List<MapNode>, val width: Float, val height: Float)

private const val DOTS_PER_ROW = 10
private const val DOT = 10f
private const val DOT_STEP = 14f
private const val HEADER = 28f
private const val PADDING = 10f
private const val NODE_WIDTH = 2 * 12f + DOTS_PER_ROW * DOT_STEP - (DOT_STEP - DOT)
/** Between two columns: room for the line that branches off to a box's targets, in its middle. */
private const val COLUMN_GAP = 36f
private const val ROW_GAP = 12f
/** Between the parts of a map that aren't reached from each other. */
private const val PART_GAP = 28f
/** Left free beside the boxes for the bars that scroll the map. */
private const val SCROLL_BAR = 10f
/** The map's view at its tallest: a taller map scrolls in it, so its sideways bar stays in sight. */
private const val MAP_VIEW = 600f

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
 * [map] laid out as a tree from its start, left to right: a box's column is how far it is from the start, and the
 * boxes first reached from it (and a big region's name groups) stand in the next column, one below the other from
 * its own height on; whatever they lead to takes its room below them before the next one. So every line stays in
 * the gap between two columns, beside the boxes it joins: none crosses a box or another line. What a box connects to
 * besides (a way back, a second way in) is kept as [MapNode.also]. Parts that can't be reached from the start follow
 * below it, each from its own first box.
 */
internal fun layoutMap(map: MapView): MapLayout {
  val nodes = mutableListOf<MapNode>()
  val regionNode = IntArray(map.regions.size)
  // The regions first, then the big ones' groups: a group is its region's first child
  val groups = mutableListOf<Triple<Int, String, List<String>>>()
  map.regions.forEachIndexed { i, region ->
    val split = nameGroups(region.locations)
    regionNode[i] = nodes.size
    nodes += MapNode(region.name, split?.first ?: region.locations)
    split?.second?.forEach { (name, locations) -> groups += Triple(regionNode[i], name, locations) }
  }
  val regions = nodes.size
  for ((parent, name, locations) in groups) {
    nodes[parent].children += nodes.size
    nodes += MapNode(name, locations)
  }
  val neighbours = Array(regions) { mutableListOf<Int>() }
  for (pair in map.links) {
    val a = pair.getOrNull(0)?.let { regionNode.getOrNull(it) } ?: continue
    val b = pair.getOrNull(1)?.let { regionNode.getOrNull(it) } ?: continue
    if (a == b) continue
    neighbours[a] += b
    neighbours[b] += a
  }

  // The tree: breadth first from the start, then from each part that isn't reached from it
  val parent = IntArray(nodes.size) { -1 }
  val seen = BooleanArray(regions)
  val roots = mutableListOf<Int>()
  for (root in 0 until regions) {
    if (seen[root]) continue
    seen[root] = true
    roots += root
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
      val at = queue.removeFirst()
      for (next in neighbours[at]) if (!seen[next]) {
        seen[next] = true
        parent[next] = at
        nodes[at].children += next
        queue += next
      }
    }
  }
  for (a in 0 until regions) for (b in neighbours[a]) {
    if (parent[b] != a && parent[a] != b && b !in nodes[a].also) nodes[a].also += b
  }

  // Each box at the top of its room; its children below each other in the next column, each with its own room
  var deepest = 0
  fun place(at: Int, column: Int, top: Float): Float {
    val node = nodes[at]
    node.column = column
    node.x = MARGIN + column * (NODE_WIDTH + COLUMN_GAP)
    node.y = top
    deepest = max(deepest, column)
    var y = top
    for (child in node.children) y += place(child, column + 1, y) + ROW_GAP
    return max(node.height, y - top - if (node.children.isEmpty()) 0f else ROW_GAP)
  }
  var y = MARGIN
  for (root in roots) y += place(root, 0, y) + PART_GAP
  return MapLayout(nodes, 2 * MARGIN + (deepest + 1) * (NODE_WIDTH + COLUMN_GAP) - COLUMN_GAP, y - PART_GAP + MARGIN)
}

/** Around the map, inside its panel. */
private const val MARGIN = 8f

/** What the pointer is over: a location (its name, its state) or a box (its name). */
private data class Hovered(val name: String, val state: MapState?, val at: Offset, val node: MapNode)

/**
 * A slot's map: its regions as boxes, a tree from its start (a line from each box branching off to the ones first
 * reached from it; a box's other connections on hover), each location a dot in its box (checked, open, available
 * now), its name on hover; filtered by state. Drawn on one canvas: a slot can
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
    // A view onto the map, as tall as it at most: larger than it, the map is scrolled by the view's bars or by
    // dragging it (room for the bars beside the boxes)
    Box(Modifier.fillMaxWidth().heightIn(max = MAP_VIEW.dp).clip(RoundedCornerShape(12.dp)).background(p.panelAlt).scrollingBoth()) {
      Box(Modifier.size((layout.width + SCROLL_BAR).dp, (layout.height + SCROLL_BAR).dp)) {
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
          // One line from each box to what it leads to: out of its right edge into the gap, down the gap's middle,
          // and off to each target's left edge (its first target stands at its own height: straight across)
          val line = Stroke(1.5f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
          for (node in layout.nodes) {
            if (node.children.isEmpty()) continue
            val from = node.y + HEADER / 2
            val trunk = node.x + NODE_WIDTH + COLUMN_GAP / 2
            val targets = node.children.map { layout.nodes[it] }
            drawPath(
              Path().apply {
                moveTo((node.x + NODE_WIDTH) * d, from * d)
                lineTo(trunk * d, from * d)
                lineTo(trunk * d, (targets.maxOf { it.y } + HEADER / 2) * d)
                for (target in targets) {
                  moveTo(trunk * d, (target.y + HEADER / 2) * d)
                  lineTo(target.x * d, (target.y + HEADER / 2) * d)
                }
              },
              p.muted.copy(alpha = 0.55f), style = line,
            )
          }
          // What the box under the pointer connects to besides: their edges say so
          val also = hovered?.node?.also.orEmpty()
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
            if (layout.nodes.indexOf(node) in also) {
              drawRoundRect(p.accent, topLeft, size, CornerRadius(10 * d), style = Stroke(2f * d))
            }
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
            // A box's other connections, which have no line
            if (h.state == null && h.node.also.isNotEmpty()) {
              Txt(t.dialogs.mapAlso(h.node.also.joinToString(", ") { layout.nodes[it].name }), size = 12.sp, color = p.accent)
            }
          }
        }
      }
    }
  }
}
