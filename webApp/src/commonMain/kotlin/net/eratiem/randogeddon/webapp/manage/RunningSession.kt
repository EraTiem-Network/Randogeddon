package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.AdminOverview
import net.eratiem.randogeddon.shared.AdminSessionView
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.KeepRequest
import net.eratiem.randogeddon.shared.KeepRunningRequest
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionInfo
import net.eratiem.randogeddon.shared.SlotFile
import net.eratiem.randogeddon.shared.SlotFiles
import net.eratiem.randogeddon.shared.i18n.Texts
import net.eratiem.randogeddon.shared.i18n.fileSize
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.shared.web.running
import net.eratiem.randogeddon.webapp.api.AdminCache
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.api.post
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.clock
import net.eratiem.randogeddon.webapp.app.date
import net.eratiem.randogeddon.webapp.dashboard.SpoilerConsent
import net.eratiem.randogeddon.webapp.dashboard.allChecked
import net.eratiem.randogeddon.webapp.dashboard.dotSegment
import net.eratiem.randogeddon.webapp.dashboard.slotFilePath
import net.eratiem.randogeddon.webapp.ui.Button
import net.eratiem.randogeddon.webapp.ui.Check
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Mono
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.rememberAction

/**
 * Session [id] as the admin panel shows it: how it stands ([overview]: also how many game servers are in use), its
 * actions, its game server ([server]); who plays which slot below. Kept in [cache]. An [administrator] can keep its
 * game server running without players.
 */
@Composable
fun SessionSection(
  id: String,
  overview: AdminOverview?,
  cache: AdminCache,
  administrator: Boolean,
  onChanged: () -> Unit,
  onSpoiler: (String) -> Unit,
  onDashboard: (String) -> Unit,
  server: @Composable () -> Unit = {},
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  // The last one loaded at once, loaded again behind
  val view = cache.views[id]
  var problem by remember { mutableStateOf<String?>(null) }
  var gone by remember { mutableStateOf(false) }
  fun load() = scope.launch {
    when (val answer = call("GET", "api/sessions/$id/manage", null, AdminSessionView.serializer())) {
      is Answer.Ok -> { cache.views[id] = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  LaunchedEffect(Unit) { load() }
  // One action at a time: a second click while one is sent (finish, delete, …) is dropped
  val action = rememberAction()
  fun act(path: String, body: String? = null) = action.run {
    post("api/sessions/$id/manage/$path", body).also {
      load()
      onChanged()
    }
  }

  val session = view?.session
  Section(t.sessionAdmin.stateAndControl, subtitle = session?.let { sessionDetails(t, it) } ?: "…") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      val entry = overview?.sessions?.firstOrNull { it.id == id }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        stateLines(t, entry, overview).forEachIndexed { i, line ->
          Txt(line, size = if (i == 0) 13.5.sp else 12.sp, weight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (i == 0) p.text else p.muted)
        }
      }
      (action.problem ?: problem)?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      when {
        gone -> Txt(t.sessionAdmin.deleted, color = p.muted)
        session == null -> Txt("…", color = p.muted)
        else -> SessionSummary(
          session,
          busy = action.busy,
          onDelete = {
            action.run {
              when (val answer = call("DELETE", "api/sessions/$id/manage", null) { }) {
                is Answer.Ok -> { gone = true; onChanged(); null }
                is Answer.Failed -> answer.message
              }
            }
          },
          onFinish = { act("finish") },
          onCancelFinish = { act("finish/cancel") },
          onReopen = { act("reopen") },
          onKeep = { keep -> act("keep", ApiJson.encodeToString(KeepRequest.serializer(), KeepRequest(keep))) },
          // Administrators' only: the tracker refuses `api/admin/…` to everyone else too
          onKeepRunning = if (!administrator) null else { keep ->
            action.run {
              val body = ApiJson.encodeToString(KeepRunningRequest.serializer(), KeepRunningRequest(keep))
              post("api/admin/sessions/$id/keep-running", body, method = "PUT").also { load() }
            }
          },
          // Asked (a running session) and confirmed: the spoiler page doesn't ask again in this tab
          onSpoiler = { confirmed -> if (confirmed) SpoilerConsent.give(id); onSpoiler(id) },
          onDashboard = { onDashboard(id) },
        )
      }
      if (!gone) server()
    }
  }
}

/** Every slot's files from the seed (patches, mods), to download, e.g. for someone who can't log in. */
@Composable
fun PatchFilesSection(id: String) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var files by remember(id) { mutableStateOf<List<SlotFile>?>(null) }
  var problem by remember(id) { mutableStateOf<String?>(null) }
  LaunchedEffect(id) {
    when (val answer = call("GET", "api/sessions/$id/files", null, SlotFiles.serializer())) {
      is Answer.Ok -> { files = answer.value.files; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  val list = files.orEmpty()
  Section(
    t.sessionAdmin.patchFiles, subtitle = if (files == null) "…" else t.sessionAdmin.patchFilesSubtitle(list.size),
    actions = { InfoTip("${t.sessionAdmin.filesTip} ${t.connection.patchHowTo}") },
  ) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      if (files != null && list.isEmpty()) Txt(t.sessionAdmin.noPatchFiles, size = 13.sp, color = p.muted)
      list.forEach { file ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Pill(file.slotName ?: t.sessionAdmin.slotNumber(file.slot), p.hue(file.slotName ?: file.name))
          Mono(file.name, Modifier.weight(1f))
          Txt(t.fileSize(file.size), size = 12.sp, color = p.muted, maxLines = 1)
          // `.` or `..` would be resolved away in the path (encoded too): no download for such a name
          if (!dotSegment(file.name)) SmallButton(t.connection.download, { Platform.download(slotFilePath(id, file.name)) })
        }
      }
    }
  }
}

/** How [session] stands (in the switch's list too), and how many of the gateway's game servers are in use. */
private fun stateLines(t: Texts, session: SessionEntry?, overview: AdminOverview?): List<String> {
  val all = overview?.sessions.orEmpty()
  return listOfNotNull(
    session?.let { switchDetails(t, it) }?.replaceFirstChar { it.uppercase() },
    overview?.let { t.sessionAdmin.sessionsRunning(all.count { s -> s.status.running }, it.inUse, it.capacity) },
  )
}

/** Archipelago release, when it was created, the main options: the session section's subtitle. */
private fun sessionDetails(t: Texts, session: SessionInfo): String = listOfNotNull(
  session.apVersion?.let { t.sessionAdmin.archipelagoVersion(it) } ?: t.sessionAdmin.deployedWithCli,
  session.createdAt?.let { t.sessionAdmin.created(t.date(it)) },
  session.options?.let { o ->
    listOfNotNull(
      if (o.pointHints) t.sessionAdmin.pointHints(o.hintCost) else null,
      if (o.autoHints) t.sessionAdmin.automaticHints else null,
      t.sessionAdmin.releaseMode(o.releaseMode),
    ).joinToString(", ")
  },
).joinToString(" · ")

/** When a finished session is deleted by itself, or that it isn't. */
internal fun deletion(t: Texts, deletesAt: Long?, keep: Boolean): String? = when {
  keep -> t.sessionAdmin.autoDeletionDisabled
  deletesAt != null -> t.sessionAdmin.deletedAutomaticallyOn(t.date(deletesAt))
  else -> null
}

/** Reopening is possible until [until]; `null`: not at all (it was completed). */
internal fun reopenable(until: Long?): Boolean = until != null && Platform.now() < until

/**
 * The current session: who plays which slot, how it stands (it ends by itself soon, or it is paused or ended), and one
 * line of actions: pause or end it (reopen it), its auto-deletion, delete it, and on the right its spoiler log. What
 * needs a second thought asks in place of that line.
 */
@Composable
private fun SessionSummary(
  session: SessionInfo,
  /** An action is being sent: the buttons wait for it. */
  busy: Boolean,
  onDelete: () -> Unit,
  onFinish: () -> Unit,
  onCancelFinish: () -> Unit,
  onReopen: () -> Unit,
  onKeep: (Boolean) -> Unit,
  /** Administrators: its game server keeps running without players (or sleeps); `null`: not theirs to choose. */
  onKeepRunning: ((Boolean) -> Unit)?,
  /** Opens the spoiler log; `true`: after its question was answered yes (a running session). */
  onSpoiler: (confirmed: Boolean) -> Unit,
  onDashboard: () -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // "finish", "spoiler", "delete" or "keep": the question shown instead of the actions
  var pending by remember { mutableStateOf<String?>(null) }
  val finishedAt = session.finishedAt
  val finishesAt = session.finishesAt
  // By the counts: a big session's rounded percentage would say 100 with a check or two left
  val complete = allChecked(session.checked, session.total)
  val until = session.reopenUntil
  when {
    finishedAt != null -> Txt(
      listOfNotNull(
        t.sessionAdmin.finishedSentence(complete, t.date(finishedAt)),
        when {
          until == null -> t.sessionAdmin.completedNoReopen
          reopenable(until) -> t.sessionAdmin.reopenUntilSentence(t.date(until))
          else -> t.sessionAdmin.pausedTooLong
        },
        deletion(t, session.deletesAt, session.keep)?.let { t.sessionAdmin.onceNewer(it) },
      ).joinToString(" "),
      size = 13.sp,
    )
    finishesAt != null -> Txt(t.sessionAdmin.endsByItselfAt(t.clock(finishesAt)), size = 13.sp)
  }
  when (pending) {
    "finish" -> Confirm(
      if (complete) t.sessionAdmin.confirmEnd else t.sessionAdmin.confirmPause,
      if (complete) t.sessionAdmin.yesEnd else t.sessionAdmin.yesPause, danger = true,
      onYes = { pending = null; onFinish() }, onCancel = { pending = null },
    )
    "spoiler" -> Confirm(
      t.sessionAdmin.confirmSpoiler, t.sessionAdmin.showIt, danger = true,
      onYes = { pending = null; onSpoiler(true) }, onCancel = { pending = null },
    )
    "delete" -> Confirm(
      if (finishedAt != null) t.sessionAdmin.confirmDeleteFinished else t.sessionAdmin.confirmDeleteRunning,
      t.sessionAdmin.yesDelete, danger = true,
      onYes = { pending = null; onDelete() }, onCancel = { pending = null },
    )
    "keep" -> Confirm(
      if (session.keep) t.sessionAdmin.confirmEnable else t.sessionAdmin.confirmDisable,
      if (session.keep) t.sessionAdmin.yesEnable else t.sessionAdmin.yesDisable, danger = false,
      onYes = { pending = null; onKeep(!session.keep) }, onCancel = { pending = null },
    )
    else -> ActionsLine(actions = {
      when {
        finishedAt != null -> if (reopenable(until)) Tip(t.tips.reopen) { Button(t.sessionAdmin.reopen, enabled = !busy) { onReopen() } }
        finishesAt != null -> {
          Tip(t.tips.end) { Button(t.finish.endNow, enabled = !busy) { onFinish() } }
          Button(t.common.cancel, enabled = !busy) { onCancelFinish() }
        }
        else -> Tip(if (complete) t.tips.end else t.tips.pause) {
          Button(if (complete) t.sessionAdmin.endSession else t.sessionAdmin.pauseSession, enabled = !busy) { pending = "finish" }
        }
      }
      Button(t.sessionAdmin.deleteSession, enabled = !busy, danger = true) { pending = "delete" }
      if (finishedAt == null && onKeepRunning != null) {
        Check(t.sessionAdmin.keepRunning, session.keepRunning, t.sessionAdmin.keepRunningTip, enabled = !busy) { onKeepRunning(it) }
      }
    }, links = {
      if (finishedAt != null) {
        Tip(t.tips.keep) { SmallButton(if (session.keep) t.sessionAdmin.enableAutoDeletion else t.sessionAdmin.disableAutoDeletion, { pending = "keep" }) }
      }
      Tip(t.tips.dashboard) { SmallButton(t.sessionAdmin.dashboard, onDashboard) }
      Tip(t.tips.spoiler) { SmallButton(t.sessionAdmin.spoilerLog, { if (finishedAt != null) onSpoiler(false) else pending = "spoiler" }) }
    })
  }
}

/**
 * A session's line of [actions], its [links] (dashboard, spoiler log) on the right. Where that doesn't fit (a phone),
 * the actions wrap as they need, and the links go on a line of their own below them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsLine(actions: @Composable () -> Unit, links: @Composable () -> Unit) {
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth < 640.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) { actions() }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) { links() }
    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      actions()
      Spacer(Modifier.weight(1f))
      links()
    }
  }
}
