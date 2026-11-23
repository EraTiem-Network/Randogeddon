package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform

/**
 * A small "i" next to an option or button: what it does, on hover, or on tap on a phone (tap again or anywhere else
 * to close). [options]: the choices of the option (name, what it means), each below a divider.
 */
@Composable
fun InfoTip(
  text: String,
  modifier: Modifier = Modifier,
  options: List<Pair<String, String>> = emptyList(),
  /** Parts of a longer explanation: each its heading, its text below it. */
  sections: List<Pair<String, String>> = emptyList(),
) {
  val p = LocalPalette.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  // The tip itself keeps it open: where there is no room below, it can come up under the pointer
  val tipInteraction = remember { MutableInteractionSource() }
  val tipHovered by tipInteraction.collectIsHoveredAsState()
  val open = stayOpen(hovered || tipHovered)
  var tapped by remember { mutableStateOf(false) }
  var closedAt by remember { mutableLongStateOf(0L) }
  Box(
    modifier.size(16.dp).focusRing(CircleShape, gap = 2.dp).clip(CircleShape)
      .border(1.2.dp, if (open || tapped) p.accent else p.muted, CircleShape)
      .hoverable(interaction)
      // Enter or Space opens it from the keyboard, as a tap does
      .hand().clickable(interactionSource = interaction, indication = null, role = Role.Button) {
        if (!justClosed(closedAt)) tapped = !tapped
      },
    contentAlignment = Alignment.Center,
  ) {
    Txt("i", size = 10.sp, color = if (open || tapped) p.accent else p.muted, weight = FontWeight.Bold, maxLines = 1)
    if (open || tapped) {
      AppPopup(
        offset = IntOffset(0, with(LocalDensity.current) { 20.dp.roundToPx() }),
        onDismissRequest = { tapped = false; closedAt = Platform.now() },
      ) {
        TipCard(text, options, sections, modifier = Modifier.hoverable(tipInteraction))
      }
    }
  }
}

/**
 * Whether a tip stays open: while [hovered] (its anchor, or the tip itself), and a moment after, so the pointer can
 * go from one to the other (and a tip that comes up under the pointer doesn't close and open again and again).
 */
@Composable
private fun stayOpen(hovered: Boolean): Boolean {
  var open by remember { mutableStateOf(false) }
  LaunchedEffect(hovered) {
    if (hovered) {
      open = true
    } else {
      delay(TIP_GRACE_MS)
      open = false
    }
  }
  return open || hovered
}

/** How long a tip stays after the pointer left it and its anchor. */
private const val TIP_GRACE_MS = 200L

/**
 * What a tip says: the text, then each choice (name, meaning) and each part (heading, text) below a divider. [wide]:
 * as wide as the window allows (a long message, such as Archipelago's), else a narrow column; such a message has a
 * button that copies it. Its text can be selected (and copied) in it: the tip is a window of its own, with its own
 * selection, and stays while the pointer is on it.
 */
@Composable
private fun TipCard(
  text: String,
  options: List<Pair<String, String>>,
  sections: List<Pair<String, String>> = emptyList(),
  wide: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
  SelectionContainer {
    TipText(text, options, sections, wide, window, modifier)
  }
}

@Composable
private fun TipText(
  text: String,
  options: List<Pair<String, String>>,
  sections: List<Pair<String, String>>,
  wide: Boolean,
  window: Dp,
  modifier: Modifier,
) {
  val p = LocalPalette.current
  Column(
    modifier.widthIn(max = if (wide) (window - 32.dp).coerceAtLeast(340.dp) else 340.dp)
      .card(p, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (wide && text.isNotBlank()) {
      DisableSelection { CopyTextButton({ text }) }
    }
    if (text.isNotBlank()) Txt(text, size = 12.sp)
    sections.forEachIndexed { i, (heading, body) ->
      if (i > 0 || text.isNotBlank()) Box(Modifier.width(316.dp).height(1.dp).background(p.border.copy(alpha = 0.7f)))
      Txt(heading, size = 12.sp, weight = FontWeight.SemiBold, color = p.accent)
      Txt(body, size = 12.sp)
    }
    options.forEach { (name, meaning) ->
      Box(Modifier.width(316.dp).height(1.dp).background(p.border.copy(alpha = 0.7f)))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(name, size = 12.sp, weight = FontWeight.SemiBold, color = p.accent, modifier = Modifier.width(84.dp))
        Txt(meaning, size = 12.sp, modifier = Modifier.weight(1f))
      }
    }
  }
}

/** How long the pointer rests on something before its tip shows. */
private const val TIP_DELAY_MS = 600L

/**
 * A popup closed less than a moment ago: the tap on its own anchor that closed it (popups close on the press outside
 * them) mustn't open it again on release.
 */
internal fun justClosed(closedAt: Long): Boolean = Platform.now() - closedAt < 400

/**
 * The text of the [Tip] around something: an [IconButton] in it takes it as its label (for assistive tools) and shows
 * no tip of its own. `null` outside a tip, and in a popup.
 */
internal val LocalTipLabel = compositionLocalOf<String?> { null }

/**
 * A tip shown on [content] itself (a button, a dropdown): once the pointer rests on it for a moment, below it; it
 * stays while the pointer is on it or on the tip. A click hides it until the pointer comes back (a dropdown's list
 * opens there). Touch screens have no hover: there a long press shows it, instead of the click; a tap anywhere closes
 * it. [wide]: the tip as wide as the window allows (a long message). An [IconButton] in [content] takes [text] as its
 * label.
 */
@Composable
fun Tip(text: String, options: List<Pair<String, String>> = emptyList(), wide: Boolean = false, content: @Composable () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val anchorHovered by interaction.collectIsHoveredAsState()
  val tipInteraction = remember { MutableInteractionSource() }
  val tipHovered by tipInteraction.collectIsHoveredAsState()
  // Where there is no room below, the tip can come up under the pointer: then it keeps itself open
  val hovered = stayOpen(anchorHovered || tipHovered)
  var shown by remember { mutableStateOf(false) }
  var clicked by remember { mutableStateOf(false) }
  // Shown by a long press
  var held by remember { mutableStateOf(false) }
  var height by remember { mutableStateOf(0) }
  LaunchedEffect(hovered, clicked) {
    if (hovered && !clicked) {
      if (!shown) delay(TIP_DELAY_MS)
      shown = true
    } else {
      shown = false
      if (!hovered) clicked = false
    }
  }
  Box(
    Modifier.hoverable(interaction).onSizeChanged { height = it.height }.pointerInput(Unit) {
      // Seen before the button or dropdown handles it, without taking it away from them
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        clicked = true
        if (down.type != PointerType.Touch) return@awaitEachGesture
        // Lifted or moved (a scroll) before the long press: an ordinary tap
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
          while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
          }
          true
        }
        if (tapped == null) {
          held = true
          // The button never sees this press end, so it doesn't click
          do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
          } while (event.changes.any { it.pressed })
        }
      }
    }
  ) {
    CompositionLocalProvider(LocalTipLabel provides text, content = content)
    if (shown || held) {
      AppPopup(
        offset = IntOffset(0, height + with(LocalDensity.current) { 6.dp.roundToPx() }),
        onDismissRequest = { shown = false; held = false },
      ) {
        TipCard(text, options, wide = wide, modifier = Modifier.hoverable(tipInteraction))
      }
    }
  }
}

/**
 * A popup (dropdown, tip) that stays out of the page's text selection: it is a window of its own, and selection
 * across windows can make the whole page hang. A [Tip] around its anchor doesn't name the buttons in it.
 */
@Composable
fun AppPopup(
  offset: IntOffset,
  onDismissRequest: () -> Unit,
  properties: PopupProperties = PopupProperties(),
  content: @Composable () -> Unit,
) {
  Popup(offset = offset, onDismissRequest = onDismissRequest, properties = properties) {
    CompositionLocalProvider(LocalTipLabel provides null) { DisableSelection(content) }
  }
}

/**
 * The hand pointer over something clickable, also over its text (which the page's selection would show the text
 * cursor for).
 */
fun Modifier.hand(): Modifier = pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true)

/** A clickable row that tints itself while hovered; a button to the keyboard (Tab, Enter) and to assistive tools. */
@Composable
fun HoverRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  val p = LocalPalette.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(12.dp)
  Row(
    modifier.focusRing(shape, gap = 1.dp).clip(shape)
      .background(if (hovered) p.panelAlt else Color.Transparent)
      .hoverable(interaction)
      .hand().clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}
