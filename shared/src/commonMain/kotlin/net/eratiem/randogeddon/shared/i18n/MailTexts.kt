package net.eratiem.randogeddon.shared.i18n

/** The mails Randogeddon sends (plain text): each one's subject and text. */
interface MailTexts {
  val invitationSubject: String
  fun invitation(link: String, validDays: Int): String
  val confirmationSubject: String
  fun confirmation(name: String, link: String, validDays: Int): String
  /** To the account's address so far, when another one is asked for. */
  val addressChangeSubject: String
  fun addressChange(name: String, newAddress: String): String
  fun deletionSubject(date: String): String
  fun deletionWarning(name: String, date: String, settingsLink: String): String
  /** An Administrator keeps the account out: [until] when (`null`: suspended), and why if they said. */
  val timedOutSubject: String
  val suspendedSubject: String
  fun keptOut(name: String, until: String?, reason: String?): String
  /** They can log in again (taken back before its time). */
  val letInSubject: String
  fun letIn(name: String): String
  /** An Administrator deleted the account. */
  val removedSubject: String
  fun removed(name: String, reason: String?): String
  /** A link that sets a new password (a forgotten one): it works once, [validMinutes] long, until [until]. */
  val passwordResetSubject: String
  fun passwordReset(name: String, link: String, validMinutes: Int, until: String): String
  /** The password was set anew with such a link, [at] then; every login ended. */
  val passwordChangedSubject: String
  fun passwordChanged(name: String, at: String): String
}

internal object EnglishMails : MailTexts {
  override val invitationSubject = "Your invitation to Randogeddon"
  override fun invitation(link: String, validDays: Int) = """
    |Hi,
    |
    |you've been invited to Randogeddon, where your group's Archipelago sessions are tracked. Choose your name and
    |password here:
    |
    |$link
    |
    |The link works once, for $validDays days. If you didn't expect this mail, you can ignore it.
    |""".trimMargin()

  override val confirmationSubject = "Confirm your new address for Randogeddon"
  override fun confirmation(name: String, link: String, validDays: Int) = """
    |Hi $name,
    |
    |to use this address for your Randogeddon account, open this link:
    |
    |$link
    |
    |The link works once, for $validDays days. Until then your old address stays. If you didn't ask for this, you can
    |ignore this mail.
    |""".trimMargin()

  override val addressChangeSubject = "A new address was asked for your Randogeddon account"
  override fun addressChange(name: String, newAddress: String) = """
    |Hi $name,
    |
    |someone logged in to your Randogeddon account asked to change its e-mail address to $newAddress. It changes once
    |the link sent there is opened.
    |
    |If that was you, there is nothing to do. If not, change your password in your settings right away: that ends
    |every login of your account.
    |""".trimMargin()

  override fun deletionSubject(date: String) = "Your Randogeddon account will be deleted on $date"
  override fun deletionWarning(name: String, date: String, settingsLink: String) = """
    |Hi $name,
    |
    |as you chose when you signed up, your Randogeddon account will be deleted on $date, since your last session
    |ended a while ago. Your sessions stay, with your name in them; only the account goes (login, e-mail address,
    |settings).
    |
    |To keep it, choose a longer period or "never" in your settings:
    |
    |$settingsLink
    |
    |A new session you play in postpones the deletion too.
    |""".trimMargin()
  override val timedOutSubject = "Your Randogeddon account is timed out"
  override val suspendedSubject = "Your Randogeddon account is suspended"
  override fun keptOut(name: String, until: String?, reason: String?) = """
    |Hi $name,
    |
    |${if (until != null) "an Administrator has timed your Randogeddon account out until $until. Until then" else "an Administrator has suspended your Randogeddon account. Until that is lifted"}
    |you can't log in, and your logins (Randogeddon Bridge's too) have ended.${if (reason.isNullOrBlank()) "" else "\n|\n|The reason given: $reason"}
    |
    |Your sessions go on, and your games stay connected.
    |""".trimMargin()

  override val letInSubject = "You can log in to Randogeddon again"
  override fun letIn(name: String) = """
    |Hi $name,
    |
    |an Administrator has let your Randogeddon account in again: you can log in as before.
    |""".trimMargin()

  override val removedSubject = "Your Randogeddon account was deleted"
  override fun removed(name: String, reason: String?) = """
    |Hi $name,
    |
    |an Administrator has deleted your Randogeddon account (login, e-mail address, settings). Your sessions stay, with
    |your name in them.${if (reason.isNullOrBlank()) "" else "\n|\n|The reason given: $reason"}
    |""".trimMargin()

  override val passwordResetSubject = "A new password for your Randogeddon account"
  override fun passwordReset(name: String, link: String, validMinutes: Int, until: String) = """
    |Hi $name,
    |
    |someone asked to reset the password of your Randogeddon account. To choose a new one, open this link:
    |
    |$link
    |
    |The link works once, for $validMinutes minutes (until $until). Your two-factor login stays as it is.
    |
    |If you didn't ask for this, you can ignore this mail: your password stays as it is.
    |""".trimMargin()

  override val passwordChangedSubject = "The password of your Randogeddon account was changed"
  override fun passwordChanged(name: String, at: String) = """
    |Hi $name,
    |
    |the password of your Randogeddon account was set anew with a link from a mail to this address ($at). Every
    |login of your account has ended (Randogeddon Bridge's too).
    |
    |If that was you, there is nothing to do. If not, someone can read your mail: secure your mailbox, then reset
    |your password again from the login page.
    |""".trimMargin()
}

internal object GermanMails : MailTexts {
  override val invitationSubject = "Deine Einladung zu Randogeddon"
  override fun invitation(link: String, validDays: Int) = """
    |Hallo,
    |
    |du wurdest zu Randogeddon eingeladen, wo die Archipelago-Sessions deiner Gruppe verfolgt werden. Wähle hier deinen
    |Namen und dein Passwort:
    |
    |$link
    |
    |Der Link funktioniert einmal, $validDays Tage lang. Wenn du diese Mail nicht erwartet hast, kannst du sie
    |ignorieren.
    |""".trimMargin()

  override val confirmationSubject = "Bestätige deine neue Adresse für Randogeddon"
  override fun confirmation(name: String, link: String, validDays: Int) = """
    |Hallo $name,
    |
    |um diese Adresse für dein Randogeddon-Konto zu nutzen, öffne diesen Link:
    |
    |$link
    |
    |Der Link funktioniert einmal, $validDays Tage lang. Bis dahin bleibt deine alte Adresse. Wenn du das nicht
    |angefordert hast, kannst du diese Mail ignorieren.
    |""".trimMargin()

  override val addressChangeSubject = "Für dein Randogeddon-Konto wurde eine neue Adresse angefordert"
  override fun addressChange(name: String, newAddress: String) = """
    |Hallo $name,
    |
    |jemand, der in deinem Randogeddon-Konto angemeldet ist, möchte dessen E-Mail-Adresse zu $newAddress ändern. Sie
    |ändert sich, sobald der Link geöffnet wird, der dorthin ging.
    |
    |Wenn du das warst, ist nichts zu tun. Wenn nicht, ändere sofort dein Passwort in deinen Einstellungen: Das beendet
    |jede Anmeldung deines Kontos.
    |""".trimMargin()

  override fun deletionSubject(date: String) = "Dein Randogeddon-Konto wird am $date gelöscht"
  override fun deletionWarning(name: String, date: String, settingsLink: String) = """
    |Hallo $name,
    |
    |wie du es bei der Registrierung gewählt hast, wird dein Randogeddon-Konto am $date gelöscht, weil deine letzte
    |Session schon eine Weile vorbei ist. Deine Sessions bleiben, mit deinem Namen darin; nur das Konto geht
    |(Anmeldung, E-Mail-Adresse, Einstellungen).
    |
    |Um es zu behalten, wähle in deinen Einstellungen einen längeren Zeitraum oder "nie":
    |
    |$settingsLink
    |
    |Eine neue Session, in der du mitspielst, schiebt das Löschen auch hinaus.
    |""".trimMargin()
  override val timedOutSubject = "Dein Randogeddon-Konto ist vorübergehend gesperrt"
  override val suspendedSubject = "Dein Randogeddon-Konto ist gesperrt"
  override fun keptOut(name: String, until: String?, reason: String?) = """
    |Hallo $name,
    |
    |${if (until != null) "ein Administrator hat dein Randogeddon-Konto bis $until gesperrt. Bis dahin" else "ein Administrator hat dein Randogeddon-Konto gesperrt. Bis die Sperre aufgehoben wird,"}
    |kannst du dich nicht anmelden, und deine Anmeldungen (auch die von Randogeddon Bridge) sind beendet.${if (reason.isNullOrBlank()) "" else "\n|\n|Der angegebene Grund: $reason"}
    |
    |Deine Sessions laufen weiter, und deine Spiele bleiben verbunden.
    |""".trimMargin()

  override val letInSubject = "Du kannst dich wieder bei Randogeddon anmelden"
  override fun letIn(name: String) = """
    |Hallo $name,
    |
    |ein Administrator hat die Sperre deines Randogeddon-Kontos aufgehoben: Du kannst dich wie zuvor anmelden.
    |""".trimMargin()

  override val removedSubject = "Dein Randogeddon-Konto wurde gelöscht"
  override fun removed(name: String, reason: String?) = """
    |Hallo $name,
    |
    |ein Administrator hat dein Randogeddon-Konto gelöscht (Anmeldung, E-Mail-Adresse, Einstellungen). Deine Sessions
    |bleiben, mit deinem Namen darin.${if (reason.isNullOrBlank()) "" else "\n|\n|Der angegebene Grund: $reason"}
    |""".trimMargin()

  override val passwordResetSubject = "Ein neues Passwort für dein Randogeddon-Konto"
  override fun passwordReset(name: String, link: String, validMinutes: Int, until: String) = """
    |Hallo $name,
    |
    |jemand möchte das Passwort deines Randogeddon-Kontos zurücksetzen. Um ein neues zu wählen, öffne diesen Link:
    |
    |$link
    |
    |Der Link funktioniert einmal, $validMinutes Minuten lang (bis $until). Deine Zwei-Faktor-Anmeldung bleibt, wie sie
    |ist.
    |
    |Wenn du das nicht angefordert hast, kannst du diese Mail ignorieren: Dein Passwort bleibt, wie es ist.
    |""".trimMargin()

  override val passwordChangedSubject = "Das Passwort deines Randogeddon-Kontos wurde geändert"
  override fun passwordChanged(name: String, at: String) = """
    |Hallo $name,
    |
    |das Passwort deines Randogeddon-Kontos wurde mit einem Link aus einer Mail an diese Adresse neu gesetzt ($at).
    |Jede Anmeldung deines Kontos ist beendet (auch die von Randogeddon Bridge).
    |
    |Wenn du das warst, ist nichts zu tun. Wenn nicht, kann jemand deine Mails lesen: Sichere dein Postfach und setze
    |dein Passwort danach erneut über die Anmeldeseite zurück.
    |""".trimMargin()
}
