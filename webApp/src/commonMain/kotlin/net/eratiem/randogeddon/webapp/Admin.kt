package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CommandRequest
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.shared.LogLines

private const val MAX_LOG_LINES = 2000

/** The admin panel's view of the game server: its state and log over one stream, and the actions. */
class AdminRepository(private val scope: CoroutineScope) {
  private val _state = MutableStateFlow<GameServerState?>(null)
  val state: StateFlow<GameServerState?> = _state.asStateFlow()

  /** Why the gateway can't be reached; `null` while it can. */
  private val _problem = MutableStateFlow<String?>(null)
  val problem: StateFlow<String?> = _problem.asStateFlow()

  val log = mutableStateListOf<LogLine>()
  private var close: (() -> Unit)? = null

  fun start() {
    if (close != null) return
    close = Platform.events("api/admin/stream", listOf("state", "log", "unreachable"), ::onEvent, onConnection = {})
  }

  fun stop() {
    close?.invoke()
    close = null
  }

  /** [action]: start, stop or restart; the error message, or `null`. */
  suspend fun control(action: String): String? = answer(Platform.request("POST", "api/admin/server/$action"))

  suspend fun command(line: String): String? = answer(
    Platform.request("POST", "api/admin/command", ApiJson.encodeToString(CommandRequest.serializer(), CommandRequest(line)))
  )

  private fun answer(result: HttpResult?): String? = when {
    result == null -> "The server can't be reached"
    result.status in 200..299 -> null
    else -> runCatching { ApiJson.decodeFromString(ApiError.serializer(), result.body).message }
      .getOrDefault("Failed (${result.status})")
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

/** The admin panel: the game server (state, start/stop/restart), its log and its console. */
@Composable
fun AdminView(now: Long, onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  val repository = remember { AdminRepository(scope) }
  DisposableEffect(repository) {
    repository.start()
    onDispose { repository.stop() }
  }
  val state by repository.state.collectAsState()
  val problem by repository.problem.collectAsState()
  var message by remember { mutableStateOf<String?>(null) }
  fun run(block: suspend () -> String?) {
    scope.launch { message = block() }
  }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      IconButton(onBack, Icon.BACK)
      Txt("Admin", size = 20.sp, weight = FontWeight.Bold)
    }
    ServerSection(state, problem, now, message, onAction = { action -> run { repository.control(action) } })
    LogSection(repository.log)
    ConsoleSection(onSend = { line -> run { repository.command(line) } })
  }
}

@Composable
private fun ServerSection(
  state: GameServerState?,
  problem: String?,
  now: Long,
  message: String?,
  onAction: (String) -> Unit,
) {
  val p = LocalPalette.current
  // Stop and restart end every running game: they ask once more
  var confirm by remember { mutableStateOf<String?>(null) }
  Section("Game server", subtitle = state?.seed?.let { "Seed file $it" } ?: "MultiServer, run by the gateway") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val status = state?.status
        Pill(
          when {
            problem != null -> "gateway unreachable"
            status == null -> "…"
            else -> status.name.lowercase().replace('_', ' ')
          },
          when (status) {
            GameServerStatus.RUNNING -> p.ok
            GameServerStatus.STARTING -> p.useful
            GameServerStatus.CRASHED -> p.trap
            GameServerStatus.STOPPED, GameServerStatus.NO_SEED, null -> if (problem != null) p.trap else p.muted
          },
        )
        val details = listOfNotNull(
          state?.since?.let { "up ${duration(now - it)}" },
          state?.lastExitCode?.let { "last exit code $it" },
          state?.restarts?.takeIf { it > 0 }?.let { "$it ${if (it == 1) "restart" else "restarts"} after crashes" },
        )
        Txt(details.joinToString(" · "), size = 12.5.sp, color = p.muted, maxLines = 1, modifier = Modifier.weight(1f))
      }
      if (problem != null) Txt(problem, size = 12.5.sp, color = p.trap)
      val pending = confirm
      if (pending == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val running = state?.status == GameServerStatus.RUNNING || state?.status == GameServerStatus.STARTING
          ActionButton("Start", enabled = !running) { onAction("start") }
          ActionButton("Stop", enabled = running) { confirm = "stop" }
          ActionButton("Restart", enabled = running) { confirm = "restart" }
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt(
            "${pending.replaceFirstChar { it.uppercase() }} the game server? Every player is disconnected.",
            size = 13.sp, modifier = Modifier.weight(1f),
          )
          ActionButton("Yes, ${pending}", danger = true) { onAction(pending); confirm = null }
          ActionButton("Cancel") { confirm = null }
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
  // Follows new lines while at the bottom; scrolled up, it stays where it is
  var follow by remember { mutableStateOf(true) }
  LaunchedEffect(scroll.value, scroll.maxValue) { follow = scroll.maxValue - scroll.value < 40 }
  LaunchedEffect(log.size) { if (follow) scroll.scrollTo(scroll.maxValue) }
  Section("Log", subtitle = "the game server's output, newest at the bottom") {
    Box(Modifier.fillMaxWidth().card(p).height(420.dp)) {
      SelectionContainer {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(12.dp)) {
          if (log.isEmpty()) Txt("Nothing yet.", size = 13.sp, color = p.muted)
          log.forEach { line ->
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
  Section("Console", subtitle = "server commands, e.g. /players, /send <player> <item>, /hint <player> <item>") {
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
      ActionButton("Send") { send() }
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
      .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}
