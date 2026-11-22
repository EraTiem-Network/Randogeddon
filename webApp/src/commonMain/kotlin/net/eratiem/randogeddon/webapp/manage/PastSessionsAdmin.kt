package net.eratiem.randogeddon.webapp.manage

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.KeepRequest
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.date
import net.eratiem.randogeddon.webapp.dashboard.Fact
import net.eratiem.randogeddon.webapp.dashboard.StatusPill
import net.eratiem.randogeddon.webapp.ui.Avatar
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.HoverRow
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.NARROW
import net.eratiem.randogeddon.webapp.ui.ProgressBar
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.hand
import net.eratiem.randogeddon.webapp.ui.scrolling

/**
 * The finished sessions, the newest first: spoiler log, reopen, auto-deletion, delete; a click shows a session's
 * overview, floating over the panel. [onChanged] after an action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PastSessionsAdmin(sessions: List<SessionEntry>, onChanged: () -> Unit, onSpoiler: (String) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  var problem by remember { mutableStateOf<String?>(null) }
  fun act(method: String, path: String, body: String? = null) = scope.launch {
    problem = (call(method, path, body) { } as? Answer.Failed)?.message
    onChanged()
  }
  val onReopen: (String) -> Unit = { act("POST", "api/sessions/$it/manage/reopen") }
  val onKeep: (String, Boolean) -> Unit = { id, keep ->
    act("POST", "api/sessions/$id/manage/keep", ApiJson.encodeToString(KeepRequest.serializer(), KeepRequest(keep)))
  }
  val onDelete: (String) -> Unit = { act("DELETE", "api/sessions/$it/manage") }
  // (session, "delete" or "keep"): the question shown in place of that session's buttons
  var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
  var overview by remember { mutableStateOf<SessionEntry?>(null) }
  overview?.let { PastSessionOverview(it, onClose = { overview = null }) }
  Section(t.sessionAdmin.pastSessions, subtitle = t.sessionAdmin.pastSubtitle, actions = {
    InfoTip(t.tips.keep)
  }) {
    BoxWithConstraints(Modifier.fillMaxWidth().card(p).padding(16.dp)) {
      // A phone: the title, the details and the buttons one below the other
      val stacked = maxWidth < NARROW
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
        if (sessions.isEmpty()) Txt(t.sessionAdmin.noneYet, size = 13.sp, color = p.muted)
        sessions.forEachIndexed { i, past ->
          if (i > 0) Divider()
          val seed = past.id
          // A click shows the overview, with the players
          val title: @Composable (Modifier) -> Unit = { modifier ->
            HoverRow(onClick = { overview = past }, modifier = modifier) {
              Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Txt(seed, size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
                  StatusPill(past.status)
                }
                Txt(
                  listOfNotNull(
                    past.finishedAt?.let { t.sessionAdmin.finishedOn(t.date(it), past.finishedBy) },
                    "${t.decimal(past.percent)}%",
                    deletion(t, past.deletesAt, past.keep),
                  ).joinToString(" · "),
                  size = 12.sp, color = p.muted, maxLines = if (stacked) 4 else 2,
                )
              }
              Box(Modifier.padding(end = 4.dp).rotate(180f)) { DrawnIcon(Icon.BACK, p.muted, 14.dp) }
            }
          }
          val buttons: @Composable () -> Unit = {
            if (pending?.first != seed) {
              if (reopenable(past.reopenUntil)) Tip(t.tips.reopen) { SmallButton(t.sessionAdmin.reopen, { onReopen(seed) }) }
              Tip(t.tips.spoiler) { SmallButton(t.finish.spoilerLog, { onSpoiler(seed) }) }
              Tip(t.tips.keep) {
                SmallButton(if (past.keep) t.sessionAdmin.enableAutoDeletion else t.sessionAdmin.disableAutoDeletion, { pending = seed to "keep" })
              }
              SmallButton(t.sessionAdmin.delete, { pending = seed to "delete" })
            }
          }
          if (stacked) {
            title(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { buttons() }
          } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              title(Modifier.weight(1f))
              buttons()
            }
          }
          when (pending?.takeIf { it.first == seed }?.second) {
            "delete" -> Confirm(
              t.sessionAdmin.confirmDeleteFinished, t.sessionAdmin.yesDelete, danger = true,
              onYes = { pending = null; onDelete(seed) }, onCancel = { pending = null },
            )
            "keep" -> Confirm(
              if (past.keep) t.sessionAdmin.confirmEnable else t.sessionAdmin.confirmDisable,
              if (past.keep) t.sessionAdmin.yesEnable else t.sessionAdmin.yesDisable, danger = false,
              onYes = { pending = null; onKeep(seed, !past.keep) }, onCancel = { pending = null },
            )
          }
        }
      }
    }
  }
}

/** A past session at a glance, floating over the panel: how it went, when it finished, what happens to it, who played. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PastSessionOverview(past: SessionEntry, onClose: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    SelectionContainer {
      Column(
        Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth().card(p, RoundedCornerShape(20.dp)),
      ) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(Modifier.weight(1f)) {
            Txt(past.id, size = 18.sp, weight = FontWeight.Bold, maxLines = 2)
            Txt(t.frame.seed(past.seedName), size = 12.5.sp, color = p.muted, maxLines = 1)
          }
          Txt("${t.decimal(past.percent)}%", size = 22.sp, weight = FontWeight.Bold, brush = p.brand)
          IconButton(onClose, Icon.CLOSE)
        }
        Divider()
        Column(Modifier.scrolling().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          ProgressBar((past.percent / 100).toFloat())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact(t.dialogs.slots, "${past.slots}")
            Fact(t.dialogs.goals, "${past.goals} / ${past.slots}", if (past.goals > 0) p.ok else p.text)
            Fact(t.dashboard.players, "${past.players.size}")
          }
          val until = past.reopenUntil
          listOfNotNull(
            past.createdAt?.let { t.sessionAdmin.createdLabel to t.date(it) },
            t.sessionAdmin.finishedLabel to t.sessionAdmin.finishedValue(past.finishedAt?.let { t.date(it) }, past.finishedBy),
            t.sessionAdmin.reopenLabel to when {
              until == null -> t.sessionAdmin.reopenNotPossible
              reopenable(until) -> t.sessionAdmin.reopenPossibleUntil(t.date(until))
              else -> t.sessionAdmin.reopenNoLonger
            },
            t.sessionAdmin.deletionLabel to (deletion(t, past.deletesAt, past.keep) ?: t.sessionAdmin.notAutomatic),
          ).forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Txt(label, size = 12.5.sp, color = p.muted, modifier = Modifier.width(72.dp))
              Txt(value, size = 13.sp, modifier = Modifier.weight(1f))
            }
          }
          Txt(t.sessionAdmin.playersHeading, size = 12.sp, color = p.muted, weight = FontWeight.SemiBold)
          if (past.players.isEmpty()) Txt(t.sessionAdmin.nobodyWasNamed, size = 13.sp, color = p.muted)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            past.players.forEach { name ->
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Avatar(name, 26.dp)
                Txt(name, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
              }
            }
          }
        }
      }
    }
  }
}

/** A button of the switch; the page that is shown is filled. */
@Composable
internal fun PageButton(text: String, active: Boolean, attention: Boolean = false, onClick: () -> Unit) {
  val p = LocalPalette.current
  // [attention]: it breathes (a little bigger and brighter, and back), while it is the one thing to do here
  val pulse = if (attention) {
    val beat by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    beat
  } else 0f
  Txt(
    text, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1,
    color = if (active) p.onBrand else p.accent,
    modifier = Modifier.graphicsLayer { scaleX = 1f + 0.07f * pulse; scaleY = 1f + 0.07f * pulse }
      .heightIn(min = FIELD_HEIGHT).clip(RoundedCornerShape(10.dp))
      .background(if (active) p.accent else p.accent.copy(alpha = 0.1f + 0.22f * pulse))
      .hand().clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}
