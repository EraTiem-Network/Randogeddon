package net.eratiem.randogeddon.webapp.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.AccountView
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.EmailRequest
import net.eratiem.randogeddon.shared.NameRequest
import net.eratiem.randogeddon.shared.TokenRequest
import net.eratiem.randogeddon.shared.UserNames
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.manage.Button
import net.eratiem.randogeddon.webapp.manage.FIELD_HEIGHT
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card

/** Name, password, two-factor login and address: the account's own (not the provider's). */
@Composable
internal fun OwnAccount(a: AccountView, load: suspend () -> Unit, onRenamed: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val acc = t.account
  val scope = rememberCoroutineScope()

  // The name
  var name by remember(a.name) { mutableStateOf(a.name) }
  var nameState by remember { mutableStateOf<String?>(null) }
  Section(acc.name, subtitle = acc.nameSubtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      val nameProblem = UserNames.problem(name.trim())?.let { t.problems.text(it) }.takeIf { name != a.name }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterField(name, { name = it; nameState = null }, acc.yourName, Modifier.width(240.dp), minHeight = FIELD_HEIGHT)
        Button(t.common.save, enabled = name.trim() != a.name && nameProblem == null) {
          scope.launch {
            nameState = when (val r = call("PUT", "api/account/name", body(NameRequest(name.trim()))) { }) {
              is Answer.Ok -> { load(); onRenamed(); acc.nameSaved }
              is Answer.Failed -> r.message
            }
          }
        }
      }
      (nameProblem ?: nameState)?.let { Txt(it, size = 12.5.sp, color = if (nameProblem != null) p.trap else p.muted) }
      Tip(acc.idTip) {
        Txt(acc.id(a.id), size = 11.5.sp, color = p.muted)
      }
    }
  }

  // The password: index.html's form, so the password manager saves the new one
  var passwordState by remember { mutableStateOf<String?>(null) }
  Section(acc.password) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(acc.changePassword) {
          Platform.showPasswordChange(a.name) { changed -> if (changed) passwordState = acc.passwordChanged }
        }
      }
      passwordState?.let { Txt(it, size = 12.5.sp, color = p.muted) }
    }
  }

  TwoFactorSection(a, onChanged = { scope.launch { load() } })

  // The e-mail address
  var email by remember { mutableStateOf("") }
  var emailState by remember { mutableStateOf<String?>(null) }
  Section(acc.email, subtitle = acc.emailSubtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Txt(a.email ?: acc.noAddress, size = 13.5.sp, weight = FontWeight.SemiBold, color = if (a.email == null) p.muted else p.text)
      a.pendingEmail?.let { Txt(acc.waitingForConfirmation(it), size = 12.5.sp, color = p.muted) }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterField(email, { email = it; emailState = null }, acc.newAddress, Modifier.width(240.dp), minHeight = FIELD_HEIGHT)
        Button(acc.sendConfirmation, enabled = EmailAddresses.valid(EmailAddresses.normalize(email))) {
          scope.launch {
            emailState = when (val r = call("POST", "api/account/email", body(EmailRequest(email.trim()))) { }) {
              is Answer.Ok -> { load(); email = ""; acc.confirmationSent }
              is Answer.Failed -> r.message
            }
          }
        }
      }
      emailState?.let { Txt(it, size = 12.5.sp, color = p.muted) }
    }
  }
}

/** The link of the mail confirming a new address: confirms it once the user is logged in, says how it went. */
@Composable
fun ConfirmEmailView(token: String, onSettings: () -> Unit) {
  val p = LocalPalette.current
  val acc = LocalTexts.current.account
  var result by remember { mutableStateOf<String?>(null) }
  var ok by remember { mutableStateOf(false) }
  LaunchedEffect(token) {
    when (val r = call("POST", "api/email/confirm", body(TokenRequest(token)), EmailRequest.serializer())) {
      is Answer.Ok -> { ok = true; result = acc.addressIsNow(r.value.email) }
      is Answer.Failed -> result = r.message
    }
  }
  Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Txt(result ?: acc.confirmingAddress, color = if (ok || result == null) p.text else p.trap)
    if (result != null) Row { SmallButton(acc.yourAccount, onSettings) }
  }
}
