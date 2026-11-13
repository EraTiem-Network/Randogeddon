package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import kotlinx.serialization.encodeToString
import net.eratiem.randogeddon.shared.AccountView
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.AutoDeleteRequest
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.EmailRequest
import net.eratiem.randogeddon.shared.NameRequest
import net.eratiem.randogeddon.shared.TokenRequest
import net.eratiem.randogeddon.shared.UserNames

/**
 * The user's own account: their name (unique; a rename shows in the running sessions), their password (the form of
 * index.html, for the password manager), their e-mail address (a new one is confirmed by a link mailed to it) and when
 * the account deletes itself. [onRenamed]: the new name is everywhere else too.
 */
@Composable
fun SettingsView(onBack: () -> Unit, onRenamed: () -> Unit) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var account by remember { mutableStateOf<AccountView?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  suspend fun load() {
    when (val answer = call("GET", "api/account", null) { ApiJson.decodeFromString(AccountView.serializer(), it) }) {
      is Answer.Ok -> { account = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  LaunchedEffect(Unit) { load() }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      IconButton(onBack, Icon.BACK)
      Txt("Your account", size = 20.sp, weight = FontWeight.Bold)
    }
    problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    val a = account ?: return@Column

    // The name
    var name by remember(a.name) { mutableStateOf(a.name) }
    var nameState by remember { mutableStateOf<String?>(null) }
    Section("Name", subtitle = "what the others see; you log in with it or with your e-mail address") {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val nameProblem = UserNames.problem(name.trim()).takeIf { name != a.name }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterField(name, { name = it; nameState = null }, "Your name", Modifier.width(240.dp), minHeight = FIELD_HEIGHT)
          Button("Save", enabled = name.trim() != a.name && nameProblem == null) {
            scope.launch {
              nameState = when (val r = call("PUT", "api/account/name", body(NameRequest(name.trim()))) { }) {
                is Answer.Ok -> { load(); onRenamed(); "Saved. The running sessions show it; finished ones keep the old name." }
                is Answer.Failed -> r.message
              }
            }
          }
        }
        (nameProblem ?: nameState)?.let { Txt(it, size = 12.5.sp, color = if (nameProblem != null) p.trap else p.muted) }
        Tip("Your account's ID: it tells you apart from someone who had your name before") {
          Txt("ID ${a.id}", size = 11.5.sp, color = p.muted)
        }
      }
    }

    // The password: index.html's form, so the password manager saves the new one
    var passwordState by remember { mutableStateOf<String?>(null) }
    Section("Password") {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button("Change password") {
            Platform.showPasswordChange(a.name) { changed -> if (changed) passwordState = "Changed. Your other logins have ended." }
          }
        }
        passwordState?.let { Txt(it, size = 12.5.sp, color = p.muted) }
      }
    }

    // The e-mail address
    var email by remember { mutableStateOf("") }
    var emailState by remember { mutableStateOf<String?>(null) }
    Section("E-mail address", subtitle = "for the mails about your account; you can log in with it too") {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(a.email ?: "No address yet", size = 13.5.sp, weight = FontWeight.SemiBold, color = if (a.email == null) p.muted else p.text)
        a.pendingEmail?.let { Txt("Waiting for the confirmation of $it: open the link in the mail sent there.", size = 12.5.sp, color = p.muted) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterField(email, { email = it; emailState = null }, "New address", Modifier.width(240.dp), minHeight = FIELD_HEIGHT)
          Button("Send confirmation", enabled = EmailAddresses.valid(EmailAddresses.normalize(email))) {
            scope.launch {
              emailState = when (val r = call("POST", "api/account/email", body(EmailRequest(email.trim()))) { }) {
                is Answer.Ok -> { load(); email = ""; "A link went to the new address; your address changes when you open it." }
                is Answer.Failed -> r.message
              }
            }
          }
        }
        emailState?.let { Txt(it, size = 12.5.sp, color = p.muted) }
      }
    }

    // When the account deletes itself
    var deletionState by remember { mutableStateOf<String?>(null) }
    Section("Deleting your account", subtitle = "by itself, after your last session; your sessions keep your name") {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt("Delete it", size = 13.sp)
          Select(
            AutoDelete.entries.map { it to if (it == AutoDelete.NEVER) "never" else "${it.label} after my last session" },
            a.autoDelete,
            { choice ->
              scope.launch {
                deletionState = when (val r = call("PUT", "api/account/auto-delete", body(AutoDeleteRequest(choice))) { }) {
                  is Answer.Ok -> { load(); null }
                  is Answer.Failed -> r.message
                }
              }
            },
            Modifier.width(260.dp), minHeight = FIELD_HEIGHT,
          )
        }
        val due = a.deletesAt
        Txt(
          when {
            a.autoDelete == AutoDelete.NEVER -> "Your account stays until you change this."
            due == null -> "Not while one of your sessions runs."
            else -> "As of now on ${Platform.date(due)}; a mail reminds you 14 days before, and a new session moves it later."
          },
          size = 12.5.sp, color = p.muted,
        )
        deletionState?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      }
    }
  }
}

/** The link of the mail confirming a new address: confirms it once the user is logged in, says how it went. */
@Composable
fun ConfirmEmailView(token: String, onSettings: () -> Unit) {
  val p = LocalPalette.current
  var result by remember { mutableStateOf<String?>(null) }
  var ok by remember { mutableStateOf(false) }
  LaunchedEffect(token) {
    when (val r = call("POST", "api/email/confirm", body(TokenRequest(token))) { ApiJson.decodeFromString(EmailRequest.serializer(), it) }) {
      is Answer.Ok -> { ok = true; result = "Your address is ${r.value.email} now." }
      is Answer.Failed -> result = r.message
    }
  }
  Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Txt(result ?: "Confirming your new address…", color = if (ok || result == null) p.text else p.trap)
    if (result != null) Row { SmallButton("Your account", onSettings) }
  }
}

private inline fun <reified T> body(value: T): String = ApiJson.encodeToString(value)
