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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small button with a drawn icon (the web build has no icon font). [label]: what it does, for assistive tools, and
 * its tip; inside a [Tip] it is that tip's text (and the tip is the only one). With a [label] and no [Tip] around it,
 * the tip wraps it: a `weight` or `align` in [modifier] then no longer reaches the row or column it stands in (put a
 * [Tip] around it yourself there).
 */
@Composable
fun IconButton(
  onClick: () -> Unit,
  icon: Icon,
  modifier: Modifier = Modifier,
  tint: Color? = null,
  background: Color? = null,
  label: String? = null,
) {
  val tipped = LocalTipLabel.current
  if (label != null && tipped == null) Tip(label) { IconButtonBox(onClick, icon, modifier, tint, background, label) }
  else IconButtonBox(onClick, icon, modifier, tint, background, label ?: tipped)
}

@Composable
private fun IconButtonBox(onClick: () -> Unit, icon: Icon, modifier: Modifier, tint: Color?, background: Color?, label: String?) {
  val p = LocalPalette.current
  val shape = RoundedCornerShape(10.dp)
  // 34 dp unless [modifier] gives a size; the icon is about half of it
  BoxWithConstraints(
    modifier.size(34.dp).focusRing(shape).clip(shape).background(background ?: p.panelAlt).hand()
      .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
      .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    DrawnIcon(icon, tint ?: p.text, minOf(16.dp, maxWidth * 0.47f))
  }
}

/** [THEME]: half light, half dark, the automatic mode; [SUN] light, [MOON] dark. */
enum class Icon { THEME, SUN, MOON, CLOSE, CHEVRON, LOCK, KEY, FLAG, FEED, BACK, LOGOUT, ADMIN, CHECK, USER, STAR, BULB, GIFT, LINK, CLOCK, HOURGLASS,
  RELEASE, PAUSE, CIRCLE, SEARCH, TRAP, SKULL, POWER, SERVER, SIGNPOST, RECEIVED, SENT, COPY, EYE, EYE_OFF, EXPAND, SHRINK, PIN }

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

      // A chain broken in two, its links pulled apart: released (its items set free)
      Icon.RELEASE -> rotate(45f, Offset(w * .5f, w * .5f)) {
        val line = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply {
          moveTo(w * .42f, w * .34f); lineTo(w * .2f, w * .34f)
          arcTo(Rect(w * .04f, w * .34f, w * .36f, w * .66f), 270f, -180f, false)
          lineTo(w * .42f, w * .66f)
        }, color, style = line)
        drawPath(Path().apply {
          moveTo(w * .58f, w * .34f); lineTo(w * .8f, w * .34f)
          arcTo(Rect(w * .64f, w * .34f, w * .96f, w * .66f), 270f, 180f, false)
          lineTo(w * .58f, w * .66f)
        }, color, style = line)
        // Where it broke
        drawLine(color, Offset(w * .5f, w * .2f), Offset(w * .5f, w * .12f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * .5f, w * .8f), Offset(w * .5f, w * .88f), stroke, StrokeCap.Round)
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

      // An arrow down into a tray: received
      Icon.RECEIVED -> {
        drawPath(Path().apply {
          moveTo(w * .15f, w * .6f); lineTo(w * .15f, w * .88f); lineTo(w * .85f, w * .88f); lineTo(w * .85f, w * .6f)
        }, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * .5f, w * .1f), Offset(w * .5f, w * .66f), stroke, StrokeCap.Round)
        drawPath(Path().apply { moveTo(w * .3f, w * .46f); lineTo(w * .5f, w * .66f); lineTo(w * .7f, w * .46f) },
          color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
      }

      // An arrow up out of an open box: sent (to the other slots)
      Icon.SENT -> {
        drawPath(Path().apply {
          moveTo(w * .3f, w * .45f); lineTo(w * .15f, w * .45f); lineTo(w * .15f, w * .88f); lineTo(w * .85f, w * .88f)
          lineTo(w * .85f, w * .45f); lineTo(w * .7f, w * .45f)
        }, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * .5f, w * .68f), Offset(w * .5f, w * .1f), stroke, StrokeCap.Round)
        drawPath(Path().apply { moveTo(w * .32f, w * .28f); lineTo(w * .5f, w * .1f); lineTo(w * .68f, w * .28f) },
          color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
      }

      // A pin: its head, its plate and its needle, leaning
      Icon.PIN -> {
        val line = Stroke(stroke * 1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply {
          moveTo(w * .56f, w * .1f); lineTo(w * .9f, w * .44f)       // the head's top
          moveTo(w * .64f, w * .18f); lineTo(w * .4f, w * .42f)      // its sides, down to the plate
          moveTo(w * .82f, w * .36f); lineTo(w * .58f, w * .6f)
          moveTo(w * .26f, w * .36f); lineTo(w * .64f, w * .74f)     // the plate
          moveTo(w * .45f, w * .55f); lineTo(w * .12f, w * .88f)     // the needle
        }, color, style = line)
      }

      // Four corners pulled apart (larger) or pushed together (smaller)
      Icon.EXPAND, Icon.SHRINK -> {
        val line = Stroke(stroke * 1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Each corner's bend: at the edge for larger, towards the middle for smaller
        val at = if (icon == Icon.EXPAND) .14f else .38f
        val arm = .24f
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
          val x = if (sx < 0) at else 1 - at
          val y = if (sy < 0) at else 1 - at
          // Its arms point inwards for larger (a frame's corner), outwards for smaller
          val turn = if (icon == Icon.EXPAND) 1f else -1f
          drawPath(Path().apply {
            moveTo(w * (x - sx * arm * turn), w * y); lineTo(w * x, w * y); lineTo(w * x, w * (y - sy * arm * turn))
          }, color, style = line)
        }
      }

      // Two sheets, one over the other: copy
      Icon.COPY -> {
        val line = Stroke(stroke, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(w * .32f, w * .32f), Size(w * .54f, w * .56f), CornerRadius(w * .08f), style = line)
        drawPath(Path().apply {
          moveTo(w * .2f, w * .68f); lineTo(w * .14f, w * .68f); lineTo(w * .14f, w * .12f); lineTo(w * .66f, w * .12f)
          lineTo(w * .66f, w * .2f)
        }, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
      }

      // An eye: show; struck through: hide
      Icon.EYE, Icon.EYE_OFF -> {
        drawPath(Path().apply {
          moveTo(w * .06f, w * .5f); quadraticTo(w * .5f, w * .08f, w * .94f, w * .5f); quadraticTo(w * .5f, w * .92f, w * .06f, w * .5f)
          close()
        }, color, style = Stroke(stroke, join = StrokeJoin.Round))
        drawCircle(color, radius = w * .13f, center = Offset(w * .5f, w * .5f))
        if (icon == Icon.EYE_OFF) drawLine(color, Offset(w * .14f, w * .86f), Offset(w * .86f, w * .14f), stroke * 1.2f, StrokeCap.Round)
      }

      // A signpost, its signs pointing both ways: the checks available now
      Icon.SIGNPOST -> {
        val line = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(w * .5f, w * .92f), Offset(w * .5f, w * .08f), stroke, StrokeCap.Round)
        drawPath(Path().apply {
          moveTo(w * .5f, w * .18f); lineTo(w * .82f, w * .18f); lineTo(w * .92f, w * .28f); lineTo(w * .82f, w * .38f); lineTo(w * .5f, w * .38f)
        }, color, style = line)
        drawPath(Path().apply {
          moveTo(w * .5f, w * .5f); lineTo(w * .18f, w * .5f); lineTo(w * .08f, w * .6f); lineTo(w * .18f, w * .7f); lineTo(w * .5f, w * .7f)
        }, color, style = line)
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
    modifier = modifier.focusRing(CircleShape).clip(CircleShape).background(p.accent.copy(alpha = 0.1f)).hand()
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}
