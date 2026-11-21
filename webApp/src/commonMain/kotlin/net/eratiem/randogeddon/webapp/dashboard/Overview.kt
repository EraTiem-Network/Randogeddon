package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.PlayerStatus
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.i18n.age
import net.eratiem.randogeddon.shared.i18n.duration
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.app.date
import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.ui.Dot
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card

/**
 * Lays [count] cells out in rows of as many columns of at least [minWidth] as fit into [width]. [balanced]: rows as
 * even as possible (3 + 3 instead of 5 + 1). [fillHeight]: the rows share the height the grid is given.
 */
@Composable
internal fun Grid(
  count: Int,
  width: Dp,
  minWidth: Dp,
  modifier: Modifier = Modifier,
  gap: Dp = GAP,
  balanced: Boolean = false,
  fillHeight: Boolean = false,
  /**
   * Cells of a row as tall as the tallest one. That height comes from the cells' measured minimum, which content
   * that wraps (a line of pills) can underestimate: there the cells' ends would be cut off, so they keep their own.
   */
  equalHeight: Boolean = true,
  cell: @Composable (Int, Modifier) -> Unit,
) {
  val fitting = ((width + gap) / (minWidth + gap)).toInt().coerceIn(1, count.coerceAtLeast(1))
  val rowCount = (count + fitting - 1) / fitting
  val columns = if (balanced && rowCount > 0) (count + rowCount - 1) / rowCount else fitting
  Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
    (0 until count).chunked(columns).forEach { row ->
      // Cells of a row share the height of the tallest one (unless they keep their own)
      Row(
        when {
          fillHeight -> Modifier.weight(1f)
          equalHeight -> Modifier.height(IntrinsicSize.Min)
          else -> Modifier
        },
        horizontalArrangement = Arrangement.spacedBy(gap),
      ) {
        row.forEach { cell(it, if (equalHeight || fillHeight) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f)) }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

/**
 * Lays [count] cells out in as many columns of at least [minWidth] as fit, each cell as wide as a column and as tall
 * as it is: each goes into the column that is shortest so far (the leftmost of equal ones), so the cells fill the
 * space instead of leaving rows' gaps, and their order reads left to right. Columns don't widen for few cells.
 */
@Composable
internal fun Masonry(count: Int, minWidth: Dp, modifier: Modifier = Modifier, gap: Dp = GAP, cell: @Composable (Int) -> Unit) {
  Layout(content = { repeat(count) { cell(it) } }, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
    val gapPx = gap.roundToPx()
    val columns = ((constraints.maxWidth + gapPx) / (minWidth.roundToPx() + gapPx)).coerceAtLeast(1)
    val columnWidth = ((constraints.maxWidth - gapPx * (columns - 1)) / columns).coerceAtLeast(0)
    val tops = IntArray(columns)
    val placed = measurables.map { measurable ->
      val placeable = measurable.measure(Constraints.fixedWidth(columnWidth))
      val column = tops.indices.minBy { tops[it] }
      val top = tops[column]
      tops[column] += placeable.height + gapPx
      Triple(placeable, column, top)
    }
    val height = ((tops.maxOrNull() ?: 0) - gapPx).coerceAtLeast(0)
    layout(constraints.maxWidth, height) {
      placed.forEach { (placeable, column, top) -> placeable.place(column * (columnWidth + gapPx), top) }
    }
  }
}

/** A tile: [label], [value], below it [pills], or [sub] where there are none. */
private data class Tile(
  val label: String,
  val value: String,
  val sub: String,
  val color: Color,
  val icon: Icon,
  val tip: String? = null,
  val pills: List<TilePill> = emptyList(),
)

/** A part of a tile: its [icon] and [text] (a number; empty: the icon alone), what it is as its [tip]. */
private data class TilePill(val icon: Icon, val text: String, val tip: String, val color: Color)

@Composable
internal fun Stats(s: Summary, now: Long, width: Dp, modifier: Modifier = Modifier, fillHeight: Boolean = false) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val totals = s.totals
  val open = s.hints.count { !it.found }
  // The slots' states, counted from the slots (also right for an archive from before these counts)
  val states = s.players.groupingBy { it.status }.eachCount()
  fun count(status: PlayerStatus) = states[status] ?: 0
  val tiles = buildList {
    val d = t.dashboard
    // Done: complete, at the goal, released; with release mode auto a goal becomes complete (said only if there is one)
    val done = s.players.count { it.status.done }
    // A state's pill, its name as the tip
    fun state(status: PlayerStatus, icon: Icon, color: Color) =
      TilePill(icon, "${count(status)}", d.status(status).replaceFirstChar { it.uppercase() }, color)
    val parts = listOfNotNull(
      state(PlayerStatus.COMPLETE, Icon.CHECK, p.ok),
      state(PlayerStatus.GOAL, Icon.FLAG, p.ok).takeIf { s.releaseMode != "auto" || count(PlayerStatus.GOAL) > 0 },
      state(PlayerStatus.RELEASED, Icon.RELEASE, p.progression).takeIf { count(PlayerStatus.RELEASED) > 0 },
    )
    add(Tile(d.finished, "$done / ${s.players.size}", d.slotsFinished, p.ok, Icon.FLAG, pills = parts))
    val online = s.players.count { it.online }
    // A locked slot hasn't started either: it can't connect before its key turns up
    val notStarted = count(PlayerStatus.NOT_STARTED) + count(PlayerStatus.LOCKED)
    val waiting = listOfNotNull(
      state(PlayerStatus.IDLE, Icon.PAUSE, p.muted).takeIf { count(PlayerStatus.IDLE) > 0 },
      TilePill(Icon.CIRCLE, "$notStarted", d.status(PlayerStatus.NOT_STARTED).replaceFirstChar { it.uppercase() }, p.muted)
        .takeIf { notStarted > 0 },
    )
    add(Tile(d.slots, "$online / ${s.players.size}", d.everyoneOnline, p.useful, Icon.USER, pills = waiting))
    if (totals.lockable > 0) {
      add(Tile(d.unlocked, "${totals.lockable - totals.locked} / ${totals.lockable}", d.slotsOpened, p.key, Icon.KEY))
    }
    if (showHints(s)) {
      add(Tile(d.hints, "${s.hints.size}", d.notFoundYet(open), p.progression, Icon.BULB, pills = listOf(TilePill(Icon.SEARCH, "$open", d.tipOpen, p.warn))))
    }
    // Items sent; an archive from before has its slots' received items only
    val sent = totals.itemsSent.takeIf { it > 0 } ?: s.players.sumOf { it.itemsReceived }
    val progression = totals.progressionSent.takeIf { totals.itemsSent > 0 } ?: s.players.sumOf { it.progressionReceived }
    if (sent > 0) {
      add(
        Tile(
          d.items, t.number(sent), d.itemsOf(t.number(progression), t.number(totals.trapsSent)), p.progression, Icon.GIFT,
          pills = listOf(
            TilePill(Icon.STAR, t.number(progression), d.tipProgression, p.progression),
            TilePill(Icon.TRAP, t.number(totals.trapsSent), d.tipTraps, p.trap),
          ),
        )
      )
    }
    // Links from the first one on
    val links = totals.deaths + totals.trapLinks
    if (links > 0) {
      add(
        Tile(
          d.links, t.number(links), d.linksOf(t.number(totals.deaths), t.number(totals.trapLinks)), p.trap, Icon.LINK,
          pills = listOf(
            TilePill(Icon.SKULL, t.number(totals.deaths), d.tipDeaths, p.trap),
            TilePill(Icon.TRAP, t.number(totals.trapLinks), d.tipTraps, p.warn),
          ),
        )
      )
    }
    add(Tile(d.playTime, t.duration(s.players.sumOf { playTime(it, now) }), d.allSlotsTogether, p.useful, Icon.CLOCK))
    // Its age; below the game server's running time for this seed, without the times it was stopped
    val since = s.sessionRunningSince
    val ran = t.duration(s.sessionTimeMs + if (since != null) (now - since).coerceAtLeast(0) else 0)
    val created = s.createdAt
    add(
      Tile(
        d.session, if (created != null) t.age(now - created) else ran, d.serverTime(ran, since != null), p.brandStart, Icon.HOURGLASS,
        tip = created?.let { d.madeOn(t.date(it)) },
        // The game server's state (the icon alone), then how long it ran for this seed
        pills = listOf(
          if (since != null) TilePill(Icon.POWER, "", d.serverOn, p.ok) else TilePill(Icon.POWER, "", d.serverOff, p.muted),
          TilePill(Icon.SERVER, ran, d.tipUptime, p.useful),
        ),
      )
    )
  }
  Section(t.dashboard.overview, modifier) {
    Grid(
      tiles.size, width, 160.dp, if (fillHeight) Modifier.weight(1f) else Modifier,
      gap = 12.dp, balanced = true, fillHeight = fillHeight,
    ) { i, cellModifier ->
      StatTile(tiles[i], cellModifier)
    }
  }
}

@Composable
private fun StatTile(tile: Tile, modifier: Modifier) {
  val p = LocalPalette.current
  Row(modifier.card(p).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(
      Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tile.color.copy(alpha = 0.15f)),
      contentAlignment = Alignment.Center,
    ) {
      DrawnIcon(tile.icon, tile.color, 16.dp)
    }
    Column {
      Txt(tile.label, size = 12.sp, color = p.muted, weight = FontWeight.Medium)
      val tip = tile.tip
      if (tip != null) Tip(tip) { Txt(tile.value, size = 22.sp, weight = FontWeight.Bold, maxLines = 1) }
      else Txt(tile.value, size = 22.sp, weight = FontWeight.Bold, maxLines = 1)
      if (tile.pills.isEmpty()) {
        Txt(tile.sub, size = 11.5.sp, color = p.muted, maxLines = 2)
      } else {
        // Its parts as pills, one below the other: the tiles of a row take the tallest one's height, measured from
        // their content, which a wrapping row of pills would come short of (its last pill cut off)
        Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          tile.pills.forEach { pill -> Tip(pill.tip) { Pill(pill.text, pill.color, pill.icon) } }
        }
      }
    }
  }
}
