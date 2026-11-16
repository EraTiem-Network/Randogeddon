package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The thumb's width, its distance from the edge, the strip along the edge that takes the mouse, its least height. */
private val THUMB = 6.dp
private val INSET = 3.dp
private val GRIP = 14.dp
private val MIN_THUMB = 28.dp

/**
 * Scrolls vertically like [verticalScroll] and shows where: a thumb along the right edge while there is more than fits
 * (the page is drawn on a canvas, so the browser shows no scroll bar of its own). The mouse drags the thumb, or a click
 * beside it jumps there; touch keeps scrolling the content itself.
 */
fun Modifier.scrolling(state: ScrollState? = null): Modifier = composed {
  val scroll = state ?: rememberScrollState()
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var dragging by remember { mutableStateOf(false) }
  this
    .pointerInput(scroll) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val max = scroll.maxValue
        if (down.type == PointerType.Touch || max <= 0 || max == Int.MAX_VALUE) return@awaitEachGesture
        if (down.position.x < size.width - GRIP.toPx()) return@awaitEachGesture
        val viewport = size.height.toFloat()
        val thumb = maxOf(MIN_THUMB.toPx(), viewport * viewport / (viewport + max))
        val top = (viewport - thumb) * scroll.value / max
        // Beside the thumb: there at once, its middle under the pointer
        if (down.position.y !in top..(top + thumb)) {
          val target = ((down.position.y - thumb / 2) / (viewport - thumb)).coerceIn(0f, 1f) * max
          scope.launch { scroll.scrollTo(target.toInt()) }
        }
        down.consume()
        dragging = true
        // Each pixel of the thumb is as much of the content as the track is shorter than it
        val perPixel = max / (viewport - thumb).coerceAtLeast(1f)
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val change = event.changes.firstOrNull { it.id == down.id } ?: break
          if (!change.pressed) break
          val dy = change.position.y - change.previousPosition.y
          if (dy != 0f) scroll.dispatchRawDelta(dy * perPixel)
          change.consume()
        }
        dragging = false
      }
    }
    .drawWithContent {
      drawContent()
      val max = scroll.maxValue
      if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
      val viewport = size.height
      val thumb = maxOf(MIN_THUMB.toPx(), viewport * viewport / (viewport + max))
      val top = (viewport - thumb) * scroll.value / max
      val width = THUMB.toPx()
      drawRoundRect(
        color = p.muted.copy(alpha = if (dragging || scroll.isScrollInProgress) 0.75f else 0.4f),
        topLeft = Offset(size.width - width - INSET.toPx(), top),
        size = Size(width, thumb),
        cornerRadius = CornerRadius(width / 2, width / 2),
      )
    }
    .verticalScroll(scroll)
}
