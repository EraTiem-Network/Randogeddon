package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.AdminOverview
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.duration
import net.eratiem.randogeddon.webapp.api.AdminCache
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.dashboard.StatusPill
import net.eratiem.randogeddon.webapp.ui.Action
import net.eratiem.randogeddon.webapp.ui.Button
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Selectable
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.rememberAction

/**
 * The panel, for everyone: the sessions the user manages (an [administrator]: every session). In a frame on top, one
 * page at a time, picked in the switch ([picked], from the URL; [onPage] goes to another): a running session with its
 * state and control (its game server too), players and people, cheats, console and log; or a new session. Below it the
 * finished ones (each with its actions, a click shows its overview); for Administrators what concerns everyone: the
 * users, the APWorlds, the settings.
 */
@Composable
fun AdminView(
  now: Long,
  administrator: Boolean,
  picked: String?,
  onPage: (String) -> Unit,
  onBack: () -> Unit,
  onSpoiler: (String) -> Unit,
  onDashboard: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val cache = remember { AdminCache(scope) }
  var overview by remember { mutableStateOf<AdminOverview?>(null) }
  var loadProblem by remember { mutableStateOf<String?>(null) }
  fun load() = scope.launch {
    when (val answer = call("GET", "api/manage", null, AdminOverview.serializer())) {
      is Answer.Ok -> {
        overview = answer.value
        loadProblem = null
        // What a Session-Manager sees of a session; support gets its own (the session's page loads it)
        val running = answer.value.sessions.filter { it.status.running }
        cache.prefetch(running.filter { !supportOnly(it, administrator) }.map { it.id }, all = running.map { it.id })
      }
      is Answer.Failed -> loadProblem = answer.message
    }
  }
  // The game servers' states move on by themselves
  LaunchedEffect(Unit) {
    while (true) {
      load()
      delay(15_000)
    }
  }
  val all = overview?.sessions.orEmpty()
  // A deleted (or finished) session's page goes to the newest running one; with none running, to none (the new
  // session's form only once it is asked for)
  val page = when {
    picked == NEW_PAGE -> NEW_PAGE
    picked != null && all.any { it.id == picked && it.status.running } -> picked
    overview == null -> null
    else -> all.firstOrNull { it.status.running }?.id
  }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      IconButton(onBack, Icon.BACK, label = LocalTexts.current.dialogs.back)
      Txt(if (administrator) LocalTexts.current.panel.titleAdministrator else LocalTexts.current.panel.title, size = 20.sp, weight = FontWeight.Bold)
    }
    loadProblem?.let { Txt(it, size = 12.5.sp, color = LocalPalette.current.trap) }
    // The session's space: framed and tinted, apart from what concerns every session below it
    val p = LocalPalette.current
    val frame = RoundedCornerShape(20.dp)
    Column(
      Modifier.fillMaxWidth().clip(frame).background(p.accent.copy(alpha = 0.05f))
        .border(1.dp, p.accent.copy(alpha = 0.3f), frame).padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      AdminSwitch(overview, page, onPage = onPage)
      when (page) {
        null -> if (overview != null) Txt(LocalTexts.current.sessionAdmin.nothingRunning, size = 13.sp, color = p.muted)
        // The new session's page once the list has it: before, its ID reads as a session that's gone, and the page
        // went elsewhere
        NEW_PAGE -> NewSessionSection(overview, administrator, onCreated = { created -> scope.launch { load().join(); onPage(created.id) } })
        else -> key(page) {
          val entry = all.firstOrNull { it.id == page }
          // Support's view and the manager's sections only for looks: the tracker keeps `…/manage` to the managers
          if (entry != null && supportOnly(entry, administrator)) {
            SupportedSession(entry, cache, onSpoiler = onSpoiler, onDashboard = onDashboard)
          } else {
            SelectedSession(page, overview, cache, now, administrator, onChanged = { load() }, onSpoiler = onSpoiler, onDashboard = onDashboard)
          }
        }
      }
    }
    // What concerns every session: Administrators'
    if (administrator) Txt(LocalTexts.current.panel.general, size = ADMIN_HEADING, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    // Finished ones to reopen or delete: not the ones the user only supports
    PastSessionsAdmin(all.filter { !it.status.running && !supportOnly(it, administrator) }, onChanged = { load() }, onSpoiler = onSpoiler)
    // Shown to Administrators only; the tracker refuses `api/admin/…` to everyone else too
    if (administrator) {
      UsersAdmin(onChanged = { load() }, onSession = onDashboard)
      ApworldsSection()
      VersionsSection()
    }
  }
}

/** The user is [session]'s support and nothing more: they help its players, they don't manage it. */
internal fun supportOnly(session: SessionEntry, administrator: Boolean): Boolean =
  !administrator && session.myRole == SessionRole.SUPPORT

/**
 * A session for its support: how it stands, its dashboard, and what support may do (give an item, check a location,
 * a free hint; the spoiler log; its watchers and the share link).
 */
@Composable
private fun SupportedSession(session: SessionEntry, cache: AdminCache, onSpoiler: (String) -> Unit, onDashboard: (String) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // Not in a section: a selection of its own
  Selectable {
    Row(
      Modifier.fillMaxWidth().card(p).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt(session.title, size = 15.sp, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
          StatusPill(session.status)
          Pill(t.people.role(SessionRole.SUPPORT), p.accent)
        }
        Txt("${session.id} · ${t.decimal(session.percent)}% · ${t.sessions.slots(session.slots)}", size = 12.sp, color = p.muted, maxLines = 1)
      }
      Tip(t.tips.dashboard) { SmallButton(t.sessionAdmin.dashboard, { onDashboard(session.id) }) }
    }
  }
  SpoilerRunSection(session.id, "support")
  // A hint's next copy only once the spoiler log has its playthrough. The session runs (support's view is only for
  // running ones): its spoiler log asks first, as the Session-Manager's button does
  CheatSection(
    session.id, server = null, cache, support = true, onSpoiler = { onSpoiler(session.id) }, playthrough = session.spoilerReady,
    spoilerAsks = session.finishedAt == null,
  )
  // Who watches, and the share link: support hands them out as the players do
  WatchersSection(session.id)
}

/** One session's sections: its state and actions with its game server, players, people, cheats, console and log. */
@Composable
private fun SelectedSession(
  id: String,
  overview: AdminOverview?,
  cache: AdminCache,
  now: Long,
  administrator: Boolean,
  onChanged: () -> Unit,
  onSpoiler: (String) -> Unit,
  onDashboard: (String) -> Unit,
) {
  val repository = remember(id) { cache.repository(id) }
  DisposableEffect(repository) {
    repository.start()
    onDispose { repository.stop() }
  }
  val state by repository.state.collectAsState()
  val problem by repository.problem.collectAsState()
  // Start, stop, restart: one at a time (a second click while one is sent is dropped); the console's lines apart
  val control = rememberAction()
  val console = rememberAction()
  fun send(action: Action, block: suspend () -> String?) = action.run { block().also { onChanged() } }
  // Until the stream's first state: the one of the sessions' list
  val server = state ?: overview?.sessions?.firstOrNull { it.id == id }?.server
  SessionSection(
    id, overview, cache, administrator, onChanged = { control.problem = null; onChanged() }, onSpoiler = onSpoiler, onDashboard = onDashboard,
    server = {
      // Failed (ended again and again): its own last line says why, e.g. a save it can't read
      val lastLine = if (server?.status != GameServerStatus.FAILED) null
      else repository.log.lastOrNull { !it.text.startsWith("[gateway]") }?.text
      ServerControls(
        server, problem, now, control.problem, busy = control.busy, lastLine = lastLine,
        onAction = { action -> send(control) { repository.control(action) } },
      )
    },
  )
  // Every section's log in one place, as tabs (each only for whoever may read it)
  val logs = remember(id) { PanelLogs() }
  CompositionLocalProvider(LocalPanelLogs provides logs) {
    PeopleSection(id)
    SlotsSection(id)
    SpoilerRunSection(id, "manage")
    LogicSection(id, administrator)
    MinecraftSection(id, administrator)
    PatchFilesSection(id)
    // A hint's next copy only once the spoiler log has its playthrough
    CheatSection(id, server, cache, playthrough = overview?.sessions?.firstOrNull { it.id == id }?.spoilerReady != false)
    ConsoleSection(console.busy, console.problem, onSend = { line -> send(console) { repository.command(line) } })
    LogSection(repository.log, logs)
  }
}

/** The session's game server, in its State and Control: how it runs, start, stop, restart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerControls(
  state: GameServerState?,
  problem: String?,
  now: Long,
  message: String?,
  /** An action is being sent: the buttons wait for it. */
  busy: Boolean,
  /** While it has failed: its own last line (why), from its output. */
  lastLine: String? = null,
  onAction: (String) -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // Stop and restart end every running game: they ask once more
  var confirm by remember { mutableStateOf<String?>(null) }
  val status = state?.status
  Divider()
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    FlowRow(
      verticalArrangement = Arrangement.spacedBy(4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      Txt(t.panel.gameServer, size = 14.sp, weight = FontWeight.SemiBold)
      // Its state and uptime next to it
      Pill(
        when {
          problem != null -> t.panel.gatewayUnreachable
          status == null -> "…"
          else -> t.panel.status(status)
        },
        when (status) {
          GameServerStatus.RUNNING -> p.ok
          GameServerStatus.STARTING -> p.useful
          GameServerStatus.CRASHED, GameServerStatus.FAILED -> p.trap
          GameServerStatus.IDLE -> p.muted
          GameServerStatus.STOPPED, GameServerStatus.NO_SEED, null -> if (problem != null) p.trap else p.muted
        },
      )
      state?.since?.let { Pill(t.panel.up(t.duration(now - it)), p.muted) }
    }
    run {
      val details = listOfNotNull(
        state?.seed?.let { t.panel.seedFile(it) } ?: t.panel.runByGateway,
        state?.lastExitCode?.let { t.panel.lastExitCode(it) },
        state?.restarts?.takeIf { it > 0 }?.let { t.panel.restarts(it) },
      )
      Txt(details.joinToString(" · "), size = 12.sp, color = p.muted, maxLines = 2)
      if (problem != null) Txt(problem, size = 12.5.sp, color = p.trap)
      if (lastLine != null) Txt(t.panel.failedBecause(lastLine), size = 12.5.sp, color = p.trap)
      val pending = confirm
      if (pending == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val running = state?.status == GameServerStatus.RUNNING || state?.status == GameServerStatus.STARTING
          Tip(t.panel.startTip) {
            Button(t.panel.start, enabled = !running && !busy) { onAction("start") }
          }
          Tip(t.panel.stopTip) {
            Button(t.panel.stop, enabled = running && !busy) { confirm = "stop" }
          }
          Tip(t.panel.restartTip) {
            Button(t.panel.restart, enabled = running && !busy) { confirm = "restart" }
          }
        }
      } else {
        Confirm(
          t.panel.confirm(restart = pending == "restart"), t.panel.yes(restart = pending == "restart"), danger = true,
          onYes = { onAction(pending); confirm = null }, onCancel = { confirm = null },
        )
      }
      if (message != null) Txt(message, size = 12.5.sp, color = p.trap)
    }
  }
}

/** The game server's output, newest on top, a page at a time and searchable; the gateway's own notes stand out. */
@Composable
private fun LogSection(log: List<LogLine>, logs: PanelLogs) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // The game server's is always there, and first
  SectionLog("game-server", t.panel.gameServer, LogOrder.GAME_SERVER, log, color = { line -> if (line.text.startsWith("[gateway]")) p.accent else null })
  Section(t.panel.log, subtitle = t.panel.logSubtitle) {
    LogTabs(logs)
  }
}

/** The game server's console: a command at a time ([busy] while one is sent), and why the last one wasn't. */
@Composable
private fun ConsoleSection(busy: Boolean, problem: String?, onSend: (String) -> Unit) {
  val p = LocalPalette.current
  var input by remember { mutableStateOf("") }
  val history = remember { mutableStateListOf<String>() }
  var back by remember { mutableStateOf(0) }
  fun send() {
    val line = input.trim()
    if (line.isEmpty() || busy) return
    onSend(line)
    history.remove(line)
    history.add(line)
    input = ""
    back = 0
  }
  Section(
    LocalTexts.current.panel.console, subtitle = LocalTexts.current.panel.consoleSubtitle,
    actions = {
      InfoTip(LocalTexts.current.panel.consoleTip)
    },
  ) {
    Column(Modifier.fillMaxWidth().card(p).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterField(
          input, { input = it }, "/players",
          Modifier.weight(1f).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
              Key.Enter, Key.NumPadEnter -> { send(); true }
              // Up and down walk through the commands sent before
              Key.DirectionUp -> {
                if (back < history.size) { back++; input = history[history.size - back] }
                true
              }
              Key.DirectionDown -> {
                if (back > 0) { back--; input = if (back == 0) "" else history[history.size - back] }
                true
              }
              else -> false
            }
          },
        )
        Button(LocalTexts.current.panel.send, enabled = !busy) { send() }
      }
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    }
  }
}
