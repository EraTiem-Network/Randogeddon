package net.eratiem.archipelago.webapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.eratiem.archipelago.shared.ApiJson
import net.eratiem.archipelago.shared.PlayerDetail
import net.eratiem.archipelago.shared.Summary

/** The dashboard's view of the tracker server: the latest summary, pushed over the live stream. */
class TrackerRepository(private val scope: CoroutineScope) {
  private val _summary = MutableStateFlow<Summary?>(null)
  val summary: StateFlow<Summary?> = _summary.asStateFlow()

  /** Whether the live stream to the tracker server is open (not whether the tracker reaches the game server). */
  private val _streamOpen = MutableStateFlow(false)
  val streamOpen: StateFlow<Boolean> = _streamOpen.asStateFlow()

  private var close: (() -> Unit)? = null

  fun start() {
    if (close != null) return
    // The first state comes with the stream; fetching it too shows the page sooner on slow connections
    scope.launch { Platform.get("api/state")?.let(::accept) }
    close = Platform.stream("api/stream", onState = ::accept, onConnection = { _streamOpen.value = it })
  }

  fun stop() {
    close?.invoke()
    close = null
  }

  suspend fun player(slot: Int): PlayerDetail? =
    Platform.get("api/player/$slot")?.let { runCatching { ApiJson.decodeFromString(PlayerDetail.serializer(), it) }.getOrNull() }

  private fun accept(json: String) {
    val summary = runCatching { ApiJson.decodeFromString(Summary.serializer(), json) }.getOrNull() ?: return
    // The initial fetch can arrive after a newer pushed state
    if (summary.version >= (_summary.value?.version ?: -1) || summary.seedName != _summary.value?.seedName) {
      _summary.value = summary
    }
  }
}
