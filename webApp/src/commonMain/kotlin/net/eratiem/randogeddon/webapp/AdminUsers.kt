package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.AccountState
import net.eratiem.randogeddon.shared.AdminUser
import net.eratiem.randogeddon.shared.AppSettings
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.EmailRequest
import net.eratiem.randogeddon.shared.InviteRequest
import net.eratiem.randogeddon.shared.UserNames
import net.eratiem.randogeddon.shared.UserRef

/** Invites [email] (with a [suggestedName] for the sign-up page); the new account, or why not. */
internal suspend fun invite(email: String, suggestedName: String? = null): Answer<AdminUser> = call(
  "POST", "api/admin/users",
  ApiJson.encodeToString(InviteRequest.serializer(), InviteRequest(email.trim(), suggestedName?.trim()?.takeIf { it.isNotEmpty() })),
) { ApiJson.decodeFromString(AdminUser.serializer(), it) }

/**
 * Invites [email] to play in a new session (anyone creating one): only an address without an account; the account as a
 * choice of the form, or why not.
 */
internal suspend fun inviteForSession(email: String): Answer<UserRef> = call(
  "POST", "api/sessions/new/invite", ApiJson.encodeToString(InviteRequest.serializer(), InviteRequest(email.trim(), null)),
) { ApiJson.decodeFromString(UserRef.serializer(), it) }

/**
 * The accounts: who signed up, who is invited (and until when the link works); invite someone by e-mail, send an
 * invited one's link again or to another address. [onChanged]: the new-session form gets the new accounts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsersAdmin(onChanged: () -> Unit) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var users by remember { mutableStateOf<List<AdminUser>?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  suspend fun load() {
    when (val r = call("GET", "api/admin/users", null) { ApiJson.decodeFromString(ListSerializer(AdminUser.serializer()), it) }) {
      is Answer.Ok -> { users = r.value; problem = null }
      is Answer.Failed -> problem = r.message
    }
  }
  LaunchedEffect(Unit) { load() }
  val list = users.orEmpty()
  val invited = list.count { it.state == AccountState.INVITED }
  Section("Users", subtitle = "${list.size - invited} accounts" + if (invited > 0) ", $invited invited" else "") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      // Inviting someone
      var email by remember { mutableStateOf("") }
      var name by remember { mutableStateOf("") }
      var state by remember { mutableStateOf<String?>(null) }
      val nameProblem = name.trim().takeIf { it.isNotEmpty() }?.let(UserNames::problem)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterField(email, { email = it; state = null }, "E-mail address", Modifier.width(240.dp), minHeight = FIELD_HEIGHT)
        FilterField(name, { name = it; state = null }, "Their name (a suggestion)", Modifier.width(200.dp), minHeight = FIELD_HEIGHT)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Button("Invite", enabled = EmailAddresses.valid(EmailAddresses.normalize(email)) && nameProblem == null) {
            scope.launch {
              state = when (val r = invite(email, name)) {
                is Answer.Ok -> { email = ""; name = ""; load(); onChanged(); "Invited ${r.value.email}: the link works for 7 days." }
                is Answer.Failed -> r.message
              }
            }
          }
          InfoTip(Tips.INVITE)
        }
      }
      (nameProblem?.let { "Name: $it" } ?: state)?.let { Txt(it, size = 12.5.sp, color = if (nameProblem != null) p.trap else p.muted) }
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      list.forEach { user ->
        Divider()
        UserRow(user, onChanged = { scope.launch { load(); onChanged() } })
      }
    }
  }
}

/** One account: name (UUID as a tip) or the invited address, its state, sessions and deletion; for an invited one its link. */
@Composable
private fun UserRow(user: AdminUser, onChanged: () -> Unit) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var editing by remember { mutableStateOf(false) }
  var address by remember { mutableStateOf(user.email.orEmpty()) }
  var state by remember { mutableStateOf<String?>(null) }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Avatar(user.name ?: user.email.orEmpty(), 28.dp)
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PersonName(user.name ?: user.email.orEmpty(), user.id, deleted = false, size = 13.5.sp)
          if (user.state == AccountState.INVITED) Pill("invited", p.accent)
          if (user.administrator) Pill("Administrator", p.ok)
        }
        val parts = listOfNotNull(
          user.email.takeIf { user.name != null },
          when (user.sessions.size) {
            0 -> "no session"
            1 -> "in ${user.sessions.single()}"
            else -> "in ${user.sessions.size} sessions"
          },
          user.invitationExpiresAt?.let {
            if (it < Platform.now()) "the link has expired" else "the link works until ${Platform.date(it)}"
          },
          user.deletesAt?.let { "deleted on ${Platform.date(it)}" }
            ?: user.autoDelete.takeIf { it != AutoDelete.NEVER && user.state == AccountState.ACTIVE }?.let { "auto-deletion: ${it.label}" },
        )
        Txt(parts.joinToString(" · "), size = 12.sp, color = p.muted, maxLines = 2)
      }
      if (user.state == AccountState.INVITED) {
        SmallButton("Send again", {
          scope.launch {
            state = (call("POST", "api/admin/users/${user.id}/invite", null) { } as? Answer.Failed)?.message
              ?: "A new link went to ${user.email}; the old one stops working."
            onChanged()
          }
        })
        SmallButton(if (editing) "Cancel" else "Other address", { editing = !editing; address = user.email.orEmpty() })
      }
    }
    if (editing) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterField(address, { address = it }, "E-mail address", Modifier.width(240.dp), minHeight = FIELD_HEIGHT)
        Button("Send the link there", enabled = EmailAddresses.valid(EmailAddresses.normalize(address)) && address.trim() != user.email) {
          scope.launch {
            val body = ApiJson.encodeToString(EmailRequest.serializer(), EmailRequest(address.trim()))
            state = when (val r = call("PUT", "api/admin/users/${user.id}/email", body) { }) {
              is Answer.Ok -> { editing = false; onChanged(); "A new link went to ${address.trim()}." }
              is Answer.Failed -> r.message
            }
          }
        }
      }
    }
    state?.let { Txt(it, size = 12.sp, color = p.muted) }
  }
}

/** What Administrators set for everyone: how many running sessions one user may manage at once (0: none). */
@Composable
fun AppSettingsSection() {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var saved by remember { mutableStateOf<AppSettings?>(null) }
  var perUser by remember { mutableStateOf("") }
  var state by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
  LaunchedEffect(Unit) {
    when (val r = call("GET", "api/admin/settings", null) { ApiJson.decodeFromString(AppSettings.serializer(), it) }) {
      is Answer.Ok -> { saved = r.value; perUser = r.value.sessionsPerUser.toString() }
      is Answer.Failed -> state = false to r.message
    }
  }
  val value = perUser.trim().toIntOrNull()?.takeIf { it in 0..20 }
  Section("Settings", subtitle = "for everyone") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Sessions per user", size = 13.sp, weight = FontWeight.SemiBold)
        InfoTip(
          "How many running sessions one user may create and manage at once (0 to 20; 0: only Administrators create " +
            "sessions). Administrators have no limit; the game servers' number applies to everyone."
        )
        FilterField(perUser, { perUser = it; state = null }, "1", Modifier.width(70.dp), minHeight = FIELD_HEIGHT)
        Button("Save", enabled = value != null && value != saved?.sessionsPerUser) {
          scope.launch {
            val settings = AppSettings(value!!)
            state = when (val r = call("PUT", "api/admin/settings", ApiJson.encodeToString(AppSettings.serializer(), settings)) { }) {
              is Answer.Ok -> { saved = settings; true to "Saved." }
              is Answer.Failed -> false to r.message
            }
          }
        }
      }
      if (value == null && perUser.isNotBlank()) Txt("0 to 20", size = 12.5.sp, color = p.trap)
      state?.let { (ok, text) -> Txt(text, size = 12.5.sp, color = if (ok) p.ok else p.trap) }
    }
  }
}
