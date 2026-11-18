package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import net.eratiem.randogeddon.webapp.app.LocalFonts
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.manage.FIELD_HEIGHT

/** Lists longer than this get a search field on top. */
private const val SEARCH_FROM = 10

/**
 * The search field on top of a dropdown's list: focused right away, except on a touch screen (its keyboard would
 * cover the list).
 */
@Composable
internal fun DropdownSearch(query: String, onChange: (String) -> Unit) {
  val focus = remember { FocusRequester() }
  FilterField(query, onChange, LocalTexts.current.common.search, Modifier.fillMaxWidth().focusRequester(focus))
  LaunchedEffect(Unit) { if (!Platform.touchScreen()) focus.requestFocus() }
}

/**
 * A compact dropdown: shows the selected option's label, lists all options in a popup (with a search field when
 * there are more than [SEARCH_FROM]). [minHeight]: e.g. a form's field height. [maxLabel]: how wide the selected
 * option's label gets before it is cut.
 */
@Composable
fun <T> Select(
  options: List<Pair<T, String>>,
  selected: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  minHeight: Dp = 0.dp,
  maxLabel: Dp = 160.dp,
) {
  val p = LocalPalette.current
  var open by remember { mutableStateOf(false) }
  var closedAt by remember { mutableLongStateOf(0L) }
  var query by remember { mutableStateOf("") }
  var anchorHeight by remember { mutableStateOf(0) }
  Box(modifier.onSizeChanged { anchorHeight = it.height }) {
    Row(
      // Shaped like the buttons and text fields
      Modifier.heightIn(min = minHeight).clip(RoundedCornerShape(10.dp)).background(p.panelAlt)
        .border(1.dp, p.border, RoundedCornerShape(10.dp))
        .hand().clickable { if (!justClosed(closedAt)) { open = !open; query = "" } }
        .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Txt(options.firstOrNull { it.first == selected }?.second ?: "", size = 12.5.sp, maxLines = 1,
        modifier = Modifier.widthIn(max = maxLabel))
      Spacer(Modifier.width(4.dp))
      DrawnIcon(Icon.CHEVRON, p.muted, 14.dp)
    }
    if (open) {
      val searchable = options.size > SEARCH_FROM
      val shown = options.filter { (_, label) -> !searchable || query.isBlank() || label.contains(query.trim(), ignoreCase = true) }
      AppPopup(
        offset = IntOffset(0, anchorHeight + 6),
        onDismissRequest = { open = false; closedAt = Platform.now() },
        properties = PopupProperties(focusable = true),
      ) {
        Column(
          Modifier.widthIn(min = 150.dp, max = 260.dp).card(p, RoundedCornerShape(12.dp)).padding(4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (searchable) DropdownSearch(query) { query = it }
          Column(Modifier.heightIn(max = 320.dp).scrolling()) {
            if (shown.isEmpty()) Txt(LocalTexts.current.common.nothingFound, size = 13.sp, color = p.muted, modifier = Modifier.padding(10.dp))
            shown.forEach { (value, label) ->
              val active = value == selected
              Txt(
                label, size = 13.sp, maxLines = 1,
                weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) p.accent else p.text,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                  .background(if (active) p.accent.copy(alpha = 0.1f) else Color.Transparent)
                  .hand().clickable { onSelect(value); open = false }
                  .padding(horizontal = 12.dp, vertical = 7.dp),
              )
            }
          }
        }
      }
    }
  }
}

/**
 * A dropdown with a second line per option, and a search field on top of its list when it has more than
 * [SEARCH_FROM]. [options]: value, label and a second line (e.g. the game, shown as a game chip too unless [chip] is
 * off); the search matches both. Long lists show the first 300 matches: the search finds the rest. With [heading]
 * (its text size) it is part of a heading: just the label and the chevron, no field around them, no second line.
 */
@Composable
fun <T> SearchableSelect(
  options: List<Triple<T, String, String>>,
  selected: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  chip: Boolean = true,
  placeholder: String = "",
  /** E.g. as tall as the buttons next to it. */
  minHeight: Dp = 0.dp,
  heading: TextUnit? = null,
  /** The label's colour with [heading] (e.g. white on the gradient); the text's otherwise. */
  headingColor: Color? = null,
) {
  val p = LocalPalette.current
  var open by remember { mutableStateOf(false) }
  var closedAt by remember { mutableLongStateOf(0L) }
  var query by remember { mutableStateOf("") }
  var anchorHeight by remember { mutableStateOf(0) }
  val current = options.firstOrNull { it.first == selected }
  Box(modifier.onSizeChanged { anchorHeight = it.height }) {
    if (heading != null) {
      Row(
        Modifier.heightIn(min = minHeight).clip(RoundedCornerShape(10.dp))
          .hand().clickable { if (!justClosed(closedAt)) { open = !open; query = "" } }
          .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val color = headingColor ?: p.text
        Txt(current?.second ?: placeholder, size = heading, weight = FontWeight.Bold, maxLines = 1,
          color = if (current == null) color.copy(alpha = 0.7f) else color, modifier = Modifier.weight(1f, fill = false))
        DrawnIcon(Icon.CHEVRON, headingColor?.copy(alpha = 0.85f) ?: p.muted, 16.dp)
      }
    } else Row(
      Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(10.dp)).background(p.panelAlt)
        .border(1.dp, p.border, RoundedCornerShape(10.dp))
        .hand().clickable { if (!justClosed(closedAt)) { open = !open; query = "" } }
        .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (current != null && chip) GameChip(current.third, size = 24.dp)
      Column(Modifier.weight(1f)) {
        Txt(current?.second ?: placeholder, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1,
          color = if (current == null) p.muted else p.text)
        if (current != null && current.third.isNotEmpty()) Txt(current.third, size = 11.5.sp, color = p.muted, maxLines = 1)
      }
      DrawnIcon(Icon.CHEVRON, p.muted, 14.dp)
    }
    if (open) {
      val searchable = options.size > SEARCH_FROM
      val shown = options.filter { (_, label, detail) ->
        !searchable || query.isBlank() || label.contains(query.trim(), ignoreCase = true) ||
          detail.contains(query.trim(), ignoreCase = true)
      }.take(300)
      AppPopup(
        offset = IntOffset(0, anchorHeight + 6),
        onDismissRequest = { open = false; closedAt = Platform.now() },
        properties = PopupProperties(focusable = true),
      ) {
        Column(
          Modifier.widthIn(min = 220.dp, max = 320.dp).card(p, RoundedCornerShape(12.dp)).padding(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (searchable) DropdownSearch(query) { query = it }
          Column(Modifier.heightIn(max = 280.dp).scrolling()) {
            if (shown.isEmpty()) Txt(LocalTexts.current.common.nothingFound, size = 13.sp, color = p.muted, modifier = Modifier.padding(10.dp))
            shown.forEach { (value, label, detail) ->
              val active = value == selected
              Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                  .background(if (active) p.accent.copy(alpha = 0.1f) else Color.Transparent)
                  .hand().clickable { onSelect(value); open = false }
                  .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                if (chip) GameChip(detail, size = 24.dp)
                Column {
                  Txt(label, size = 13.sp, maxLines = 2, weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) p.accent else p.text)
                  if (detail.isNotEmpty()) Txt(detail, size = 11.5.sp, color = p.muted, maxLines = 1)
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * A slider over the whole numbers of [range]: tap the track or drag the knob. Each number is a dot on the track while
 * there are few of them.
 */
@Composable
fun StepSlider(value: Int, range: IntRange, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
  val p = LocalPalette.current
  val change by rememberUpdatedState(onChange)
  val steps = (range.last - range.first).coerceAtLeast(1)
  val fraction = ((value - range.first).toFloat() / steps).coerceIn(0f, 1f)
  BoxWithConstraints(
    modifier.height(28.dp).hand().pointerInput(range.first, range.last) {
      val knob = KNOB.toPx() / 2
      fun pick(x: Float) {
        val f = ((x - knob) / (size.width - 2 * knob)).coerceIn(0f, 1f)
        change(range.first + (f * steps).roundToInt())
      }
      awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        pick(down.position.x)
        while (true) {
          val moved = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
          if (!moved.pressed) break
          if (moved.positionChanged()) {
            moved.consume()
            pick(moved.position.x)
          }
        }
      }
    },
    contentAlignment = Alignment.CenterStart,
  ) {
    val travel = maxWidth - KNOB
    Box(Modifier.padding(horizontal = KNOB / 2).fillMaxWidth().height(6.dp).clip(CircleShape).background(p.barBackground)) {
      Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape).background(p.brand))
    }
    if (steps <= 20) {
      for (i in 0..steps) {
        Box(
          Modifier.offset(x = KNOB / 2 - 2.dp + travel * (i.toFloat() / steps)).size(4.dp).clip(CircleShape)
            .background(if (i.toFloat() / steps <= fraction) p.onBrand.copy(alpha = 0.8f) else p.muted.copy(alpha = 0.6f))
        )
      }
    }
    Box(
      Modifier.offset(x = travel * fraction).size(KNOB).shadow(2.dp, CircleShape).clip(CircleShape)
        .background(p.onBrand).border(2.dp, p.accent, CircleShape)
    )
  }
}

private val KNOB = 20.dp

/** A one-line text field. [clearable] (a search): an × at its end empties it, while there is something in it. */
@Composable
fun FilterField(
  value: String,
  onChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier,
  minHeight: Dp = 0.dp,
  clearable: Boolean = false,
) {
  val p = LocalPalette.current
  BasicTextField(
    value = value,
    onValueChange = onChange,
    singleLine = true,
    textStyle = TextStyle(color = p.text, fontSize = 13.sp * LocalFonts.current.textScale, fontFamily = LocalFonts.current.text),
    cursorBrush = SolidColor(p.accent),
    modifier = modifier,
    decorationBox = { inner ->
      Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(10.dp)).background(p.panelAlt)
          .border(1.dp, p.border, RoundedCornerShape(10.dp)).padding(start = 10.dp, end = if (clearable) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.weight(1f).padding(vertical = 7.dp), contentAlignment = Alignment.CenterStart) {
          if (value.isEmpty()) Txt(placeholder, size = 13.sp, color = p.muted)
          inner()
        }
        if (clearable && value.isNotEmpty()) {
          Box(
            Modifier.size(22.dp).clip(CircleShape).hand().clickable { onChange("") },
            contentAlignment = Alignment.Center,
          ) { DrawnIcon(Icon.CLOSE, p.muted, 10.dp) }
        }
      }
    },
  )
}

/**
 * One of a few choices as buttons side by side (only one is chosen, filled): [options] are value, label and maybe an
 * icon before it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choice(options: List<Triple<T, String, Icon?>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
  val p = LocalPalette.current
  FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    options.forEach { (value, label, icon) ->
      val active = value == selected
      val shape = RoundedCornerShape(10.dp)
      Row(
        Modifier.heightIn(min = FIELD_HEIGHT).clip(shape).background(if (active) p.accent else p.panelAlt)
          .border(1.dp, if (active) p.accent else p.border, shape)
          .hand().clickable { if (!active) onSelect(value) }
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (icon != null) DrawnIcon(icon, if (active) p.onBrand else p.text, 14.dp)
        Txt(
          label, size = 12.5.sp, maxLines = 1, color = if (active) p.onBrand else p.text,
          weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
      }
    }
  }
}

/**
 * A field for a whole number in [range], with an arrow up and down inside it. Empty, it shows [placeholder] (the
 * number that applies without one of its own), which is also where the arrows start from.
 */
@Composable
fun NumberField(
  value: String,
  onChange: (String) -> Unit,
  placeholder: String,
  range: IntRange,
  modifier: Modifier = Modifier,
  minHeight: Dp = 0.dp,
) {
  val p = LocalPalette.current
  fun step(by: Int) {
    val from = value.trim().toIntOrNull() ?: placeholder.toIntOrNull()
    onChange((if (from == null) range.first else (from + by)).coerceIn(range).toString())
  }
  BasicTextField(
    value = value,
    onValueChange = { typed -> onChange(typed.filter(Char::isDigit).take(range.last.toString().length)) },
    singleLine = true,
    textStyle = TextStyle(color = p.text, fontSize = 13.sp * LocalFonts.current.textScale, fontFamily = LocalFonts.current.text),
    cursorBrush = SolidColor(p.accent),
    modifier = modifier,
    decorationBox = { inner ->
      Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(10.dp)).background(p.panelAlt)
          .border(1.dp, p.border, RoundedCornerShape(10.dp)).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.weight(1f).padding(vertical = 7.dp), contentAlignment = Alignment.CenterStart) {
          if (value.isEmpty()) Txt(placeholder, size = 13.sp, color = p.muted)
          inner()
        }
        Column {
          Box(Modifier.size(18.dp, 14.dp).rotate(180f).hand().clickable { step(1) }, contentAlignment = Alignment.Center) {
            DrawnIcon(Icon.CHEVRON, p.muted, 12.dp)
          }
          Box(Modifier.size(18.dp, 14.dp).hand().clickable { step(-1) }, contentAlignment = Alignment.Center) {
            DrawnIcon(Icon.CHEVRON, p.muted, 12.dp)
          }
        }
      }
    },
  )
}

/** Fades content of locked worlds. */
fun Modifier.dimmedIf(dimmed: Boolean): Modifier = if (dimmed) alpha(0.6f) else this
