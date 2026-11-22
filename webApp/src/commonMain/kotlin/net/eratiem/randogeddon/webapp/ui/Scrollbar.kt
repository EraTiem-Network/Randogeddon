package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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

/**
 * Scrolls both ways (a view onto something larger, a map) and shows where: a thumb along the bottom and one along
 * the right edge, each while there is more than fits that way. The mouse drags a thumb (a click beside it jumps
 * there), or the content itself; the wheel goes up and down. Touch keeps scrolling the content as it does.
 */
fun Modifier.scrollingBoth(): Modifier = composed {
  val across = rememberScrollState()
  val down = rememberScrollState()
  val p = LocalPalette.current
  var dragging by remember { mutableStateOf(false) }
  // How far a way scrolls (0: it fits), and its thumb's length in a view of [viewport]
  fun more(scroll: ScrollState) = scroll.maxValue.takeIf { it != Int.MAX_VALUE } ?: 0
  fun thumb(viewport: Float, max: Int, least: Float) = maxOf(least, viewport * viewport / (viewport + max))
  this
    .pointerInput(across, down) {
      awaitEachGesture {
        val press = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val maxX = more(across)
        val maxY = more(down)
        if (press.type == PointerType.Touch || (maxX <= 0 && maxY <= 0)) return@awaitEachGesture
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val thumbX = thumb(width, maxX, MIN_THUMB.toPx())
        val thumbY = thumb(height, maxY, MIN_THUMB.toPx())
        val onAcross = maxX > 0 && press.position.y >= height - GRIP.toPx()
        val onDown = !onAcross && maxY > 0 && press.position.x >= width - GRIP.toPx()
        // Beside a thumb: there at once, its middle under the pointer
        if (onAcross) {
          val left = (width - thumbX) * across.value / maxX
          if (press.position.x !in left..(left + thumbX)) {
            across.dispatchRawDelta(((press.position.x - thumbX / 2) / (width - thumbX).coerceAtLeast(1f)).coerceIn(0f, 1f) * maxX - across.value)
          }
        }
        if (onDown) {
          val top = (height - thumbY) * down.value / maxY
          if (press.position.y !in top..(top + thumbY)) {
            down.dispatchRawDelta(((press.position.y - thumbY / 2) / (height - thumbY).coerceAtLeast(1f)).coerceIn(0f, 1f) * maxY - down.value)
          }
        }
        if (onAcross || onDown) press.consume()
        dragging = true
        // On a bar each pixel of its thumb is as much of the content as its track is shorter than it (the other
        // way stays); on the content, the content follows the pointer
        val perX = if (onAcross) maxX / (width - thumbX).coerceAtLeast(1f) else if (onDown) 0f else -1f
        val perY = if (onDown) maxY / (height - thumbY).coerceAtLeast(1f) else if (onAcross) 0f else -1f
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val change = event.changes.firstOrNull { it.id == press.id } ?: break
          if (!change.pressed) break
          val moved = change.position - change.previousPosition
          if (moved.x != 0f && perX != 0f) across.dispatchRawDelta(moved.x * perX)
          if (moved.y != 0f && perY != 0f) down.dispatchRawDelta(moved.y * perY)
          if (onAcross || onDown) change.consume()
        }
        dragging = false
      }
    }
    .drawWithContent {
      drawContent()
      val colour = p.muted.copy(alpha = if (dragging || across.isScrollInProgress || down.isScrollInProgress) 0.75f else 0.4f)
      val thick = THUMB.toPx()
      val round = CornerRadius(thick / 2, thick / 2)
      val maxX = more(across)
      if (maxX > 0) {
        val length = thumb(size.width, maxX, MIN_THUMB.toPx())
        drawRoundRect(colour, Offset((size.width - length) * across.value / maxX, size.height - thick - INSET.toPx()), Size(length, thick), round)
      }
      val maxY = more(down)
      if (maxY > 0) {
        val length = thumb(size.height, maxY, MIN_THUMB.toPx())
        drawRoundRect(colour, Offset(size.width - thick - INSET.toPx(), (size.height - length) * down.value / maxY), Size(thick, length), round)
      }
    }
    .horizontalScroll(across)
    .verticalScroll(down)
}
