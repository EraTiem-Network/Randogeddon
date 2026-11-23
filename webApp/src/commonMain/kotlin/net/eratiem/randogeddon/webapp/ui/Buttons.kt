package net.eratiem.randogeddon.webapp.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.webapp.manage.FIELD_HEIGHT

/**
 * A form's button, filled in the accent colour (red if [danger]); grey and out of the Tab order while not [enabled].
 * The same as `manage`'s `Button`, with the focus ring: that one moves here.
 */
@Composable
fun Button(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
  val p = LocalPalette.current
  val color = if (danger) p.trap else p.accent
  val shape = RoundedCornerShape(10.dp)
  Txt(
    text, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1,
    color = if (enabled) p.onBrand else p.muted,
    modifier = Modifier.focusRing(shape).clip(shape)
      .background(if (enabled) color else p.panelAlt)
      .then(if (enabled) Modifier.hand().clickable(role = Role.Button, onClick = onClick) else Modifier)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}

/**
 * A check box with its [label] (and an "i" with [tip]); a click on either, or Space or Enter from the keyboard, turns
 * it. The same as `manage`'s `Check`, with the focus ring and its role: that one moves here.
 */
@Composable
fun Check(label: String, checked: Boolean, tip: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  val p = LocalPalette.current
  val shape = RoundedCornerShape(8.dp)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      Modifier.focusRing(shape, gap = 1.dp).clip(shape)
        .then(
          if (enabled) Modifier.hand().toggleable(checked, role = Role.Checkbox) { onChange(it) }
          else Modifier.alpha(0.45f)
        )
        .padding(vertical = 2.dp, horizontal = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
          .background(if (checked) p.accent else Color.Transparent)
          .border(1.5.dp, if (checked) p.accent else p.muted, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
      ) {
        if (checked) DrawnIcon(Icon.CHECK, p.onBrand, 12.dp)
      }
      Txt(label, size = 12.5.sp, maxLines = 1)
    }
    if (tip != null) InfoTip(tip)
  }
}

/**
 * A button of a switch between pages (the panel's, the spoiler log's tabs); the page shown is filled ([active]). The
 * same as `manage`'s `PageButton`, with the focus ring and the role of a tab: that one moves here.
 */
@Composable
fun PageButton(text: String, active: Boolean, attention: Boolean = false, onClick: () -> Unit) {
  val p = LocalPalette.current
  val shape = RoundedCornerShape(10.dp)
  // [attention]: it breathes (a little bigger and brighter, and back), while it is the one thing to do here
  val pulse = if (attention) {
    val beat by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    beat
  } else 0f
  Txt(
    text, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1,
    color = if (active) p.onBrand else p.accent,
    modifier = Modifier.graphicsLayer { scaleX = 1f + 0.07f * pulse; scaleY = 1f + 0.07f * pulse }
      .heightIn(min = FIELD_HEIGHT).focusRing(shape).clip(shape)
      .background(if (active) p.accent else p.accent.copy(alpha = 0.1f + 0.22f * pulse))
      .hand().clickable(role = Role.Tab, onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}

/**
 * An action that asks the server, behind a button: [busy] while it runs (the button `enabled = !busy`, so a second
 * click can't send it twice), [problem] what went wrong (shown beside the button), `null` while nothing did.
 */
@Stable
class Action internal constructor(private val scope: CoroutineScope) {
  var busy by mutableStateOf(false)
    private set
  var problem by mutableStateOf<String?>(null)

  /** Runs [block] unless it runs already; [block] answers the problem, `null` if it went through (as `post` does). */
  fun run(block: suspend () -> String?) {
    if (busy) return
    busy = true
    problem = null
    scope.launch {
      try {
        problem = block()
      } finally {
        busy = false
      }
    }
  }
}

/** An [Action] for as long as the composable that asks for it is shown. */
@Composable
fun rememberAction(): Action {
  val scope = rememberCoroutineScope()
  return remember { Action(scope) }
}
