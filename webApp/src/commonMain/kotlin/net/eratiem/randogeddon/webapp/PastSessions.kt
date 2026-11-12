package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.shared.PastSession
import net.eratiem.randogeddon.shared.Summary

/** The finished sessions the viewer took part in (all for admins), the newest first; [onOpen] shows one. */
@Composable
fun PastSessionsSection(sessions: List<PastSession>, onOpen: (String) -> Unit) {
  val p = LocalPalette.current
  Section("Past sessions", subtitle = "finished; as they were when they finished") {
    Column(Modifier.fillMaxWidth().card(p)) {
      sessions.forEachIndexed { i, session ->
        if (i > 0) Divider()
        HoverRow(onClick = { onOpen(session.seedName) }, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
          Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Txt(session.title + if (session.current) " (current seed)" else "", size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
            Txt(
              listOfNotNull(
                "Seed ${session.seedName}", "finished ${Platform.date(session.finishedAt)}",
                session.deletesAt?.let { "deleted on ${Platform.date(it)}" },
              ).filter { it.isNotEmpty() }.joinToString(" · "),
              size = 12.sp, color = p.muted, maxLines = 2,
            )
          }
          Txt("${oneDecimal(session.percent)}%", size = 16.sp, weight = FontWeight.Bold, brush = p.brand,
            modifier = Modifier.padding(end = 12.dp))
        }
      }
    }
  }
}

/**
 * A past session, read-only: its overview, players, hints and feed as they were when it finished ([source], its
 * archive; `null` while it loads, or with [missing] if it can't be had). Slot details come from the archive too.
 * [onSpoiler]: shows its spoiler log.
 */
@Composable
fun PastSessionView(
  seed: String,
  source: ArchiveSource?,
  missing: Boolean,
  contentWidth: Dp,
  onBack: () -> Unit,
  onSpoiler: () -> Unit,
) {
  val p = LocalPalette.current
  var detailSlot by remember(seed) { mutableStateOf<Int?>(null) }
  var detailPerson by remember(seed) { mutableStateOf<String?>(null) }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    val s = source?.archived?.summary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      IconButton(onBack, Icon.BACK)
      Column(Modifier.weight(1f)) {
        Txt("Past session" + (s?.let { ": ${it.title}" } ?: ""), size = 20.sp, weight = FontWeight.Bold)
        val finished = s?.finishedAt?.let { "finished ${Platform.date(it)}" }
        val by = source?.archived?.finishedBy?.let { "by $it" } ?: if (s != null) "when every slot was done" else null
        Txt(listOfNotNull("Seed $seed", s?.serverVersion?.let { "AP $it" }, listOfNotNull(finished, by).joinToString(" ").ifEmpty { null })
          .joinToString(" · "), size = 12.5.sp, color = p.muted)
      }
      if (s != null) SmallButton("Spoiler log", onSpoiler)
    }
    if (missing) Txt("This session can't be shown (it was deleted, or you didn't play in it).", color = p.muted)
    if (s == null) return@Column
    val now = s.finishedAt ?: Platform.now()
    Stats(s, now, contentWidth)
    People(s, now, contentWidth, onOpen = { detailSlot = it }, onOpenPerson = { detailPerson = it })
    if (showHints(s)) Hints(s, compact = contentWidth < 700.dp)
    Feed(s, Modifier.fillMaxWidth(), FeedPosition.BELOW, false, onPosition = null, onHide = null, title = "Feed")
  }
  val src = source
  if (src != null) {
    detailPerson?.let { name -> PersonDialog(name, src.archived.summary, src.archived.summary.finishedAt ?: 0, src, onClose = { detailPerson = null }) }
    detailSlot?.let { slot -> PlayerDialog(slot, 0, src, onClose = { detailSlot = null }) }
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
