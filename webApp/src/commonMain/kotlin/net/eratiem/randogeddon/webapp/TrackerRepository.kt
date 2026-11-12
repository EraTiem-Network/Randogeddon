package net.eratiem.randogeddon.webapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.ApiError
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ArchivedSession
import net.eratiem.randogeddon.shared.Me
import net.eratiem.randogeddon.shared.PastSession
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.Summary

/** Where the slot and player views get a slot's details: the live session, or a past session's archive. */
interface SessionSource {
  suspend fun player(slot: Int): PlayerDetail?

  /** Point hints are (or were) in play: the slot view shows hint points then. */
  val pointHints: Boolean
}

/** A past session, as it was when it finished. */
class ArchiveSource(val archived: ArchivedSession) : SessionSource {
  override suspend fun player(slot: Int): PlayerDetail? = archived.details.firstOrNull { it.slot == slot }

  override val pointHints: Boolean get() = archived.summary.hintSettings.pointHints
}

/**
 * The dashboard's view of the tracker server: who is logged in, and the latest summary, pushed over the live stream.
 * Nothing about the session is loaded before the login; logging out drops all of it from memory.
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

  /** The server can't be reached before the login is known (offline, or restarting); the page says so. */
  private val _unreachable = MutableStateFlow(false)
  val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

  private var close: (() -> Unit)? = null
  private var checking: Job? = null

  fun start() = checkLogin()

  fun stop() = closeStream()

  suspend fun logout() {
    Platform.request("POST", "api/logout")
    closeStream()
    _summary.value = null
    _me.value = null
    Platform.showLogin(null, ::checkLogin)
  }

  override suspend fun player(slot: Int): PlayerDetail? = Platform.request("GET", "api/player/$slot")
    ?.takeIf { it.status == 200 }
    ?.let { runCatching { ApiJson.decodeFromString(PlayerDetail.serializer(), it.body) }.getOrNull() }

  override val pointHints: Boolean get() = summary.value?.hintSettings?.pointHints != false

  /** The finished sessions this user may see, the newest first; empty if they can't be loaded. */
  suspend fun pastSessions(): List<PastSession> = Platform.request("GET", "api/sessions")
    ?.takeIf { it.status == 200 }
    ?.let { runCatching { ApiJson.decodeFromString(ListSerializer(PastSession.serializer()), it.body) }.getOrNull() }
    .orEmpty()

  suspend fun pastSession(seed: String): ArchivedSession? = Platform.request("GET", "api/sessions/$seed")
    ?.takeIf { it.status == 200 }
    ?.let { runCatching { ApiJson.decodeFromString(ArchivedSession.serializer(), it.body) }.getOrNull() }

  /** Asks the server who is logged in: opens the stream, or shows the login form. Retries while unreachable. */
  private fun checkLogin() {
    if (checking?.isActive == true) return
    checking = scope.launch {
      while (true) {
        val result = Platform.request("GET", "api/me")
        _unreachable.value = result == null || result.status >= 500
        when (result?.status) {
          200 -> {
            val me = runCatching { ApiJson.decodeFromString(Me.serializer(), result.body) }.getOrNull()
            _me.value = me
            Platform.hideLogin()
            // A player of past sessions only sees those: the live session isn't theirs
            if (me == null || me.inSession || me.admin) openStream()
            return@launch
          }
          401, 403 -> {
            closeStream()
            _summary.value = null
            _me.value = null
            // 403: logged in, but not part of this session
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

  private fun openStream() {
    if (close != null) return
    // The first state comes with the stream; fetching it too shows the page sooner on slow connections
    scope.launch {
      Platform.request("GET", "api/state")?.takeIf { it.status == 200 }?.let { accept(it.body) }
    }
    close = Platform.stream("api/stream", onState = ::accept, onConnection = { open ->
      _streamOpen.value = open
      // The browser gives up on a stream the server refused (an ended login, or a 502 while the tracker restarts), so
      // the stream is reopened here instead, once the server answers and the login is still valid
      if (!open) {
        closeStream()
        checkLogin()
      }
    })
  }

  private fun closeStream() {
    close?.invoke()
    close = null
    _streamOpen.value = false
  }

  private fun error(body: String): String? =
    runCatching { ApiJson.decodeFromString(ApiError.serializer(), body).message }.getOrNull()

  private fun accept(json: String) {
    val summary = runCatching { ApiJson.decodeFromString(Summary.serializer(), json) }.getOrNull() ?: return
    val previous = _summary.value
    // The initial fetch can arrive after a newer pushed state
    if (summary.version >= (previous?.version ?: -1) || summary.seedName != previous?.seedName) {
      _summary.value = summary
    }
    // Another session: other slots and slot passwords (or none) for this user
    if (previous != null && summary.seedName != previous.seedName) scope.launch { refreshMe() }
  }

  /** Who is logged in, again: their slots change with the session. */
  private suspend fun refreshMe() {
    val result = Platform.request("GET", "api/me")?.takeIf { it.status == 200 } ?: return
    runCatching { ApiJson.decodeFromString(Me.serializer(), result.body) }.getOrNull()?.let { _me.value = it }
  }
}
