package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionStatus
import net.eratiem.randogeddon.shared.Summary

/**
 * The page after the login, unless exactly one running session is the user's (it opens then): the user's sessions as
 * cards, running ones first, each with its title, ID, how it stands, how far it got, the user's slots and who is
 * online. [onOpen] shows one. [admin]: a hint where the other sessions are.
 */
@Composable
fun SessionPicker(sessions: List<SessionEntry>, admin: Boolean, onOpen: (String) -> Unit) {
  val p = LocalPalette.current
  val running = sessions.filter { it.status.running }
  val finished = sessions.filter { !it.status.running }
  if (sessions.isEmpty()) {
    Txt(
      if (admin) "You don't play in a session. Every session is in the admin panel." else "You aren't part of a session yet.",
      color = p.muted, modifier = Modifier.padding(vertical = 8.dp),
    )
    return
  }
  listOf("Running" to running, "Finished" to finished).filter { it.second.isNotEmpty() }.forEach { (title, list) ->
    Section(
      title,
      subtitle = if (title == "Running") "pick one to see its dashboard" else "as they were when they finished",
    ) {
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + 16.dp) / (360.dp + 16.dp)).toInt().coerceIn(1, 3)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          list.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              row.forEach { SessionCard(it, Modifier.weight(1f), onOpen) }
              repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SessionCard(session: SessionEntry, modifier: Modifier, onOpen: (String) -> Unit) {
  val p = LocalPalette.current
  Column(modifier.card(p)) {
    HoverRow(onClick = { onOpen(session.id) }, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
      Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt(session.title, size = 15.sp, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
          StatusPill(session.status)
        }
        Txt(
          listOfNotNull(
            session.id,
            session.finishedAt?.let { "finished ${Platform.date(it)}" } ?: session.createdAt?.let { "since ${Platform.date(it)}" },
          ).joinToString(" · "),
          size = 12.sp, color = p.muted, maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          ProgressBar((session.percent / 100).toFloat(), Modifier.weight(1f), height = 6.dp)
          Txt("${oneDecimal(session.percent)}%", size = 13.sp, weight = FontWeight.Bold, brush = p.brand)
        }
        Txt(
          listOfNotNull(
            session.mySlots.takeIf { it.isNotEmpty() }?.let { "Your slots: ${it.joinToString(", ")}" },
            session.online.takeIf { it > 0 && session.status.running }?.let { "$it online" },
          ).joinToString(" · ").ifEmpty { "${session.slots} slots" },
          size = 12.sp, color = p.muted, maxLines = 2,
        )
      }
    }
  }
}

/** Live, ending soon, paused or ended. */
@Composable
fun StatusPill(status: SessionStatus) {
  val p = LocalPalette.current
  when (status) {
    SessionStatus.LIVE -> Pill("live", p.ok)
    SessionStatus.FINISHING -> Pill("ending", p.key)
    SessionStatus.PAUSED -> Pill("paused", p.warn)
    SessionStatus.ENDED -> Pill("ended", p.muted)
  }
}

/**
 * Under the header, while the session finishes or once it has: every slot is done and it finishes in m:ss (admins can
 * finish now or cancel), or it is finished (everyone can read the spoiler log now; admins can reopen it).
 */
@Composable
fun FinishBanner(
  s: Summary,
  admin: Boolean,
  onFinishNow: () -> Unit,
  onCancel: () -> Unit,
  onSpoiler: () -> Unit,
) {
  val p = LocalPalette.current
  val finishesAt = s.finishesAt
  val finishedAt = s.finishedAt
  if (finishesAt == null && finishedAt == null) return
  // Its own clock: the countdown moves every second
  var now by remember { mutableLongStateOf(Platform.now()) }
  LaunchedEffect(finishesAt) {
    while (finishesAt != null) {
      now = Platform.now()
      delay(1_000)
    }
  }
  val color = if (finishedAt != null) p.ok else p.key
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f))
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(Modifier.weight(1f)) {
      if (finishedAt != null) {
        val ended = s.totals.percent >= 100.0
        Txt(if (ended) "The session has ended" else "The session is paused", size = 14.sp, weight = FontWeight.SemiBold)
        Txt(
          "${if (ended) "Ended" else "Paused"} ${Platform.date(finishedAt)}. " +
            (if (ended) "This is how it ended" else "This is how far it got") + "; the spoiler log shows the whole seed.",
          size = 12.5.sp, color = p.muted,
        )
      } else {
        val left = ((finishesAt!! - now) / 1000).coerceAtLeast(0)
        Txt("Every slot is done!", size = 14.sp, weight = FontWeight.SemiBold)
        Txt(
          "The session ends in ${left / 60}:${(left % 60).toString().padStart(2, '0')}; then the game server stops " +
            "and the spoiler log is revealed.",
          size = 12.5.sp, color = p.muted,
        )
      }
    }
    if (finishedAt != null) {
      SmallButton("Spoiler log", onSpoiler)
    } else if (admin) {
      SmallButton("End now", onFinishNow)
      SmallButton("Cancel", onCancel)
    }
    InfoTip(
      "A session ends 2 minutes after every slot reached its goal or was released; an admin can also pause or end it " +
        "earlier. Then it is read-only: the game server stops, and its players see the spoiler log. Unless it was " +
        "completed (100 %), admins can reopen it within 14 days.",
      Modifier.width(16.dp),
    )
  }
}
