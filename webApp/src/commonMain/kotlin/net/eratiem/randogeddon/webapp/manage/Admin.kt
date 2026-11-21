package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.hand

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
      IconButton(onBack, Icon.BACK)
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
        NEW_PAGE -> NewSessionSection(overview, administrator, onCreated = { onPage(it.id); load() })
        else -> key(page) {
          val entry = all.firstOrNull { it.id == page }
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
  SpoilerRunSection(session.id, "support")
  // Not before the spoiler log has its playthrough: the hints and cheats need it
  if (session.spoilerReady) CheatSection(session.id, server = null, cache, support = true, onSpoiler = { onSpoiler(session.id) })
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
  val scope = rememberCoroutineScope()
  val repository = remember(id) { cache.repository(id) }
  DisposableEffect(repository) {
    repository.start()
    onDispose { repository.stop() }
  }
  val state by repository.state.collectAsState()
  val problem by repository.problem.collectAsState()
  var message by remember { mutableStateOf<String?>(null) }
  fun run(block: suspend () -> String?) {
    scope.launch {
      message = block()
      onChanged()
    }
  }
  // Until the stream's first state: the one of the sessions' list
  val server = state ?: overview?.sessions?.firstOrNull { it.id == id }?.server
  SessionSection(
    id, overview, cache, administrator, onChanged = { message = null; onChanged() }, onSpoiler = onSpoiler, onDashboard = onDashboard,
    server = { ServerControls(server, problem, now, message, onAction = { action -> run { repository.control(action) } }) },
  )
  // Every section's log in one place, as tabs (each only for whoever may read it)
  val logs = remember(id) { PanelLogs() }
  CompositionLocalProvider(LocalPanelLogs provides logs) {
    PeopleSection(id)
    SpoilerRunSection(id, "manage")
    LogicSection(id, administrator)
    MinecraftSection(id, administrator)
    PatchFilesSection(id)
    // Not before the spoiler log has its playthrough: the hints and cheats need it
    if (overview?.sessions?.firstOrNull { it.id == id }?.spoilerReady != false) CheatSection(id, server, cache)
    ConsoleSection(onSend = { line -> run { repository.command(line) } })
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
      val pending = confirm
      if (pending == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val running = state?.status == GameServerStatus.RUNNING || state?.status == GameServerStatus.STARTING
          Tip(t.panel.startTip) {
            ActionButton(t.panel.start, enabled = !running) { onAction("start") }
          }
          Tip(t.panel.stopTip) {
            ActionButton(t.panel.stop, enabled = running) { confirm = "stop" }
          }
          Tip(t.panel.restartTip) {
            ActionButton(t.panel.restart, enabled = running) { confirm = "restart" }
          }
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt(t.panel.confirm(restart = pending == "restart"), size = 13.sp, modifier = Modifier.weight(1f))
          ActionButton(t.panel.yes(restart = pending == "restart"), danger = true) { onAction(pending); confirm = null }
          ActionButton(t.common.cancel) { confirm = null }
        }
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

@Composable
private fun ConsoleSection(onSend: (String) -> Unit) {
  val p = LocalPalette.current
  var input by remember { mutableStateOf("") }
  val history = remember { mutableStateListOf<String>() }
  var back by remember { mutableStateOf(0) }
  fun send() {
    val line = input.trim()
    if (line.isEmpty()) return
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
    Row(
      Modifier.fillMaxWidth().card(p).padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
      ActionButton(LocalTexts.current.panel.send) { send() }
    }
  }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
  val p = LocalPalette.current
  val color = if (danger) p.trap else p.accent
  Txt(
    text, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1,
    color = if (enabled) LocalPalette.current.onBrand else p.muted,
    modifier = Modifier.clip(RoundedCornerShape(10.dp))
      .background(if (enabled) color else p.panelAlt)
      .then(if (enabled) Modifier.hand().clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}
