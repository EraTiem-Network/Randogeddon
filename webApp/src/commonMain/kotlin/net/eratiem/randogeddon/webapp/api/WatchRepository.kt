package net.eratiem.randogeddon.webapp.api

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

  /** The stream's failures in a row: each waits longer before the next attempt ([retryDelay]). */
  private var failures = 0

  /** The token as a part of the API's paths (the route lets only a token's characters through; encoded all the same). */
  private val path = "api/watch/${token.pathSegment()}"

  fun start() {
    if (close != null) return
    scope.launch {
      val result = Platform.request("GET", "$path/state")
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
    close = Platform.stream("$path/stream", onState = ::accept, onConnection = { open ->
      _streamOpen.value = open
      if (open) failures = 0
      // The browser gives up on a refused stream: tried again while the link works, each time a little later
      if (!open) {
        stop()
        failures++
        val wait = retryDelay(failures)
        scope.launch {
          delay(wait)
          if (Platform.request("GET", "$path/state")?.status == 404) _gone.value = true else open()
        }
      }
    })
  }

  override suspend fun player(slot: Int): PlayerDetail? = load("$path/player/$slot", PlayerDetail.serializer())

  override val pointHints: Boolean get() = summary.value?.hintSettings?.pointHints != false

  private fun accept(json: String) {
    val summary = runCatching { ApiJson.decodeFromString(Summary.serializer(), json) }.getOrNull() ?: return
    if (summary.replaces(_summary.value)) _summary.value = summary
  }
}
