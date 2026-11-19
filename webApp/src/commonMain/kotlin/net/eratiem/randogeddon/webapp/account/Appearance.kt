package net.eratiem.randogeddon.webapp.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.shared.palettes
import net.eratiem.randogeddon.webapp.app.Fonts
import net.eratiem.randogeddon.webapp.app.LocalFonts
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.hand
import net.eratiem.randogeddon.webapp.ui.palette

/** One theme to pick: a small page in its colours (the brand, a panel with text, the item and state colours), its name. */
@Composable
internal fun ThemeCard(theme: Theme, name: String, chosen: Boolean, onPick: () -> Unit) {
  val p = LocalPalette.current
  val t = palettes(theme).let { (light, dark) -> if (p.dark) dark else light }.palette()
  val shape = RoundedCornerShape(12.dp)
  Column(
    Modifier.width(150.dp).clip(shape)
      .border(if (chosen) 2.dp else 1.dp, if (chosen) p.accent else p.border, shape)
      .hand().selectable(chosen, role = Role.RadioButton, onClick = onPick)
      .background(t.background),
  ) {
    Box(Modifier.fillMaxWidth().height(6.dp).background(t.brand))
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(t.panel)
          .border(1.dp, t.border, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
      ) {
        // In the theme's own font if that is loaded already (it isn't fetched for the card)
        BasicText("Aa", style = TextStyle(color = t.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Fonts.there(theme) ?: LocalFonts.current.text))
        Box(Modifier.padding(top = 3.dp).width(64.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.muted))
      }
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(t.progression, t.useful, t.trap, t.ok, t.warn, t.key).forEach { colour ->
          Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(colour))
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(name, size = 12.5.sp, color = t.text, weight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
          maxLines = 1, modifier = Modifier.weight(1f))
        if (chosen) DrawnIcon(Icon.CHECK, t.accent, 14.dp)
      }
    }
  }
}
