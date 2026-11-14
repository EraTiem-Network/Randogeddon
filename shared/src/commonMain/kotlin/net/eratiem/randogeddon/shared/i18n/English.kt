package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.SessionStatus

/** British English: the default, and the fallback. */
object English : Texts {
  override val language = Language.EN
  override val dashboard: DashboardTexts = EnglishDashboard
  override val feed: FeedTexts = EnglishFeed
  override val finish: FinishTexts = EnglishFinish
  override val dialogs: DialogTexts = EnglishDialogs
  override val connection: ConnectionTexts = EnglishConnection
  override val people: PeopleTexts = EnglishPeople
  override val watch: WatchTexts = EnglishWatch
  override val spoiler: SpoilerTexts = EnglishSpoiler
  override val panel: PanelTexts = EnglishPanel
  override val cheats: CheatTexts = EnglishCheats
  override val apworlds: ApworldTexts = EnglishApworlds
  override val users: UserAdminTexts = EnglishUserAdmin
  override val sessionAdmin: SessionAdminTexts = EnglishSessionAdmin
  override val tips: SessionTips = EnglishSessionTips
  override val problems: ProblemTexts = EnglishProblems
  override val mails: MailTexts = EnglishMails

  override val format = object : FormatTexts {
    override val decimalSeparator = '.'
    override val groupSeparator = ','
    override val never = "never"
    override val justNow = "just now"
    override fun minutesAgo(n: Long) = "${n}m ago"
    override fun hoursAgo(n: Long) = "${n}h ago"
    override fun daysAgo(n: Long) = "${n}d ago"
    override fun hoursMinutes(hours: Long, minutes: Long) = "${hours}h ${minutes}m"
    override fun minutes(n: Long) = "${n}m"
    override val underAMinute = "<1m"
  }

  override val common = object : CommonTexts {
    override val save = "Save"
    override val cancel = "Cancel"
    override val copy = "Copy"
    override val search = "Search…"
    override val nothingFound = "Nothing found"
    override val logOut = "Log out"
    override val allYourSessions = "All your sessions"
    override val serverUnreachable = "The server can't be reached"
    override val unexpectedAnswer = "Unexpected answer"
    override fun failed(status: Int) = "Failed ($status)"
  }

  override val frame = object : FrameTexts {
    override val cantReachServer = "Can't reach the server – trying again…"
    override fun loggedInAs(name: String) =
      "You're logged in as $name. To sign up with this invitation, log out and open its link again."
    override fun noSuchSession(id: String) = "There is no session $id, or you aren't part of it."
    override val allSessions = "All sessions"
    override val pickASession = "Pick a session"
    override val noSession = "No session"
    override fun seed(name: String) = "Seed $name"
    override fun archipelago(version: String) = "AP $version"
    override val pickOneOfYourSessions = "Pick one of your sessions"
    override val connecting = "Connecting to the tracker…"
    override val reconnecting = "reconnecting…"
    override val finished = "finished"
    override val live = "live"
    override val serverOffline = "server offline"
    override fun ofChecksDone(total: String) = "of $total checks done"
    override fun hintMode(pointHints: Boolean, costPercent: Int, autoHints: Boolean): String {
      val points = when {
        !pointHints -> null
        costPercent == 0 -> "free hints"
        else -> "hints $costPercent%"
      }
      return when {
        points != null && autoHints -> "$points + auto hints"
        points != null -> points
        autoHints -> "auto hints only"
        else -> "no hints"
      }
    }
  }

  override val sessions = object : SessionListTexts {
    override fun status(status: SessionStatus) = when (status) {
      SessionStatus.LIVE -> "live"
      SessionStatus.FINISHING -> "ending"
      SessionStatus.PAUSED -> "paused"
      SessionStatus.ENDED -> "ended"
    }
    override val noPartAdministrator = "You have no part in a session. Every session is under Manage."
    override val noPart = "You aren't part of a session yet."
    override val running = "Running"
    override val runningSubtitle = "pick one to see its dashboard"
    override val finished = "Finished"
    override val finishedSubtitle = "as they were when they finished"
    override fun finishedOn(date: String) = "finished $date"
    override fun since(date: String) = "since $date"
    override fun yourSlots(slots: String) = "Your slots: $slots"
    override fun online(n: Int) = "$n online"
    override fun slots(n: Int) = if (n == 1) "1 slot" else "$n slots"
  }

  override val account = object : AccountTexts {
    override val title = "Your account"
    override val name = "Name"
    override val nameSubtitle = "what the others see; you log in with it or with your e-mail address"
    override val yourName = "Your name"
    override val nameSaved = "Saved. The running sessions show it; finished ones keep the old name."
    override val idTip = "Your account's ID: it tells you apart from someone who had your name before"
    override fun id(id: String) = "ID $id"
    override val password = "Password"
    override val changePassword = "Change password"
    override val passwordChanged = "Changed. Your other logins have ended."
    override val twoFactor = "Two-factor login"
    override fun twoFactorSubtitleSso(provider: String) = "your logins go through $provider, which checks it"
    override val twoFactorSubtitleAdministrator = "Administrators always log in with it"
    override val twoFactorSubtitle = "a code from an authenticator app after your password"
    override fun twoFactorNotNeeded(provider: String) = "Not needed here: $provider asks for your second factor."
    override val twoFactorAdministratorsNeedIt = "What only Administrators do opens once two-factor login is on."
    override val twoFactorIsOff = "Two-factor login is off."
    override val recoveryCodes = "Your recovery codes"
    override val recoveryCodesHint =
      "Each works once, in place of a code, if you lose your authenticator. They are shown only now: keep them safe."
    override val savedThem = "I have saved them"
    override val scanThis =
      "Scan this with an authenticator app (any TOTP app), or type the key in; then enter the code it shows."
    override val turnItOn = "Turn it on"
    override val off = "Off."
    override val setItUp = "Set it up"
    override fun on(codesLeft: Int) = "On · $codesLeft recovery ${if (codesLeft == 1) "code" else "codes"} left"
    override val codeFor = "A code from your authenticator (or a recovery code) for:"
    override val newRecoveryCodes = "New recovery codes"
    override val turnItOff = "Turn it off"
    override val code = "Code"
    override val email = "E-mail address"
    override val emailSubtitle = "for the mails about your account; you can log in with it too"
    override val noAddress = "No address yet"
    override fun waitingForConfirmation(address: String) =
      "Waiting for the confirmation of $address: open the link in the mail sent there."
    override val newAddress = "New address"
    override val sendConfirmation = "Send confirmation"
    override val confirmationSent = "A link went to the new address; your address changes when you open it."
    override fun addressIsNow(address: String) = "Your address is $address now."
    override val confirmingAddress = "Confirming your new address…"
    override val yourAccount = "Your account"
    override val languageTitle = "Language"
    override val languageSubtitle = "of this app and of the mails to you"
    override fun automatic(current: Language) = "Automatic (the browser's: ${current.label})"
    override val deletion = "Deleting your account"
    override fun deletionSubtitleSso(provider: String) = "with your $provider account; your sessions keep your name"
    override val deletionSubtitle = "by itself, after your last session; your sessions keep your name"
    override fun deletedWithSso(provider: String) = "Your account here is deleted when $provider deletes yours there, not by itself."
    override val deleteIt = "Delete it"
    override fun autoDelete(period: AutoDelete) = when (period) {
      AutoDelete.WEEKS_2 -> "2 weeks after my last session"
      AutoDelete.WEEKS_4 -> "4 weeks after my last session"
      AutoDelete.MONTHS_3 -> "3 months after my last session"
      AutoDelete.MONTHS_6 -> "6 months after my last session"
      AutoDelete.YEAR_1 -> "1 year after my last session"
      AutoDelete.NEVER -> "never"
    }
    override val staysUntilChanged = "Your account stays until you change this."
    override val notWhileRunning = "Not while one of your sessions runs."
    override fun deletesOn(date: String) =
      "As of now on $date; a mail reminds you 14 days before, and a new session moves it later."
  }
}
