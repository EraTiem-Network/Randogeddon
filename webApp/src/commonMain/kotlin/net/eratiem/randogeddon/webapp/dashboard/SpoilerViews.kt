package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.SpoilerFindings
import net.eratiem.randogeddon.shared.SpoilerOption
import net.eratiem.randogeddon.shared.SpoilerPaths
import net.eratiem.randogeddon.shared.SpoilerPlacements
import net.eratiem.randogeddon.shared.SpoilerSettings
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.api.pathSegment
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.FoldRow
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.ListingState
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Mono
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.RangePager
import net.eratiem.randogeddon.webapp.ui.SearchAndFilter
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.rememberListingState
import net.eratiem.randogeddon.webapp.ui.slotFilters

/** How many placements show at once, and how many paths. */
private const val PLACEMENTS = 100
private const val PATHS = 50

/**
 * A list of the spoiler log that the tracker filters and pages (a big seed's is too long for a browser): which slot
 * and what text it is narrowed to, and which rows ([state]), and the page shown ([page]: `null` while the first one
 * loads).
 */
private class Listing<T>(val state: ListingState<String?>, val page: T?, val problem: String?)

/**
 * The [path] list of session [id]'s spoiler log, [count] at a time: asked for again when the slot, the search (a
 * moment after the last key) or the page changes; a new slot or search starts at its first page.
 */
@Composable
private fun <T> rememberListing(id: String, path: String, count: Int, decode: (String) -> T): Listing<T> {
  val state = rememberListingState(id, null as String?, SpoilerFindings.MAX_QUERY)
  var page by remember(id) { mutableStateOf<T?>(null) }
  var problem by remember(id) { mutableStateOf<String?>(null) }
  val q = state.needle
  val slot = state.filter
  val from = state.from
  LaunchedEffect(id, slot, q, from) {
    // Typing on restarts this: only what stays for a moment is asked for
    if (q.isNotEmpty()) delay(SEARCH_DELAY_MS)
    val url = "api/sessions/${id.pathSegment()}/spoiler/$path?from=$from&count=$count" +
      (slot?.let { "&slot=${it.percentEncoded()}" } ?: "") + (if (q.isEmpty()) "" else "&q=${q.percentEncoded()}")
    when (val answer = call("GET", url, null, decode)) {
      is Answer.Ok -> { page = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  return Listing(state, page, problem)
}

/** A listing's search and its slot ([search]: the search field's width, or the whole line's on a phone). */
@Composable
private fun ListingSearch(listing: Listing<*>, slots: List<String>, placeholder: String, search: Modifier) {
  val t = LocalTexts.current
  val state = listing.state
  val filters = remember(slots, t) { slotFilters(slots, t.spoiler.everySlot) }
  SearchAndFilter(state.query, { state.search(it) }, placeholder, state.filter, filters, { state.pick(it) }, searchModifier = search)
}

/** Options as a list: each one's name, then what it is set to. */
@Composable
private fun Options(options: List<SpoilerOption>, narrow: Boolean) {
  val p = LocalPalette.current
  Column(Modifier.fillMaxWidth().padding(start = if (narrow) 8.dp else 38.dp, end = if (narrow) 8.dp else 16.dp, bottom = 12.dp)) {
    options.forEachIndexed { j, option ->
      val row = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (j % 2 == 1) p.panelAlt else Color.Transparent)
        .padding(horizontal = 10.dp, vertical = 5.dp)
      if (narrow) {
        Column(row) {
          Txt(option.name, size = 12.sp, color = p.muted)
          Txt(option.value.ifEmpty { "–" }, size = 13.sp)
        }
      } else {
        Row(row, verticalAlignment = Alignment.Top) {
          Txt(option.name, size = 12.5.sp, color = p.muted, modifier = Modifier.width(280.dp).padding(end = 12.dp))
          Txt(option.value.ifEmpty { "–" }, size = 13.sp, modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

/**
 * What the seed was made with: its own settings, each slot's options (folded: there are many), and what its worlds
 * wrote into the log besides (a game's prices, its starting items), as they wrote it.
 */
@Composable
internal fun SpoilerSettingsView(id: String, narrow: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var settings by remember(id) { mutableStateOf<SpoilerSettings?>(null) }
  var problem by remember(id) { mutableStateOf<String?>(null) }
  LaunchedEffect(id) {
    when (val answer = call("GET", "api/sessions/${id.pathSegment()}/spoiler/settings", null, SpoilerSettings.serializer())) {
      is Answer.Ok -> { settings = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  val s = settings
  if (s == null) {
    // Its failure where the settings would be
    problem?.let { Section(t.spoiler.settings, subtitle = t.spoiler.settingsSubtitle) { Txt(it, size = 12.5.sp, color = p.trap) } }
    return
  }
  if (s.general.isEmpty() && s.slots.isEmpty() && s.sections.isEmpty()) return
  var open by remember(id) { mutableStateOf(emptySet<String>()) }
  fun toggle(key: String) { open = if (key in open) open - key else open + key }
  Section(t.spoiler.settings, subtitle = t.spoiler.settingsSubtitle) {
    Column(Modifier.fillMaxWidth().card(p)) {
      // [line]: a line above it (every one but the first)
      val fold: @Composable (String, Boolean, @Composable RowScope.() -> Unit, @Composable () -> Unit) -> Unit = { key, line, head, body ->
        if (line) Divider()
        FoldRow(key in open, { toggle(key) }, narrow, content = head)
        if (key in open) body()
      }
      val general = s.general.isNotEmpty()
      if (general) fold("general", false, {
        Txt(t.spoiler.generalSettings, size = 13.5.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Pill(t.spoiler.options(s.general.size), p.muted)
      }, { Options(s.general, narrow) })
      s.slots.forEachIndexed { i, slot ->
        fold("slot ${slot.number}", general || i > 0, {
          SlotTag(slot.name)
          Txt(slot.game.orEmpty(), size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
          Pill(t.spoiler.options(slot.options.size), p.muted)
        }, { Options(slot.options, narrow) })
      }
      s.sections.forEachIndexed { i, section ->
        fold("section $i", general || s.slots.isNotEmpty() || i > 0, {
          Txt(section.title, size = 13.5.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }, {
          Column(Modifier.fillMaxWidth().padding(start = if (narrow) 12.dp else 38.dp, end = 16.dp, bottom = 12.dp)) {
            Mono(section.lines.joinToString("\n"), size = 12.sp)
            if (section.more) Txt(t.spoiler.moreInRaw, size = 12.sp, color = p.muted, modifier = Modifier.padding(top = 6.dp))
          }
        })
      }
    }
  }
}

/**
 * Where every item of the seed lies: a row a location as the playthrough has them (whose item, the item, the slot
 * and location it lies at; checked ones marked), narrowed to a slot (its items, and what lies in its world) and
 * searched, [PLACEMENTS] at a time.
 */
@Composable
internal fun SpoilerPlacementsView(id: String, slots: List<String>, narrow: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val listing = rememberListing(id, "placements", PLACEMENTS) { ApiJson.decodeFromString(SpoilerPlacements.serializer(), it) }
  Section(t.spoiler.placements, subtitle = t.spoiler.placementsSubtitle, actions = {
    if (!narrow) ListingSearch(listing, slots, t.spoiler.searchSteps, Modifier.width(220.dp))
  }) {
    if (narrow) ListingSearch(listing, slots, t.spoiler.searchSteps, Modifier.fillMaxWidth())
    listing.problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    Column(Modifier.fillMaxWidth().card(p)) {
      val page = listing.page
      when {
        page == null -> if (listing.problem == null) Txt(t.dialogs.loading, color = p.muted, modifier = Modifier.padding(16.dp))
        page.placements.isEmpty() -> Txt(t.common.nothingFound, color = p.muted, modifier = Modifier.padding(16.dp))
        else -> {
          Column(Modifier.fillMaxWidth().padding(horizontal = if (narrow) 8.dp else 16.dp, vertical = 10.dp)) {
            if (!narrow) StepHeader()
            page.placements.forEachIndexed { j, placement ->
              val step = Step(placement.item, placement.receiver, placement.location, placement.finder, placement.checked)
              if (narrow) StepCard(step, odd = j % 2 == 1) else StepRow(step, odd = j % 2 == 1)
            }
          }
          RangePager(page.from, page.placements.size, page.total, PLACEMENTS) { listing.state.go(it) }
        }
      }
    }
  }
}

/**
 * The way to every item of the playthrough: a row a location, folded; unfolded, the regions passed from the start to
 * it, in order. Narrowed to a slot and searched (location, slot, region), [PATHS] at a time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpoilerPathsView(id: String, slots: List<String>, narrow: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val listing = rememberListing(id, "paths", PATHS) { ApiJson.decodeFromString(SpoilerPaths.serializer(), it) }
  val page = listing.page
  // Folded at first; which ones are unfolded is forgotten with the page
  var open by remember(page) { mutableStateOf(emptySet<Int>()) }
  Section(t.spoiler.paths, subtitle = t.spoiler.pathsSubtitle, actions = {
    if (!narrow) ListingSearch(listing, slots, t.spoiler.searchPaths, Modifier.width(220.dp))
  }) {
    if (narrow) ListingSearch(listing, slots, t.spoiler.searchPaths, Modifier.fillMaxWidth())
    listing.problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    Column(Modifier.fillMaxWidth().card(p)) {
      when {
        page == null -> if (listing.problem == null) Txt(t.dialogs.loading, color = p.muted, modifier = Modifier.padding(16.dp))
        page.paths.isEmpty() -> Txt(t.common.nothingFound, color = p.muted, modifier = Modifier.padding(16.dp))
        else -> {
          page.paths.forEachIndexed { i, path ->
            if (i > 0) Divider()
            FoldRow(i in open, { open = if (i in open) open - i else open + i }, narrow) {
              SlotTag(path.slot)
              Txt(path.location, size = 13.5.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
              Pill(t.spoiler.regions(path.regions.size), p.muted)
            }
            if (i in open) {
              FlowRow(
                Modifier.fillMaxWidth().padding(start = if (narrow) 12.dp else 38.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
              ) {
                path.regions.forEachIndexed { j, region ->
                  if (j > 0) Box(Modifier.size(11.dp).rotate(-90f)) { DrawnIcon(Icon.CHEVRON, p.muted, 11.dp) }
                  Txt(
                    region, size = 12.5.sp, weight = if (j == path.regions.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(p.panelAlt).padding(horizontal = 8.dp, vertical = 3.dp),
                  )
                }
              }
            }
          }
          RangePager(page.from, page.paths.size, page.total, PATHS) { listing.state.go(it) }
        }
      }
    }
  }
}
