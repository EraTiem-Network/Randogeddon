package net.eratiem.randogeddon.webapp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.webapp.app.HEADING_FROM_SP
import net.eratiem.randogeddon.webapp.app.LocalFontChange
import net.eratiem.randogeddon.webapp.app.LocalFonts
import net.eratiem.randogeddon.webapp.app.inChange

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
  val fonts = LocalFonts.current
  // The large lines are headings: a display font shows only there
  val heading = size.value >= HEADING_FROM_SP
  // While the font changes, each letter is in the old or the new one
  LocalFontChange.current?.let { change ->
    BasicText(
      text = AnnotatedString(text).inChange(change, heading, size),
      modifier = modifier,
      style = TextStyle(
        color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.4f,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
      ).let { if (brush != null) it.merge(TextStyle(brush = brush)) else it },
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
    )
    return
  }
  BasicText(
    text = text,
    modifier = modifier,
    style = TextStyle(
      // A font with wide letters is drawn smaller, on the same line height
      color = color, fontSize = size * (if (heading) fonts.headingScale else fonts.textScale), fontWeight = weight, lineHeight = size * 1.4f,
      fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
      fontFamily = if (heading) fonts.heading else fonts.text,
    ).let { if (brush != null) it.merge(TextStyle(brush = brush)) else it },
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
fun Txt(text: AnnotatedString, modifier: Modifier = Modifier, size: TextUnit = 14.sp) {
  val change = LocalFontChange.current
  BasicText(
    text = if (change != null) text.inChange(change, heading = false, size) else text,
    modifier = modifier,
    style = TextStyle(
      color = LocalPalette.current.text, fontSize = size * LocalFonts.current.textScale, lineHeight = size * 1.4f,
      fontFamily = LocalFonts.current.text,
    ),
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
    Txt(name.take(1).uppercase(), size = (size.value * 0.42f).sp, color = LocalPalette.current.onBrand, weight = FontWeight.Bold)
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

/** Narrower than this (a phone): what sits side by side in a row goes one below the other. */
internal val NARROW = 600.dp

/**
 * Monospaced and selectable, so similar characters (l, 1, I) are told apart and can be copied by hand too: keys,
 * passwords, file names, a program's output. [color]: the text's colour if not given.
 */
@Composable
fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, size: TextUnit = 12.5.sp) {
  val shown = color.takeOrElse { LocalPalette.current.text }
  SelectionContainer(modifier) {
    BasicText(text, style = TextStyle(color = shown, fontSize = size, fontFamily = FontFamily.Monospace))
  }
}

/** A small heading inside a card: a part of it (the slot's files, the bridge's downloads, the uploaded apworlds). */
@Composable
fun SmallHeading(text: String, modifier: Modifier = Modifier) {
  Txt(text, size = 12.5.sp, weight = FontWeight.SemiBold, color = LocalPalette.current.muted, modifier = modifier)
}

/** Something works meanwhile: an arc going round, in [color]. */
@Composable
fun Working(color: Color, size: Dp = 12.dp) {
  val turn by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)))
  Canvas(Modifier.size(size).graphicsLayer { rotationZ = turn }) {
    val stroke = 2.dp.toPx()
    drawArc(color, 0f, 270f, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round),
      topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke))
  }
}
