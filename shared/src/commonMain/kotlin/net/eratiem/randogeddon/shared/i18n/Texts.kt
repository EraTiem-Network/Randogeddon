package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.ThemeGroup
import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.SessionStatus

/**
 * Every text Randogeddon shows, in one language ([English], [German]): grouped by where it is shown. Plain texts are
 * values, texts with something in them functions, so the compiler checks that every language has every text with the
 * same values. Names from Archipelago (games, items, locations, slots) and people's names are passed in as they are.
 *
 * Adding a text: a member here, then its words in every language (it doesn't compile before).
 */
interface Texts {
  val language: Language
  val format: FormatTexts
  val common: CommonTexts
  val frame: FrameTexts
  val sessions: SessionListTexts
  val account: AccountTexts
  val dashboard: DashboardTexts
  val feed: FeedTexts
  val finish: FinishTexts
  val dialogs: DialogTexts
  val connection: ConnectionTexts
  val people: PeopleTexts
  val watch: WatchTexts
  val spoiler: SpoilerTexts
  val panel: PanelTexts
  val cheats: CheatTexts
  val apworlds: ApworldTexts
  val users: UserAdminTexts
  val sessionAdmin: SessionAdminTexts
  val tips: SessionTips
  val problems: ProblemTexts
  val mails: MailTexts
}

/** How numbers and times are written; the functions using them are in `Formatting.kt`. */
interface FormatTexts {
  /** `12.3` or `12,3`. */
  val decimalSeparator: Char
  /** `12,345` or `12.345`. */
  val groupSeparator: Char
  val never: String
  val justNow: String
  fun minutesAgo(n: Long): String
  fun hoursAgo(n: Long): String
  fun daysAgo(n: Long): String
  /** A duration of hours and minutes, e.g. `4h 12m`. */
  fun hoursMinutes(hours: Long, minutes: Long): String
  fun minutes(n: Long): String
  /** Less than a minute, but more than nothing. */
  val underAMinute: String
}

/** Words used all over the app. */
interface CommonTexts {
  val save: String
  val cancel: String
  val copy: String
  val search: String
  val nothingFound: String
  val logOut: String
  val allYourSessions: String
  val serverUnreachable: String
  val unexpectedAnswer: String
  fun failed(status: Int): String
}

/** Around every page: the top card, and what the page says when there is nothing to show. */
interface FrameTexts {
  val cantReachServer: String
  fun loggedInAs(name: String): String
  fun noSuchSession(id: String): String
  val allSessions: String
  val pickASession: String
  val noSession: String
  fun seed(name: String): String
  fun archipelago(version: String): String
  val pickOneOfYourSessions: String
  val connecting: String
  val reconnecting: String
  val finished: String
  val live: String
  val serverOffline: String
  /** After the percentage: `of 12,345 checks done` ([total] formatted already). */
  fun ofChecksDone(total: String): String
  /** The session's hint mode: `hints 10%`, `free hints`, `auto hints only`, … */
  fun hintMode(pointHints: Boolean, costPercent: Int, autoHints: Boolean): String
}

/** The list of the user's sessions, and how a session stands. */
interface SessionListTexts {
  fun status(status: SessionStatus): String
  val noPartAdministrator: String
  val noPart: String
  val running: String
  val runningSubtitle: String
  val finished: String
  val finishedSubtitle: String
  fun finishedOn(date: String): String
  fun since(date: String): String
  fun yourSlots(slots: String): String
  fun online(n: Int): String
  fun slots(n: Int): String
}

/**
 * *Your account*: name, password, two-factor login, e-mail address, language, appearance, deletion; for Administrators
 * with single sign-on the provider's account instead of the first four and the deletion.
 */
interface AccountTexts {
  val title: String
  // The provider's account (single sign-on)
  fun providerAccount(provider: String): String
  val providerAccountSubtitle: String
  fun providerAccountHint(provider: String): String
  fun openProvider(provider: String): String
  // The name
  val name: String
  val nameSubtitle: String
  val yourName: String
  val nameSaved: String
  val idTip: String
  fun id(id: String): String
  // The password
  val password: String
  val changePassword: String
  val passwordChanged: String
  // Two-factor login
  val twoFactor: String
  val twoFactorSubtitleAdministrator: String
  val twoFactorSubtitle: String
  val twoFactorAdministratorsNeedIt: String
  val twoFactorIsOff: String
  val recoveryCodes: String
  val recoveryCodesHint: String
  val savedThem: String
  val scanThis: String
  val turnItOn: String
  val off: String
  val setItUp: String
  fun on(codesLeft: Int): String
  val codeFor: String
  val newRecoveryCodes: String
  val turnItOff: String
  val code: String
  // The e-mail address
  val email: String
  val emailSubtitle: String
  val noAddress: String
  fun waitingForConfirmation(address: String): String
  val newAddress: String
  val sendConfirmation: String
  val confirmationSent: String
  fun addressIsNow(address: String): String
  val confirmingAddress: String
  val yourAccount: String
  // The language
  val languageTitle: String
  val languageSubtitle: String
  /** The choice that follows the browser, which speaks [current] now. */
  fun automatic(current: Language): String
  // The appearance
  val appearanceTitle: String
  val appearanceSubtitle: String
  fun theme(theme: Theme): String
  /** The heading over a group's theme cards. */
  fun themeGroup(group: ThemeGroup): String
  /** Under the cards: the console themes are only inspired, the names are trademarks. */
  val themesNotice: String
  fun mode(mode: ThemeMode): String
  /** The font choice: the chosen theme's ([family]; `null`: it has none of its own), the original, another theme's. */
  fun fontOfTheme(family: String?): String
  val fontOriginal: String
  fun fontOf(theme: String, family: String): String
  /** Where the font shows: as it is made ([display]: a font for headings), in all text, in the headings only. */
  fun fontScopeAuto(display: Boolean): String
  val fontScopeAll: String
  val fontScopeHeadings: String
  // Randogeddon Bridge's keys
  val bridgeKeys: String
  val bridgeKeysSubtitle: String
  /** One key: when it was made, last used (`null`: not yet) and until when it works. */
  fun bridgeKey(made: String, used: String?, until: String): String
  val endKey: String
  // Deleting the account
  val deletion: String
  val deletionSubtitle: String
  val deleteIt: String
  /** An auto-deletion period in the choice: `never`, `3 months after my last session`. */
  fun autoDelete(period: AutoDelete): String
  val staysUntilChanged: String
  val notWhileRunning: String
  fun deletesOn(date: String): String
}
