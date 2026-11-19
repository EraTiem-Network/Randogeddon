package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.AdminOverview
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.i18n.Texts
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.date
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.SearchableSelect
import net.eratiem.randogeddon.webapp.ui.Txt

/** The admin panel's page besides the sessions; no session ID looks like it. */
internal const val NEW_PAGE = "+new"

/** The admin panel's headings (the session's, the general part's), a little bigger than the sections' below them. */
internal val ADMIN_HEADING = 20.sp

/**
 * The admin panel's heading: "Session" and the running session shown below, picked in it (the newest first, each with
 * how it stands and its game server), and the new session's button on the right.
 */
@Composable
fun AdminSwitch(overview: AdminOverview?, page: String?, onPage: (String) -> Unit) {
  val t = LocalTexts.current
  val sessions = overview?.sessions.orEmpty().filter { it.status.running }
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Txt(t.dashboard.session, size = ADMIN_HEADING, weight = FontWeight.Bold)
    // None running: nothing to pick from, so no switch; the new session's button draws the eye instead
    val none = overview != null && sessions.isEmpty()
    if (none) {
      Txt(
        t.sessionAdmin.noneRunning, size = ADMIN_HEADING, weight = FontWeight.Bold, color = LocalPalette.current.muted,
        maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp),
      )
    } else {
      SearchableSelect(
        sessions.map { Triple<String?, String, String>(it.id, it.id, switchDetails(t, it)) },
        page?.takeIf { id -> sessions.any { it.id == id } }, { id -> id?.let(onPage) },
        Modifier.weight(1f), chip = false, heading = ADMIN_HEADING, placeholder = t.sessionAdmin.pickOne,
      )
    }
    PageButton(t.sessionAdmin.newSession, page == NEW_PAGE, attention = none && page != NEW_PAGE) { onPage(NEW_PAGE) }
  }
}

/** A session in the switch's list: how it stands, how far it got, its game server, who is online. */
internal fun switchDetails(t: Texts, session: SessionEntry): String = listOfNotNull(
  t.sessions.status(session.status),
  "${t.decimal(session.percent)}%",
  session.server?.status?.let { t.sessionAdmin.server(it) },
  session.online.takeIf { it > 0 }?.let { t.sessions.online(it) },
  session.finishedAt?.let { t.sessions.finishedOn(t.date(it)) },
).joinToString(" · ")
