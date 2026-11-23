package net.eratiem.randogeddon.webapp.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.eratiem.randogeddon.webapp.app.Platform

/** Whether the page is shown ([Platform.pageVisible]), followed from its first use on. */
internal object PageVisibility {
  private val _shown = MutableStateFlow(true)
  val shown: StateFlow<Boolean> = _shown.asStateFlow()

  private var watching = false

  fun watch() {
    if (watching) return
    watching = true
    _shown.value = Platform.pageVisible()
    Platform.onVisibilityChange { _shown.value = it }
  }
}

/**
 * The wait of a poller between two of its requests: [ms] while the page is shown. While it is hidden (another tab in
 * front, the window minimised) the wait lasts until it is shown again, and then ends at once: the poller asks right
 * away instead of after the rest of its wait. The live streams aren't polling and don't wait for it.
 */
internal suspend fun pollWait(ms: Long) {
  PageVisibility.watch()
  val shown = PageVisibility.shown
  // Shown all along: the wait is over
  withTimeoutOrNull(ms) { shown.first { !it } } ?: return
  shown.first { it }
}
