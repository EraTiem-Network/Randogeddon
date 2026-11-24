package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.HintView
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.i18n.DashboardTexts
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.appendTemplate
import net.eratiem.randogeddon.webapp.app.fill
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Select
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.scrolling

/** Hint states as players see them: Archipelago's HintStatus, plus "not found" for all open ones. */
private enum class HintState(val matches: (HintView) -> Boolean) {
  OPEN({ !it.found }),
  ALL({ true }),
  FOUND({ it.found }),
  PRIORITY({ !it.found && it.status == 30 }),
  AVOID({ !it.found && it.status == 20 }),
  NO_PRIORITY({ !it.found && it.status == 10 }),
  UNSPECIFIED({ !it.found && it.status == 0 }),
  ;

  fun label(d: DashboardTexts): String = when (this) {
    OPEN -> d.hintNotFound
    ALL -> d.hintAllStates
    FOUND -> d.hintFound
    PRIORITY -> d.hintPriority
    AVOID -> d.hintAvoid
    NO_PRIORITY -> d.hintNoPriority
    UNSPECIFIED -> d.hintUnmarked
  }
}

@Composable
internal fun Hints(s: Summary, compact: Boolean) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  var person by remember { mutableStateOf<String?>(null) }
  var world by remember { mutableStateOf<String?>(null) }
  var state by remember { mutableStateOf(HintState.OPEN) }
  val worldsOfPerson = person?.let { name ->
    val slots = s.people.firstOrNull { it.name == name }?.slots.orEmpty().toSet()
    s.players.filter { it.slot in slots }
  } ?: s.players
  // A hint concerns a world if the item is for it or in it (key hints are "for" the world they unlock)
  val shownWorlds = world?.let { setOf(it) } ?: worldsOfPerson.map { it.name }.toSet()
  val hints = s.hints.filter { h ->
    (person == null && world == null || h.receiverName in shownWorlds || h.finderName in shownWorlds) && state.matches(h)
  }
  Section(d.hints, Modifier.fillMaxWidth(), subtitle = d.hintsSubtitle(s.hints.count { !it.found }, s.hints.size), actions = {
    if (s.people.size > 1) {
      Select(listOf<Pair<String?, String>>(null to d.allPlayers) + s.people.map { it.name to it.name }, person, {
        person = it
        world = null
      })
    }
    Select(listOf<Pair<String?, String>>(null to d.allSlots) + worldsOfPerson.map { it.name to it.name }, world, { world = it })
    Select(HintState.entries.map { it to it.label(d) }, state, { state = it })
    // Narrow, there is no heading row for it: beside the filter
    if (compact) HintStateTip()
  }) {
    Column(Modifier.fillMaxWidth().card(p)) { HintList(s, hints, compact) }
  }
}

@Composable
private fun HintList(s: Summary, hints: List<HintView>, compact: Boolean) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  if (hints.isEmpty()) {
    Txt(if (s.hints.isEmpty()) d.noHintsYet else d.noHintsMatch, color = p.muted, modifier = Modifier.padding(16.dp))
    return
  }
  if (!compact) {
    HintRow(
      listOf(d.columnFor, d.columnItem, d.columnCheck, d.columnInSlot).map<String, @Composable () -> Unit> {
        { Txt(it, size = 12.sp, color = p.muted, weight = FontWeight.SemiBold) }
      } + listOf<@Composable () -> Unit>({ HintStateTip() }),
    )
    Divider()
  }
  Column(Modifier.heightIn(max = 600.dp).scrolling()) {
    hints.forEachIndexed { i, h ->
      if (i > 0) Divider()
      if (compact) CompactHint(h) else HintRow(listOf(
        { Txt(h.receiverName, size = 13.sp, weight = FontWeight.SemiBold) },
        { ItemText(h.itemName, h.itemFlags, key = h.itemName.startsWith("Key to ")) },
        {
          Column {
            // Only the slot: no location, no entrance
            if (h.slotOnly) Txt(fill(d.hintSomewhere, "finder" to h.finderName), size = 13.sp, color = p.muted)
            else Txt(h.locationName, size = 13.sp)
            if (h.entrance.isNotEmpty() && !h.slotOnly) Txt(h.entrance, size = 12.sp, color = p.muted)
          }
        },
        { Txt(h.finderName, size = 13.sp, weight = FontWeight.SemiBold) },
        { HintStatus(h) },
      ))
    }
  }
}

@Composable
internal fun ItemText(name: String, flags: Int, key: Boolean) {
  val p = LocalPalette.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    if (key) DrawnIcon(Icon.KEY, p.key, 12.dp)
    Txt(name, size = 13.sp, weight = FontWeight.SemiBold, color = if (key) p.key else p.item(flags))
  }
}

private val HINT_WEIGHTS = listOf(1f, 1.5f, 2f, 1.2f, .7f)

@Composable
private fun HintRow(cells: List<@Composable () -> Unit>) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    cells.forEachIndexed { i, cell -> Box(Modifier.weight(HINT_WEIGHTS[i])) { cell() } }
  }
}

@Composable
private fun CompactHint(h: HintView) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Txt(buildAnnotatedString {
        appendTemplate(d.hintItem, mapOf(
          "receiver" to (h.receiverName to SpanStyle(fontWeight = FontWeight.SemiBold)),
          "item" to (h.itemName to SpanStyle(fontWeight = FontWeight.SemiBold, color = p.item(h.itemFlags))),
        ))
      }, size = 13.sp, modifier = Modifier.weight(1f))
      HintStatus(h)
    }
    Txt(
      if (h.slotOnly) fill(d.hintSomewhere, "finder" to h.finderName)
      else fill(d.hintWhere, "location" to h.locationName, "finder" to h.finderName),
      size = 12.sp, color = p.muted,
    )
  }
}

/** What the hints' state is, and each one (the pill's words) with what it means. */
@Composable
private fun HintStateTip() {
  val d = LocalTexts.current.dashboard
  InfoTip(
    d.hintStateTip,
    options = listOf(
      d.pillOpen to d.hintOpenMeaning, d.pillPriority to d.hintPriorityMeaning, d.pillNoPriority to d.hintNoPriorityMeaning,
      d.pillAvoid to d.hintAvoidMeaning, d.pillFound to d.hintFoundMeaning,
    ),
  )
}

/** Archipelago's HintStatus: players can mark their hints as priority, avoid or no priority in their client. */
@Composable
internal fun HintStatus(h: HintView) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  when {
    h.found || h.status == 40 -> Pill(d.pillFound, p.ok)
    h.status == 30 -> Pill(d.pillPriority, p.brandStart)
    h.status == 20 -> Pill(d.pillAvoid, p.trap)
    h.status == 10 -> Pill(d.pillNoPriority, p.muted)
    else -> Pill(d.pillOpen, p.warn)
  }
}

/** Hints are shown when a hint system is in play, or when the host handed some out anyway (/hint). */
internal fun showHints(s: Summary): Boolean = s.hintSettings.enabled || s.hints.isNotEmpty()
