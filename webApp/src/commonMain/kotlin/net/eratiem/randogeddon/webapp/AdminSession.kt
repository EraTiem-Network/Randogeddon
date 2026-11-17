package net.eratiem.randogeddon.webapp

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.i18n.Texts
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.fileSize
import net.eratiem.randogeddon.shared.i18n.texts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.AdminOverview
import net.eratiem.randogeddon.shared.AdminSessionView
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CreateSessionRequest
import net.eratiem.randogeddon.shared.CreateSessionResult
import net.eratiem.randogeddon.shared.GenerationStage
import net.eratiem.randogeddon.shared.GenerationProgress
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.InspectRequest
import net.eratiem.randogeddon.shared.InspectResult
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.KeepRequest
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionIds
import net.eratiem.randogeddon.shared.SessionStatus
import net.eratiem.randogeddon.shared.SessionInfo
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.SlotFile
import net.eratiem.randogeddon.shared.SlotFiles
import net.eratiem.randogeddon.shared.UploadedYaml
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.UserNames
import net.eratiem.randogeddon.shared.WorldPlan
import net.eratiem.randogeddon.shared.YamlWorld

/** Archipelago's choices for release and collect (see .env.example). */
private val RELEASE_MODES = listOf("auto", "enabled", "disabled", "goal", "auto-enabled")
private val REMAINING_MODES = listOf("enabled", "disabled", "goal")

/** How the locks of a new session are chosen. */
private enum class LockMode { CHOOSE, RANDOM, MIXED }

/** A slot's lock with mixed locks: the switch's three stops, left to right. */
private enum class LockChoice { OPEN, RANDOM, LOCKED }

/** POST without a body; the error message, or `null`. */
private suspend fun post(path: String, body: String? = null): String? =
  (call("POST", path, body) { } as? Answer.Failed)?.message

/** An answer of the admin API: the decoded body, or the error message. */
internal sealed interface Answer<out T> {
  data class Ok<T>(val value: T) : Answer<T>
  /** [detail]: what another program said (Generate.py), shown as it is under [message]. */
  data class Failed(val message: String, val detail: String? = null) : Answer<Nothing>
}

internal suspend fun <T> call(method: String, path: String, body: String?, decode: (String) -> T): Answer<T> {
  val t = texts(Languages.current)
  val result = Platform.request(method, path, body) ?: return Answer.Failed(t.common.serverUnreachable)
  if (result.status in 200..299) return runCatching { Answer.Ok(decode(result.body)) }.getOrElse { Answer.Failed(t.common.unexpectedAnswer) }
  val error = runCatching { ApiJson.decodeFromString(ApiError.serializer(), result.body) }.getOrNull()
  return Answer.Failed(error?.let { t.word(it) } ?: t.common.failed(result.status), error?.detail)
}

/**
 * Session [id] as the admin panel shows it: how it stands ([overview]: also how many game servers are in use), its
 * actions, its game server ([server]); who plays which slot below. Kept in [cache].
 */
@Composable
fun SessionSection(
  id: String,
  overview: AdminOverview?,
  cache: AdminCache,
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
    when (val answer = call("GET", "api/sessions/$id/manage", null) { ApiJson.decodeFromString(AdminSessionView.serializer(), it) }) {
      is Answer.Ok -> { cache.views[id] = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  LaunchedEffect(Unit) { load() }
  fun act(path: String, body: String? = null) = scope.launch {
    problem = post("api/sessions/$id/manage/$path", body)
    load()
    onChanged()
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
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      when {
        gone -> Txt(t.sessionAdmin.deleted, color = p.muted)
        session == null -> Txt("…", color = p.muted)
        else -> SessionSummary(
          session,
          onDelete = {
            scope.launch {
              when (val answer = call("DELETE", "api/sessions/$id/manage", null) { }) {
                is Answer.Ok -> { gone = true; onChanged() }
                is Answer.Failed -> problem = answer.message
              }
            }
          },
          onFinish = { act("finish") },
          onCancelFinish = { act("finish/cancel") },
          onReopen = { act("reopen") },
          onKeep = { keep -> act("keep", ApiJson.encodeToString(KeepRequest.serializer(), KeepRequest(keep))) },
          onSpoiler = { onSpoiler(id) },
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
  LaunchedEffect(id) {
    (call("GET", "api/sessions/$id/files", null) { ApiJson.decodeFromString(SlotFiles.serializer(), it) } as? Answer.Ok)
      ?.let { files = it.value.files }
  }
  val list = files.orEmpty()
  Section(
    t.sessionAdmin.patchFiles, subtitle = if (files == null) "…" else t.sessionAdmin.patchFilesSubtitle(list.size),
    actions = { InfoTip("${t.sessionAdmin.filesTip} ${t.connection.patchHowTo}") },
  ) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (files != null && list.isEmpty()) Txt(t.sessionAdmin.noPatchFiles, size = 13.sp, color = p.muted)
      list.forEach { file ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Pill(file.slotName ?: t.sessionAdmin.slotNumber(file.slot), p.hue(file.slotName ?: file.name))
          Mono(file.name, p.text, Modifier.weight(1f))
          Txt(t.fileSize(file.size), size = 12.sp, color = p.muted, maxLines = 1)
          SmallButton(t.connection.download, { Platform.download(slotFilePath(id, file.name)) })
        }
      }
    }
  }
}

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

/** How [session] stands (in the switch's list too), and how many of the gateway's game servers are in use. */
private fun stateLines(t: Texts, session: SessionEntry?, overview: AdminOverview?): List<String> {
  val all = overview?.sessions.orEmpty()
  return listOfNotNull(
    session?.let { switchDetails(t, it) }?.replaceFirstChar { it.uppercase() },
    overview?.let { t.sessionAdmin.sessionsRunning(all.count { s -> s.status.running }, it.inUse, it.capacity) },
  )
}

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

/** A session in the switch's list: how it stands, how far it got, its game server, who is online. */
private fun switchDetails(t: Texts, session: SessionEntry): String = listOfNotNull(
  t.sessions.status(session.status),
  "${t.decimal(session.percent)}%",
  session.server?.status?.let { t.sessionAdmin.server(it) },
  session.online.takeIf { it > 0 }?.let { t.sessions.online(it) },
  session.finishedAt?.let { t.sessions.finishedOn(t.date(it)) },
).joinToString(" · ")

/** A button of the switch; the page that is shown is filled. */
@Composable
private fun PageButton(text: String, active: Boolean, attention: Boolean = false, onClick: () -> Unit) {
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

/** The game server states that take one of the gateway's ports. */
private val RUNNING = setOf(GameServerStatus.STARTING, GameServerStatus.RUNNING, GameServerStatus.CRASHED)

/**
 * A new session: its ID (a suggestion to start with; checked as it is typed) and the form (YAMLs, who plays which
 * slot, locks, options). Its logins are shown once it is created.
 */
@Composable
fun NewSessionSection(overview: AdminOverview?, administrator: Boolean, onCreated: (CreateSessionResult) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var id by remember { mutableStateOf<String?>(null) }
  var created by remember { mutableStateOf<CreateSessionResult?>(null) }
  // A new form (and a new suggestion) after each session
  var round by remember { mutableStateOf(0) }
  val suggested = overview?.suggestedId.orEmpty()
  val chosen = id ?: suggested
  val taken = overview?.taken?.contains(chosen) == true || overview?.sessions?.any { it.id == chosen } == true
  // What the server says about the ID (a word nobody wants in one), asked a moment after the last key
  var idRefused by remember { mutableStateOf<String?>(null) }
  val formProblem = if (chosen.isEmpty()) null else SessionIds.problem(chosen)?.let { t.problems.text(it) } ?: if (taken) t.sessionAdmin.taken else null
  LaunchedEffect(chosen) {
    idRefused = null
    if (chosen.isEmpty() || formProblem != null) return@LaunchedEffect
    delay(400)
    idRefused = (call("GET", "api/sessions/new/id?id=$chosen", null) { } as? Answer.Failed)?.message
  }
  val idProblem = formProblem ?: idRefused
  // While it generates, the form makes room for the ID and the bar
  var generating by remember { mutableStateOf(false) }
  val full = overview != null && overview.inUse >= overview.capacity
  // The user manages as many running sessions as the Administrators allow
  val atLimit = overview?.left == 0
  Section(t.sessionAdmin.newSession, subtitle = t.sessionAdmin.newSessionSubtitle(overview?.capacity?.toString() ?: "…")) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      created?.let { result ->
        Created(result.locked, onDone = { created = null })
      }
      if (!generating) {
        if (full) Txt(t.sessionAdmin.everyServerInUse, size = 12.5.sp, color = p.trap)
        if (atLimit) Txt(t.sessionAdmin.atLimit, size = 12.5.sp, color = p.trap)
        overview?.left?.takeIf { it > 0 }?.let { Txt(t.sessionAdmin.mayCreate(it), size = 12.5.sp, color = p.muted) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt(t.sessionAdmin.sessionId, size = 13.sp, weight = FontWeight.SemiBold)
          InfoTip(t.tips.sessionId)
          FilterField(chosen, { id = it.lowercase().trim() }, "friday-chaos", Modifier.width(220.dp), minHeight = FIELD_HEIGHT)
          idProblem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
        }
      }
      if (overview != null) key(round) {
        CreateSession(
          AdminSessionView(apVersions = overview.apVersions, users = overview.users, people = overview.people), chosen,
          administrator = administrator,
          ready = idProblem == null && chosen.isNotEmpty() && !full && !atLimit,
          onBusy = { generating = it },
          onCreated = { result ->
            created = result
            id = null
            round++
            onCreated(result)
          },
        )
      }
    }
  }
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
private fun deletion(t: Texts, deletesAt: Long?, keep: Boolean): String? = when {
  keep -> t.sessionAdmin.autoDeletionDisabled
  deletesAt != null -> t.sessionAdmin.deletedAutomaticallyOn(t.date(deletesAt))
  else -> null
}

/** Reopening is possible until [until]; `null`: not at all (it was completed). */
private fun reopenable(until: Long?): Boolean = until != null && Platform.now() < until

/** A question before something is done, in place of the buttons: Yes (red if [danger]) or Cancel. */
@Composable
private fun Confirm(question: String, yes: String, danger: Boolean, onYes: () -> Unit, onCancel: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Txt(question, size = 13.sp, modifier = Modifier.weight(1f))
    Button(yes, danger = danger) { onYes() }
    Button(LocalTexts.current.common.cancel) { onCancel() }
  }
}

/**
 * The current session: who plays which slot, how it stands (it ends by itself soon, or it is paused or ended), and one
 * line of actions: pause or end it (reopen it), its auto-deletion, delete it, and on the right its spoiler log. What
 * needs a second thought asks in place of that line.
 */
@Composable
private fun SessionSummary(
  session: SessionInfo,
  onDelete: () -> Unit,
  onFinish: () -> Unit,
  onCancelFinish: () -> Unit,
  onReopen: () -> Unit,
  onKeep: (Boolean) -> Unit,
  onSpoiler: () -> Unit,
  onDashboard: () -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // "finish", "spoiler", "delete" or "keep": the question shown instead of the actions
  var pending by remember { mutableStateOf<String?>(null) }
  val finishedAt = session.finishedAt
  val finishesAt = session.finishesAt
  val complete = session.percent >= 100.0
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
      onYes = { pending = null; onSpoiler() }, onCancel = { pending = null },
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
    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      when {
        finishedAt != null -> if (reopenable(until)) Tip(t.tips.reopen) { Button(t.sessionAdmin.reopen) { onReopen() } }
        finishesAt != null -> {
          Tip(t.tips.end) { Button(t.finish.endNow) { onFinish() } }
          Button(t.common.cancel) { onCancelFinish() }
        }
        else -> Tip(if (complete) t.tips.end else t.tips.pause) {
          Button(if (complete) t.sessionAdmin.endSession else t.sessionAdmin.pauseSession) { pending = "finish" }
        }
      }
      Button(t.sessionAdmin.deleteSession, danger = true) { pending = "delete" }
      Spacer(Modifier.weight(1f))
      if (finishedAt != null) {
        Tip(t.tips.keep) { SmallButton(if (session.keep) t.sessionAdmin.enableAutoDeletion else t.sessionAdmin.disableAutoDeletion, { pending = "keep" }) }
      }
      Tip(t.tips.dashboard) { SmallButton(t.sessionAdmin.dashboard, onDashboard) }
      Tip(t.tips.spoiler) { SmallButton(t.sessionAdmin.spoilerLog, { if (finishedAt != null) onSpoiler() else pending = "spoiler" }) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateSession(
  view: AdminSessionView,
  id: String,
  ready: Boolean,
  onCreated: (CreateSessionResult) -> Unit,
  administrator: Boolean,
  /** It generates (or stopped): the form around this hides what it shows besides. */
  onBusy: (Boolean) -> Unit = {},
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  val yamls = remember { mutableStateListOf<UploadedYaml>() }
  var worlds by remember { mutableStateOf<List<YamlWorld>>(emptyList()) }
  var problems by remember { mutableStateOf<List<ApiError>>(emptyList()) }
  // Slot -> the IDs of its players; the accounts to pick from, with those invited from here
  val users = remember { mutableStateMapOf<String, List<String>>() }
  // The hosted slots: no players, their games run as servers of their own
  val hosted = remember { mutableStateMapOf<String, Boolean>() }
  val people = remember { mutableStateListOf<UserRef>().apply { addAll(view.people) } }
  val locked = remember { mutableStateMapOf<String, Boolean>() }
  var lockMode by remember { mutableStateOf(LockMode.CHOOSE) }
  // Mixed locks: each slot's switch (left to chance until moved)
  val choices = remember { mutableStateMapOf<String, LockChoice>() }
  // How many slots start open with random or mixed locks; `null`: about half
  var openCount by remember { mutableStateOf<Int?>(null) }
  // Hints are off until switched on for the session
  var options by remember { mutableStateOf(SessionOptions(pointHints = false, autoHints = false)) }
  var version by remember { mutableStateOf(view.apVersions.firstOrNull()) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<Answer.Failed?>(null) }
  // How far the generation is while it runs (asked about three times a second)
  var progress by remember { mutableStateOf<GenerationProgress?>(null) }
  LaunchedEffect(busy) {
    onBusy(busy)
    progress = null
    while (busy) {
      delay(300)
      (call("GET", "api/sessions/new/progress/$id", null) {
        if (it.isBlank()) null else ApiJson.decodeFromString(GenerationProgress.serializer(), it)
      } as? Answer.Ok)?.value?.let { progress = it }
    }
  }
  // The YAML to remove, asked once more; "": all of them
  var removing by remember { mutableStateOf<String?>(null) }
  // Why the files just picked that aren't player YAMLs weren't taken
  var refused by remember { mutableStateOf<List<String>>(emptyList()) }

  fun inspect(): Job = scope.launch {
    val body = ApiJson.encodeToString(InspectRequest.serializer(), InspectRequest(yamls.toList(), version))
    when (val answer = call("POST", "api/sessions/new/inspect", body) { ApiJson.decodeFromString(InspectResult.serializer(), it) }) {
      is Answer.Ok -> {
        val invalid = answer.value.invalid.toSet()
        if (invalid.isNotEmpty()) {
          // Not taken at all; the others are checked again without them
          refused = refused + answer.value.problems.filter { it.args["file"] in invalid }.map { t.word(it) }
          yamls.removeAll { it.fileName in invalid }
          if (yamls.isEmpty()) {
            worlds = emptyList(); problems = emptyList()
          } else {
            inspect()
          }
          return@launch
        }
        worlds = answer.value.worlds
        problems = answer.value.problems
      }
      is Answer.Failed -> problems = listOf(ApiError(answer.message))
    }
  }
  // Another release may have other games
  LaunchedEffect(version) { if (yamls.isNotEmpty()) inspect() }

  // While it generates: nothing of the form (there is nothing to change now), just the session's ID, the stage
  // and about how far. A failure brings the form back as it was
  if (busy) {
    val now = progress ?: GenerationProgress(GenerationStage.STARTING, 0)
    Txt(id, size = 18.sp, weight = FontWeight.Bold)
    Txt(t.sessionAdmin.generatesSeed, size = 12.5.sp, color = p.muted)
    ProgressBar(now.percent / 100f)
    Txt("${t.sessionAdmin.generationStage(now.stage)} ${now.percent} %", size = 12.5.sp, color = p.muted)
    return
  }

  Txt(t.sessionAdmin.stepYamls, size = 13.sp, weight = FontWeight.SemiBold)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(t.sessionAdmin.addYamls) {
      Platform.pickFiles(".yaml,.yml") { json ->
        val picked = runCatching { ApiJson.decodeFromString(ListSerializer(UploadedYaml.serializer()), json) }.getOrDefault(emptyList())
        // Choosing again adds files; one with the same name replaces the earlier one
        picked.forEach { file -> yamls.removeAll { it.fileName == file.fileName }; yamls += file }
        refused = emptyList()
        inspect()
      }
    }
    if (yamls.isNotEmpty() && removing == null) Button(t.sessionAdmin.clearAll, danger = true) { removing = "" }
  }
  // Asked once more: removing all of them right below their buttons, one of them below the YAMLs
  @Composable
  fun ConfirmRemoval(file: String) = Confirm(
    if (file.isEmpty()) t.sessionAdmin.removeAll(yamls.size) else t.sessionAdmin.removeFile(file),
    t.apworlds.yesRemove, danger = true,
    onYes = {
      removing = null
      if (file.isEmpty()) yamls.clear() else yamls.removeAll { it.fileName == file }
      if (yamls.isEmpty()) {
        worlds = emptyList(); problems = emptyList(); users.clear(); locked.clear(); choices.clear()
      } else {
        inspect()
      }
    },
    onCancel = { removing = null },
  )
  if (removing == "") ConfirmRemoval("")
  if (yamls.isNotEmpty()) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      yamls.forEach { yaml ->
        YamlChip(yaml.fileName, worlds.filter { it.fileName == yaml.fileName }.map { it.game }.distinct()) { removing = yaml.fileName }
      }
    }
  }
  removing?.takeIf { it.isNotEmpty() }?.let { ConfirmRemoval(it) }
  refused.forEach { Txt(t.sessionAdmin.notAdded(it), size = 12.5.sp, color = p.trap) }
  problems.filter { it.code != Problem.NO_APWORLD }.forEach { Txt(t.word(it), size = 12.5.sp, color = p.trap) }
  // YAMLs with a game the release has no APWorld for: no slots, one message; checked again by themselves meanwhile (an
  // upload by anyone, anywhere, makes them slots)
  val missingGames = problems.filter { it.code == Problem.NO_APWORLD }.mapNotNull { it.args["game"] }.toSet()
  val waitingFiles = worlds.filter { w -> w.game.split(" / ").any { it in missingGames } }.map { it.fileName }.toSet()
  if (waitingFiles.isNotEmpty()) {
    val files = waitingFiles.joinToString(", ") { file ->
      "$file (${worlds.filter { it.fileName == file }.flatMap { it.game.split(" / ") }.filter { it in missingGames }.distinct().joinToString(", ")})"
    }
    Txt(
      "${t.sessionAdmin.missingApworlds(files, version.orEmpty())} " +
        if (administrator) t.sessionAdmin.uploadThem else t.sessionAdmin.askAdministrator,
      size = 12.5.sp, color = p.warn,
    )
    LaunchedEffect(Unit) {
      while (true) {
        delay(8_000)
        inspect().join()
      }
    }
  }
  val usable = worlds.filter { it.fileName !in waitingFiles }

  if (usable.isNotEmpty()) {
    // A single slot can't be locked; otherwise at least one slot starts open
    val n = usable.size
    val lockable = n > 1
    val mode = if (lockable) lockMode else LockMode.CHOOSE
    // A switch left to chance whose outcome is already given by the others shows that outcome: e.g. of two slots,
    // one open, the other has to be locked
    val chosen = usable.associate { it.name to (choices[it.name] ?: LockChoice.RANDOM) }
    val settled = run {
      val chance = chosen.values.count { it == LockChoice.RANDOM }
      val sureLocked = chosen.values.count { it == LockChoice.LOCKED }
      val sure = n - sureLocked - chance
      val least = maxOf(1, sure)
      val most = if (sureLocked == 0) minOf(sure + chance, n - 1) else sure + chance
      val outcome = when {
        chance == 0 || least < most -> null
        least - sure == 0 -> LockChoice.LOCKED
        least - sure == chance -> LockChoice.OPEN
        else -> null
      }
      if (outcome == null) chosen else chosen.mapValues { (_, c) -> if (c == LockChoice.RANDOM) outcome else c }
    }
    fun choice(world: YamlWorld) = settled.getValue(world.name)
    // Locked for sure, and how many are left to chance
    val lockedNow = when (mode) {
      LockMode.CHOOSE -> usable.filter { lockable && locked[it.name] == true }
      LockMode.RANDOM -> emptyList()
      LockMode.MIXED -> usable.filter { choice(it) == LockChoice.LOCKED }
    }.map { it.name }.toSet()
    val pool = when (mode) {
      LockMode.CHOOSE -> 0
      LockMode.RANDOM -> n
      LockMode.MIXED -> usable.count { choice(it) == LockChoice.RANDOM }
    }
    val sureOpen = n - lockedNow.size - pool
    // How many start open in all: at least one; with nothing locked for sure, one of the random ones is. Nothing left
    // to chance: every slot says open or locked, as when they are chosen
    val openRange = if (pool == 0) null else maxOf(1, sureOpen)..(if (lockedNow.isEmpty()) minOf(sureOpen + pool, n - 1) else sureOpen + pool)
    val open = openRange?.let { (openCount ?: (n / 2)).coerceIn(it) }
    // A slot can be locked while another one can still start open
    val canLock = n - lockedNow.size > 1
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Txt(t.sessionAdmin.stepPlayers, size = 13.sp, weight = FontWeight.SemiBold)
      InfoTip(t.tips.players)
    }
    Txt(t.sessionAdmin.playersHint, size = 12.sp, color = p.muted)
    if (lockable) {
      Labeled(t.sessionAdmin.locks, t.tips.locks, t.tips.lockModes) {
        Select(
          LockMode.entries.map {
            it to when (it) {
              LockMode.CHOOSE -> t.sessionAdmin.lockChoose
              LockMode.RANDOM -> t.sessionAdmin.lockRandom
              LockMode.MIXED -> t.sessionAdmin.lockMixed
            }
          },
          mode, { lockMode = it }, minHeight = FIELD_HEIGHT)
      }
      // On a line of its own: room for the slider on a phone
      if (open != null && openRange != null) {
        Row(
          Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = FIELD_HEIGHT),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Txt(t.sessionAdmin.startOpen, size = 12.5.sp, color = p.muted, maxLines = 1)
          InfoTip(t.tips.openSlots)
          if (openRange.first < openRange.last) {
            // The slider's own ends, then what it is set to
            Txt("${openRange.first}", size = 12.sp, color = p.muted, maxLines = 1)
            StepSlider(open, openRange, { openCount = it }, Modifier.weight(1f))
            Txt("${openRange.last}", size = 12.sp, color = p.muted, maxLines = 1)
          } else {
            Spacer(Modifier.weight(1f))
          }
          Txt(t.sessionAdmin.openOf(open, n), size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
        }
      }
    }
    usable.forEach { world ->
      val isLocked = world.name in lockedNow
      WorldRow(
        world, users[world.name].orEmpty(), people, hosted = hosted[world.name] == true,
        onHosted = { yes -> hosted[world.name] = yes; if (yes) users[world.name] = emptyList() },
        hostedLabel = t.sessionAdmin.hostedCheck,
        onInvite = { email ->
          when (val r = inviteForSession(email)) {
            is Answer.Ok -> r.value.also { people += it }
            is Answer.Failed -> { error = r; null }
          }
        },
        lock = {
          when (mode) {
            // The last open slot stays open
            LockMode.CHOOSE -> if (lockable) {
              val enabled = isLocked || canLock
              Check(t.sessionAdmin.startsLocked, isLocked, if (enabled) t.tips.startsLocked else t.tips.lastOpen, enabled = enabled) {
                locked[world.name] = it
              }
            }
            LockMode.RANDOM -> {}
            LockMode.MIXED -> LockSwitch(choice(world), lockAllowed = isLocked || canLock) { choices[world.name] = it }
          }
        },
        onUsers = { users[world.name] = it },
      )
    }

    Txt(t.sessionAdmin.stepOptions, size = 13.sp, weight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Check(t.sessionAdmin.pointHintsCheck, options.pointHints, t.tips.pointHints) { options = options.copy(pointHints = it) }
      Check(t.sessionAdmin.automaticHintsCheck, options.autoHints, t.tips.autoHints) { options = options.copy(autoHints = it) }
      if (pool > 0 || lockedNow.isNotEmpty()) {
        Check(t.sessionAdmin.keysEarly, options.earlyKeys, t.tips.earlyKeys) { options = options.copy(earlyKeys = it) }
      }
      Check(t.sessionAdmin.cheatHints, options.cheatHints, t.tips.cheatHints) { options = options.copy(cheatHints = it) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (options.pointHints) {
        NumberField(t.sessionAdmin.hintCost, options.hintCost, t.tips.hintCost) { options = options.copy(hintCost = it) }
        NumberField(t.sessionAdmin.pointsPerCheck, options.locationCheckPoints, t.tips.checkPoints) { options = options.copy(locationCheckPoints = it) }
      }
      Labeled(t.sessionAdmin.release, t.tips.release, t.tips.releaseChoices) { Select(RELEASE_MODES.map { it to it }, options.releaseMode, { options = options.copy(releaseMode = it) }, minHeight = FIELD_HEIGHT) }
      Labeled(t.sessionAdmin.collect, t.tips.collect, t.tips.collectChoices) { Select(RELEASE_MODES.map { it to it }, options.collectMode, { options = options.copy(collectMode = it) }, minHeight = FIELD_HEIGHT) }
      Labeled(t.sessionAdmin.remaining, t.tips.remaining, t.tips.remainingChoices) { Select(REMAINING_MODES.map { it to it }, options.remainingMode, { options = options.copy(remainingMode = it) }, minHeight = FIELD_HEIGHT) }
      when {
        view.apVersions.size > 1 -> Labeled(t.sessionAdmin.archipelago, t.tips.releaseVersion) {
          Select(view.apVersions.map { it to it }, version ?: "", { version = it }, minHeight = FIELD_HEIGHT)
        }
        version != null -> Labeled(t.sessionAdmin.archipelagoVersion(version.orEmpty()), t.tips.releaseVersion) {}
      }
    }
    if (view.apVersions.isEmpty()) Txt(t.apworlds.noRelease, size = 12.5.sp, color = p.trap)
    val openOk = lockedNow.size < n
    // Every slot played by someone, or hosted
    val unplayed = usable.filter { hosted[it.name] != true && users[it.name].isNullOrEmpty() }.map { it.name }
    if (unplayed.isNotEmpty()) Txt(t.sessionAdmin.slotsWithoutPlayer(unplayed.joinToString(", ")), size = 12.5.sp, color = p.warn)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        t.sessionAdmin.generateAndStart,
        enabled = ready && problems.none { it.code != Problem.NO_APWORLD } && view.apVersions.isNotEmpty() && openOk &&
          unplayed.isEmpty(),
      ) {
        busy = true
        error = null
        scope.launch {
          val request = CreateSessionRequest(
            id = id,
            yamls = yamls.filter { it.fileName !in waitingFiles },
            worlds = usable.map {
              WorldPlan(
                it.name, userIds = if (hosted[it.name] == true) emptyList() else users[it.name].orEmpty(),
                locked = it.name in lockedNow, random = mode == LockMode.MIXED && choice(it) == LockChoice.RANDOM,
                hosted = hosted[it.name] == true,
              )
            },
            options = options.copy(openSlots = open),
            apVersion = version,
          )
          val body = ApiJson.encodeToString(CreateSessionRequest.serializer(), request)
          when (val answer = call("POST", "api/sessions", body) { ApiJson.decodeFromString(CreateSessionResult.serializer(), it) }) {
            is Answer.Ok -> onCreated(answer.value)
            is Answer.Failed -> error = answer
          }
          busy = false
        }
      }
    }
    error?.let { GenerationError(it.message, it.detail) }
  }
}

/** A failed generation: why (the suspected games), Generate.py's output ([detail]) below in monospace. */
@Composable
private fun GenerationError(message: String, detail: String?) {
  val p = LocalPalette.current
  if (detail == null) {
    Mono(message, p.trap)
  } else {
    Txt(message, size = 12.5.sp, color = p.trap)
    Mono(detail, p.text)
  }
}

/**
 * One slot of the new session: its players (chips; type to search the accounts, Enter or a click picks one) and its
 * [lock] control (nothing with random locks, or a single slot). [users] are account IDs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldRow(
  world: YamlWorld,
  users: List<String>,
  known: List<UserRef>,
  /** Hosted (its checkbox, asked once more): no players, and no picker to add them. */
  hosted: Boolean,
  onHosted: (Boolean) -> Unit,
  hostedLabel: String,
  /** Invites an address from here: the new account, or `null` (the form shows why). */
  onInvite: suspend (String) -> UserRef?,
  lock: @Composable () -> Unit,
  onUsers: (List<String>) -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var asking by remember { mutableStateOf(false) }
  Divider()
  Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    GameChip(world.game)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
          Txt(world.name, size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
          Txt("${world.game} · ${world.fileName}", size = 11.5.sp, color = p.muted, maxLines = 1)
        }
        lock()
        Check(hostedLabel, hosted, t.sessionAdmin.hostedTip) { yes -> if (yes) asking = true else onHosted(false) }
      }
      if (asking) {
        Confirm(t.sessionAdmin.hostedQuestion, t.sessionAdmin.yesHosted, danger = false,
          onYes = { asking = false; onHosted(true) }, onCancel = { asking = false })
      }
      if (hosted) Txt(t.sessionAdmin.hostedLine, size = 12.sp, color = p.muted)
      else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        users.forEach { id ->
          val user = known.firstOrNull { it.id == id }
          UserChip(user?.label ?: id, invited = user?.name == null, onRemove = { onUsers(users - id) })
        }
        PlayerPicker(
          known.filter { it.id !in users }, if (users.isEmpty()) t.sessionAdmin.addPlayer else t.sessionAdmin.addAnother,
          onPick = { if (it.id !in users) onUsers(users + it.id) }, onInvite = onInvite,
        )
      }
    }
  }
}

/**
 * Adds a player: a dropdown of the accounts ([choices]) with a search field on top; a typed e-mail address that no
 * account has offers its invitation instead ([onInvite]: the new account, or `null`).
 */
@Composable
private fun PlayerPicker(
  choices: List<UserRef>,
  placeholder: String,
  onPick: (UserRef) -> Unit,
  onInvite: suspend (String) -> UserRef?,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  var open by remember { mutableStateOf(false) }
  var closedAt by remember { mutableLongStateOf(0L) }
  var query by remember { mutableStateOf("") }
  var inviting by remember { mutableStateOf(false) }
  var anchorHeight by remember { mutableStateOf(0) }
  Box(Modifier.width(190.dp).onSizeChanged { anchorHeight = it.height }) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = FIELD_HEIGHT).clip(RoundedCornerShape(10.dp)).background(p.panelAlt)
        .border(1.dp, p.border, RoundedCornerShape(10.dp))
        .hand().clickable { if (!justClosed(closedAt)) { open = !open; query = "" } }
        .padding(start = 10.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Txt(placeholder, size = 12.5.sp, color = p.muted, maxLines = 1, modifier = Modifier.weight(1f))
      DrawnIcon(Icon.CHEVRON, p.muted, 14.dp)
    }
    if (open) AppPopup(
      offset = IntOffset(0, anchorHeight + 6),
      onDismissRequest = { open = false; closedAt = Platform.now() },
      properties = PopupProperties(focusable = true),
    ) {
      Column(
        Modifier.widthIn(min = 240.dp, max = 320.dp).card(p, RoundedCornerShape(12.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        DropdownSearch(query) { query = it }
        val matches = choices.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }.take(200)
        val address = EmailAddresses.normalize(query)
        Column(Modifier.heightIn(max = 260.dp).scrolling()) {
          matches.forEach { user ->
            Txt(
              user.label, size = 13.sp, maxLines = 1, color = if (user.name == null) p.muted else p.text,
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hand()
                .clickable { onPick(user); open = false; query = "" }.padding(horizontal = 8.dp, vertical = 7.dp),
            )
          }
          when {
            matches.isNotEmpty() -> {}
            EmailAddresses.valid(address) -> Txt(
              if (inviting) t.sessionAdmin.inviting else t.sessionAdmin.inviteAddress(address), size = 13.sp, color = p.accent,
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hand().clickable {
                if (!inviting) scope.launch {
                  inviting = true
                  onInvite(address)?.let(onPick)
                  inviting = false
                  open = false
                  query = ""
                }
              }.padding(horizontal = 8.dp, vertical = 7.dp),
            )
            else -> Txt(t.sessionAdmin.noAccountMatches, size = 12.sp, color = p.muted, modifier = Modifier.padding(8.dp))
          }
        }
      }
    }
  }
}

/** A chosen YAML: its file and its game(s), × to remove it. */
@Composable
private fun YamlChip(fileName: String, games: List<String>, onRemove: () -> Unit) {
  val p = LocalPalette.current
  Row(
    Modifier.clip(CircleShape).background(p.accent.copy(alpha = 0.12f)).padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Txt(fileName, size = 12.5.sp, weight = FontWeight.SemiBold, color = p.accent, maxLines = 1)
    if (games.isNotEmpty()) Txt(games.joinToString(", "), size = 11.5.sp, color = p.accent.copy(alpha = 0.8f), maxLines = 1)
    Box(Modifier.size(18.dp).clip(CircleShape).hand().clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
      DrawnIcon(Icon.CLOSE, p.accent, 10.dp)
    }
  }
}

@Composable
private fun UserChip(name: String, invited: Boolean, onRemove: () -> Unit) {
  val p = LocalPalette.current
  Row(
    Modifier.heightIn(min = FIELD_HEIGHT).clip(CircleShape).background(p.hue(name).copy(alpha = 0.15f))
      .padding(start = 12.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Txt(if (invited) LocalTexts.current.sessionAdmin.invitedName(name) else name, size = 12.5.sp, weight = FontWeight.SemiBold, color = p.hue(name), maxLines = 1)
    Box(Modifier.size(18.dp).clip(CircleShape).hand().clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
      DrawnIcon(Icon.CLOSE, p.hue(name), 10.dp)
    }
  }
}

/** A session just created: its game server starts; the slots that start locked. */
@Composable
private fun Created(locked: List<String>, onDone: () -> Unit) {
  val p = LocalPalette.current
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.ok.copy(alpha = 0.1f)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    val texts = LocalTexts.current.sessionAdmin
    Txt(texts.sessionCreated, size = 13.sp, weight = FontWeight.SemiBold)
    if (locked.isNotEmpty()) Txt(texts.startLocked(locked.joinToString(", ")), size = 12.5.sp)
    Row { Button(texts.done) { onDone() } }
  }
}

/**
 * A slot's lock with mixed locks: a switch with three stops, open, left to chance, locked. Tap a stop or drag the
 * knob; locked only while [lockAllowed].
 */
@Composable
private fun LockSwitch(choice: LockChoice, lockAllowed: Boolean, onChange: (LockChoice) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val current by rememberUpdatedState(choice)
  val change by rememberUpdatedState(onChange)
  val color = when (choice) {
    LockChoice.OPEN -> p.ok
    LockChoice.RANDOM -> p.accent
    LockChoice.LOCKED -> p.locked
  }
  val position by animateFloatAsState(choice.ordinal / 2f, tween(160))
  Tip(t.tips.lockSwitch) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(
        when (choice) {
          LockChoice.OPEN -> t.sessionAdmin.lockOpen
          LockChoice.RANDOM -> t.sessionAdmin.lockChance
          LockChoice.LOCKED -> t.sessionAdmin.lockLocked
        },
        size = 12.5.sp, weight = FontWeight.SemiBold, color = color, maxLines = 1,
      )
      BoxWithConstraints(
        Modifier.width(78.dp).height(28.dp).clip(CircleShape).background(color.copy(alpha = 0.16f))
          .border(1.dp, color.copy(alpha = 0.5f), CircleShape).hand()
          .pointerInput(lockAllowed) {
            awaitEachGesture {
              val down = awaitFirstDown()
              down.consume()
              var at = down.position.x
              while (true) {
                val moved = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!moved.pressed) break
                if (moved.positionChanged()) {
                  moved.consume()
                  at = moved.position.x
                }
              }
              val picked = LockChoice.entries[(at / size.width * 3).toInt().coerceIn(0, 2)]
              if (picked != LockChoice.LOCKED || lockAllowed || current == LockChoice.LOCKED) change(picked)
            }
          },
        contentAlignment = Alignment.CenterStart,
      ) {
        val travel = maxWidth - 28.dp
        // The stops; locked is faint while it can't be chosen
        LockChoice.entries.forEach { stop ->
          Box(
            Modifier.offset(x = 4.dp + travel * (stop.ordinal / 2f) + 7.dp).size(6.dp).clip(CircleShape)
              .background(p.muted.copy(alpha = if (stop == LockChoice.LOCKED && !lockAllowed) 0.2f else 0.6f))
          )
        }
        Box(
          Modifier.offset(x = 4.dp + travel * position).size(20.dp).clip(CircleShape).background(color),
          contentAlignment = Alignment.Center,
        ) {
          when (choice) {
            LockChoice.OPEN -> DrawnIcon(Icon.CHECK, p.onBrand, 12.dp)
            LockChoice.RANDOM -> Txt("?", size = 12.sp, color = p.onBrand, weight = FontWeight.Bold, maxLines = 1)
            LockChoice.LOCKED -> DrawnIcon(Icon.LOCK, p.onBrand, 12.dp)
          }
        }
      }
    }
  }
}

@Composable
private fun Check(label: String, checked: Boolean, tip: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  val p = LocalPalette.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      Modifier.clip(RoundedCornerShape(8.dp))
        .then(if (enabled) Modifier.hand().clickable { onChange(!checked) } else Modifier.alpha(0.45f))
        .padding(vertical = 2.dp, horizontal = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
          .background(if (checked) p.accent else Color.Transparent)
          .border(1.5.dp, if (checked) p.accent else p.muted, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
      ) {
        if (checked) DrawnIcon(Icon.CHECK, p.onBrand, 12.dp)
      }
      Txt(label, size = 12.5.sp, maxLines = 1)
    }
    if (tip != null) InfoTip(tip)
  }
}

@Composable
private fun Labeled(
  label: String,
  tip: String? = null,
  choices: List<Pair<String, String>> = emptyList(),
  content: @Composable () -> Unit,
) {
  // One height for every option, so labels, dropdowns and number fields line up in their rows
  Row(Modifier.heightIn(min = FIELD_HEIGHT), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Txt(label, size = 12.5.sp, color = LocalPalette.current.muted)
    if (tip != null) InfoTip(tip, options = choices)
    content()
  }
}

@Composable
private fun NumberField(label: String, value: Int, tip: String? = null, onChange: (Int) -> Unit) {
  var text by remember(value) { mutableStateOf(value.toString()) }
  Labeled(label, tip) {
    FilterField(text, { typed ->
      text = typed.filter { it.isDigit() }.take(3)
      text.toIntOrNull()?.let(onChange)
    }, "0", Modifier.width(56.dp), minHeight = FIELD_HEIGHT)
  }
}

/** The height of the form's text fields, dropdowns and player chips, so they line up. */
internal val FIELD_HEIGHT = 34.dp

@Composable
internal fun Mono(text: String, color: Color, modifier: Modifier = Modifier) {
  SelectionContainer(modifier) {
    BasicText(text, style = TextStyle(color = color, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace))
  }
}

@Composable
internal fun Button(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
  val p = LocalPalette.current
  val color = if (danger) p.trap else p.accent
  Txt(
    text, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1,
    color = if (enabled) p.onBrand else p.muted,
    modifier = Modifier.clip(RoundedCornerShape(10.dp))
      .background(if (enabled) color else p.panelAlt)
      .then(if (enabled) Modifier.hand().clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}
