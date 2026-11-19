package net.eratiem.randogeddon.webapp.api

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.AdminSessionView
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CheatView
import net.eratiem.randogeddon.shared.CommandRequest
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.webapp.app.Platform

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
  suspend fun control(action: String): String? = post("api/sessions/$id/manage/server/$action")

  suspend fun command(line: String): String? =
    post("api/sessions/$id/manage/command", ApiJson.encodeToString(CommandRequest.serializer(), CommandRequest(line)))

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

  /**
   * Loads what isn't there yet of the sessions [ids], and forgets the sessions that are gone: the ones not in [all]
   * (the running ones; more than [ids] for someone who supports some: their pages load their own).
   */
  fun prefetch(ids: List<String>, all: Collection<String> = ids) {
    val kept = all.toSet()
    (views.keys - kept).forEach { views.remove(it) }
    (cheats.keys - kept).forEach { cheats.remove(it) }
    (repositories.keys - kept).forEach { repositories.remove(it)?.stop() }
    ids.forEach { id ->
      if (id !in views) scope.launch {
        val answer = call("GET", "api/sessions/$id/manage", null, AdminSessionView.serializer())
        if (answer is Answer.Ok && id !in views) views[id] = answer.value
      }
      if (id !in cheats) scope.launch {
        val answer = call("GET", "api/sessions/$id/manage/cheat", null, CheatView.serializer())
        if (answer is Answer.Ok && id !in cheats) cheats[id] = answer.value
      }
    }
  }
}
