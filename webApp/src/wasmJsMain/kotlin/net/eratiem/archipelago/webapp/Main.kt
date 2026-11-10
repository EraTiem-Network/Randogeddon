package net.eratiem.archipelago.webapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(document.body!!) {
    App()
  }
  // Compose is drawing now; remove the loading splash of index.html
  document.getElementById("splash")?.remove()
}
