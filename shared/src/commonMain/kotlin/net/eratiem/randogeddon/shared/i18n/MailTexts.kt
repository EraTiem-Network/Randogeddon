package net.eratiem.randogeddon.shared.i18n

/** The mails Randogeddon sends (plain text): each one's subject and text. */
interface MailTexts {
  val invitationSubject: String
  fun invitation(link: String, validDays: Int): String
  val confirmationSubject: String
  fun confirmation(name: String, link: String, validDays: Int): String
  fun deletionSubject(date: String): String
  fun deletionWarning(name: String, date: String, settingsLink: String): String
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
}
