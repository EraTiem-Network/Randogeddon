package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.LockInfo
import net.eratiem.randogeddon.shared.PersonSummary
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.PlayerStatus
import net.eratiem.randogeddon.shared.PlayerSummary
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.i18n.Texts
import net.eratiem.randogeddon.shared.i18n.ago
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.duration
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.shared.i18n.texts
import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.ui.AppPopup
import net.eratiem.randogeddon.webapp.ui.Avatar
import net.eratiem.randogeddon.webapp.ui.Dot
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.GameChip
import net.eratiem.randogeddon.webapp.ui.HoverRow
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.LabeledBar
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Panel
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.ProgressBar
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Select
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.dimmedIf
import net.eratiem.randogeddon.webapp.ui.hand

private enum class PeopleSort { PLAYER, PROGRESS, ACTIVITY }

@Composable
internal fun People(s: Summary, now: Long, width: Dp, onOpen: (Int) -> Unit, onOpenPerson: (String) -> Unit) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  var sort by remember { mutableStateOf(PeopleSort.PLAYER) }
  // By name first, so equal ones (the same progress, nothing done yet) always come in the same order
  val byName = s.people.sortedBy { it.name.lowercase() }
  val people = when (sort) {
    PeopleSort.PLAYER -> byName
    PeopleSort.PROGRESS -> byName.sortedByDescending { it.percent }
    PeopleSort.ACTIVITY -> byName.sortedByDescending { it.lastActivity ?: 0 }
  }
  val worlds = s.players.associateBy { it.slot }
  // A person's slots in the same order as the people: by name, by progress or by what happened last
  fun slotsOf(person: PersonSummary): List<PlayerSummary> {
    val own = person.slots.mapNotNull { worlds[it] }.sortedBy { it.name.lowercase() }
    return when (sort) {
      PeopleSort.PLAYER -> own
      PeopleSort.PROGRESS -> own.sortedByDescending { it.percent }
      PeopleSort.ACTIVITY -> own.sortedByDescending { it.lastActivity ?: 0 }
    }
  }
  Section(
    d.players, subtitle = d.playersSubtitle(s.people.size, s.players.size),
    actions = {
      Select(
        PeopleSort.entries.map {
          it to when (it) {
            PeopleSort.PLAYER -> d.sortPlayer
            PeopleSort.PROGRESS -> d.sortProgress
            PeopleSort.ACTIVITY -> d.sortActivity
          }
        },
        sort, { sort = it },
      )
    },
  ) {
    if (people.isEmpty()) {
      Panel(Modifier.fillMaxWidth()) { Txt(d.waitingForServer, color = p.muted, modifier = Modifier.padding(16.dp)) }
    } else {
      Grid(people.size, width, 330.dp, equalHeight = false) { i, modifier ->
        PersonCard(people[i], slotsOf(people[i]), now, s.hintSettings.pointHints, modifier, onOpen, onOpenPerson)
      }
    }
  }
}

/**
 * A person's name, with their account's ID as a tip (it tells a deleted account's name in an old session from a new
 * account of the same name); a deleted account is marked so.
 */
@Composable
internal fun PersonName(name: String, userId: String?, deleted: Boolean, size: TextUnit, color: Color? = null) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  val text = if (deleted) d.deleted(name) else name
  if (userId == null) {
    Txt(text, size = size, weight = FontWeight.Bold, color = color ?: p.text, maxLines = 1)
  } else {
    Tip(d.account(userId, deleted)) {
      Txt(text, size = size, weight = FontWeight.Bold, color = color ?: if (deleted) p.muted else p.text, maxLines = 1)
    }
  }
}

@Composable
private fun PersonCard(
  person: PersonSummary,
  worlds: List<PlayerSummary>,
  now: Long,
  hintsByPoints: Boolean,
  modifier: Modifier,
  onOpen: (Int) -> Unit,
  onOpenPerson: (String) -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val locked = worlds.count { it.status == PlayerStatus.LOCKED }
  Column(modifier.card(p).padding(8.dp)) {
    // The header opens the person's overview; each world row opens that world
    HoverRow(onClick = { onOpenPerson(person.name) }, modifier = Modifier.fillMaxWidth()) {
     Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Box {
        Avatar(person.name)
        if (person.online) {
          Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(p.panel).padding(2.dp)) {
            Dot(p.ok, 9.dp)
          }
        }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PersonName(person.name, person.userId, person.deleted, size = 16.sp)
        val played = worlds.sumOf { playTime(it, now) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          // Its slots and how many are done, in one
          Pill(t.dashboard.slotsDone(person.goals, worlds.size), if (person.goals > 0) p.ok else p.muted)
          if (locked > 0) Pill(t.dashboard.locked(locked), p.locked)
          if (played > 0) Pill(t.dashboard.played(t.duration(played)), p.useful)
          ProgressPills(Progress.sum(worlds))
        }
      }
      Txt("${t.decimal(person.percent)}%", size = 18.sp, weight = FontWeight.Bold, brush = p.brand)
     }
    }
    Bars(Progress.sum(worlds), Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp), height = 6.dp)
    worlds.forEach { WorldRow(it, now, hintsByPoints, onOpen) }
  }
}

@Composable
internal fun statusColor(status: PlayerStatus): Color = LocalPalette.current.let { p ->
  when (status) {
    PlayerStatus.GOAL, PlayerStatus.COMPLETE -> p.ok
    PlayerStatus.RELEASED -> p.progression
    PlayerStatus.ONLINE -> p.useful
    PlayerStatus.LOCKED -> p.locked
    PlayerStatus.IDLE, PlayerStatus.NOT_STARTED -> p.muted
  }
}

internal fun statusLabel(t: Texts, world: PlayerSummary): String = t.dashboard.status(world.status)

/**
 * The time beside a slot's state: since it was complete or released; since its goal and its last check; for one that
 * is idle or online, its last check. `null`: nothing to say (not started, locked, or an archive of before 0.19.11).
 */
internal fun stateTime(t: Texts, world: PlayerSummary, now: Long): String? {
  val lastCheck = world.lastCheckAt?.let { t.dashboard.lastCheck(t.ago(it, now)) }
  return when (world.status) {
    PlayerStatus.COMPLETE -> world.completedAt?.let { t.ago(it, now) }
    PlayerStatus.RELEASED -> world.releasedAt?.let { t.ago(it, now) }
    PlayerStatus.GOAL -> listOfNotNull(world.goalAt?.let { t.ago(it, now) }, lastCheck).joinToString(" · ").ifEmpty { null }
    PlayerStatus.IDLE, PlayerStatus.ONLINE -> lastCheck
    PlayerStatus.LOCKED, PlayerStatus.NOT_STARTED -> null
  }
}

/**
 * For worlds that start locked, under the game icon, as wide and down to the end of the world's texts: a key once it
 * was found, a lock until then (so the status pill leaves out "locked"). What it
 * means (who found the key, where it was hinted, which key it waits for) shows on hover, or on tap on a phone.
 */
@Composable
private fun LockBadge(lock: LockInfo, now: Long, modifier: Modifier = Modifier) {
  val t = LocalTexts.current
  val p = LocalPalette.current
  val color = if (lock.unlocked) p.key else p.locked
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  var tapped by remember { mutableStateOf(false) }
  Box(
    modifier.width(34.dp).heightIn(min = 18.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.18f))
      .hoverable(interaction)
      // Its own click: a tap shows the text instead of opening the world
      .hand().clickable(interactionSource = interaction, indication = null) { tapped = !tapped },
    contentAlignment = Alignment.Center,
  ) {
    DrawnIcon(if (lock.unlocked) Icon.KEY else Icon.LOCK, color, 11.dp)
    if (hovered || tapped) {
      AppPopup(offset = IntOffset(0, with(LocalDensity.current) { 22.dp.roundToPx() }), onDismissRequest = { tapped = false }) {
        Txt(
          when {
            lock.unlocked -> t.dashboard.unlockedBy(lock.foundBy.orEmpty()) + (lock.foundAt?.let { " · ${t.ago(it, now)}" } ?: "")
            lock.hintedAt != null -> t.dashboard.keyHinted(
              lock.hintLocation?.let { t.dashboard.hintPlace(it, lock.hintSlot.orEmpty()) } ?: lock.hintedAt.orEmpty()
            )
            else -> t.dashboard.waitingForKey(lock.key)
          },
          size = 12.sp, maxLines = 3,
          modifier = Modifier.widthIn(max = 260.dp).card(p, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
}

/** The hints a world can buy with its points, for the bubble next to its status ("3 hints · 12 pts"). */
private fun hintBubble(t: Texts, world: PlayerSummary, hintsByPoints: Boolean): String? {
  val points = world.hintPoints ?: return null
  if (!hintsByPoints || world.status == PlayerStatus.LOCKED) return null
  val cost = world.hintCost
  return when {
    cost == null -> t.dashboard.points(points)
    cost == 0 -> t.dashboard.hintsFree
    else -> t.dashboard.hintsForPoints(points / cost, points)
  }
}

/** One world of a person: game, progress, status; a locked one says what opens it. */
@Composable
private fun WorldRow(world: PlayerSummary, now: Long, hintsByPoints: Boolean, onOpen: (Int) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val locked = world.status == PlayerStatus.LOCKED
  HoverRow(onClick = { onOpen(world.slot) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
    // Its own height (one taken from the content's measured minimum cut off its last lines when its pills wrapped)
    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.dimmedIf(locked)) { GameChip(world.game, dimmed = locked) }
        world.lock?.let { LockBadge(it, now, Modifier.height(36.dp)) }
      }
      Column(Modifier.weight(1f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Txt(world.game, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.weight(1f).dimmedIf(locked))
          hintBubble(t, world, hintsByPoints)?.let { Pill(it, p.progression) }
          // Locked: the badge under the game icon says it
          if (!locked) Pill(
            statusLabel(t, world), statusColor(world.status),
            icon = if (world.status == PlayerStatus.GOAL) Icon.FLAG else null,
          )
        }
        Row(Modifier.fillMaxWidth()) {
          Txt(t.dashboard.slot(shortSlotName(world.name, world.person)), size = 11.5.sp, color = p.muted, maxLines = 1, modifier = Modifier.weight(1f))
          stateTime(t, world, now)?.let { Txt(it, size = 11.5.sp, maxLines = 1, color = p.muted) }
          // More than one game client on it (played together, or a second device)
          if (world.connections > 1) Txt(t.dashboard.connections(world.connections), size = 11.5.sp, maxLines = 1, color = p.ok)
        }
        val progress = Progress.of(world)
        val played = playTime(world, now)
        FlowRow(
          Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          ProgressPills(progress)
          if (played > 0) Pill(t.dashboard.played(t.duration(played)), p.useful)
        }
        Spacer(Modifier.height(6.dp))
        Bars(progress, dimmed = locked)
      }
    }
  }
}

/**
 * A slot's (or several slots' together) numbers: checked of its locations, and of the progression locations among
 * them (`null` until scouted); progression received, and sent to others (no totals: its players would learn how much
 * is still missing).
 */
internal data class Progress(
  val checked: Int,
  val total: Int,
  val progressionChecked: Int,
  val progressionChecks: Int?,
  val received: Int,
  val sent: Int?,
) {
  companion object {
    fun of(w: PlayerSummary) =
      Progress(w.checked, w.total, w.progressionChecked, w.progressionChecks, w.progressionReceived, w.sent)

    fun of(d: PlayerDetail) =
      Progress(d.checked, d.total, d.progressionChecked, d.progressionChecks, d.progressionReceived, d.sent)

    /** Summed over [worlds]; what is known only once for each of them (a slot not scouted yet). */
    fun sum(worlds: List<PlayerSummary>): Progress {
      fun known(of: (PlayerSummary) -> Int?) = worlds.map(of).takeIf { all -> all.none { it == null } }?.sumOf { it ?: 0 }
      return Progress(
        worlds.sumOf { it.checked }, worlds.sumOf { it.total }, worlds.sumOf { it.progressionChecked }, known { it.progressionChecks },
        worlds.sumOf { it.progressionReceived }, known { it.sent },
      )
    }
  }
}

/** The two bars: the locations checked in its own world, and those of them that hold a progression item. */
@Composable
internal fun Bars(progress: Progress, modifier: Modifier = Modifier, height: Dp = 5.dp, dimmed: Boolean = false) {
  val t = LocalTexts.current
  fun fraction(part: Int, whole: Int) = if (whole > 0) part.toFloat() / whole else 0f
  Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
    LabeledBar(
      t.dashboard.checksBar, fraction(progress.checked, progress.total),
      "${t.number(progress.checked)} / ${t.number(progress.total)}", height = height, dimmed = dimmed, icon = Icon.CHECK,
    )
    val checks = progress.progressionChecks
    LabeledBar(
      t.dashboard.progressionChecksBar, checks?.let { fraction(progress.progressionChecked, it) },
      if (checks == null) "…" else "${t.number(progress.progressionChecked)} / ${t.number(checks)}", height = height,
      dimmed = dimmed, icon = Icon.STAR,
    )
  }
}

/** Progression received, and sent to others (once scouted), as pills: counts only. */
@Composable
internal fun ProgressPills(progress: Progress) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  Pill(t.dashboard.received(t.number(progress.received)), p.progression)
  progress.sent?.let { Pill(t.dashboard.sent(t.number(it)), p.useful) }
}

/** Connected time of [world] so far, including the running session. */
fun playTime(world: PlayerSummary, now: Long): Long =
  world.playTimeMs + (world.onlineSince?.let { (now - it).coerceAtLeast(0) } ?: 0)
