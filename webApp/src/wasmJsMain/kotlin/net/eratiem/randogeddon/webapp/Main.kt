package net.eratiem.randogeddon.webapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  // Not <body>: Compose attaches a shadow root to its container, which hides the container's other children (the
  // loading splash and the login form of index.html)
  ComposeViewport(document.getElementById("app")!!) {
    App()
  }
  // Compose is drawing now; remove the loading splash of index.html
  document.getElementById("splash")?.remove()
}
