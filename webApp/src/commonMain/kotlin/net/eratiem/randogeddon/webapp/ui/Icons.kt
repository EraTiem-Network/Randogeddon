package net.eratiem.randogeddon.webapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

/** [THEME]: half light, half dark, the automatic mode; [SUN] light, [MOON] dark. */
enum class Icon { THEME, SUN, MOON, CLOSE, CHEVRON, LOCK, KEY, FLAG, FEED, BACK, LOGOUT, ADMIN, CHECK, USER, STAR, BULB, GIFT, LINK, CLOCK, HOURGLASS,
  RELEASE, PAUSE, CIRCLE, SEARCH, TRAP, SKULL, POWER, SERVER, TARGET }

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

      Icon.SUN -> {
        drawCircle(color, radius = w * .2f)
        for (i in 0 until 8) {
          val a = i * PI / 4
          val (dx, dy) = cos(a).toFloat() to sin(a).toFloat()
          drawLine(color, Offset(w * (.5f + dx * .32f), w * (.5f + dy * .32f)), Offset(w * (.5f + dx * .45f), w * (.5f + dy * .45f)),
            stroke, StrokeCap.Round)
        }
      }

      // A full moon with a smaller one taken out of its upper right
      Icon.MOON -> drawPath(
        Path.combine(
          PathOperation.Difference,
          Path().apply { addOval(Rect(Offset(w * .5f, w * .5f), w * .4f)) },
          Path().apply { addOval(Rect(Offset(w * .72f, w * .32f), w * .32f)) },
        ),
        color,
      )

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

      // A light bulb: hints
      Icon.BULB -> {
        drawCircle(color, radius = w * .26f, center = Offset(w * .5f, w * .38f), style = Stroke(stroke))
        drawLine(color, Offset(w * .38f, w * .74f), Offset(w * .62f, w * .74f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * .42f, w * .88f), Offset(w * .58f, w * .88f), stroke, StrokeCap.Round)
      }

      // A gift: items
      Icon.GIFT -> {
        drawRect(color, Offset(w * .16f, w * .42f), Size(w * .68f, w * .44f), style = Stroke(stroke))
        drawLine(color, Offset(w * .1f, w * .42f), Offset(w * .9f, w * .42f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * .5f, w * .28f), Offset(w * .5f, w * .86f), stroke)
        drawArc(color, 180f, 180f, useCenter = false, topLeft = Offset(w * .3f, w * .18f), size = Size(w * .2f, w * .2f), style = Stroke(stroke))
        drawArc(color, 180f, 180f, useCenter = false, topLeft = Offset(w * .5f, w * .18f), size = Size(w * .2f, w * .2f), style = Stroke(stroke))
      }

      // Two chain links: links between games
      Icon.LINK -> {
        drawRoundRect(color, Offset(w * .08f, w * .34f), Size(w * .5f, w * .32f), CornerRadius(w * .16f), style = Stroke(stroke))
        drawRoundRect(color, Offset(w * .42f, w * .34f), Size(w * .5f, w * .32f), CornerRadius(w * .16f), style = Stroke(stroke))
      }

      // A clock: play time
      Icon.CLOCK -> {
        drawCircle(color, radius = w * .4f, center = Offset(w / 2, w / 2), style = Stroke(stroke))
        drawLine(color, Offset(w * .5f, w * .5f), Offset(w * .5f, w * .26f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * .5f, w * .5f), Offset(w * .68f, w * .6f), stroke, StrokeCap.Round)
      }

      // An hourglass: the session's age
      Icon.HOURGLASS -> {
        drawPath(Path().apply {
          moveTo(w * .22f, w * .1f); lineTo(w * .78f, w * .1f); lineTo(w * .22f, w * .9f); lineTo(w * .78f, w * .9f); close()
        }, color, style = Stroke(stroke, join = StrokeJoin.Round))
      }

      // A five-pointed star: progression
      Icon.STAR -> drawPath(Path().apply { moveTo(w * 0.5f, w * 0.07f); lineTo(w * 0.618f, w * 0.368f); lineTo(w * 0.937f, w * 0.388f); lineTo(w * 0.69f, w * 0.592f); lineTo(w * 0.77f, w * 0.902f); lineTo(w * 0.5f, w * 0.73f); lineTo(w * 0.23f, w * 0.902f); lineTo(w * 0.31f, w * 0.592f); lineTo(w * 0.063f, w * 0.388f); lineTo(w * 0.382f, w * 0.368f); close() }, color)

      // An arrow up out of an open box: released
      Icon.RELEASE -> {
        drawPath(Path().apply {
          moveTo(w * .3f, w * .45f); lineTo(w * .15f, w * .45f); lineTo(w * .15f, w * .88f); lineTo(w * .85f, w * .88f)
          lineTo(w * .85f, w * .45f); lineTo(w * .7f, w * .45f)
        }, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * .5f, w * .68f), Offset(w * .5f, w * .1f), stroke, StrokeCap.Round)
        drawPath(Path().apply { moveTo(w * .32f, w * .28f); lineTo(w * .5f, w * .1f); lineTo(w * .68f, w * .28f) },
          color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
      }

      // Two bars: idle
      Icon.PAUSE -> {
        drawRoundRect(color, Offset(w * .24f, w * .18f), Size(w * .17f, w * .64f), CornerRadius(w * .05f))
        drawRoundRect(color, Offset(w * .59f, w * .18f), Size(w * .17f, w * .64f), CornerRadius(w * .05f))
      }

      // An empty ring: not started
      Icon.CIRCLE -> drawCircle(color, radius = w * .34f, center = Offset(w / 2, w / 2), style = Stroke(stroke * 1.2f))

      // A magnifying glass: not found yet
      Icon.SEARCH -> {
        drawCircle(color, radius = w * .26f, center = Offset(w * .42f, w * .42f), style = Stroke(stroke * 1.2f))
        drawLine(color, Offset(w * .62f, w * .62f), Offset(w * .88f, w * .88f), stroke * 1.4f, StrokeCap.Round)
      }

      // A warning triangle with its mark: traps
      Icon.TRAP -> {
        drawPath(Path().apply { moveTo(w * .5f, w * .1f); lineTo(w * .92f, w * .86f); lineTo(w * .08f, w * .86f); close() },
          color, style = Stroke(stroke, join = StrokeJoin.Round))
        drawLine(color, Offset(w * .5f, w * .4f), Offset(w * .5f, w * .6f), stroke, StrokeCap.Round)
        drawCircle(color, radius = stroke * .7f, center = Offset(w * .5f, w * .73f))
      }

      // A skull, its eyes cut out: deaths
      Icon.SKULL -> drawPath(
        Path.combine(
          PathOperation.Difference,
          Path().apply {
            addOval(Rect(Offset(w * .5f, w * .44f), w * .36f))
            addRoundRect(RoundRect(w * .3f, w * .6f, w * .7f, w * .9f, CornerRadius(w * .06f)))
          },
          Path().apply {
            addOval(Rect(Offset(w * .36f, w * .46f), w * .1f))
            addOval(Rect(Offset(w * .64f, w * .46f), w * .1f))
            addRect(Rect(w * .47f, w * .78f, w * .53f, w * .9f))
          },
        ),
        color,
      )

      // A power sign: the game server's state
      Icon.POWER -> {
        drawArc(color, -60f, 300f, useCenter = false, topLeft = Offset(w * .16f, w * .2f), size = Size(w * .68f, w * .68f),
          style = Stroke(stroke * 1.2f, cap = StrokeCap.Round))
        drawLine(color, Offset(w * .5f, w * .08f), Offset(w * .5f, w * .48f), stroke * 1.2f, StrokeCap.Round)
      }

      // A target, rings round a dot: the checks available now
      Icon.TARGET -> {
        drawCircle(color, radius = w * .38f, center = Offset(w / 2, w / 2), style = Stroke(stroke))
        drawCircle(color, radius = w * .2f, center = Offset(w / 2, w / 2), style = Stroke(stroke))
        drawCircle(color, radius = w * .07f, center = Offset(w / 2, w / 2))
      }

      // Two stacked server units: how long the game server ran
      Icon.SERVER -> listOf(.12f, .54f).forEach { y ->
        drawRoundRect(color, Offset(w * .12f, w * y), Size(w * .76f, w * .34f), CornerRadius(w * .08f), style = Stroke(stroke))
        drawCircle(color, radius = stroke * .8f, center = Offset(w * .3f, w * (y + .17f)))
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
