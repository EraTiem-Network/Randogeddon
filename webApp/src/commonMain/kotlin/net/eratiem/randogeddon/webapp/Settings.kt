package net.eratiem.randogeddon.webapp

import net.eratiem.randogeddon.shared.BridgeKeyView
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.ThemeRequest
import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.i18n.text
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import net.eratiem.randogeddon.shared.AccountView
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.AutoDeleteRequest
import net.eratiem.randogeddon.shared.CodeRequest
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.EmailRequest
import net.eratiem.randogeddon.shared.LanguageRequest
import net.eratiem.randogeddon.shared.NameRequest
import net.eratiem.randogeddon.shared.RecoveryCodeList
import net.eratiem.randogeddon.shared.TokenRequest
import net.eratiem.randogeddon.shared.TotpSetup
import net.eratiem.randogeddon.shared.UserNames
import net.eratiem.randogeddon.shared.i18n.Language

/**
 * The user's own account: their name (unique; a rename shows in the running sessions), their password (the form of
 * index.html, for the password manager), their e-mail address (a new one is confirmed by a link mailed to it), their
 * language, appearance and when the account deletes itself. [onRenamed]: the new name is everywhere else too.
 * Administrators with single sign-on change their name, password, address and second factor at the provider (its page
 * is the first panel), and their account goes with theirs there: those panels aren't shown to them.
 */
@Composable
fun SettingsView(onBack: () -> Unit, onRenamed: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val acc = t.account
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
      Txt(acc.title, size = 20.sp, weight = FontWeight.Bold)
    }
    problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    val a = account ?: return@Column
    val sso = a.singleSignOn

    // The provider's account: where an Administrator with single sign-on changes the rest
    if (sso != null) {
      Section(acc.providerAccount(sso), subtitle = acc.providerAccountSubtitle) {
        Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Txt(acc.providerAccountHint(sso), size = 13.sp)
          a.singleSignOnAccount?.let { url -> Row { Button(acc.openProvider(sso)) { Platform.openLink(url) } } }
        }
      }
    }

    if (sso == null) OwnAccount(a, { load() }, onRenamed)

    // The language: of the app, and of the mails; none chosen follows the browser
    var languageState by remember { mutableStateOf<String?>(null) }
    Section(acc.languageTitle, subtitle = acc.languageSubtitle) {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val browser = Languages.browser ?: Language.DEFAULT
        Select(
          listOf<Pair<Language?, String>>(null to acc.automatic(browser)) + Language.entries.map { it to it.label },
          a.language,
          { choice ->
            scope.launch {
              languageState = when (val r = call("PUT", "api/account/language", body(LanguageRequest(choice))) { }) {
                is Answer.Ok -> { Languages.apply(choice); load(); null }
                is Answer.Failed -> r.message
              }
            }
          },
          Modifier.width(260.dp), minHeight = FIELD_HEIGHT,
        )
        languageState?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      }
    }

    // The appearance: theme and mode, applied at once
    var appearanceState by remember { mutableStateOf<String?>(null) }
    Section(acc.appearanceTitle, subtitle = acc.appearanceSubtitle) {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fun save(theme: Theme, mode: ThemeMode) {
          Themes.apply(theme, mode)
          scope.launch {
            appearanceState = when (val r = call("PUT", "api/account/theme", body(ThemeRequest(theme, mode))) { }) {
              is Answer.Ok -> { load(); null }
              is Answer.Failed -> r.message
            }
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Select(Theme.entries.map { it to acc.theme(it) }, Themes.theme, { save(it, Themes.mode) }, Modifier.width(200.dp), minHeight = FIELD_HEIGHT)
          Select(ThemeMode.entries.map { it to acc.mode(it) }, Themes.mode, { save(Themes.theme, it) }, Modifier.width(220.dp), minHeight = FIELD_HEIGHT)
        }
        // The theme's colours, as they show now: the brand, the items, the states
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(p.brandStart, p.brandEnd, p.progression, p.useful, p.trap, p.ok, p.warn, p.key).forEach { colour ->
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(colour))
          }
        }
        appearanceState?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      }
    }

    BridgeKeysSection()

    // When the account deletes itself (with single sign-on: with theirs at the provider)
    if (sso != null) return@Column
    var deletionState by remember { mutableStateOf<String?>(null) }
    Section(acc.deletion, subtitle = acc.deletionSubtitle) {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt(acc.deleteIt, size = 13.sp)
          Select(
            AutoDelete.entries.map { it to acc.autoDelete(it) },
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
            a.autoDelete == AutoDelete.NEVER -> acc.staysUntilChanged
            due == null -> acc.notWhileRunning
            else -> acc.deletesOn(t.date(due))
          },
          size = 12.5.sp, color = p.muted,
        )
        deletionState?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      }
    }
  }
}

/** Name, password, two-factor login and address: the account's own (not the provider's). */
@Composable
private fun OwnAccount(a: AccountView, load: suspend () -> Unit, onRenamed: () -> Unit) {
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

/** Randogeddon Bridge's keys (made under *Your connection*), each with *End*; only shown if there are any. */
@Composable
private fun BridgeKeysSection() {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val acc = t.account
  val scope = rememberCoroutineScope()
  var keys by remember { mutableStateOf<List<BridgeKeyView>>(emptyList()) }
  var problem by remember { mutableStateOf<String?>(null) }
  suspend fun load() {
    when (val r = call("GET", "api/account/bridge-keys", null) { ApiJson.decodeFromString(ListSerializer(BridgeKeyView.serializer()), it) }) {
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

/** The link of the mail confirming a new address: confirms it once the user is logged in, says how it went. */
@Composable
fun ConfirmEmailView(token: String, onSettings: () -> Unit) {
  val p = LocalPalette.current
  val acc = LocalTexts.current.account
  var result by remember { mutableStateOf<String?>(null) }
  var ok by remember { mutableStateOf(false) }
  LaunchedEffect(token) {
    when (val r = call("POST", "api/email/confirm", body(TokenRequest(token))) { ApiJson.decodeFromString(EmailRequest.serializer(), it) }) {
      is Answer.Ok -> { ok = true; result = acc.addressIsNow(r.value.email) }
      is Answer.Failed -> result = r.message
    }
  }
  Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Txt(result ?: acc.confirmingAddress, color = if (ok || result == null) p.text else p.trap)
    if (result != null) Row { SmallButton(acc.yourAccount, onSettings) }
  }
}

private inline fun <reified T> body(value: T): String = ApiJson.encodeToString(value)

/**
 * Two-factor login: off, set it up (a QR code and the key, confirmed with a first code); on, new recovery codes or turn
 * it off, each with a code. Administrators can't turn it off (with single sign-on the provider has their second
 * factor, and the section isn't shown). The recovery codes are shown once, right after.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TwoFactorSection(a: AccountView, onChanged: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val acc = t.account
  val scope = rememberCoroutineScope()
  var setup by remember { mutableStateOf<TotpSetup?>(null) }
  var code by remember { mutableStateOf("") }
  var codes by remember { mutableStateOf<List<String>?>(null) }
  var state by remember { mutableStateOf<String?>(null) }
  fun request(path: String, onCodes: Boolean) = scope.launch {
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
          list.forEach { Mono(it, p.text) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SmallButton(t.common.copy, { Platform.copy(list.joinToString("\n")) })
          Button(acc.savedThem) { codes = null }
        }
        return@Column
      }
      val current = setup
      when {
        current != null -> {
          Txt(acc.scanThis, size = 12.5.sp)
          QrCode(current.rows, Modifier.size(200.dp))
          Mono(current.secret.chunked(4).joinToString(" "), p.text)
          CodeRow(code, { code = it; state = null }, acc.turnItOn) { request("api/account/2fa/enable", onCodes = true) }
          SmallButton(t.common.cancel, { setup = null; code = "" })
        }
        !a.twoFactor -> {
          Txt(acc.off, size = 13.sp)
          Row {
            Button(acc.setItUp) {
              scope.launch {
                when (val r = call("POST", "api/account/2fa/setup", null) { ApiJson.decodeFromString(TotpSetup.serializer(), it) }) {
                  is Answer.Ok -> { setup = r.value; state = null }
                  is Answer.Failed -> state = r.message
                }
              }
            }
          }
        }
        else -> {
          Txt(acc.on(a.recoveryCodesLeft), size = 13.sp, weight = FontWeight.SemiBold)
          Txt(acc.codeFor, size = 12.5.sp, color = p.muted)
          CodeRow(code, { code = it; state = null }, acc.newRecoveryCodes) { request("api/account/2fa/codes", onCodes = true) }
          if (!a.administrator) Row { SmallButton(acc.turnItOff, { request("api/account/2fa/disable", onCodes = false) }) }
        }
      }
      state?.let { Txt(it, size = 12.5.sp, color = p.muted) }
    }
  }
}

/** A code field with its button. */
@Composable
private fun CodeRow(code: String, onCode: (String) -> Unit, action: String, onAction: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterField(code, onCode, LocalTexts.current.account.code, Modifier.width(140.dp), minHeight = FIELD_HEIGHT)
    Button(action, enabled = code.isNotBlank(), onClick = onAction)
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
