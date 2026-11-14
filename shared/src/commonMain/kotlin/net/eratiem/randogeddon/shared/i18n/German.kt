package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.AutoDelete
import net.eratiem.randogeddon.shared.SessionStatus

/**
 * German, with "du". A group not translated yet can take English's words meanwhile (`… by English.group`); a test
 * lists such groups, so none stays that way unnoticed.
 */
object German : Texts {
  override val language = Language.DE
  override val dashboard: DashboardTexts = GermanDashboard
  override val feed: FeedTexts = GermanFeed
  override val finish: FinishTexts = GermanFinish
  override val dialogs: DialogTexts = GermanDialogs
  override val connection: ConnectionTexts = GermanConnection
  override val people: PeopleTexts = GermanPeople
  override val watch: WatchTexts = GermanWatch
  override val spoiler: SpoilerTexts = GermanSpoiler
  override val panel: PanelTexts = GermanPanel
  override val cheats: CheatTexts = GermanCheats
  override val apworlds: ApworldTexts = GermanApworlds
  override val users: UserAdminTexts = GermanUserAdmin
  override val sessionAdmin: SessionAdminTexts = GermanSessionAdmin
  override val tips: SessionTips = GermanSessionTips
  override val problems: ProblemTexts = GermanProblems
  override val mails: MailTexts = GermanMails

  override val format = object : FormatTexts {
    override val decimalSeparator = ','
    override val groupSeparator = '.'
    override val never = "nie"
    override val justNow = "gerade eben"
    override fun minutesAgo(n: Long) = "vor $n Min."
    override fun hoursAgo(n: Long) = "vor $n Std."
    override fun daysAgo(n: Long) = if (n == 1L) "vor 1 Tag" else "vor $n Tagen"
    override fun hoursMinutes(hours: Long, minutes: Long) = "$hours Std. $minutes Min."
    override fun minutes(n: Long) = "$n Min."
    override val underAMinute = "<1 Min."
  }

  override val common = object : CommonTexts {
    override val save = "Speichern"
    override val cancel = "Abbrechen"
    override val copy = "Kopieren"
    override val search = "Suchen…"
    override val nothingFound = "Nichts gefunden"
    override val logOut = "Abmelden"
    override val allYourSessions = "Alle deine Sessions"
    override val serverUnreachable = "Der Server ist nicht erreichbar"
    override val unexpectedAnswer = "Unerwartete Antwort"
    override fun failed(status: Int) = "Fehlgeschlagen ($status)"
  }

  override val frame = object : FrameTexts {
    override val cantReachServer = "Der Server ist nicht erreichbar – neuer Versuch läuft…"
    override fun loggedInAs(name: String) =
      "Du bist als $name angemeldet. Um dich mit dieser Einladung zu registrieren, melde dich ab und öffne ihren Link erneut."
    override fun noSuchSession(id: String) = "Es gibt keine Session $id, oder du bist nicht dabei."
    override val allSessions = "Alle Sessions"
    override val pickASession = "Session wählen"
    override val noSession = "Keine Session"
    override fun seed(name: String) = "Seed $name"
    override fun archipelago(version: String) = "AP $version"
    override val pickOneOfYourSessions = "Wähle eine deiner Sessions"
    override val connecting = "Verbinde mit dem Tracker…"
    override val reconnecting = "verbinde neu…"
    override val finished = "beendet"
    override val live = "live"
    override val serverOffline = "Server offline"
    override fun ofChecksDone(total: String) = "von $total Checks erledigt"
    override fun hintMode(pointHints: Boolean, costPercent: Int, autoHints: Boolean): String {
      val points = when {
        !pointHints -> null
        costPercent == 0 -> "Hinweise gratis"
        else -> "Hinweise $costPercent %"
      }
      return when {
        points != null && autoHints -> "$points + Auto-Hinweise"
        points != null -> points
        autoHints -> "nur Auto-Hinweise"
        else -> "keine Hinweise"
      }
    }
  }

  override val sessions = object : SessionListTexts {
    override fun status(status: SessionStatus) = when (status) {
      SessionStatus.LIVE -> "live"
      SessionStatus.FINISHING -> "endet"
      SessionStatus.PAUSED -> "pausiert"
      SessionStatus.ENDED -> "beendet"
    }
    override val noPartAdministrator = "Du bist in keiner Session dabei. Alle Sessions findest du unter Verwalten."
    override val noPart = "Du bist noch in keiner Session dabei."
    override val running = "Laufend"
    override val runningSubtitle = "wähle eine, um ihr Dashboard zu sehen"
    override val finished = "Beendet"
    override val finishedSubtitle = "so, wie sie zu Ende gingen"
    override fun finishedOn(date: String) = "beendet $date"
    override fun since(date: String) = "seit $date"
    override fun yourSlots(slots: String) = "Deine Slots: $slots"
    override fun online(n: Int) = "$n online"
    override fun slots(n: Int) = if (n == 1) "1 Slot" else "$n Slots"
  }

  override val account = object : AccountTexts {
    override val title = "Dein Konto"
    override val name = "Name"
    override val nameSubtitle = "was die anderen sehen; du meldest dich damit oder mit deiner E-Mail-Adresse an"
    override val yourName = "Dein Name"
    override val nameSaved = "Gespeichert. Laufende Sessions zeigen ihn; beendete behalten den alten Namen."
    override val idTip = "Die ID deines Kontos: Sie unterscheidet dich von jemandem, der deinen Namen früher hatte"
    override fun id(id: String) = "ID $id"
    override val password = "Passwort"
    override val changePassword = "Passwort ändern"
    override val passwordChanged = "Geändert. Deine anderen Anmeldungen sind beendet."
    override val twoFactor = "Zwei-Faktor-Anmeldung"
    override fun twoFactorSubtitleSso(provider: String) = "deine Anmeldungen laufen über $provider, das sie prüft"
    override val twoFactorSubtitleAdministrator = "Administratoren melden sich immer damit an"
    override val twoFactorSubtitle = "ein Code aus einer Authenticator-App nach deinem Passwort"
    override fun twoFactorNotNeeded(provider: String) = "Hier nicht nötig: $provider fragt deinen zweiten Faktor ab."
    override val twoFactorAdministratorsNeedIt =
      "Was nur Administratoren tun, ist erst mit eingeschalteter Zwei-Faktor-Anmeldung möglich."
    override val twoFactorIsOff = "Die Zwei-Faktor-Anmeldung ist aus."
    override val recoveryCodes = "Deine Wiederherstellungscodes"
    override val recoveryCodesHint =
      "Jeder funktioniert einmal anstelle eines Codes, falls du deinen Authenticator verlierst. Sie werden nur jetzt " +
        "angezeigt: Bewahre sie sicher auf."
    override val savedThem = "Ich habe sie gespeichert"
    override val scanThis =
      "Scanne das mit einer Authenticator-App (jede TOTP-App) oder tippe den Schlüssel ab; gib dann den angezeigten Code ein."
    override val turnItOn = "Einschalten"
    override val off = "Aus."
    override val setItUp = "Einrichten"
    override fun on(codesLeft: Int) = "An · noch $codesLeft ${if (codesLeft == 1) "Wiederherstellungscode" else "Wiederherstellungscodes"}"
    override val codeFor = "Ein Code aus deinem Authenticator (oder ein Wiederherstellungscode) für:"
    override val newRecoveryCodes = "Neue Wiederherstellungscodes"
    override val turnItOff = "Ausschalten"
    override val code = "Code"
    override val email = "E-Mail-Adresse"
    override val emailSubtitle = "für die Mails zu deinem Konto; du kannst dich auch damit anmelden"
    override val noAddress = "Noch keine Adresse"
    override fun waitingForConfirmation(address: String) =
      "Wartet auf die Bestätigung von $address: Öffne den Link in der Mail, die dorthin ging."
    override val newAddress = "Neue Adresse"
    override val sendConfirmation = "Bestätigung senden"
    override val confirmationSent = "Ein Link ging an die neue Adresse; deine Adresse ändert sich, wenn du ihn öffnest."
    override fun addressIsNow(address: String) = "Deine Adresse ist jetzt $address."
    override val confirmingAddress = "Deine neue Adresse wird bestätigt…"
    override val yourAccount = "Dein Konto"
    override val languageTitle = "Sprache"
    override val languageSubtitle = "dieser App und der Mails an dich"
    override fun automatic(current: Language) = "Automatisch (die des Browsers: ${current.label})"
    override val deletion = "Dein Konto löschen"
    override fun deletionSubtitleSso(provider: String) = "mit deinem $provider-Konto; deine Sessions behalten deinen Namen"
    override val deletionSubtitle = "von selbst, nach deiner letzten Session; deine Sessions behalten deinen Namen"
    override fun deletedWithSso(provider: String) =
      "Dein Konto hier wird gelöscht, wenn $provider deines dort löscht, nicht von selbst."
    override val deleteIt = "Löschen"
    override fun autoDelete(period: AutoDelete) = when (period) {
      AutoDelete.WEEKS_2 -> "2 Wochen nach meiner letzten Session"
      AutoDelete.WEEKS_4 -> "4 Wochen nach meiner letzten Session"
      AutoDelete.MONTHS_3 -> "3 Monate nach meiner letzten Session"
      AutoDelete.MONTHS_6 -> "6 Monate nach meiner letzten Session"
      AutoDelete.YEAR_1 -> "1 Jahr nach meiner letzten Session"
      AutoDelete.NEVER -> "nie"
    }
    override val staysUntilChanged = "Dein Konto bleibt, bis du das änderst."
    override val notWhileRunning = "Nicht, solange eine deiner Sessions läuft."
    override fun deletesOn(date: String) =
      "Stand jetzt am $date; eine Mail erinnert dich 14 Tage vorher, und eine neue Session schiebt es nach hinten."
  }
}
