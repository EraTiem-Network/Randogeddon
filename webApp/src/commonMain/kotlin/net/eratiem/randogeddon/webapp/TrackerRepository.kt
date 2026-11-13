package net.eratiem.randogeddon.webapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.Connection
import net.eratiem.randogeddon.shared.Me
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionStatus
import net.eratiem.randogeddon.shared.SlotFile
import net.eratiem.randogeddon.shared.SlotFiles
import net.eratiem.randogeddon.shared.Summary

/** Where the slot and player views get a slot's details. */
interface SessionSource {
  suspend fun player(slot: Int): PlayerDetail?

  /** Point hints are (or were) in play: the slot view shows hint points then. */
  val pointHints: Boolean
}

/**
 * The dashboard's view of the tracker server: who is logged in, the sessions they see, and the selected session's
 * latest summary, pushed over its live stream (a finished session's is the one frozen when it finished). Nothing about
 * the sessions is loaded before the login; logging out drops all of it from memory.
 */
class TrackerRepository(private val scope: CoroutineScope) : SessionSource {
  private val _summary = MutableStateFlow<Summary?>(null)
  val summary: StateFlow<Summary?> = _summary.asStateFlow()

  /** Whether the live stream to the tracker server is open (not whether the tracker reaches the game server). */
  private val _streamOpen = MutableStateFlow(false)
  val streamOpen: StateFlow<Boolean> = _streamOpen.asStateFlow()

  /** The logged-in user; `null` before the login (the login form is shown then). */
  private val _me = MutableStateFlow<Me?>(null)
  val me: StateFlow<Me?> = _me.asStateFlow()

  /** The sessions the user sees (all for admins), the newest first; `null` until loaded. */
  private val _sessions = MutableStateFlow<List<SessionEntry>?>(null)
  val sessions: StateFlow<List<SessionEntry>?> = _sessions.asStateFlow()

  /** The session the dashboard shows (the page's route picks it); `null` while there is none to show. */
  private val _selected = MutableStateFlow<String?>(null)
  val selected: StateFlow<String?> = _selected.asStateFlow()

  /** Where the user's game clients connect to the selected session, with their slots' passwords. */
  private val _connection = MutableStateFlow<Connection?>(null)
  val connection: StateFlow<Connection?> = _connection.asStateFlow()

  /** The seed's files of the user's slots in the selected session (patches, mods), to download. */
  private val _files = MutableStateFlow<List<SlotFile>>(emptyList())
  val files: StateFlow<List<SlotFile>> = _files.asStateFlow()

  /** The server can't be reached before the login is known (offline, or restarting); the page says so. */
  private val _unreachable = MutableStateFlow(false)
  val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

  private var close: (() -> Unit)? = null
  private var checking: Job? = null
  private var listing: Job? = null

  fun start() = checkLogin()

  fun stop() {
    closeStream()
    listing?.cancel()
  }

  suspend fun logout() {
    Platform.request("POST", "api/logout")
    stop()
    _summary.value = null
    _me.value = null
    _sessions.value = null
    _selected.value = null
    _connection.value = null
    _files.value = emptyList()
    Platform.showLogin(null, ::checkLogin)
  }

  /** Shows session [id] instead; `null`: none. */
  fun select(id: String?) {
    if (id == _selected.value) return
    closeStream()
    _summary.value = null
    _connection.value = null
    _files.value = emptyList()
    _selected.value = id
    if (id != null) openStream(id)
  }

  override suspend fun player(slot: Int): PlayerDetail? {
    val id = _selected.value ?: return null
    return Platform.request("GET", "api/sessions/$id/player/$slot")
      ?.takeIf { it.status == 200 }
      ?.let { runCatching { ApiJson.decodeFromString(PlayerDetail.serializer(), it.body) }.getOrNull() }
  }

  override val pointHints: Boolean get() = summary.value?.hintSettings?.pointHints != false

  /** Who is logged in, again (after a rename in the settings). */
  suspend fun reloadMe() {
    val result = Platform.request("GET", "api/me")?.takeIf { it.status == 200 } ?: return
    runCatching { ApiJson.decodeFromString(Me.serializer(), result.body) }.getOrNull()?.let { _me.value = it }
    refreshSessions()
  }

  /** The sessions again (one was created, finished or deleted); one that is gone stops being shown. */
  suspend fun refreshSessions() {
    val list = Platform.request("GET", "api/sessions")?.takeIf { it.status == 200 }
      ?.let { runCatching { ApiJson.decodeFromString(ListSerializer(SessionEntry.serializer()), it.body) }.getOrNull() }
      ?: return
    _sessions.value = list
    val current = _selected.value
    if (current != null && list.none { it.id == current }) select(null)
  }

  /** Asks the server who is logged in: loads the sessions, or shows the login form. Retries while unreachable. */
  private fun checkLogin() {
    if (checking?.isActive == true) return
    checking = scope.launch {
      while (true) {
        val result = Platform.request("GET", "api/me")
        _unreachable.value = result == null || result.status >= 500
        when (result?.status) {
          200 -> {
            _me.value = runCatching { ApiJson.decodeFromString(Me.serializer(), result.body) }.getOrNull()
            Platform.hideLogin()
            refreshSessions()
            // New sessions, and how the others are doing
            if (listing?.isActive != true) listing = scope.launch {
              while (true) {
                delay(30_000)
                refreshSessions()
              }
            }
            return@launch
          }
          401, 403 -> {
            stop()
            _summary.value = null
            _me.value = null
            // 403: logged in, but not part of any session
            val message = if (result.status == 403) error(result.body) else null
            Platform.showLogin(message, ::checkLogin)
            return@launch
          }
          // Server unreachable or restarting
          else -> delay(5_000)
        }
      }
    }
  }

  private fun openStream(id: String) {
    if (close != null) return
    // The first state comes with the stream; fetching it too shows the page sooner on slow connections
    scope.launch {
      Platform.request("GET", "api/sessions/$id/state")?.takeIf { it.status == 200 }?.let { accept(id, it.body) }
    }
    scope.launch { loadConnection(id) }
    close = Platform.stream("api/sessions/$id/stream", onState = { accept(id, it) }, onConnection = { open ->
      _streamOpen.value = open
      // The browser gives up on a stream the server refused (an ended login, or a 502 while the tracker restarts), so
      // the stream is reopened here instead, once the server answers and the login is still valid
      if (!open) {
        closeStream()
        checkLogin()
        scope.launch {
          delay(2_000)
          if (_selected.value == id && close == null && _me.value != null) openStream(id)
        }
      }
    })
  }

  private suspend fun loadConnection(id: String) {
    val connection = Platform.request("GET", "api/sessions/$id/connection")?.takeIf { it.status == 200 }
      ?.let { runCatching { ApiJson.decodeFromString(Connection.serializer(), it.body) }.getOrNull() }
    if (_selected.value == id) _connection.value = connection
    // Only the user's own slots here, also for a Session-Manager (the panel has every slot's)
    val mine = connection?.slots.orEmpty().map { it.name }.toSet()
    val files = Platform.request("GET", "api/sessions/$id/files")?.takeIf { it.status == 200 }
      ?.let { runCatching { ApiJson.decodeFromString(SlotFiles.serializer(), it.body).files }.getOrNull() }
    if (_selected.value == id && files != null) _files.value = files.filter { it.slotName in mine }
  }

  private fun closeStream() {
    close?.invoke()
    close = null
    _streamOpen.value = false
  }

  private fun error(body: String): String? =
    runCatching { ApiJson.decodeFromString(ApiError.serializer(), body).message }.getOrNull()

  private fun accept(id: String, json: String) {
    if (_selected.value != id) return
    val summary = runCatching { ApiJson.decodeFromString(Summary.serializer(), json) }.getOrNull() ?: return
    val previous = _summary.value
    // The initial fetch can arrive after a newer pushed state
    if (summary.version >= (previous?.version ?: -1) || summary.finishedAt != previous?.finishedAt) {
      _summary.value = summary
    }
    // The seed got its slots (or finished): the slot passwords and the list change with it
    if (previous == null || summary.players.size != previous.players.size || summary.finishedAt != previous.finishedAt) {
      scope.launch {
        loadConnection(id)
        if (previous != null) refreshSessions()
      }
    }
  }
}

/** Not finished yet. */
val SessionStatus.running: Boolean get() = this == SessionStatus.LIVE || this == SessionStatus.FINISHING
