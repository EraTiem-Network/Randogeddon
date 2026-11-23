package net.eratiem.randogeddon.webapp.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.Connection
import net.eratiem.randogeddon.shared.FontScope
import net.eratiem.randogeddon.shared.Me
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SlotFile
import net.eratiem.randogeddon.shared.SlotFiles
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.i18n.texts
import net.eratiem.randogeddon.shared.web.pathSegment
import net.eratiem.randogeddon.shared.web.replaces
import net.eratiem.randogeddon.shared.web.retryDelay
import net.eratiem.randogeddon.webapp.app.Languages
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.wordError
import net.eratiem.randogeddon.webapp.dashboard.BridgeFold
import net.eratiem.randogeddon.webapp.dashboard.FeedSettings
import net.eratiem.randogeddon.webapp.ui.Themes

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

  /** The stream's failures in a row: each waits longer before it is opened again ([retryDelay]). */
  private var failures = 0

  init {
    // Any request finding the login ended: the same as at the start, the login form; once (while the form is open,
    // other requests answering the same don't open it again over what is typed)
    LoginWatch.onEnded = { if (_me.value != null) checkLogin() }
  }
  private var listing: Job? = null

  fun start() = checkLogin()

  fun stop() {
    closeStream()
    listing?.cancel()
  }

  /**
   * Logs out: whether the server took it. Only then is everything dropped and the login form shown; a logout that
   * didn't reach the server (offline, the tracker restarting) leaves the login valid, so the page stays as it is.
   */
  suspend fun logout(): Boolean {
    val result = Platform.request("POST", "api/logout")
    // 401: the login had ended already
    if (result == null || (result.status !in 200..299 && result.status != 401)) return false
    stop()
    forget()
    resetLocalSettings()
    Platform.showLogin(null, ::checkLogin)
    return true
  }

  /** Drops what the login loaded (the previous user's sessions, slot passwords and files); the next login loads it anew. */
  private fun forget() {
    _summary.value = null
    _me.value = null
    _sessions.value = null
    _selected.value = null
    _connection.value = null
    _files.value = emptyList()
  }

  /** Shows session [id] instead; `null`: none. */
  fun select(id: String?) {
    if (id == _selected.value) return
    closeStream()
    failures = 0
    _summary.value = null
    _connection.value = null
    _files.value = emptyList()
    _selected.value = id
    if (id != null) openStream(id)
  }

  override suspend fun player(slot: Int): PlayerDetail? {
    val id = _selected.value ?: return null
    return load("api/sessions/${id.pathSegment()}/player/$slot", PlayerDetail.serializer())
  }

  override val pointHints: Boolean get() = summary.value?.hintSettings?.pointHints != false

  /** Who is logged in, again (after a rename in the settings). */
  suspend fun reloadMe() {
    _me.value = load("api/me", Me.serializer()) ?: return
    refreshSessions()
  }

  /** The sessions again (one was created, finished or deleted); one that is gone stops being shown. */
  suspend fun refreshSessions() {
    val list = load("api/sessions", ListSerializer(SessionEntry.serializer())) ?: return
    // One that arrives after the logout (or the login's end) isn't kept
    if (_me.value == null) return
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
        // An answer of another shape (a tracker of another version, while it is deployed) counts as unreachable: the
        // page says so and asks again, instead of showing nothing
        val me = result?.takeIf { it.status == 200 }
          ?.let { runCatching { ApiJson.decodeFromString(Me.serializer(), it.body) }.getOrNull() }
        _unreachable.value = result == null || result.status >= 500 || (result.status == 200 && me == null)
        if (me != null) {
          _me.value = me
          Platform.hideLogin()
          refreshSessions()
          // New sessions, and how the others are doing (not while the page is hidden)
          if (listing?.isActive != true) listing = scope.launch {
            while (true) {
              pollWait(30_000)
              refreshSessions()
            }
          }
          return@launch
        }
        val status = result?.status
        if (status == 401 || status == 403) {
          stop()
          // All of it, the selected session too: after the next login its route selects it again, and its stream opens
          forget()
          // 403: logged in, but not part of any session; 401: the login ended
          val message = if (status == 403) error(result?.body.orEmpty()) else null
          if (status == 401) resetLocalSettings()
          Platform.showLogin(message, ::checkLogin)
          return@launch
        }
        // Server unreachable or restarting
        delay(5_000)
      }
    }
  }

  /**
   * Opens session [id]'s live stream. The first time ([retry] false) its state, connection and files are fetched beside
   * it; a [retry] opens only the stream (its first event is the state), and the connection and files are loaded again
   * once it is open.
   */
  private fun openStream(id: String, retry: Boolean = false) {
    if (close != null) return
    val segment = id.pathSegment()
    if (!retry) {
      // The first state comes with the stream; fetching it too shows the page sooner on slow connections
      scope.launch {
        Platform.request("GET", "api/sessions/$segment/state")?.takeIf { it.status == 200 }?.let { accept(id, it.body) }
      }
      scope.launch { loadConnection(id) }
    }
    close = Platform.stream("api/sessions/$segment/stream", onState = { accept(id, it) }, onConnection = { open ->
      _streamOpen.value = open
      if (open && failures > 0) {
        // Back after a failure (the tracker restarted, perhaps with the seed's slots now): what was loaded may be old
        failures = 0
        scope.launch { loadConnection(id) }
      }
      // The browser gives up on a stream the server refused (an ended login, or a 502 while the tracker restarts), so
      // the stream is reopened here instead, once the server answers and the login is still valid; each failure in a
      // row waits longer (every open dashboard asks at once while the tracker is away)
      if (!open) {
        closeStream()
        checkLogin()
        failures++
        val wait = retryDelay(failures)
        scope.launch {
          delay(wait)
          if (_selected.value == id && close == null && _me.value != null) openStream(id, retry = true)
        }
      }
    })
  }

  private suspend fun loadConnection(id: String) {
    val connection = load("api/sessions/${id.pathSegment()}/connection", Connection.serializer())
    if (_selected.value == id) _connection.value = connection
    // Only the user's own slots here, also for a Session-Manager (the panel has every slot's)
    val mine = connection?.slots.orEmpty().map { it.name }.toSet()
    val files = load("api/sessions/${id.pathSegment()}/files", SlotFiles.serializer())?.files
    if (_selected.value == id && files != null) _files.value = files.filter { it.slotName in mine }
  }

  private fun closeStream() {
    close?.invoke()
    close = null
    _streamOpen.value = false
  }

  private fun error(body: String): String? = texts(Languages.current).wordError(body)

  private fun accept(id: String, json: String) {
    if (_selected.value != id) return
    val summary = runCatching { ApiJson.decodeFromString(Summary.serializer(), json) }.getOrNull() ?: return
    val previous = _summary.value
    if (summary.replaces(previous)) _summary.value = summary
    // The seed got its slots (or finished): the slot passwords and the list change with it (the first summary needs
    // nothing: the connection is loaded beside the stream when it opens)
    if (previous != null && (summary.players.size != previous.players.size || summary.finishedAt != previous.finishedAt)) {
      scope.launch {
        loadConnection(id)
        refreshSessions()
      }
    }
  }
}

/**
 * Without a login (logged out, or the login ended): the settings kept in this browser go back to the defaults, so the
 * next person on it starts fresh. A login sets the account's theme, mode and language again.
 */
internal fun resetLocalSettings() {
  Themes.apply(null, null)
  Themes.applyFont(null, FontScope.AUTO)
  Languages.reset()
  FeedSettings.reset()
  BridgeFold.reset()
  // The cheats' last way of picking a hint (the panel's `HINT_PICK_KEY`)
  Platform.saveSetting("ap-hint-pick", null)
}
