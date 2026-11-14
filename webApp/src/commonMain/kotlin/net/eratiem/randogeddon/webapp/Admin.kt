package net.eratiem.randogeddon.webapp

import net.eratiem.randogeddon.shared.i18n.texts
import net.eratiem.randogeddon.shared.i18n.duration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.AdminOverview
import net.eratiem.randogeddon.shared.AdminSessionView
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CheatView
import net.eratiem.randogeddon.shared.CommandRequest
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.shared.LogLines

private const val MAX_LOG_LINES = 2000

/** The admin panel's view of session [id]'s game server: its state and log over one stream, and the actions. */
class AdminRepository(private val scope: CoroutineScope, private val id: String) {
  private val _state = MutableStateFlow<GameServerState?>(null)
  val state: StateFlow<GameServerState?> = _state.asStateFlow()

  /** Why the gateway can't be reached; `null` while it can. */
  private val _problem = MutableStateFlow<String?>(null)
  val problem: StateFlow<String?> = _problem.asStateFlow()

  val log = mutableStateListOf<LogLine>()
  private var close: (() -> Unit)? = null

  fun start() {
    if (close != null) return
    close = Platform.events("api/sessions/$id/manage/stream", listOf("state", "log", "unreachable"), ::onEvent, onConnection = {})
  }

  fun stop() {
    close?.invoke()
    close = null
  }

  /** [action]: start, stop or restart; the error message, or `null`. */
  suspend fun control(action: String): String? = answer(Platform.request("POST", "api/sessions/$id/manage/server/$action"))

  suspend fun command(line: String): String? = answer(
    Platform.request("POST", "api/sessions/$id/manage/command", ApiJson.encodeToString(CommandRequest.serializer(), CommandRequest(line)))
  )

  private fun answer(result: HttpResult?): String? {
    val common = texts(Languages.current).common
    return when {
      result == null -> common.serverUnreachable
      result.status in 200..299 -> null
      else -> texts(Languages.current).wordError(result.body) ?: common.failed(result.status)
    }
  }

  private fun onEvent(type: String, data: String) {
    when (type) {
      "state" -> runCatching { ApiJson.decodeFromString(GameServerState.serializer(), data) }.onSuccess {
        _state.value = it
        _problem.value = null
      }
      "log" -> runCatching { ApiJson.decodeFromString(LogLines.serializer(), data) }.onSuccess { lines ->
        // After a gateway restart the numbers start at 1 again
        if (lines.lines.isNotEmpty() && log.isNotEmpty() && lines.lines.first().n <= log.last().n) log.clear()
        log.addAll(lines.lines)
        if (log.size > MAX_LOG_LINES) log.removeRange(0, log.size - MAX_LOG_LINES)
      }
      "unreachable" -> _problem.value = data
    }
  }
}

/**
 * What the admin panel loaded per session, kept while it is open: switching to a session shows its last state at once
 * (its sections load it again behind). The running sessions are loaded ahead, so the first switch doesn't wait either;
 * only a game server's log starts with its stream.
 */
class AdminCache(private val scope: CoroutineScope) {
  val views = mutableStateMapOf<String, AdminSessionView>()
  val cheats = mutableStateMapOf<String, CheatView>()
  private val repositories = mutableMapOf<String, AdminRepository>()

  /** Session [id]'s game server; its stream runs while it is shown, its state and log stay after. */
  fun repository(id: String): AdminRepository = repositories.getOrPut(id) { AdminRepository(scope, id) }

  /** Loads what isn't there yet of the sessions [ids], and forgets the sessions that are gone. */
  fun prefetch(ids: List<String>) {
    (views.keys - ids.toSet()).forEach { views.remove(it) }
    (cheats.keys - ids.toSet()).forEach { cheats.remove(it) }
    (repositories.keys - ids.toSet()).forEach { repositories.remove(it)?.stop() }
    ids.forEach { id ->
      if (id !in views) scope.launch {
        val answer = call("GET", "api/sessions/$id/manage", null) { ApiJson.decodeFromString(AdminSessionView.serializer(), it) }
        if (answer is Answer.Ok && id !in views) views[id] = answer.value
      }
      if (id !in cheats) scope.launch {
        val answer = call("GET", "api/sessions/$id/manage/cheat", null) { ApiJson.decodeFromString(CheatView.serializer(), it) }
        if (answer is Answer.Ok && id !in cheats) cheats[id] = answer.value
      }
    }
  }
}

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
    when (val answer = call("GET", "api/manage", null) { ApiJson.decodeFromString(AdminOverview.serializer(), it) }) {
      is Answer.Ok -> {
        overview = answer.value
        loadProblem = null
        cache.prefetch(answer.value.sessions.filter { it.status.running }.map { it.id })
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
  // A deleted (or finished) session's page goes to the newest running one (or to a new session, with none running)
  val page = when {
    picked == NEW_PAGE -> NEW_PAGE
    picked != null && all.any { it.id == picked && it.status.running } -> picked!!
    overview == null -> null
    else -> all.firstOrNull { it.status.running }?.id ?: NEW_PAGE
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
        null -> {}
        NEW_PAGE -> NewSessionSection(overview, onCreated = { onPage(it.id); load() })
        else -> key(page) {
          SelectedSession(page, overview, cache, now, onChanged = { load() }, onSpoiler = onSpoiler, onDashboard = onDashboard)
        }
      }
    }
    // What concerns every session: Administrators'
    if (administrator) Txt(LocalTexts.current.panel.general, size = ADMIN_HEADING, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    PastSessionsAdmin(all.filter { !it.status.running }, onChanged = { load() }, onSpoiler = onSpoiler)
    if (administrator) {
      UsersAdmin(onChanged = { load() })
      ApworldsSection()
      AppSettingsSection()
    }
  }
}

/** One session's sections: its state and actions with its game server, players, people, cheats, console and log. */
@Composable
private fun SelectedSession(
  id: String,
  overview: AdminOverview?,
  cache: AdminCache,
  now: Long,
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
    id, overview, cache, onChanged = { message = null; onChanged() }, onSpoiler = onSpoiler, onDashboard = onDashboard,
    server = { ServerControls(server, problem, now, message, onAction = { action -> run { repository.control(action) } }) },
  )
  PeopleSection(id)
  CheatSection(id, server, cache)
  ConsoleSection(onSend = { line -> run { repository.command(line) } })
  LogSection(repository.log)
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
          GameServerStatus.CRASHED -> p.trap
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

@Composable
private fun LogSection(log: List<LogLine>) {
  val p = LocalPalette.current
  val scroll = rememberScrollState()
  // Shows new lines while at the top; scrolled down, it stays on the lines it shows
  var follow by remember { mutableStateOf(true) }
  var lastMax by remember { mutableStateOf(0) }
  LaunchedEffect(scroll.value) { follow = scroll.value < 40 }
  LaunchedEffect(scroll.maxValue) {
    val grown = scroll.maxValue - lastMax
    lastMax = scroll.maxValue
    if (!follow && grown > 0) scroll.scrollBy(grown.toFloat())
  }
  Section(LocalTexts.current.panel.log, subtitle = LocalTexts.current.panel.logSubtitle) {
    Box(Modifier.fillMaxWidth().card(p).height(420.dp)) {
      SelectionContainer {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(12.dp)) {
          if (log.isEmpty()) Txt(LocalTexts.current.panel.nothingYet, size = 13.sp, color = p.muted)
          log.asReversed().forEach { line ->
            BasicText(
              line.text,
              style = TextStyle(
                color = if (line.text.startsWith("[gateway]")) p.accent else p.text,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
              ),
            )
          }
        }
      }
    }
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
    color = if (enabled) Color.White else p.muted,
    modifier = Modifier.clip(RoundedCornerShape(10.dp))
      .background(if (enabled) color else p.panelAlt)
      .then(if (enabled) Modifier.hand().clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}
