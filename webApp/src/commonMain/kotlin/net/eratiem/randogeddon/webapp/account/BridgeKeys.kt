package net.eratiem.randogeddon.webapp.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.BridgeKeyView
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.date
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card

/** Randogeddon Bridge's keys (made under *Your connection*), each with *End*; only shown if there are any. */
@Composable
internal fun BridgeKeysSection() {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val acc = t.account
  val scope = rememberCoroutineScope()
  var keys by remember { mutableStateOf<List<BridgeKeyView>>(emptyList()) }
  var problem by remember { mutableStateOf<String?>(null) }
  suspend fun load() {
    when (val r = call("GET", "api/account/bridge-keys", null, ListSerializer(BridgeKeyView.serializer()))) {
      is Answer.Ok -> keys = r.value
      is Answer.Failed -> problem = r.message
    }
  }
  LaunchedEffect(Unit) { load() }
  if (keys.isEmpty() && problem == null) return
  Section(acc.bridgeKeys, subtitle = acc.bridgeKeysSubtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      keys.forEach { key ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt(
            acc.bridgeKey(t.date(key.createdAt), key.lastUsedAt?.let { t.date(it) }, t.date(key.expiresAt)),
            size = 13.sp, modifier = Modifier.weight(1f),
          )
          SmallButton(acc.endKey, {
            scope.launch {
              when (val r = call("DELETE", "api/account/bridge-keys/${key.id}", null) { }) {
                is Answer.Ok -> load()
                is Answer.Failed -> problem = r.message
              }
            }
          })
        }
      }
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    }
  }
}
