package net.eratiem.randogeddon.webapp.api

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.webapp.app.Platform

/**
 * A session by its share link ([token]), without a login: its summary over the live stream, and a slot's details.
 * Read-only: the server answers no passwords, spoiler or accounts here.
 */
class WatchRepository(private val scope: CoroutineScope, private val token: String) : SessionSource {
  private val _summary = MutableStateFlow<Summary?>(null)
  val summary: StateFlow<Summary?> = _summary.asStateFlow()

  private val _streamOpen = MutableStateFlow(false)
  val streamOpen: StateFlow<Boolean> = _streamOpen.asStateFlow()

  /** The link is off, replaced or made up. */
  private val _gone = MutableStateFlow(false)
  val gone: StateFlow<Boolean> = _gone.asStateFlow()

  private var close: (() -> Unit)? = null

  fun start() {
    if (close != null) return
    scope.launch {
      val result = Platform.request("GET", "api/watch/$token/state")
      when (result?.status) {
        200 -> accept(result.body)
        404 -> { _gone.value = true; return@launch }
      }
      open()
    }
  }

  fun stop() {
    close?.invoke()
    close = null
    _streamOpen.value = false
  }

  private fun open() {
    close = Platform.stream("api/watch/$token/stream", onState = ::accept, onConnection = { open ->
      _streamOpen.value = open
      // The browser gives up on a refused stream: tried again while the link works
      if (!open) {
        stop()
        scope.launch {
          delay(3_000)
          if (Platform.request("GET", "api/watch/$token/state")?.status == 404) _gone.value = true else open()
        }
      }
    })
  }

  override suspend fun player(slot: Int): PlayerDetail? =
    Platform.request("GET", "api/watch/$token/player/$slot")?.takeIf { it.status == 200 }
      ?.let { runCatching { ApiJson.decodeFromString(PlayerDetail.serializer(), it.body) }.getOrNull() }

  override val pointHints: Boolean get() = summary.value?.hintSettings?.pointHints != false

  private fun accept(json: String) {
    val summary = runCatching { ApiJson.decodeFromString(Summary.serializer(), json) }.getOrNull() ?: return
    val previous = _summary.value
    if (summary.version >= (previous?.version ?: -1) || summary.finishedAt != previous?.finishedAt) _summary.value = summary
  }
}
