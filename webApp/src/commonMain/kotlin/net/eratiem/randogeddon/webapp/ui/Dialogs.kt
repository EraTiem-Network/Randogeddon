package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A dialog's card (a person's, a world's, an account's panel): at most [maxWidth] wide and as high as the window, its [header] in a row
 * on top (what it is about, then its close button), a divider, then [body], which gets the width there is
 * ([available]: for one column or two). Header and body each keep a text selection to themselves ([Selectable]). The
 * keyboard starts in it: the card takes the focus when it opens (unseen, and only then: Tab doesn't stop on it again),
 * so Tab goes on to its first button or field. Escape closes the dialog around it (Compose's `Dialog`).
 */
@Composable
internal fun DialogCard(
  maxWidth: Dp,
  header: @Composable RowScope.() -> Unit,
  body: @Composable ColumnScope.(available: Dp) -> Unit,
) {
  val p = LocalPalette.current
  val entry = remember { FocusRequester() }
  val entered = remember { Entered() }
  // The same modifier on every composition: a new one would ask again whether it may keep the focus
  val takesFocusOnce = remember {
    Modifier.focusRequester(entry)
      .onFocusChanged { if (it.isFocused) entered.yes = true }
      .focusProperties { canFocus = !entered.yes }
      .focusTarget()
  }
  LaunchedEffect(Unit) { entry.requestFocus() }
  BoxWithConstraints(Modifier.padding(16.dp)) {
    val available = this.maxWidth
    Column(
      Modifier.widthIn(max = maxWidth).fillMaxWidth().heightIn(max = maxHeight).card(p, RoundedCornerShape(20.dp))
        .then(takesFocusOnce),
    ) {
      Selectable(Modifier.fillMaxWidth()) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp), content = header,
        )
      }
      Divider()
      Selectable(Modifier.fillMaxWidth()) {
        Column { body(available) }
      }
    }
  }
}

/** Whether a dialog's card has had the focus it takes when it opens. */
private class Entered {
  var yes = false
}

/** A part of a dialog: its title, in capitals. */
@Composable
internal fun DialogSection(title: String) {
  Txt(title.uppercase(), size = 12.sp, color = LocalPalette.current.muted, weight = FontWeight.SemiBold,
    modifier = Modifier.padding(bottom = 8.dp))
}

/**
 * [left] and [right] side by side ([gap] apart), or [left] above [right] where there is no room for [two]; each keeps
 * a text selection to itself ([Selectable]).
 */
@Composable
internal fun TwoColumns(
  two: Boolean,
  gap: Dp,
  left: @Composable ColumnScope.() -> Unit,
  right: @Composable ColumnScope.() -> Unit,
) {
  if (two) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
      Selectable(Modifier.weight(1f)) { Column(content = left) }
      Selectable(Modifier.weight(1f)) { Column(content = right) }
    }
  } else {
    Selectable { Column(content = left) }
    Selectable { Column(content = right) }
  }
}

/**
 * Someone as a pill: their avatar, then [content] (their name, what they are), on [background] (with [border]); a
 * click opens their panel.
 */
@Composable
internal fun AvatarPill(
  name: String,
  background: Color,
  border: Color?,
  onClick: () -> Unit,
  content: @Composable RowScope.() -> Unit,
) {
  Row(
    Modifier.focusRing(CircleShape).clip(CircleShape).background(background)
      .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
      .hand().clickable(role = Role.Button, onClick = onClick).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Avatar(name, 22.dp)
    content()
  }
}
