package net.eratiem.archipelago.webapp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
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
  Box(
    modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(background ?: p.panelAlt).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    DrawnIcon(icon, tint ?: p.text, 16.dp)
  }
}

enum class Icon { THEME, CLOSE, CHEVRON, LOCK, KEY, FLAG, FEED, BACK }

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
    }
  }
}

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
      .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

/** A compact dropdown: shows the selected option's label, lists all options in a popup. */
@Composable
fun <T> Select(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
  val p = LocalPalette.current
  var open by remember { mutableStateOf(false) }
  var anchorHeight by remember { mutableStateOf(0) }
  Box(modifier.onSizeChanged { anchorHeight = it.height }) {
    Row(
      Modifier.clip(CircleShape).background(p.panelAlt).border(1.dp, p.border, CircleShape)
        .clickable { open = true }.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Txt(options.firstOrNull { it.first == selected }?.second ?: "", size = 12.5.sp, maxLines = 1,
        modifier = Modifier.widthIn(max = 160.dp))
      Spacer(Modifier.width(4.dp))
      DrawnIcon(Icon.CHEVRON, p.muted, 14.dp)
    }
    if (open) {
      Popup(
        offset = IntOffset(0, anchorHeight + 6),
        onDismissRequest = { open = false },
        properties = PopupProperties(focusable = true),
      ) {
        Column(
          Modifier.widthIn(min = 150.dp, max = 260.dp).heightIn(max = 320.dp)
            .card(p, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState()).padding(4.dp)
        ) {
          options.forEach { (value, label) ->
            val active = value == selected
            Txt(
              label, size = 13.sp, maxLines = 1,
              weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
              color = if (active) p.accent else p.text,
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (active) p.accent.copy(alpha = 0.1f) else Color.Transparent)
                .clickable { onSelect(value); open = false }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
fun FilterField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.panelAlt)
          .border(1.dp, p.border, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp)
      ) {
        if (value.isEmpty()) Txt(placeholder, size = 13.sp, color = p.muted)
        inner()
      }
    },
  )
}

/** Fades content of locked worlds. */
fun Modifier.dimmedIf(dimmed: Boolean): Modifier = if (dimmed) alpha(0.6f) else this
