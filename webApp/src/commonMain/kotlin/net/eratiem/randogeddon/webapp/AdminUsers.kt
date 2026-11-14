package net.eratiem.randogeddon.webapp

import net.eratiem.randogeddon.shared.i18n.text
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
  val texts = LocalTexts.current.users
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
  Section(texts.title, subtitle = texts.subtitle(list.size - invited, invited)) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      // Inviting someone
      var email by remember { mutableStateOf("") }
      var name by remember { mutableStateOf("") }
      var state by remember { mutableStateOf<String?>(null) }
      val nameProblem = name.trim().takeIf { it.isNotEmpty() }?.let(UserNames::problem)?.let { LocalTexts.current.problems.text(it) }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterField(email, { email = it; state = null }, texts.email, Modifier.width(240.dp), minHeight = FIELD_HEIGHT)
        FilterField(name, { name = it; state = null }, texts.suggestedName, Modifier.width(200.dp), minHeight = FIELD_HEIGHT)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Button(texts.invite, enabled = EmailAddresses.valid(EmailAddresses.normalize(email)) && nameProblem == null) {
            scope.launch {
              state = when (val r = invite(email, name)) {
                is Answer.Ok -> { email = ""; name = ""; load(); onChanged(); texts.invited(r.value.email.orEmpty()) }
                is Answer.Failed -> r.message
              }
            }
          }
          InfoTip(LocalTexts.current.tips.invite)
        }
      }
      (nameProblem ?: state)?.let { Txt(it, size = 12.5.sp, color = if (nameProblem != null) p.trap else p.muted) }
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
  val t = LocalTexts.current
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
          if (user.state == AccountState.INVITED) Pill(t.users.invitedPill, p.accent)
          if (user.administrator) Pill(t.users.administrator, p.ok)
        }
        val parts = listOfNotNull(
          user.email.takeIf { user.name != null },
          when (user.sessions.size) {
            0 -> t.users.noSession
            1 -> t.users.inSession(user.sessions.single())
            else -> t.users.inSessions(user.sessions.size)
          },
          user.invitationExpiresAt?.let {
            if (it < Platform.now()) t.users.linkExpired else t.users.linkWorksUntil(t.date(it))
          },
          user.deletesAt?.let { t.users.deletedOn(t.date(it)) }
            ?: user.autoDelete.takeIf { it != AutoDelete.NEVER && user.state == AccountState.ACTIVE }
              ?.let { t.users.autoDeletion(t.account.autoDelete(it)) },
        )
        Txt(parts.joinToString(" · "), size = 12.sp, color = p.muted, maxLines = 2)
      }
      if (user.state == AccountState.INVITED) {
        SmallButton(t.users.sendAgain, {
          scope.launch {
            state = (call("POST", "api/admin/users/${user.id}/invite", null) { } as? Answer.Failed)?.message
              ?: t.users.newLinkWent(user.email.orEmpty())
            onChanged()
          }
        })
        SmallButton(if (editing) t.common.cancel else t.users.otherAddress, { editing = !editing; address = user.email.orEmpty() })
      }
    }
    if (editing) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterField(address, { address = it }, t.users.email, Modifier.width(240.dp), minHeight = FIELD_HEIGHT)
        Button(t.users.sendThere, enabled = EmailAddresses.valid(EmailAddresses.normalize(address)) && address.trim() != user.email) {
          scope.launch {
            val body = ApiJson.encodeToString(EmailRequest.serializer(), EmailRequest(address.trim()))
            state = when (val r = call("PUT", "api/admin/users/${user.id}/email", body) { }) {
              is Answer.Ok -> { editing = false; onChanged(); t.users.linkWentTo(address.trim()) }
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
  val t = LocalTexts.current
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
  Section(t.users.settings, subtitle = t.users.settingsSubtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(t.users.sessionsPerUser, size = 13.sp, weight = FontWeight.SemiBold)
        InfoTip(t.users.sessionsPerUserTip)
        FilterField(perUser, { perUser = it; state = null }, "1", Modifier.width(70.dp), minHeight = FIELD_HEIGHT)
        Button(t.common.save, enabled = value != null && value != saved?.sessionsPerUser) {
          scope.launch {
            val settings = AppSettings(value!!)
            state = when (val r = call("PUT", "api/admin/settings", ApiJson.encodeToString(AppSettings.serializer(), settings)) { }) {
              is Answer.Ok -> { saved = settings; true to t.users.saved }
              is Answer.Failed -> false to r.message
            }
          }
        }
      }
      if (value == null && perUser.isNotBlank()) Txt(t.users.zeroToTwenty, size = 12.5.sp, color = p.trap)
      state?.let { (ok, text) -> Txt(text, size = 12.5.sp, color = if (ok) p.ok else p.trap) }
    }
  }
}
