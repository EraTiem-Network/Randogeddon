package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.webapp.app.Platform

/** The page's margin at each side: 2 % of the window, at least this. */
private val MIN_MARGIN = 16.dp

/**
 * The frame of the dashboard and of a share link's page: the page colour with the brand's tint on top, one scrolling
 * column as wide as the window less its margins (2 % each side; a 1440p or 2160p screen is used, not left empty at the
 * sides), whose text can be selected (and copied) anywhere ([Selectable]: within the panel it was begun in), its parts
 * [spacing] apart. [content] gets the page's width
 * ([maxWidth]) and the column's ([contentWidth]).
 */
@Composable
fun PageFrame(spacing: Dp, content: @Composable ColumnScope.(maxWidth: Dp, contentWidth: Dp) -> Unit) {
  val p = LocalPalette.current
  BoxWithConstraints(
    // The page colour first: the tint on top of it is translucent
    Modifier.fillMaxSize().background(p.background).background(
      Brush.verticalGradient(listOf(p.brandEnd.copy(alpha = if (p.dark) 0.06f else 0.07f), p.background, p.background))
    )
  ) {
    val pageWidth = maxWidth
    val margin = maxOf(MIN_MARGIN, maxWidth * 0.02f)
    val contentWidth = (maxWidth - margin * 2).coerceAtLeast(0.dp)
    Column(Modifier.fillMaxSize().scrolling().padding(horizontal = margin, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      // What is outside the panels; each [Section], [Panel] and dialog part has a selection of its own
      Selectable {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
          content(pageWidth, contentWidth)
        }
      }
    }
  }
}

/** A selection this soon after a press takes the focus (for Ctrl+C); later, Tab passes the selection by. */
private const val SELECTION_FOCUS_MS = 2_000L

/** Whether a selection has the focus, and when the pointer last pressed in it; not states: nothing is drawn from them. */
private class SelectionFocus {
  var focused = false
  var pressedAt = 0L
}

/**
 * Text in [content] can be selected (and copied), and a selection begun here stays here: it doesn't run on into the
 * panels around it (a [Selectable] inside another has its own selection, which the outer one passes by). [Section],
 * [Panel], [DialogCard], [TwoColumns], [Mono] and [LogView] bring their own; wrap what stands outside them (a header
 * card, a banner) in one. Unlike a plain [SelectionContainer], it isn't a stop of its own for Tab: it takes the focus
 * only when a selection begins in it, so Ctrl+C copies it.
 */
@Composable
fun Selectable(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val state = remember { SelectionFocus() }
  // The same modifier on every composition: a new one would ask again whether it may keep the focus
  val pointerOnly = remember {
    Modifier
      .pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
          state.pressedAt = Platform.now()
        }
      }
      .onFocusChanged { state.focused = it.isFocused }
      .focusProperties { canFocus = state.focused || Platform.now() - state.pressedAt < SELECTION_FOCUS_MS }
  }
  SelectionContainer(modifier.then(pointerOnly), content = content)
}

/**
 * The time now, moved on every 30 s and whenever [key] changes (new data): relative times ("5m ago") and durations
 * move on without new data.
 */
@Composable
fun rememberNow(key: Any?): Long {
  var now by remember { mutableLongStateOf(Platform.now()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(30_000)
      now = Platform.now()
    }
  }
  LaunchedEffect(key) { now = Platform.now() }
  return now
}
