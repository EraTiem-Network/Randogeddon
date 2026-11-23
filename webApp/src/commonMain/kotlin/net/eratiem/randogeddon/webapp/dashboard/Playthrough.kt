package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.Sphere
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.FoldRow
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Paged
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.SearchAndFilter
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.slotFilters

/** How many steps of a sphere show at once; the arrows under them go to the others (a big seed's sphere has thousands). */
private const val SPHERE_PAGE = 100

/**
 * One step of a sphere: an item for [itemSlot], found at [location] in [locationSlot] (none for sphere 0); [checked]:
 * that location is checked already.
 */
internal data class Step(
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

/** What the search looks through in a step: its item, check and slots, in small letters, a line each. */
private fun searchable(step: Step): String =
  listOfNotNull(step.item, step.location, step.itemSlot, step.locationSlot).joinToString("\n").lowercase()

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Playthrough(spheres: List<Sphere>, narrow: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val steps = remember(spheres) {
    spheres.associate { sphere ->
      sphere.number to sphere.entries.mapIndexed { i, entry -> step(entry).copy(checked = sphere.checked.getOrNull(i) == true) }
    }
  }
  // Each step's text for the search, prepared once
  val texts = remember(steps) { steps.mapValues { (_, list) -> list.map(::searchable) } }
  val slots = remember(steps) {
    steps.values.flatten().flatMap { listOfNotNull(it.itemSlot, it.locationSlot) }.distinct().sortedBy { it.lowercase() }
  }
  val filters = remember(slots, t) { slotFilters(slots, t.spoiler.everySlot) }
  // Folded at first: it is the whole way through the seed; a slot filter unfolds what concerns that slot
  var open by remember { mutableStateOf(emptySet<Int>()) }
  var slot by remember { mutableStateOf<String?>(null) }
  // The search: the steps whose item, check or slots contain it (any case); like the slot, it unfolds what it finds
  var query by remember { mutableStateOf("") }
  val needle = query.trim().lowercase()
  // Each sphere's steps the slot and the search leave: worked out once for them, not on every fold
  val shown = remember(steps, texts, slot, needle) {
    steps.mapValues { (number, list) ->
      val text = texts.getValue(number)
      list.filterIndexed { i, step ->
        (slot == null || step.itemSlot == slot || step.locationSlot == slot) && (needle.isEmpty() || needle in text[i])
      }
    }
  }
  // The spheres the slot and the search leave (with a step); they unfold when filtered, fold back when not, and each
  // folds by hand either way
  val visible = remember(spheres, shown) { spheres.filter { shown[it.number].orEmpty().isNotEmpty() } }
  val visibleNumbers = remember(visible) { visible.map { it.number }.toSet() }
  val allOpen = visibleNumbers.isNotEmpty() && open.containsAll(visibleNumbers)
  LaunchedEffect(slot, needle) { open = if (slot != null || needle.isNotEmpty()) visibleNumbers else emptySet() }
  val searchAndSlot: @Composable (Modifier) -> Unit = { modifier ->
    SearchAndFilter(query, { query = it }, t.spoiler.searchSteps, slot, filters, { slot = it }, searchModifier = modifier)
  }
  // Follows the list: "Fold all" once every sphere shown is unfolded (by hand too), else "Unfold all"
  val foldAll: @Composable () -> Unit = {
    SmallButton(if (allOpen) t.spoiler.foldAll else t.spoiler.unfoldAll, {
      open = if (allOpen) open - visibleNumbers else open + visibleNumbers
    })
  }
  Section(
    t.spoiler.playthrough, subtitle = t.spoiler.playthroughSubtitle,
    actions = {
      if (!narrow) {
        searchAndSlot(Modifier.width(220.dp))
        foldAll()
      }
    },
  ) {
    // A phone: the search on a line of its own, the slot and folding below it
    if (narrow) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
        searchAndSlot(Modifier.fillMaxWidth())
        foldAll()
      }
    }
    Column(Modifier.fillMaxWidth().card(p)) {
      if (visible.isEmpty()) {
        Txt(
          if (needle.isNotEmpty()) t.spoiler.nothingMatches(query.trim()) else t.spoiler.nothingFor(slot.orEmpty()),
          color = p.muted, modifier = Modifier.padding(16.dp),
        )
      }
      visible.forEachIndexed { i, sphere ->
        key(sphere.number) {
          if (i > 0) Divider()
          val entries = shown[sphere.number].orEmpty()
          val unfolded = sphere.number in open
          FoldRow(unfolded, { open = if (sphere.number in open) open - sphere.number else open + sphere.number }, narrow) {
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
            // Each step with its place in the sphere (every other one shaded), a page of them at a time
            val numbered = remember(entries) { entries.withIndex().toList() }
            if (narrow) {
              Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                Paged(numbered, SPHERE_PAGE, reset = entries) { (j, entry) -> StepCard(entry, odd = j % 2 == 1) }
              }
            } else {
              Column(Modifier.fillMaxWidth().padding(start = 38.dp, end = 16.dp, bottom = 12.dp)) {
                StepHeader()
                Paged(numbered, SPHERE_PAGE, reset = entries) { (j, entry) -> StepRow(entry, odd = j % 2 == 1) }
              }
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
internal fun StepHeader() {
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
internal fun StepRow(step: Step, odd: Boolean) {
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
internal fun StepCard(step: Step, odd: Boolean) {
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
internal fun SlotTag(slot: String) {
  val p = LocalPalette.current
  val hue = p.hue(slot)
  Txt(
    slot, size = 11.5.sp, weight = FontWeight.SemiBold, color = hue, maxLines = 1,
    modifier = Modifier.padding(end = 8.dp).clip(CircleShape).background(hue.copy(alpha = 0.14f))
      .padding(horizontal = 8.dp, vertical = 2.dp),
  )
}
