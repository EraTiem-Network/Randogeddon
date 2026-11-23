package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.Spoiler
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.shared.web.pathSegment
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.ui.Button
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.NARROW
import net.eratiem.randogeddon.webapp.ui.PageButton
import net.eratiem.randogeddon.webapp.ui.Selectable
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card

/** The spoiler page's tabs: the whole log, and what its later levels bring once they are worked out. */
private enum class SpoilerTab { EVERYTHING, PLAYTHROUGH, PATHS, TEXT }

/**
 * The running sessions whose spoiler log was asked for after the warning in this tab (on the page, or by the panel's
 * button): it isn't asked again for them until the page is loaded anew.
 */
object SpoilerConsent {
  private val given = mutableSetOf<String>()

  fun give(id: String) {
    given += id
  }

  fun given(id: String): Boolean = id in given
}

/**
 * A session's spoiler log: what the seed was made with and where every item lies (there from the seed on, the tab it
 * opens on), and by tabs of their own what is worked out after the seed: the playthrough sphere by sphere (each
 * foldable; the shortest way through the seed, what each step makes reachable), then the paths; and the log as text,
 * searchable, for what the views leave out. [id]: the session. [onBack]: closes it. [running]: the session isn't
 * finished: the page first warns, as the panel's button does, and asks for the log only once that is confirmed (a link
 * to it doesn't spoil the seed by itself); `null`: not known yet (the sessions are loading), nothing is asked meanwhile.
 * [confirmed]: the warning was answered already where the page was opened from (the panel's button): not asked again.
 */
@Composable
fun SpoilerView(id: String, onBack: () -> Unit, running: Boolean? = false, confirmed: Boolean = false) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var spoiler by remember(id) { mutableStateOf<Spoiler?>(null) }
  var problem by remember(id) { mutableStateOf<String?>(null) }
  var answered by remember(id) { mutableStateOf(SpoilerConsent.given(id)) }
  val open = running == false || confirmed || answered
  LaunchedEffect(id, open) {
    if (!open) return@LaunchedEffect
    when (val answer = call("GET", "api/sessions/${id.pathSegment()}/spoiler", null, Spoiler.serializer())) {
      is Answer.Ok -> { spoiler = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }

  BoxWithConstraints {
    // A phone: the steps as two lines each, the pickers and the search below their headings
    val narrow = maxWidth < NARROW
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Selectable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          IconButton(onBack, Icon.BACK, label = t.dialogs.back)
          Column(Modifier.weight(1f)) {
            Txt(t.spoiler.title, size = 20.sp, weight = FontWeight.Bold)
            Txt(
              listOfNotNull(
                id, spoiler?.let { t.frame.seed(it.seedName) },
                spoiler?.takeIf { it.spheres.isNotEmpty() }?.let { t.spoiler.spheres(it.spheres.size) },
              ).joinToString(" · "),
              size = 12.5.sp, color = p.muted,
            )
          }
        }
      }
      if (!open) {
        // The panel's question, before anything of the log is asked for
        if (running == null) Txt(t.dialogs.loading, color = p.muted)
        else Selectable(Modifier.fillMaxWidth()) {
          Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(t.sessionAdmin.confirmSpoiler, size = 13.5.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(t.sessionAdmin.showIt, danger = true) {
                SpoilerConsent.give(id)
                answered = true
              }
              Button(t.common.cancel) { onBack() }
            }
          }
        }
        return@Column
      }
      problem?.let { Txt(it, size = 13.sp, color = p.trap) }
      val s = spoiler
      if (s == null) {
        if (problem == null) Txt(t.dialogs.loading, color = p.muted)
        return@Column
      }
      val tabs = listOfNotNull(
        SpoilerTab.EVERYTHING, SpoilerTab.PLAYTHROUGH.takeIf { s.spheres.isNotEmpty() }, SpoilerTab.PATHS.takeIf { s.pathsFrom != null },
        SpoilerTab.TEXT,
      )
      // It opens on its first tab, whatever the seed has
      var chosen by remember(id) { mutableStateOf(SpoilerTab.EVERYTHING) }
      if (tabs.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          tabs.forEach { tab ->
            val name = when (tab) {
              SpoilerTab.EVERYTHING -> t.spoiler.tabEverything
              SpoilerTab.PLAYTHROUGH -> t.spoiler.playthrough
              SpoilerTab.PATHS -> t.spoiler.paths
              SpoilerTab.TEXT -> t.spoiler.tabText
            }
            PageButton(name, tab == chosen) { chosen = tab }
          }
        }
      }
      when (chosen) {
        SpoilerTab.EVERYTHING -> {
          SpoilerSettingsView(id, narrow)
          if (s.placements > 0) SpoilerPlacementsView(id, s.slots, narrow)
        }
        SpoilerTab.PLAYTHROUGH -> {
          if (s.unreduced) Txt(t.spoiler.unreduced, size = 12.5.sp, color = p.warn)
          Playthrough(s.spheres, narrow)
        }
        SpoilerTab.PATHS -> SpoilerPathsView(id, s.slots, narrow)
        SpoilerTab.TEXT -> FullLog(id, s.lines, narrow, t.spoiler.fullLog, t.spoiler.fullLogSubtitle)
      }
    }
  }
}
