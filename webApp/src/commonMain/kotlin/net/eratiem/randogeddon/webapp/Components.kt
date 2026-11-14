package net.eratiem.randogeddon.webapp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

val CardShape = RoundedCornerShape(16.dp)

/** Plain text in the palette's colours; the dashboard's only text primitive. */
@Composable
fun Txt(
  text: String,
  modifier: Modifier = Modifier,
  size: TextUnit = 14.sp,
  color: Color = LocalPalette.current.text,
  weight: FontWeight = FontWeight.Normal,
  italic: Boolean = false,
  maxLines: Int = Int.MAX_VALUE,
  brush: Brush? = null,
) {
  BasicText(
    text = text,
    modifier = modifier,
    style = TextStyle(
      color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.4f,
      fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    ).let { if (brush != null) it.merge(TextStyle(brush = brush)) else it },
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
fun Txt(text: AnnotatedString, modifier: Modifier = Modifier, size: TextUnit = 14.sp) {
  BasicText(
    text = text,
    modifier = modifier,
    style = TextStyle(color = LocalPalette.current.text, fontSize = size, lineHeight = size * 1.4f),
  )
}

/** A soft, rounded surface: a light shadow on light backgrounds, a hairline border on dark ones. */
fun Modifier.card(palette: Palette, shape: RoundedCornerShape = CardShape, color: Color = palette.panel): Modifier =
  this
    .shadow(if (palette.dark) 0.dp else 6.dp, shape, ambientColor = palette.shadow, spotColor = palette.shadow)
    .clip(shape)
    .background(color)
    .border(1.dp, if (palette.dark) palette.border else palette.border.copy(alpha = 0.6f), shape)

/** A card with an optional header row (title, then [actions] on the right). */
@Composable
fun Panel(
  modifier: Modifier = Modifier,
  title: String? = null,
  subtitle: String? = null,
  actions: @Composable RowScope.() -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  val p = LocalPalette.current
  Column(modifier.card(p)) {
    if (title != null) {
      Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Column(Modifier.weight(1f)) {
          Txt(title, size = 15.sp, weight = FontWeight.SemiBold)
          if (subtitle != null) Txt(subtitle, size = 12.sp, color = p.muted, maxLines = 1)
        }
        actions()
      }
      Divider()
    }
    content()
  }
}

/**
 * A titled block of the page: the heading (title, subtitle, [actions] on the right) above its content, which brings
 * its own card. With [fillHeight] the content gets the height that is left (for blocks of a given height).
 */
@Composable
fun Section(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  actions: @Composable RowScope.() -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  val p = LocalPalette.current
  Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
      Modifier.heightIn(min = 34.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Column(Modifier.weight(1f)) {
        Txt(title, size = 17.sp, weight = FontWeight.Bold)
        if (subtitle != null) Txt(subtitle, size = 12.sp, color = p.muted, maxLines = 1)
      }
      actions()
    }
    content()
  }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.border.copy(alpha = 0.7f)))
}

/**
 * A progress bar with the brand gradient; green once [fraction] reaches 1, grey while [dimmed] (locked worlds).
 * Animates between values.
 */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, dimmed: Boolean = false) {
  val p = LocalPalette.current
  val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700))
  Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(p.barBackground)) {
    if (animated > 0f) {
      val fill = when {
        dimmed -> SolidColor(p.locked)
        fraction >= 1f -> SolidColor(p.ok)
        else -> p.brand
      }
      Box(Modifier.fillMaxWidth(animated).height(height).clip(CircleShape).background(fill))
    }
  }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
  Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** A tinted pill with an optional leading icon. */
@Composable
fun Pill(text: String, color: Color, icon: Icon? = null) {
  Row(
    Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 9.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (icon != null) DrawnIcon(icon, color, 11.dp)
    Txt(text, size = 11.5.sp, color = color, weight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** A round avatar with the initial of [name], in the name's colour. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
  val p = LocalPalette.current
  val hue = p.hue(name)
  Box(
    Modifier.size(size).clip(CircleShape)
      .background(Brush.linearGradient(listOf(hue, hue.copy(alpha = 0.7f)))),
    contentAlignment = Alignment.Center,
  ) {
    Txt(name.take(1).uppercase(), size = (size.value * 0.42f).sp, color = Color.White, weight = FontWeight.Bold)
  }
}

/** A rounded square with the initials of [game], e.g. "HK" for Hollow Knight. */
@Composable
fun GameChip(game: String, dimmed: Boolean = false, size: Dp = 34.dp) {
  val p = LocalPalette.current
  val hue = if (dimmed) p.locked else p.hue(game)
  val initials = game.split(' ', '-', ':').filter { it.isNotBlank() }.let { words ->
    if (words.size >= 2) "${words[0][0]}${words[1][0]}" else game.take(2)
  }.uppercase()
  Box(
    Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(hue.copy(alpha = 0.16f)),
    contentAlignment = Alignment.Center,
  ) {
    Txt(initials, size = 12.sp, color = hue, weight = FontWeight.Bold, maxLines = 1)
  }
}

/** A small button with a drawn icon (the web build has no icon font). */
@Composable
fun IconButton(onClick: () -> Unit, icon: Icon, modifier: Modifier = Modifier, tint: Color? = null, background: Color? = null) {
  val p = LocalPalette.current
  // 34 dp unless [modifier] gives a size; the icon is about half of it
  BoxWithConstraints(
    modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(background ?: p.panelAlt).hand().clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    DrawnIcon(icon, tint ?: p.text, minOf(16.dp, maxWidth * 0.47f))
  }
}

enum class Icon { THEME, CLOSE, CHEVRON, LOCK, KEY, FLAG, FEED, BACK, LOGOUT, ADMIN, CHECK, USER }

@Composable
fun DrawnIcon(icon: Icon, color: Color, size: Dp) {
  Canvas(Modifier.size(size)) {
    val w = this.size.width
    val stroke = w / 9
    when (icon) {
      Icon.THEME -> {
        drawCircle(color, radius = w / 2 - stroke, style = Stroke(stroke))
        drawArc(color, 90f, 180f, useCenter = true, topLeft = Offset(stroke, stroke), size = Size(w - 2 * stroke, w - 2 * stroke))
      }

      Icon.CLOSE -> {
        drawLine(color, Offset(w * .2f, w * .2f), Offset(w * .8f, w * .8f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * .8f, w * .2f), Offset(w * .2f, w * .8f), stroke, StrokeCap.Round)
      }

      Icon.CHECK -> drawPath(
        Path().apply { moveTo(w * .18f, w * .52f); lineTo(w * .42f, w * .76f); lineTo(w * .84f, w * .28f) },
        color, style = Stroke(stroke * 1.4f, cap = StrokeCap.Round),
      )

      Icon.CHEVRON -> drawPath(
        Path().apply { moveTo(w * .2f, w * .38f); lineTo(w * .5f, w * .68f); lineTo(w * .8f, w * .38f) },
        color, style = Stroke(stroke, cap = StrokeCap.Round),
      )

      Icon.LOCK -> {
        drawArc(color, 180f, 180f, useCenter = false, topLeft = Offset(w * .28f, w * .1f), size = Size(w * .44f, w * .5f),
          style = Stroke(stroke * 1.1f))
        drawLine(color, Offset(w * .28f, w * .35f), Offset(w * .28f, w * .48f), stroke * 1.1f)
        drawLine(color, Offset(w * .72f, w * .35f), Offset(w * .72f, w * .48f), stroke * 1.1f)
        drawRoundRect(color, Offset(w * .15f, w * .45f), Size(w * .7f, w * .48f), CornerRadius(w * .1f))
      }

      Icon.KEY -> {
        drawCircle(color, radius = w * .2f, center = Offset(w * .3f, w * .5f), style = Stroke(stroke * 1.2f))
        drawLine(color, Offset(w * .5f, w * .5f), Offset(w * .92f, w * .5f), stroke * 1.2f, StrokeCap.Round)
        drawLine(color, Offset(w * .78f, w * .5f), Offset(w * .78f, w * .7f), stroke * 1.2f, StrokeCap.Round)
        drawLine(color, Offset(w * .9f, w * .5f), Offset(w * .9f, w * .66f), stroke * 1.2f, StrokeCap.Round)
      }

      Icon.BACK -> drawPath(
        Path().apply { moveTo(w * .62f, w * .2f); lineTo(w * .32f, w * .5f); lineTo(w * .62f, w * .8f) },
        color, style = Stroke(stroke, cap = StrokeCap.Round),
      )

      Icon.FEED -> listOf(.28f, .5f, .72f).forEachIndexed { i, y ->
        drawCircle(color, radius = stroke * .9f, center = Offset(w * .18f, w * y))
        drawLine(color, Offset(w * .36f, w * y), Offset(w * (if (i == 1) .7f else .88f), w * y), stroke, StrokeCap.Round)
      }

      Icon.FLAG -> {
        drawLine(color, Offset(w * .22f, w * .1f), Offset(w * .22f, w * .92f), stroke * 1.1f, StrokeCap.Round)
        drawPath(Path().apply {
          moveTo(w * .22f, w * .12f); lineTo(w * .85f, w * .3f); lineTo(w * .22f, w * .5f); close()
        }, color)
      }

      // A gear: a ring with eight teeth
      Icon.ADMIN -> {
        val center = Offset(w / 2, w / 2)
        drawCircle(color, radius = w * .26f, center = center, style = Stroke(stroke * 1.3f))
        repeat(8) { i ->
          val angle = i * PI / 4
          val dx = cos(angle).toFloat()
          val dy = sin(angle).toFloat()
          drawLine(
            color, Offset(center.x + dx * w * .30f, center.y + dy * w * .30f),
            Offset(center.x + dx * w * .44f, center.y + dy * w * .44f), stroke * 1.5f, StrokeCap.Butt,
          )
        }
      }

      // A head and shoulders
      Icon.USER -> {
        drawCircle(color, radius = w * .17f, center = Offset(w / 2, w * .33f), style = Stroke(stroke))
        drawArc(color, 200f, 140f, useCenter = false, topLeft = Offset(w * .16f, w * .58f), size = Size(w * .68f, w * .6f),
          style = Stroke(stroke, cap = StrokeCap.Round))
      }

      // A door frame open to the right, an arrow leaving through it
      Icon.LOGOUT -> {
        drawPath(Path().apply {
          moveTo(w * .5f, w * .15f); lineTo(w * .15f, w * .15f); lineTo(w * .15f, w * .85f); lineTo(w * .5f, w * .85f)
        }, color, style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(color, Offset(w * .4f, w * .5f), Offset(w * .88f, w * .5f), stroke, StrokeCap.Round)
        drawPath(Path().apply { moveTo(w * .7f, w * .32f); lineTo(w * .88f, w * .5f); lineTo(w * .7f, w * .68f) },
          color, style = Stroke(stroke, cap = StrokeCap.Round))
      }
    }
  }
}

/** A small text button, e.g. "Copy". */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val p = LocalPalette.current
  Txt(
    text, size = 12.sp, weight = FontWeight.SemiBold, color = p.accent, maxLines = 1,
    modifier = modifier.clip(CircleShape).background(p.accent.copy(alpha = 0.1f)).hand().clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}

/**
 * A small "i" next to an option or button: what it does, on hover, or on tap on a phone (tap again or anywhere else
 * to close). [options]: the choices of the option (name, what it means), each below a divider.
 */
@Composable
fun InfoTip(text: String, modifier: Modifier = Modifier, options: List<Pair<String, String>> = emptyList()) {
  val p = LocalPalette.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  var tapped by remember { mutableStateOf(false) }
  var closedAt by remember { mutableLongStateOf(0L) }
  Box(
    modifier.size(16.dp).clip(CircleShape).border(1.2.dp, if (hovered || tapped) p.accent else p.muted, CircleShape)
      .hoverable(interaction)
      .hand().clickable(interactionSource = interaction, indication = null) {
        if (!justClosed(closedAt)) tapped = !tapped
      },
    contentAlignment = Alignment.Center,
  ) {
    Txt("i", size = 10.sp, color = if (hovered || tapped) p.accent else p.muted, weight = FontWeight.Bold, maxLines = 1)
    if (hovered || tapped) {
      AppPopup(
        offset = IntOffset(0, with(LocalDensity.current) { 20.dp.roundToPx() }),
        onDismissRequest = { tapped = false; closedAt = Platform.now() },
      ) {
        TipCard(text, options)
      }
    }
  }
}

/** What a tip says: the text, then each choice (name, meaning) below a divider. */
@Composable
private fun TipCard(text: String, options: List<Pair<String, String>>) {
  val p = LocalPalette.current
  Column(
    Modifier.widthIn(max = 340.dp).card(p, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Txt(text, size = 12.sp)
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
private fun justClosed(closedAt: Long): Boolean = Platform.now() - closedAt < 400

/**
 * A tip shown on [content] itself (a button, a dropdown): once the pointer rests on it for a moment, below it. A click
 * hides it until the pointer comes back (a dropdown's list opens there). Touch screens have no hover: there a long
 * press shows it, instead of the click; a tap anywhere closes it.
 */
@Composable
fun Tip(text: String, options: List<Pair<String, String>> = emptyList(), content: @Composable () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  var shown by remember { mutableStateOf(false) }
  var clicked by remember { mutableStateOf(false) }
  // Shown by a long press
  var held by remember { mutableStateOf(false) }
  var height by remember { mutableStateOf(0) }
  LaunchedEffect(hovered, clicked) {
    if (hovered && !clicked) {
      delay(TIP_DELAY_MS)
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
    content()
    if (shown || held) {
      AppPopup(
        offset = IntOffset(0, height + with(LocalDensity.current) { 6.dp.roundToPx() }),
        onDismissRequest = { shown = false; held = false },
      ) {
        TipCard(text, options)
      }
    }
  }
}

/**
 * A popup (dropdown, tip) that stays out of the page's text selection: it is a window of its own, and selection
 * across windows can make the whole page hang.
 */
@Composable
fun AppPopup(
  offset: IntOffset,
  onDismissRequest: () -> Unit,
  properties: PopupProperties = PopupProperties(),
  content: @Composable () -> Unit,
) {
  Popup(offset = offset, onDismissRequest = onDismissRequest, properties = properties) { DisableSelection(content) }
}

/**
 * The hand pointer over something clickable, also over its text (which the page's selection would show the text
 * cursor for).
 */
fun Modifier.hand(): Modifier = pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true)

/** Narrower than this (a phone): what sits side by side in a row goes one below the other. */
internal val NARROW = 600.dp

/** A clickable row that tints itself while hovered. */
@Composable
fun HoverRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  val p = LocalPalette.current
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  Row(
    modifier.clip(RoundedCornerShape(12.dp))
      .background(if (hovered) p.panelAlt else Color.Transparent)
      .hoverable(interaction)
      .hand().clickable(interactionSource = interaction, indication = null, onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

/** Lists longer than this get a search field on top. */
private const val SEARCH_FROM = 10

/**
 * The search field on top of a dropdown's list: focused right away, except on a touch screen (its keyboard would
 * cover the list).
 */
@Composable
private fun DropdownSearch(query: String, onChange: (String) -> Unit) {
  val focus = remember { FocusRequester() }
  FilterField(query, onChange, LocalTexts.current.common.search, Modifier.fillMaxWidth().focusRequester(focus))
  LaunchedEffect(Unit) { if (!Platform.touchScreen()) focus.requestFocus() }
}

/**
 * A compact dropdown: shows the selected option's label, lists all options in a popup (with a search field when
 * there are more than [SEARCH_FROM]). [minHeight]: e.g. a form's field height.
 */
@Composable
fun <T> Select(
  options: List<Pair<T, String>>,
  selected: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  minHeight: Dp = 0.dp,
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
        modifier = Modifier.widthIn(max = 160.dp))
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
          Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
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
          Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
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
            .background(if (i.toFloat() / steps <= fraction) Color.White.copy(alpha = 0.8f) else p.muted.copy(alpha = 0.6f))
        )
      }
    }
    Box(
      Modifier.offset(x = travel * fraction).size(KNOB).shadow(2.dp, CircleShape).clip(CircleShape)
        .background(Color.White).border(2.dp, p.accent, CircleShape)
    )
  }
}

private val KNOB = 20.dp

@Composable
fun FilterField(
  value: String,
  onChange: (String) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier,
  minHeight: Dp = 0.dp,
) {
  val p = LocalPalette.current
  BasicTextField(
    value = value,
    onValueChange = onChange,
    singleLine = true,
    textStyle = TextStyle(color = p.text, fontSize = 13.sp),
    cursorBrush = SolidColor(p.accent),
    modifier = modifier,
    decorationBox = { inner ->
      Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(10.dp)).background(p.panelAlt)
          .border(1.dp, p.border, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        if (value.isEmpty()) Txt(placeholder, size = 13.sp, color = p.muted)
        inner()
      }
    },
  )
}

/** Fades content of locked worlds. */
fun Modifier.dimmedIf(dimmed: Boolean): Modifier = if (dimmed) alpha(0.6f) else this
