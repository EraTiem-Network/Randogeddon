package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import kotlin.time.Duration.Companion.milliseconds

/** How long a copy's button says that it copied. */
private val COPIED_SHOWN = 1500.milliseconds

/** A copy that says so: [copied] for a moment after each [copy] (nothing else tells that the clipboard changed). */
internal class Copying(private val scope: CoroutineScope) {
  var copied by mutableStateOf(false)
    private set
  private var back: Job? = null

  fun copy(text: String) {
    Platform.copy(text)
    copied = true
    back?.cancel()
    back = scope.launch {
      delay(COPIED_SHOWN)
      copied = false
    }
  }
}

@Composable
internal fun rememberCopying(): Copying {
  val scope = rememberCoroutineScope()
  return remember { Copying(scope) }
}

/** Copy as a small button with its word, *Copied* for a moment after it; [text]: what it copies, read at the click. */
@Composable
fun CopyTextButton(text: () -> String, modifier: Modifier = Modifier) {
  val t = LocalTexts.current
  val copying = rememberCopying()
  SmallButton(if (copying.copied) t.common.copied else t.common.copy, { copying.copy(text()) }, modifier)
}

/**
 * Copy as an icon with its word as the tip: a check mark for a moment after it copied. [copying]: its own, or one
 * shared with a menu that copies for it (then [onClick] opens the menu and the menu's choice copies).
 */
@Composable
internal fun CopyIconButton(copying: Copying = rememberCopying(), onClick: (() -> Unit)? = null, text: () -> String = { "" }) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  Tip(if (copying.copied) t.common.copied else t.common.copy) {
    IconButton(
      onClick ?: { copying.copy(text()) }, if (copying.copied) Icon.CHECK else Icon.COPY, Modifier.size(28.dp),
      tint = if (copying.copied) p.ok else null,
    )
  }
}
