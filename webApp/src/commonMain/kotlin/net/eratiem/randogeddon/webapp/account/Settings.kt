package net.eratiem.randogeddon.webapp.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import kotlinx.serialization.encodeToString
import net.eratiem.randogeddon.shared.AccountView
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.AutoDeleteRequest
import net.eratiem.randogeddon.shared.FontChoice
import net.eratiem.randogeddon.shared.FontRequest
import net.eratiem.randogeddon.shared.FontScope
import net.eratiem.randogeddon.shared.LanguageRequest
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.ThemeRequest
import net.eratiem.randogeddon.shared.fontOf
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.app.Languages
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.date
import net.eratiem.randogeddon.webapp.manage.FIELD_HEIGHT
import net.eratiem.randogeddon.webapp.ui.Button
import net.eratiem.randogeddon.webapp.ui.Choice
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Select
import net.eratiem.randogeddon.webapp.ui.Themes
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card

/**
 * The user's own account: their name (unique; a rename shows in the running sessions), their password (the form of
 * index.html, for the password manager), their e-mail address (a new one is confirmed by a link mailed to it), their
 * language, appearance and when the account deletes itself. [onRenamed]: the new name is everywhere else too.
 * Administrators with single sign-on change their name, password, address and second factor at the provider (its page
 * is the first panel), and their account goes with theirs there: those panels aren't shown to them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsView(onBack: () -> Unit, onRenamed: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val acc = t.account
  val scope = rememberCoroutineScope()
  var account by remember { mutableStateOf<AccountView?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  suspend fun load() {
    when (val answer = call("GET", "api/account", null, AccountView.serializer())) {
      is Answer.Ok -> { account = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  LaunchedEffect(Unit) { load() }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      IconButton(onBack, Icon.BACK, label = t.dialogs.back)
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
          // Their ID here, as the name's panel shows it to everyone else
          Tip(acc.idTip) { Txt(acc.localId(a.id), size = 11.5.sp, color = p.muted) }
        }
      }
    }

    if (sso == null) OwnAccount(a, { load() }, onRenamed)

    // The language: of the app, and of the mails; none chosen follows the browser
    var languageState by remember { mutableStateOf<String?>(null) }
    Section(acc.languageTitle, subtitle = acc.languageSubtitle) {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val browser = Languages.browser ?: Language.DEFAULT
        Choice(
          listOf(Triple<Language?, String, Icon?>(null, acc.automatic(browser), null)) +
            Language.entries.map { Triple<Language?, String, Icon?>(it, it.label, null) },
          a.language,
          { choice ->
            scope.launch {
              languageState = when (val r = call("PUT", "api/account/language", body(LanguageRequest(choice))) { }) {
                is Answer.Ok -> { Languages.apply(choice); load(); null }
                is Answer.Failed -> r.message
              }
            }
          },
        )
        languageState?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      }
    }

    // The appearance: theme and mode, applied at once
    var appearanceState by remember { mutableStateOf<String?>(null) }
    Section(acc.appearanceTitle, subtitle = acc.appearanceSubtitle) {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fun save(theme: Theme, mode: ThemeMode) {
          Themes.changedByHand()
          Themes.apply(theme, mode)
          scope.launch {
            appearanceState = when (val r = call("PUT", "api/account/theme", body(ThemeRequest(theme, mode))) { }) {
              is Answer.Ok -> { load(); null }
              is Answer.Failed -> r.message
            }
          }
        }
        fun saveFont(choice: String?, where: FontScope) {
          Themes.changedByHand()
          Themes.applyFont(choice, where)
          scope.launch {
            appearanceState = when (val r = call("PUT", "api/account/font", body(FontRequest(choice, where))) { }) {
              is Answer.Ok -> { load(); null }
              is Answer.Failed -> r.message
            }
          }
        }
        // The mode, the font, and where the font shows: each on its line, under its name
        Labelled(acc.modeLabel) {
          Choice(
            listOf(
              Triple(ThemeMode.LIGHT, acc.mode(ThemeMode.LIGHT), Icon.SUN), Triple(ThemeMode.DARK, acc.mode(ThemeMode.DARK), Icon.MOON),
              Triple(ThemeMode.AUTO, acc.mode(ThemeMode.AUTO), Icon.THEME),
            ),
            Themes.mode, { save(Themes.theme, it) },
          )
        }
        Labelled(acc.fontLabel) {
          val own = fontOf(Themes.theme)?.family
          Select(
            listOf<Pair<String?, String>>(null to acc.fontOfTheme(own), FontChoice.ORIGINAL to acc.fontOriginal) +
              FontChoice.themes.map { it.code to acc.fontOf(acc.theme(it), fontOf(it)!!.family) },
            Themes.font,
            { saveFont(it, Themes.fontScope) },
            minHeight = FIELD_HEIGHT, maxLabel = 280.dp,
          )
        }
        // With a font that shows: as the theme's font is made (a display font: the headings), the headings, or all
        if (FontChoice.font(Themes.theme, Themes.font) != null) Labelled(acc.fontScopeLabel) {
          Choice(
            listOf(
              Triple(FontScope.AUTO, acc.fontScopeAuto, null), Triple(FontScope.HEADINGS, acc.fontScopeHeadings, null),
              Triple(FontScope.ALL, acc.fontScopeAll, null),
            ),
            Themes.fontScope, { saveFont(Themes.font, it) },
          )
        }
        // Every theme as it looks in the mode that shows now (automatic: the device's), a click picks it; by group
        Theme.entries.groupBy { it.group }.forEach { (group, themes) ->
          Txt(acc.themeGroup(group), size = 12.5.sp, color = p.muted, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
          FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            themes.forEach { theme ->
              ThemeCard(theme, acc.theme(theme), theme == Themes.theme) { save(theme, Themes.mode) }
            }
          }
        }
        Txt(acc.themesNotice, size = 11.5.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
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

/** A setting on its line: its [label] in a column of its own, then the control (below it on a phone). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(Modifier.width(96.dp).heightIn(min = FIELD_HEIGHT), contentAlignment = Alignment.CenterStart) {
      Txt(label, size = 12.5.sp, color = LocalPalette.current.muted)
    }
    content()
  }
}

internal inline fun <reified T> body(value: T): String = ApiJson.encodeToString(value)
