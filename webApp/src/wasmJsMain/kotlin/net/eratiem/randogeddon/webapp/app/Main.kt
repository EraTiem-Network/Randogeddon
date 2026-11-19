package net.eratiem.randogeddon.webapp.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
fun main() {
  // The browser's own menu on a right click, as on any page: Compose never sees the event, so it doesn't cancel it
  // (it would, to show its own). Caught on its way down, before it reaches the page.
  window.addEventListener("contextmenu", { it.stopPropagation() }, true)
  // Not <body>: Compose attaches a shadow root to its container, which hides the container's other children (the
  // loading splash and the login form of index.html)
  ComposeViewport(document.getElementById("app")!!) {
    CompositionLocalProvider(
      LocalTextContextMenuDropdownProvider provides NoContextMenu,
      LocalTextContextMenuToolbarProvider provides NoContextMenu,
    ) {
      App()
    }
  }
  // Compose is drawing now; remove the loading splash of index.html
  document.getElementById("splash")?.remove()
}

/**
 * No menu of Compose's own, neither on a right click nor as the toolbar over a selection (a long press): the browser's
 * menu instead. Ctrl+C still copies a selection.
 */
@OptIn(ExperimentalFoundationApi::class)
private object NoContextMenu : TextContextMenuProvider {
  override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {}
}
