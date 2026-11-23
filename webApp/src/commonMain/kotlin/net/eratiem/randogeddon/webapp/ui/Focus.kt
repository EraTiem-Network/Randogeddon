package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.eratiem.randogeddon.webapp.app.Platform

/** A focus this soon after a press on the same thing came from the pointer (no ring), not from the keyboard. */
private const val POINTER_FOCUS_MS = 500L

/**
 * The focus ring's colour where the accent wouldn't show: on a surface in the accent's own colours (the top panel's
 * gradient). `null`: the accent.
 */
val LocalFocusColor = compositionLocalOf<Color?> { null }

/** The focus ring's width. */
private val RING = 2.dp

/** When the pointer last pressed on something; not a state: nothing is drawn from it. */
internal class PressClock {
  var at = 0L
}

/**
 * A ring in the accent colour, [gap] outside [shape] (a negative [gap] draws it inside: rows in a scrolling list, where
 * outside would be cut), while the keyboard has put the focus here (Tab): what Enter or Space would press. A press of
 * the pointer shows none, as a browser's `:focus-visible`. Put it before the `clickable` (or other focus target) it is
 * for, and before a `clip`, which would cut it.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(10.dp), gap: Dp = 3.dp): Modifier = composed {
  val accent = LocalFocusColor.current ?: LocalPalette.current.accent
  val pressed = remember { PressClock() }
  var ring by remember { mutableStateOf(false) }
  this
    .pointerInput(Unit) {
      // Seen before the button handles it, without taking it away from it
      awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        pressed.at = Platform.now()
        ring = false
      }
    }
    .onFocusChanged { ring = it.isFocused && Platform.now() - pressed.at > POINTER_FOCUS_MS }
    .drawWithContent {
      drawContent()
      if (ring) {
        val out = gap.toPx() + RING.toPx() / 2
        val outline = shape.createOutline(Size(size.width + 2 * out, size.height + 2 * out), layoutDirection, this)
        translate(-out, -out) { drawOutline(outline, accent, style = Stroke(RING.toPx())) }
      }
    }
}
