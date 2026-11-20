package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private data class Tile(val label: String, val value: String, val sub: String, val color: Color, val icon: Icon?, val tip: String? = null)

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
    val parts = listOfNotNull(
      d.partComplete(count(PlayerStatus.COMPLETE)),
      count(PlayerStatus.GOAL).takeIf { s.releaseMode != "auto" || it > 0 }?.let(d::partGoal),
      count(PlayerStatus.RELEASED).takeIf { it > 0 }?.let(d::partReleased),
    )
    add(Tile(d.finished, "$done / ${s.players.size}", parts.joinToString(" · ").ifEmpty { d.slotsFinished }, p.ok, Icon.FLAG))
    val online = s.players.count { it.online }
    val waiting = listOfNotNull(
      count(PlayerStatus.IDLE).takeIf { it > 0 }?.let(d::partIdle),
      count(PlayerStatus.NOT_STARTED).takeIf { it > 0 }?.let(d::partNotStarted),
    )
    add(Tile(d.slots, "$online / ${s.players.size}", waiting.joinToString(" · ").ifEmpty { d.everyoneOnline }, p.useful, null))
    if (totals.lockable > 0) {
      add(Tile(d.unlocked, "${totals.lockable - totals.locked} / ${totals.lockable}", d.slotsOpened, p.key, Icon.KEY))
    }
    if (showHints(s)) add(Tile(d.hints, "${s.hints.size}", d.notFoundYet(open), p.progression, null))
    // Items sent; an archive from before has its slots' received items only
    val sent = totals.itemsSent.takeIf { it > 0 } ?: s.players.sumOf { it.itemsReceived }
    val progression = totals.progressionSent.takeIf { totals.itemsSent > 0 } ?: s.players.sumOf { it.progressionReceived }
    if (sent > 0) {
      add(Tile(d.items, t.number(sent), d.itemsOf(t.number(progression), t.number(totals.trapsSent)), p.progression, null))
    }
    // Links from the first one on
    val links = totals.deaths + totals.trapLinks
    if (links > 0) add(Tile(d.links, t.number(links), d.linksOf(t.number(totals.deaths), t.number(totals.trapLinks)), p.trap, null))
    add(Tile(d.playTime, t.duration(s.players.sumOf { playTime(it, now) }), d.allSlotsTogether, p.useful, null))
    // Its age; below the game server's running time for this seed, without the times it was stopped
    val since = s.sessionRunningSince
    val ran = t.duration(s.sessionTimeMs + if (since != null) (now - since).coerceAtLeast(0) else 0)
    val created = s.createdAt
    add(
      Tile(
        d.session, if (created != null) t.age(now - created) else ran, d.serverTime(ran, since != null), p.brandStart, null,
        tip = created?.let { d.madeOn(t.date(it)) },
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
      if (tile.icon != null) DrawnIcon(tile.icon, tile.color, 16.dp) else Dot(tile.color, 10.dp)
    }
    Column {
      Txt(tile.label, size = 12.sp, color = p.muted, weight = FontWeight.Medium)
      val tip = tile.tip
      if (tip != null) Tip(tip) { Txt(tile.value, size = 22.sp, weight = FontWeight.Bold, maxLines = 1) }
      else Txt(tile.value, size = 22.sp, weight = FontWeight.Bold, maxLines = 1)
      // Two lines where the tile is narrow: its parts (complete · goal · released) aren't cut off
      Txt(tile.sub, size = 11.5.sp, color = p.muted, maxLines = 2)
    }
  }
}
