package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.i18n.Texts
import net.eratiem.randogeddon.shared.i18n.fileSize
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.PickProblem
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.ui.Button
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.NumberField
import net.eratiem.randogeddon.webapp.ui.Txt

/**
 * A question before something is done, in place of the buttons: Yes (red if [danger]) or Cancel. It takes the focus
 * when it appears (unseen, and only then: Tab doesn't stop on it again), so Enter says yes and Escape cancels while it
 * or one of its buttons has the focus (Enter on its Cancel button is that button's); a text field elsewhere that has
 * the focus keeps its keys.
 */
@Composable
internal fun Confirm(question: String, yes: String, danger: Boolean, onYes: () -> Unit, onCancel: () -> Unit) {
  val entry = remember { FocusRequester() }
  val keys = remember { ConfirmKeys() }
  val answers by rememberUpdatedState(onYes to onCancel)
  // The same modifier on every composition: a new one would ask again whether it may keep the focus
  val takesFocusOnce = remember {
    Modifier
      .onKeyEvent { event ->
        when {
          event.key == Key.Escape && event.type == KeyEventType.KeyDown -> { answers.second(); true }
          event.key != Key.Enter && event.key != Key.NumPadEnter -> false
          // Only an Enter pressed here: the release of the one that opened it (on the button it replaces) isn't taken
          event.type == KeyEventType.KeyDown -> { keys.enterDown = true; true }
          event.type == KeyEventType.KeyUp && keys.enterDown -> { keys.enterDown = false; answers.first(); true }
          else -> false
        }
      }
      .focusRequester(entry)
      .onFocusChanged { if (it.isFocused) keys.entered = true }
      .focusProperties { canFocus = !keys.entered }
      .focusTarget()
  }
  LaunchedEffect(Unit) { entry.requestFocus() }
  Row(takesFocusOnce, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Txt(question, size = 13.sp, modifier = Modifier.weight(1f))
    Button(yes, danger = danger) { onYes() }
    Button(LocalTexts.current.common.cancel) { onCancel() }
  }
}

/** A [Confirm]'s keys: whether it has had the focus it takes when it appears, and an Enter pressed in it. */
private class ConfirmKeys {
  var entered = false
  var enterDown = false
}

@Composable
internal fun Labeled(
  label: String,
  tip: String? = null,
  choices: List<Pair<String, String>> = emptyList(),
  content: @Composable () -> Unit,
) {
  // One height for every option, so labels, dropdowns and number fields line up in their rows
  Row(Modifier.heightIn(min = FIELD_HEIGHT), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Txt(label, size = 12.5.sp, color = LocalPalette.current.muted)
    if (tip != null) InfoTip(tip, options = choices)
    content()
  }
}

/** A whole number of 0 to 999 under its [label], with the arrows of [NumberField]; empty, it keeps the last one. */
@Composable
internal fun LabeledNumber(label: String, value: Int, tip: String? = null, onChange: (Int) -> Unit) {
  var text by remember(value) { mutableStateOf(value.toString()) }
  Labeled(label, tip) {
    NumberField(text, { typed ->
      text = typed
      typed.toIntOrNull()?.let(onChange)
    }, "0", 0..999, Modifier.width(76.dp), minHeight = FIELD_HEIGHT)
  }
}

/** The height of the form's text fields, dropdowns and player chips, so they line up (also `ui`'s buttons). */
internal val FIELD_HEIGHT = 34.dp

/** Why a picked file was left out unread ([Platform.pickFiles]): its name, and for one too large the [limit]. */
internal fun pickProblem(t: Texts, file: String, problem: PickProblem, limit: Long): String = when (problem) {
  PickProblem.TOO_LARGE -> t.sessionAdmin.fileTooLarge(file, t.fileSize(limit))
  PickProblem.UNREADABLE -> t.sessionAdmin.fileUnreadable(file)
}
