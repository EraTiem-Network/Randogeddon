package net.eratiem.randogeddon.webapp.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
fun main() {
  // Not <body>: Compose attaches a shadow root to its container, which hides the container's other children (the
  // loading splash and the login form of index.html)
  ComposeViewport(document.getElementById("app")!!) {
    CompositionLocalProvider(LocalTextContextMenuDropdownProvider provides NoContextMenu) {
      App()
    }
  }
  // Compose is drawing now; remove the loading splash of index.html
  document.getElementById("splash")?.remove()
}

/** No menu of Compose's own on a right click (it only had *Copy*; Ctrl+C still copies a selection). */
@OptIn(ExperimentalFoundationApi::class)
private object NoContextMenu : TextContextMenuProvider {
  override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {}
}
