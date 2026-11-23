package net.eratiem.randogeddon.webapp.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.AccountView
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CodeRequest
import net.eratiem.randogeddon.shared.RecoveryCodeList
import net.eratiem.randogeddon.shared.TotpSetup
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.manage.FIELD_HEIGHT
import net.eratiem.randogeddon.webapp.ui.Button
import net.eratiem.randogeddon.webapp.ui.CopyTextButton
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Mono
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.rememberAction

/**
 * Two-factor login: off, set it up (a QR code and the key, confirmed with a first code); on, new recovery codes or turn
 * it off, each with a code. Administrators can't turn it off (with single sign-on the provider has their second
 * factor, and the section isn't shown). The recovery codes are shown once, right after.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TwoFactorSection(a: AccountView, onChanged: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val acc = t.account
  var setup by remember { mutableStateOf<TotpSetup?>(null) }
  var code by remember { mutableStateOf("") }
  var codes by remember { mutableStateOf<List<String>?>(null) }
  var state by remember { mutableStateOf<String?>(null) }
  // One request at a time: a second click (or Enter) while one goes is dropped (a code works once)
  val action = rememberAction()
  fun request(path: String, onCodes: Boolean) = action.run {
    val answer = call("POST", path, body(CodeRequest(code.trim()))) { body ->
      if (onCodes) ApiJson.decodeFromString(RecoveryCodeList.serializer(), body).codes else emptyList()
    }
    when (answer) {
      is Answer.Ok -> {
        code = ""
        setup = null
        codes = answer.value.takeIf { onCodes }
        state = if (onCodes) null else acc.twoFactorIsOff
        onChanged()
      }
      is Answer.Failed -> state = answer.message
    }
    null
  }
  val subtitle = if (a.administrator) acc.twoFactorSubtitleAdministrator else acc.twoFactorSubtitle
  Section(acc.twoFactor, subtitle = subtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (a.administrator && !a.twoFactor) {
        Txt(acc.twoFactorAdministratorsNeedIt, size = 12.5.sp, color = p.trap)
      }
      codes?.let { list ->
        Txt(acc.recoveryCodes, size = 13.5.sp, weight = FontWeight.SemiBold)
        Txt(acc.recoveryCodesHint, size = 12.5.sp, color = p.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          list.forEach { Mono(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CopyTextButton({ list.joinToString("\n") })
          Button(acc.savedThem) { codes = null }
        }
        return@Column
      }
      val current = setup
      when {
        current != null -> {
          Txt(acc.scanThis, size = 12.5.sp)
          QrCode(current.rows, Modifier.size(200.dp))
          Mono(current.secret.chunked(4).joinToString(" "))
          CodeRow(code, { code = it; state = null }, acc.turnItOn, action.busy) { request("api/account/2fa/enable", onCodes = true) }
          SmallButton(t.common.cancel, { setup = null; code = "" })
        }
        !a.twoFactor -> {
          Txt(acc.off, size = 13.sp)
          Row {
            Button(acc.setItUp, enabled = !action.busy) {
              action.run {
                when (val r = call("POST", "api/account/2fa/setup", null, TotpSetup.serializer())) {
                  is Answer.Ok -> { setup = r.value; state = null }
                  is Answer.Failed -> state = r.message
                }
                null
              }
            }
          }
        }
        else -> {
          Txt(acc.on(a.recoveryCodesLeft), size = 13.sp, weight = FontWeight.SemiBold)
          Txt(acc.codeFor, size = 12.5.sp, color = p.muted)
          CodeRow(code, { code = it; state = null }, acc.newRecoveryCodes, action.busy) { request("api/account/2fa/codes", onCodes = true) }
          if (!a.administrator) Row { SmallButton(acc.turnItOff, { request("api/account/2fa/disable", onCodes = false) }) }
        }
      }
      state?.let { Txt(it, size = 12.5.sp, color = p.muted) }
    }
  }
}

/** A code field with its button (Enter in the field presses it); [busy]: a request goes, the button waits. */
@Composable
private fun CodeRow(code: String, onCode: (String) -> Unit, action: String, busy: Boolean, onAction: () -> Unit) {
  val ready = code.isNotBlank() && !busy
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterField(
      code, onCode, LocalTexts.current.account.code, Modifier.width(140.dp), minHeight = FIELD_HEIGHT,
      onSubmit = { if (ready) onAction() },
    )
    Button(action, enabled = ready, onClick = onAction)
  }
}

/** A QR code from its [rows] of `0`/`1`, black on white with a quiet zone (scanners need the contrast). */
@Composable
private fun QrCode(rows: List<String>, modifier: Modifier) {
  Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White)) {
    val n = rows.size + 8
    val cell = size.minDimension / n
    rows.forEachIndexed { y, row ->
      row.forEachIndexed { x, c ->
        if (c == '1') drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
      }
    }
  }
}
