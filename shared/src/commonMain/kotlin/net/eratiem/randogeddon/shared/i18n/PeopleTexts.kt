package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.SessionRole

/** A session's people (in the panel), its watchers (for its players) and its share link. */
interface PeopleTexts {
  fun role(role: SessionRole): String
  /** `2 watchers`. */
  fun roleCount(role: SessionRole, n: Int): String
  /** What a part in the session means (the ⓘ). */
  fun roleTip(role: SessionRole): String
  val title: String
  val watchers: String
  val nobodyWatches: String
  fun watching(n: Int): String
  fun invited(address: String, role: SessionRole): String
  fun isNow(name: String, role: SessionRole): String
  val nobodyLeft: String
  val someone: String
  val or: String
  val newAddress: String
  val adding: String
  val invite: String
  val add: String
  val inviteTip: String
  fun takeAway(role: SessionRole): String
  val yes: String
  val remove: String
  val shareLink: String
  val shareLinkTip: String
  val off: String
  /** `on since 14 Nov 2026, 18:21, made by Alice`. */
  fun onSince(date: String, by: String?): String
  val newLink: String
  val makeLink: String
  val yesOff: String
  val turnOff: String
  val shownOnlyNow: String
}

/** A share link's page: the dashboard without a login. */
interface WatchTexts {
  val linkGone: String
  /** `12.3 % of 1,234 checks done · watching, read-only`. */
  fun progress(percent: String, total: String): String
}

internal object EnglishPeople : PeopleTexts {
  override fun role(role: SessionRole) = when (role) {
    SessionRole.PLAYER -> "Player"
    SessionRole.MANAGER -> "Session-Manager"
    SessionRole.SUPPORT -> "Support"
    SessionRole.WATCHER -> "Watcher"
  }
  override fun roleCount(role: SessionRole, n: Int) = when (role) {
    SessionRole.PLAYER -> if (n == 1) "1 player" else "$n players"
    SessionRole.MANAGER -> if (n == 1) "1 Session-Manager" else "$n Session-Managers"
    SessionRole.SUPPORT -> "$n support"
    SessionRole.WATCHER -> if (n == 1) "1 watcher" else "$n watchers"
  }
  override fun roleTip(role: SessionRole) = when (role) {
    SessionRole.PLAYER -> "Plays one or more of the session's slots."
    SessionRole.MANAGER -> "Manages the session as you do: its state, game server, console, cheats and people."
    SessionRole.SUPPORT -> "Helps the players: gives items, checks locations and free hints, and sees the spoiler log while it runs. Logged in the feed."
    SessionRole.WATCHER -> "Follows the session: dashboard, feed and hints; no slots, the spoiler log once it is finished."
  }
  override val title = "People"
  override val watchers = "Watchers"
  override val nobodyWatches = "nobody watches yet"
  override fun watching(n: Int) = "$n watching"
  override fun invited(address: String, role: SessionRole) =
    "Invited $address as ${role(role).lowercase()}: the link works for 7 days."
  override fun isNow(name: String, role: SessionRole) = "$name is ${role(role).lowercase()} now."
  override val nobodyLeft = "Nobody left to add"
  override val someone = "Someone…"
  override val or = "or"
  override val newAddress = "A new e-mail address"
  override val adding = "Adding…"
  override val invite = "Invite"
  override val add = "Add"
  override val inviteTip = "Only an address that has no account gets an invitation; someone who has one is picked by name."
  override fun takeAway(role: SessionRole) = "Take ${role(role).lowercase()} away?"
  override val yes = "Yes"
  override val remove = "Remove"
  override val shareLink = "Share link"
  override val shareLinkTip =
    "For a stream, or friends without an account: whoever has the link sees the dashboard, read-only and without a " +
      "login. No slot passwords, no spoiler log. A new link replaces the old one."
  override val off = "off"
  override fun onSince(date: String, by: String?) = "on since $date${by?.let { ", made by $it" } ?: ""}"
  override val newLink = "New link"
  override val makeLink = "Make a link"
  override val yesOff = "Yes, off"
  override val turnOff = "Turn off"
  override val shownOnlyNow = "Shown only now: copy it. The old link doesn't work any more."
}

internal object GermanPeople : PeopleTexts {
  override fun role(role: SessionRole) = when (role) {
    SessionRole.PLAYER -> "Spieler"
    SessionRole.MANAGER -> "Session-Manager"
    SessionRole.SUPPORT -> "Support"
    SessionRole.WATCHER -> "Zuschauer"
  }
  override fun roleCount(role: SessionRole, n: Int) = "$n ${role(role)}"
  override fun roleTip(role: SessionRole) = when (role) {
    SessionRole.PLAYER -> "Spielt einen oder mehrere Slots der Session."
    SessionRole.MANAGER -> "Verwaltet die Session wie du: ihren Zustand, Spielserver, Konsole, Cheats und Leute."
    SessionRole.SUPPORT -> "Hilft den Spielern: gibt Items, checkt Orte und vergibt Gratis-Hinweise, und sieht das " +
      "Spoiler-Log, während sie läuft. Im Feed protokolliert."
    SessionRole.WATCHER -> "Verfolgt die Session: Dashboard, Feed und Hinweise; keine Slots, das Spoiler-Log, sobald " +
      "sie beendet ist."
  }
  override val title = "Leute"
  override val watchers = "Zuschauer"
  override val nobodyWatches = "noch schaut niemand zu"
  override fun watching(n: Int) = "$n schauen zu"
  override fun invited(address: String, role: SessionRole) =
    "$address als ${role(role)} eingeladen: Der Link funktioniert 7 Tage."
  override fun isNow(name: String, role: SessionRole) = "$name ist jetzt ${role(role)}."
  override val nobodyLeft = "Niemand mehr hinzuzufügen"
  override val someone = "Jemand…"
  override val or = "oder"
  override val newAddress = "Eine neue E-Mail-Adresse"
  override val adding = "Wird hinzugefügt…"
  override val invite = "Einladen"
  override val add = "Hinzufügen"
  override val inviteTip =
    "Nur eine Adresse ohne Konto bekommt eine Einladung; wer schon eins hat, wird beim Namen ausgewählt."
  override fun takeAway(role: SessionRole) = "Rolle ${role(role)} entziehen?"
  override val yes = "Ja"
  override val remove = "Entfernen"
  override val shareLink = "Link zum Teilen"
  override val shareLinkTip =
    "Für einen Stream oder Freunde ohne Konto: Wer den Link hat, sieht das Dashboard, nur lesend und ohne " +
      "Anmeldung. Keine Slot-Passwörter, kein Spoiler-Log. Ein neuer Link ersetzt den alten."
  override val off = "aus"
  override fun onSince(date: String, by: String?) = "an seit $date${by?.let { ", erstellt von $it" } ?: ""}"
  override val newLink = "Neuer Link"
  override val makeLink = "Link erstellen"
  override val yesOff = "Ja, aus"
  override val turnOff = "Ausschalten"
  override val shownOnlyNow = "Nur jetzt angezeigt: Kopiere ihn. Der alte Link funktioniert nicht mehr."
}

internal object EnglishWatch : WatchTexts {
  override val linkGone = "This link doesn't work (any more): it was turned off or replaced by a new one."
  override fun progress(percent: String, total: String) = "$percent % of $total checks done · watching, read-only"
}

internal object GermanWatch : WatchTexts {
  override val linkGone = "Dieser Link funktioniert nicht (mehr): Er wurde ausgeschaltet oder durch einen neuen ersetzt."
  override fun progress(percent: String, total: String) = "$percent % von $total Checks erledigt · zuschauend, nur lesend"
}
