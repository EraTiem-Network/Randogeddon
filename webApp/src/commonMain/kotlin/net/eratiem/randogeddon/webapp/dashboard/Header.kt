package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.i18n.Texts
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.app.LocalFontChange
import net.eratiem.randogeddon.webapp.app.LocalFonts
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.inChange
import net.eratiem.randogeddon.webapp.ui.Dot
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.SearchableSelect
import net.eratiem.randogeddon.webapp.ui.Themes
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.hand

/** "Randogeddon" above the top card, centred, in the brand's gradient. [onHome]: a click goes to the main page. */
@Composable
internal fun Headline(onHome: (() -> Unit)? = null) {
  val p = LocalPalette.current
  Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
    val change = LocalFontChange.current
    val interaction = remember { MutableInteractionSource() }
    BasicText(
      // Its letters change with the page's when the font does
      if (change != null) AnnotatedString("RANDOGEDDON").inChange(change, heading = true, 34.sp) else AnnotatedString("RANDOGEDDON"),
      modifier = if (onHome != null) Modifier.hand().clickable(interaction, indication = null, onClick = onHome) else Modifier,
      style = TextStyle(
        brush = Brush.linearGradient(listOf(p.brandStart, p.brandEnd)),
        fontSize = 34.sp * LocalFonts.current.headingScale, fontWeight = FontWeight.Black, letterSpacing = 6.sp, textAlign = TextAlign.Center,
        fontFamily = LocalFonts.current.heading,
        shadow = Shadow(p.brandEnd.copy(alpha = if (p.dark) 0.55f else 0.3f), Offset(0f, 4f), blurRadius = 18f),
      ),
    )
  }
}

/**
 * The gradient card on top: the session (its ID, a switch to the user's others), seed, overall progress and the
 * connection state. [stacked] (a phone): the session, its info, the pill and buttons, and the bar each on their own
 * line.
 */
@Composable
internal fun Hero(
  summary: Summary?,
  streamOpen: Boolean,
  /** For the switch in the heading: the sessions, the one shown ([shown]; `null`: none), and the sessions' page. */
  sessions: List<SessionEntry>,
  shown: String?,
  onSession: (String) -> Unit,
  onAllSessions: () -> Unit,
  onToggleTheme: () -> Unit,
  feedVisible: Boolean,
  /** `null`: no feed on this page, no button. */
  onToggleFeed: (() -> Unit)?,
  onLogout: () -> Unit,
  /** The user's account; [settingsOpen] while it is shown. */
  onSettings: () -> Unit,
  settingsOpen: Boolean,
  /** The panel: the sessions the user manages (Administrators: every session), a new session. */
  onAdmin: (() -> Unit)?,
  adminOpen: Boolean,
  /** A bubble at the panel's button: a new session starts there. */
  newSessionHint: Boolean,
  stacked: Boolean,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // Light: on the full gradient; dark: on a dark card with the gradient's glow
  val onHero = if (p.dark) p.text else p.onBrand
  val live = streamOpen && summary?.connected == true
  // Light: the full gradient. Dark: a dark card with a gradient glow, so the page stays dark
  val heroBackground = if (p.dark) {
    Brush.linearGradient(listOf(p.brandStart.copy(alpha = 0.30f), p.brandEnd.copy(alpha = 0.34f)))
  } else {
    Brush.linearGradient(listOf(p.brandStart, p.brandEnd))
  }
  Column(
    Modifier.fillMaxWidth().card(p, RoundedCornerShape(20.dp), color = if (p.dark) p.panel else Color.Transparent)
      .background(heroBackground)
      .padding(horizontal = 20.dp, vertical = 18.dp)
  ) {
    val heading: @Composable (Modifier) -> Unit = { modifier ->
      Column(modifier) {
        if (sessions.isNotEmpty()) {
          // The session's ID is the heading and its switch; "All sessions" goes to the sessions' page
          SearchableSelect(
            listOf(Triple<String?, String, String>(null, t.frame.allSessions, "")) +
              sessions.map { Triple<String?, String, String>(it.id, it.id, sessionLine(t, it)) },
            shown, { id -> if (id == null) onAllSessions() else onSession(id) },
            chip = false, heading = 24.sp, headingColor = onHero, placeholder = t.frame.pickASession,
          )
        } else {
          Txt(shown ?: t.frame.noSession, size = 24.sp, weight = FontWeight.Bold, color = onHero)
        }
        val meta = listOfNotNull(
          summary?.seedName?.let { t.frame.seed(it) },
          summary?.serverVersion?.let { t.frame.archipelago(it) },
          summary?.let { t.frame.hintMode(it.hintSettings.pointHints, it.hintSettings.costPercent, it.hintSettings.autoHints) },
        ).ifEmpty {
          when {
            shown != null -> listOf(t.frame.connecting)
            // No session at all: the heading says it, nothing more
            sessions.isEmpty() -> emptyList()
            else -> listOf(t.frame.pickOneOfYourSessions)
          }
        }
        // A phone: one line each
        (if (stacked) meta else listOf(meta.joinToString("  ·  "))).forEach {
          Txt(it, size = 12.5.sp, color = onHero.copy(alpha = 0.85f))
        }
      }
    }
    val controls: @Composable () -> Unit = {
      // The game server's state, as tall as the buttons next to it: only with a session that has one (not finished)
      if (summary != null && summary.finishedAt == null) Row(
        Modifier.height(34.dp).clip(CircleShape).background(onHero.copy(alpha = 0.2f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Dot(if (live) p.liveDot else p.offlineDot)
        Txt(
          when {
            !streamOpen -> t.frame.reconnecting
            summary.connected -> t.frame.live
            else -> t.frame.serverOffline
          }, size = 12.5.sp, color = onHero, weight = FontWeight.SemiBold, maxLines = 1
        )
      }
      // Shows / hides the live feed; brighter while it is shown
      if (onToggleFeed != null) IconButton(onToggleFeed, Icon.FEED, tint = onHero,
        background = onHero.copy(alpha = if (feedVisible) 0.32f else 0.12f))
      // The mode it is in: light, dark, automatic
      val modeIcon = when (Themes.mode) {
        ThemeMode.LIGHT -> Icon.SUN
        ThemeMode.DARK -> Icon.MOON
        ThemeMode.AUTO -> Icon.THEME
      }
      IconButton(onToggleTheme, modeIcon, tint = onHero, background = onHero.copy(alpha = 0.2f))
      // Brighter while the admin panel is open
      if (onAdmin != null) Box {
        IconButton(onAdmin, Icon.ADMIN, tint = onHero, background = onHero.copy(alpha = if (adminOpen) 0.32f else 0.12f))
        // Below the card, pointing up at the button; a window of its own, so it lies over the page there
        if (newSessionHint) Popup(
          alignment = Alignment.TopCenter, offset = IntOffset(0, with(LocalDensity.current) { 56.dp.roundToPx() }),
        ) {
          DisableSelection { Bubble(t.frame.newSessionQuestion, onAdmin) }
        }
      }
      // The user's account; brighter while it is open
      IconButton(onSettings, Icon.USER, tint = onHero,
        background = onHero.copy(alpha = if (settingsOpen) 0.32f else 0.12f))
      IconButton(onLogout, Icon.LOGOUT, tint = onHero, background = onHero.copy(alpha = 0.2f))
    }
    if (stacked) {
      heading(Modifier)
      Spacer(Modifier.height(12.dp))
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
    } else {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        heading(Modifier.weight(1f))
        controls()
      }
    }
    if (summary != null) {
      val totals = summary.totals
      Spacer(Modifier.height(16.dp))
      Row(verticalAlignment = Alignment.Bottom) {
        Txt("${t.decimal(totals.percent)}%", size = 34.sp, weight = FontWeight.Bold, color = onHero)
        Txt(
          "  " + t.frame.ofChecksDone(t.number(totals.total)), size = 13.sp, color = onHero.copy(alpha = 0.85f),
          modifier = Modifier.padding(bottom = 7.dp),
        )
      }
      Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(onHero.copy(alpha = 0.25f))) {
        if (totals.percent > 0) {
          Box(
            Modifier.fillMaxWidth((totals.percent / 100).toFloat()).height(10.dp).clip(CircleShape)
              .background(if (p.dark) p.brand else SolidColor(onHero))
          )
        }
      }
    }
  }
}

/** A speech bubble pointing up at what is above it; a click does [onClick] (what the thing it points at does). */
@Composable
private fun Bubble(text: String, onClick: () -> Unit) {
  val p = LocalPalette.current
  Column(Modifier.hand().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
    Canvas(Modifier.size(16.dp, 8.dp)) {
      drawPath(
        Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() },
        p.accent,
      )
    }
    Txt(
      text, size = 13.sp, weight = FontWeight.SemiBold, color = p.onBrand, maxLines = 1,
      modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(p.accent).padding(horizontal = 12.dp, vertical = 7.dp),
    )
  }
}

/** A session in the hero's switch: how it stands, how far it got, the user's slots. */
private fun sessionLine(t: Texts, session: SessionEntry): String = listOfNotNull(
  t.sessions.status(session.status),
  "${t.decimal(session.percent)}%",
  session.mySlots.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString(" · ")

@Composable
internal fun Banner(text: String) {
  val p = LocalPalette.current
  Txt(
    text, size = 13.sp,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.warn.copy(alpha = 0.15f))
      .padding(horizontal = 14.dp, vertical = 10.dp),
  )
}
