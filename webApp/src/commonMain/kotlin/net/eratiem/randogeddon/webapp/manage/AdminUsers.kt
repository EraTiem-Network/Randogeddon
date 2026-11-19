package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.AccountActionRequest
import net.eratiem.randogeddon.shared.AccountState
import net.eratiem.randogeddon.shared.AdminUser
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AppSettings
import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.EmailRequest
import net.eratiem.randogeddon.shared.InviteRequest
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.RandogeddonVersion
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.SessionLimitRequest
import net.eratiem.randogeddon.shared.TimeOut
import net.eratiem.randogeddon.shared.UserNames
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.Versions
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.date
import net.eratiem.randogeddon.webapp.ui.Avatar
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.NumberField
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Select
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.hand
import net.eratiem.randogeddon.webapp.ui.scrolling

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
fun UsersAdmin(onChanged: () -> Unit, onSession: (String) -> Unit) {
  val p = LocalPalette.current
  val texts = LocalTexts.current.users
  val scope = rememberCoroutineScope()
  var users by remember { mutableStateOf<List<AdminUser>?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  // The default of the sessions per user; null until it is loaded
  var default by remember { mutableStateOf<Int?>(null) }
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
      // The limit of running sessions for accounts without a number of their own
      Divider()
      SessionsDefault { default = it }
      // One pill per account, in a box that scrolls once there are many; a click shows the account floating over it
      var opened by remember { mutableStateOf<String?>(null) }
      if (list.isNotEmpty()) {
        Divider()
        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).scrolling()) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { user -> UserPill(user) { opened = user.id } }
          }
        }
      }
      list.firstOrNull { it.id == opened }?.let { user ->
        UserPanel(user, default, onChanged = { scope.launch { load(); onChanged() } }, onSession = onSession, onClose = { opened = null })
      }
    }
  }
}

/** An account as a pill: its avatar and name (the address of an invited one), marked if invited or an Administrator. */
@Composable
private fun UserPill(user: AdminUser, onOpen: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current.users
  val name = user.name ?: user.email.orEmpty()
  Row(
    Modifier.clip(CircleShape).background(p.panelAlt).border(1.dp, p.border, CircleShape).hand().clickable(onClick = onOpen)
      .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Avatar(name, 22.dp)
    Txt(name, size = 12.5.sp, weight = FontWeight.SemiBold, maxLines = 1, color = if (user.state == AccountState.INVITED) p.muted else p.text)
    if (user.state == AccountState.INVITED) Pill(t.invitedPill, p.accent)
    if (user.administrator) Pill(t.administrator, p.ok)
    user.block?.let { Pill(if (it.until != null) t.timedOutPill else t.suspendedPill, if (it.until != null) p.warn else p.trap) }
  }
}

/**
 * An account's information panel, floating over the panel: everything about it, each with its label (*n/a* for what it
 * doesn't have), the sessions it plays in (a click opens one), and for an invited one its link: *Send again*, *Other
 * address*. Its own selection: one across the page and this window would make the page stop reacting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserPanel(user: AdminUser, default: Int?, onChanged: () -> Unit, onSession: (String) -> Unit, onClose: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val u = t.users
  val scope = rememberCoroutineScope()
  var editing by remember { mutableStateOf(false) }
  var address by remember { mutableStateOf(user.email.orEmpty()) }
  var state by remember { mutableStateOf<String?>(null) }
  val invited = user.state == AccountState.INVITED
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    SelectionContainer {
      Column(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().card(p, RoundedCornerShape(20.dp))) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Avatar(user.name ?: user.email.orEmpty(), 40.dp)
          Column(Modifier.weight(1f)) {
            Txt(user.name ?: user.email.orEmpty(), size = 18.sp, weight = FontWeight.Bold, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              if (invited) Pill(u.invitedPill, p.accent)
              if (user.administrator) Pill(u.administrator, p.ok)
              user.block?.let { Pill(if (it.until != null) u.timedOutPill else u.suspendedPill, if (it.until != null) p.warn else p.trap) }
            }
          }
          IconButton(onClose, Icon.CLOSE)
        }
        Divider()
        Column(Modifier.scrolling().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          val none = u.notAvailable
          // An Administrator with single sign-on has no other way in (their password is one nobody knows)
          val sso = u.ssoLogin(user.provider).takeIf { user.singleSignOn }
          val logins = if (sso != null && user.administrator) listOf(sso) else listOfNotNull(u.passwordLogin.takeIf { user.password }, sso)
          val deletion = user.deletesAt?.let { u.deletedOn(t.date(it)) }
            ?: user.autoDelete.takeIf { it != AutoDelete.NEVER && !invited }?.let { u.autoDeletion(t.account.autoDelete(it)) }
          InfoLine(u.nameLabel, user.name ?: none)
          // An address from the provider: whether it is verified there
          val verified = user.emailVerified?.let { v -> user.provider?.let { " (${u.emailVerifiedBy(it, v)})" } }.orEmpty()
          InfoLine(u.emailLabel, user.email?.let { it + verified } ?: none)
          InfoLine(u.idLabel, user.id) { SmallButton(t.common.copy, { Platform.copy(user.id) }) }
          InfoLine(u.stateLabel, if (invited) u.invitedPill else u.active)
          InfoLine(u.groupLabel, if (user.administrator) u.administrator else u.userGroup)
          InfoLine(u.loginLabel, logins.joinToString(" + ").ifEmpty { none })
          InfoLine(u.twoFactorLabel, if (invited) none else if (user.twoFactor) u.on else u.off)
          InfoLine(u.languageLabel, if (invited) none else Language.of(user.language)?.label ?: u.browserLanguage)
          InfoLine(u.madeLabel, if (user.createdAt > 0) t.date(user.createdAt) else none)
          InfoLine(
            u.invitationLabel,
            user.invitationExpiresAt?.let { if (it < Platform.now()) u.linkExpired else u.linkWorksUntil(t.date(it)) } ?: none,
          )
          InfoLine(u.deletionLabel, deletion ?: none)
          // Their running sessions against their limit (their own number, or the default)
          InfoLine(
            u.ownSessionsLabel,
            when {
              user.administrator -> u.noLimit
              invited -> none
              else -> (user.sessionLimit ?: default)?.let { limit ->
                u.sessionsOf(user.sessionsRunning, limit) + if (user.sessionLimit == null) " ${u.theDefault}" else ""
              } ?: none
            },
          )
          val block = user.block
          InfoLine(
            u.accessLabel,
            when {
              invited -> none
              block == null -> u.mayLogIn
              else -> listOfNotNull(
                block.until?.let { u.timedOutUntil(t.date(it)) } ?: u.suspended,
                block.by?.let { u.keptOutBy(it) },
              ).joinToString(" ")
            },
          )
          block?.reason?.let { InfoLine("", u.keptOutFor(it)) }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(u.sessionsLabel, size = 12.5.sp, color = p.muted, modifier = Modifier.width(FACT_LABEL))
            if (user.sessions.isEmpty()) Txt(none, size = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              user.sessions.forEach { id ->
                Txt(
                  id, size = 12.5.sp, weight = FontWeight.SemiBold, color = p.accent, maxLines = 1,
                  modifier = Modifier.clip(CircleShape).background(p.accent.copy(alpha = 0.12f)).hand()
                    .clickable { onClose(); onSession(id) }.padding(horizontal = 10.dp, vertical = 3.dp),
                )
              }
            }
          }
          if (invited) {
            Divider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SmallButton(u.sendAgain, {
                scope.launch {
                  state = (call("POST", "api/admin/users/${user.id}/invite", null) { } as? Answer.Failed)?.message
                    ?: u.newLinkWent(user.email.orEmpty())
                  onChanged()
                }
              })
              SmallButton(if (editing) t.common.cancel else u.otherAddress, { editing = !editing; address = user.email.orEmpty() })
            }
            if (editing) {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterField(address, { address = it }, u.email, Modifier.width(240.dp), minHeight = FIELD_HEIGHT)
                Button(u.sendThere, enabled = EmailAddresses.valid(EmailAddresses.normalize(address)) && address.trim() != user.email) {
                  scope.launch {
                    val body = ApiJson.encodeToString(EmailRequest.serializer(), EmailRequest(address.trim()))
                    state = when (val r = call("PUT", "api/admin/users/${user.id}/email", body) { }) {
                      is Answer.Ok -> { editing = false; onChanged(); u.linkWentTo(address.trim()) }
                      is Answer.Failed -> r.message
                    }
                  }
                }
              }
            }
          }
          state?.let { Txt(it, size = 12.sp, color = p.muted) }
          // Keeping it out, letting it in again, deleting it: never an Administrator's account
          Divider()
          if (user.administrator) {
            Txt(u.managedElsewhere, size = 12.sp, color = p.muted)
          } else {
            if (!invited) OwnLimit(user, default, onDone = { state = it; onChanged() })
            AccountActions(user, onDone = { state = it; onChanged() }, onDeleted = { onChanged(); onClose() })
          }
        }
      }
    }
  }
}

/**
 * An account's own limit of running sessions it created: a number of its own, or the default again ([default],
 * shown in the empty field).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnLimit(user: AdminUser, default: Int?, onDone: (String) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val u = t.users
  val scope = rememberCoroutineScope()
  var text by remember(user.id, user.sessionLimit) { mutableStateOf(user.sessionLimit?.toString().orEmpty()) }
  var problem by remember(user.id) { mutableStateOf<String?>(null) }
  val value = text.trim().toIntOrNull()?.takeIf { it in 0..20 }
  fun save(limit: Int?) {
    scope.launch {
      val body = ApiJson.encodeToString(SessionLimitRequest.serializer(), SessionLimitRequest(limit))
      when (val r = call("PUT", "api/admin/users/${user.id}/session-limit", body) { }) {
        is Answer.Ok -> { problem = null; onDone(u.limitSaved(user.name.orEmpty())) }
        is Answer.Failed -> problem = r.message
      }
    }
  }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    Txt(u.ownLimit, size = 13.sp, weight = FontWeight.SemiBold)
    NumberField(text, { text = it; problem = null }, default?.toString().orEmpty(), 0..20, Modifier.width(76.dp), minHeight = FIELD_HEIGHT)
    Button(t.common.save, enabled = value != null && value != user.sessionLimit) { save(value) }
    if (user.sessionLimit != null) SmallButton(u.useDefault, { save(null) })
    InfoTip(u.sessionsPerUserTip)
  }
  if (value == null && text.isNotBlank()) Txt(u.zeroToTwenty, size = 12.5.sp, color = p.trap)
  problem?.let { Txt(it, size = 12.sp, color = p.trap) }
}

/**
 * What an Administrator does to an account: a time-out or a suspension with a reason (or letting it in again), and
 * deleting it, which asks once more. [onDone]: what happened, for the panel's line; [onDeleted]: the account is gone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountActions(user: AdminUser, onDone: (String) -> Unit, onDeleted: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val u = t.users
  val scope = rememberCoroutineScope()
  val name = user.name ?: user.email.orEmpty()
  var reason by remember(user.id) { mutableStateOf("") }
  var time by remember(user.id) { mutableStateOf(TimeOut.DAY) }
  var deleting by remember(user.id) { mutableStateOf(false) }
  var problem by remember(user.id) { mutableStateOf<String?>(null) }
  fun act(method: String, path: String, request: AccountActionRequest?, done: () -> Unit) {
    scope.launch {
      val body = request?.let { ApiJson.encodeToString(AccountActionRequest.serializer(), it) }
      when (val r = call(method, "api/admin/users/${user.id}/$path", body) { }) {
        is Answer.Ok -> { problem = null; reason = ""; done() }
        is Answer.Failed -> problem = r.message
      }
    }
  }
  val invited = user.state == AccountState.INVITED
  val tooLong = reason.trim().length > AccountActionRequest.MAX_REASON
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (!invited) {
      if (user.block != null) {
        Row { SmallButton(u.letInButton, { act("DELETE", "block", null) { onDone(u.letInDone(name)) } }) }
      } else {
        FilterField(reason, { reason = it; problem = null }, u.reason, Modifier.fillMaxWidth(), minHeight = FIELD_HEIGHT)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Select(TimeOut.entries.map { it to u.timeOut(it) }, time, { time = it }, minHeight = FIELD_HEIGHT)
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(u.timeOutButton, enabled = !tooLong) {
              act("POST", "block", AccountActionRequest(time, reason.trim().ifEmpty { null })) { onDone(u.timedOutDone(name)) }
            }
            Button(u.suspendButton, enabled = !tooLong) {
              act("POST", "block", AccountActionRequest(null, reason.trim().ifEmpty { null })) { onDone(u.suspendedDone(name)) }
            }
            InfoTip(u.keptOutTip)
          }
        }
      }
    }
    // Deleting asks once more
    if (!deleting) {
      Row { SmallButton(u.deleteButton, { deleting = true }) }
    } else {
      Txt(u.reallyDelete(name), size = 12.5.sp, color = p.trap)
      if (!invited && user.block != null) {
        FilterField(reason, { reason = it; problem = null }, u.reason, Modifier.fillMaxWidth(), minHeight = FIELD_HEIGHT)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(u.deleteNow, enabled = !tooLong) {
          act("POST", "delete", AccountActionRequest(null, reason.trim().ifEmpty { null })) { onDeleted() }
        }
        SmallButton(t.common.cancel, { deleting = false })
      }
    }
    if (tooLong) Txt(t.problems.text(Refusal.of(Problem.REASON_TOO_LONG, "max" to AccountActionRequest.MAX_REASON)), size = 12.sp, color = p.trap)
    problem?.let { Txt(it, size = 12.sp, color = p.trap) }
  }
}

private val FACT_LABEL = 150.dp

/** One line of the panel: its label, its value, and maybe a button after it. */
@Composable
private fun InfoLine(label: String, value: String, after: @Composable () -> Unit = {}) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Txt(label, size = 12.5.sp, color = LocalPalette.current.muted, modifier = Modifier.width(FACT_LABEL))
    Txt(value, size = 13.sp, modifier = Modifier.weight(1f, fill = false))
    after()
  }
}

/**
 * How many running sessions of their own a user may have at once, unless their account has its own number (0: only
 * Administrators create sessions), and how many game servers may run at once (empty: no limit): the settings for
 * everyone, a row of the users' card. [onLoaded]: the sessions' number, for the accounts' panels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionsDefault(onLoaded: (Int) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  var saved by remember { mutableStateOf<AppSettings?>(null) }
  var perUser by remember { mutableStateOf("") }
  var servers by remember { mutableStateOf("") }
  var idle by remember { mutableStateOf("") }
  var state by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
  LaunchedEffect(Unit) {
    when (val r = call("GET", "api/admin/settings", null) { ApiJson.decodeFromString(AppSettings.serializer(), it) }) {
      is Answer.Ok -> {
        saved = r.value
        perUser = r.value.sessionsPerUser.toString()
        servers = r.value.gameServers.takeIf { it > 0 }?.toString().orEmpty()
        idle = r.value.idleMinutes.toString()
        onLoaded(r.value.sessionsPerUser)
      }
      is Answer.Failed -> state = false to r.message
    }
  }
  val value = perUser.trim().toIntOrNull()?.takeIf { it in 0..20 }
  // Empty: no limit (0)
  val serversValue = if (servers.isBlank()) 0 else servers.trim().toIntOrNull()?.takeIf { it in 1..100 }
  val idleValue = idle.trim().toIntOrNull()?.takeIf { it in 0..1440 }
  val settings = if (value != null && serversValue != null && idleValue != null) AppSettings(value, serversValue, idleValue) else null
  FlowRow(
    verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(t.users.sessionsPerUser, size = 13.sp, weight = FontWeight.SemiBold)
      InfoTip(t.users.sessionsPerUserTip)
      NumberField(perUser, { perUser = it; state = null }, "1", 0..20, Modifier.width(76.dp), minHeight = FIELD_HEIGHT)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(t.users.gameServers, size = 13.sp, weight = FontWeight.SemiBold)
      InfoTip(t.users.gameServersTip)
      NumberField(servers, { servers = it; state = null }, "∞", 1..100, Modifier.width(76.dp), minHeight = FIELD_HEIGHT)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(t.users.idleMinutes, size = 13.sp, weight = FontWeight.SemiBold)
      InfoTip(t.users.idleMinutesTip)
      NumberField(idle, { idle = it; state = null }, "30", 0..1440, Modifier.width(76.dp), minHeight = FIELD_HEIGHT)
    }
    Button(t.common.save, enabled = settings != null && settings != saved) {
      scope.launch {
        val changed = settings!!
        state = when (val r = call("PUT", "api/admin/settings", ApiJson.encodeToString(AppSettings.serializer(), changed)) { }) {
          is Answer.Ok -> { saved = changed; onLoaded(changed.sessionsPerUser); true to t.users.saved }
          is Answer.Failed -> false to r.message
        }
      }
    }
  }
  if (value == null && perUser.isNotBlank()) Txt(t.users.zeroToTwenty, size = 12.5.sp, color = p.trap)
  if (serversValue == null) Txt(t.users.oneToHundred, size = 12.5.sp, color = p.trap)
  if (idleValue == null && idle.isNotBlank()) Txt(t.users.zeroToDay, size = 12.5.sp, color = p.trap)
  state?.let { (ok, text) -> Txt(text, size = 12.5.sp, color = if (ok) p.ok else p.trap) }
}

/**
 * What runs now, for Administrators (to check a deploy): the tracker's and the gateway's versions, this page's (an old
 * one is still cached until a reload), and the gateway's Archipelago releases.
 */
@Composable
fun VersionsSection() {
  val p = LocalPalette.current
  val u = LocalTexts.current.users
  var versions by remember { mutableStateOf<Versions?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    when (val r = call("GET", "api/admin/versions", null) { ApiJson.decodeFromString(Versions.serializer(), it) }) {
      is Answer.Ok -> versions = r.value
      is Answer.Failed -> problem = r.message
    }
  }
  Section(u.versions, subtitle = u.versionsSubtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      val v = versions
      listOf(
        u.trackerVersion to (v?.tracker ?: "…"),
        u.gatewayVersion to (v?.let { it.gateway ?: u.notReachable } ?: "…"),
        u.pageVersion to RandogeddonVersion.VERSION,
        u.releasesLabel to (v?.releases?.joinToString(", ")?.ifEmpty { u.notReachable } ?: "…"),
      ).forEach { (label, value) ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Txt(label, size = 12.5.sp, color = p.muted, modifier = Modifier.width(96.dp))
          Txt(value, size = 13.sp, weight = FontWeight.SemiBold)
        }
      }
    }
  }
}
