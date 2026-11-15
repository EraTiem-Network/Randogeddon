package net.eratiem.randogeddon.shared.i18n

/** The overviews of a person and of a slot, opened from the dashboard. */
interface DialogTexts {
  fun personLine(slots: Int, checked: Int, total: Int, online: Boolean): String
  val played: String
  val goals: String
  val locked: String
  val hintPoints: String
  val slots: String
  /** A slot of the person: `12 / 340 · 2h 5m played · done 20:15`. */
  fun slotLine(checked: Int, total: Int, played: String, doneAt: String?): String
  fun openHints(name: String, n: Int): String
  val none: String
  /** `{location} · in {finder} · for {receiver}`. */
  val hintWhereFor: String
  fun connections(n: Int): String
  val noClientYet: String
  /** A connection: `seen playing 18:02 – 19:40`, `… – now`. */
  fun connection(implicit: Boolean, start: String, end: String?): String
  val recentActivity: String
  val nothingYet: String
  // The slot
  fun slotHeading(game: String, checked: Int, total: Int, percent: String): String
  val loading: String
  val sessions: String
  val status: String
  val online: String
  val offline: String
  fun receivedItems(n: Int): String
  val filter: String
  /** After the item: `from Bob-HK · Crossroads`. */
  fun from(sender: String, location: String?): String
  fun remainingChecks(n: Int): String
  val allDone: String
}

/** *Your connection* on the dashboard: where to connect, the user's slots, their passwords and files. */
interface ConnectionTexts {
  val title: String
  fun loggedInAs(name: String): String
  val watching: String
  val support: String
  val sessionManager: String
  val server: String
  val watcherNote: String
  val supportNote: String
  val notPlaying: String
  val slotsAppear: String
  val slot: String
  val password: String
  val hide: String
  val show: String
  /** The heading of the slot's files. */
  val patchFiles: String
  val noPatchFiles: String
  val download: String
  /** How to use a slot's file (the ⓘ next to it). */
  val patchHowTo: String
  // Randogeddon Bridge
  val bridge: String
  /** The heading of the bridge's downloads, of [version]. */
  fun downloads(version: String): String
  val bridgeLine: String
  /** What the bridge is and how it is set up (its ⓘ). */
  val bridgeHowTo: String
  val makeKey: String
  /** Under a new key: shown once, works until [until]. */
  fun keyMade(until: String): String
  /** How to start the unsigned bridge (the downloads' ⓘ). */
  val bridgeUnsigned: String
}

internal object EnglishDialogs : DialogTexts {
  override fun personLine(slots: Int, checked: Int, total: Int, online: Boolean) =
    "$slots ${if (slots == 1) "slot" else "slots"} · $checked / $total checks${if (online) " · online" else ""}"
  override val played = "Played"
  override val goals = "Goals"
  override val locked = "Locked"
  override val hintPoints = "Hint points"
  override val slots = "Slots"
  override fun slotLine(checked: Int, total: Int, played: String, doneAt: String?) =
    "$checked / $total · $played played${doneAt?.let { " · done $it" } ?: ""}"
  override fun openHints(name: String, n: Int) = "Open hints for $name ($n)"
  override val none = "None."
  override val hintWhereFor = "{location} · in {finder} · for {receiver}"
  override fun connections(n: Int) = "Connections ($n)"
  override val noClientYet = "No game client seen yet."
  override fun connection(implicit: Boolean, start: String, end: String?) =
    "${if (implicit) "seen playing " else ""}$start – ${end ?: "now"}"
  override val recentActivity = "Recent activity"
  override val nothingYet = "Nothing yet."
  override fun slotHeading(game: String, checked: Int, total: Int, percent: String) = "$game · $checked / $total checks ($percent%)"
  override val loading = "Loading…"
  override val sessions = "Sessions"
  override val status = "Status"
  override val online = "online"
  override val offline = "offline"
  override fun receivedItems(n: Int) = "Received items ($n)"
  override val filter = "Filter…"
  override fun from(sender: String, location: String?) = "  from $sender${location?.let { " · $it" } ?: ""}"
  override fun remainingChecks(n: Int) = "Remaining checks ($n)"
  override val allDone = "All done!"
}

internal object GermanDialogs : DialogTexts {
  override fun personLine(slots: Int, checked: Int, total: Int, online: Boolean) =
    "$slots ${if (slots == 1) "Slot" else "Slots"} · $checked / $total Checks${if (online) " · online" else ""}"
  override val played = "Gespielt"
  override val goals = "Ziele"
  override val locked = "Gesperrt"
  override val hintPoints = "Hinweispunkte"
  override val slots = "Slots"
  override fun slotLine(checked: Int, total: Int, played: String, doneAt: String?) =
    "$checked / $total · $played gespielt${doneAt?.let { " · fertig $it" } ?: ""}"
  override fun openHints(name: String, n: Int) = "Offene Hinweise für $name ($n)"
  override val none = "Keine."
  override val hintWhereFor = "{location} · in {finder} · für {receiver}"
  override fun connections(n: Int) = "Verbindungen ($n)"
  override val noClientYet = "Noch kein Spiel-Client gesehen."
  override fun connection(implicit: Boolean, start: String, end: String?) =
    "${if (implicit) "beim Spielen gesehen " else ""}$start – ${end ?: "jetzt"}"
  override val recentActivity = "Letzte Aktivität"
  override val nothingYet = "Noch nichts."
  override fun slotHeading(game: String, checked: Int, total: Int, percent: String) = "$game · $checked / $total Checks ($percent %)"
  override val loading = "Lädt…"
  override val sessions = "Sitzungen"
  override val status = "Status"
  override val online = "online"
  override val offline = "offline"
  override fun receivedItems(n: Int) = "Erhaltene Items ($n)"
  override val filter = "Filtern…"
  override fun from(sender: String, location: String?) = "  von $sender${location?.let { " · $it" } ?: ""}"
  override fun remainingChecks(n: Int) = "Offene Checks ($n)"
  override val allDone = "Alles erledigt!"
}

internal object EnglishConnection : ConnectionTexts {
  override val title = "Your connection"
  override fun loggedInAs(name: String) = "Logged in as $name"
  override val watching = "Watching"
  override val support = "Support"
  override val sessionManager = "Session-Manager"
  override val server = "Server"
  override val watcherNote = "You watch this session: no slots, the spoiler log once it is finished."
  override val supportNote = "You support this session: you help its players below."
  override val notPlaying = "You don't play in this session."
  override val slotsAppear = "Your slots appear here once the seed is running."
  override val slot = "Slot"
  override val password = "Password"
  override val hide = "Hide"
  override val show = "Show"
  override val patchFiles = "Patch files"
  override val noPatchFiles = "None for this slot: its game needs no patch."
  override val download = "Download"
  override val patchHowTo = "Your slot's file from the seed: a patch, or a mod for some games. A patch is opened " +
    "with the Archipelago Launcher (Open Patch) together with your own copy of the game (the ROM); the Launcher writes " +
    "the patched game next to it and can start it with its client. Mods go where the game's Archipelago guide says."
  override val bridge = "Bridge"
  override fun downloads(version: String) = "Downloads · $version"
  override val bridgeLine = "for games that can't connect securely"
  override val bridgeHowTo = "Randogeddon Bridge runs on your PC: the game connects to localhost, and the bridge takes " +
    "it to the session securely, with your slot's name and password filled in. Download it, make a key here and paste " +
    "it into the bridge's page."
  override val makeKey = "Create key"
  override fun keyMade(until: String) =
    "Paste it into the bridge. It is shown only now and works until $until; you can end it under Your account."
  override val bridgeUnsigned = "The bridge isn't signed, so the system asks first. Windows: More info, then Run " +
    "anyway. macOS: right-click it, Open (or xattr -d com.apple.quarantine <file>). Linux: chmod +x <file>."
}

internal object GermanConnection : ConnectionTexts {
  override val title = "Deine Verbindung"
  override fun loggedInAs(name: String) = "Angemeldet als $name"
  override val watching = "Zuschauer"
  override val support = "Support"
  override val sessionManager = "Session-Manager"
  override val server = "Server"
  override val watcherNote = "Du schaust bei dieser Session zu: keine Slots, das Spoiler-Log, sobald sie beendet ist."
  override val supportNote = "Du unterstützt diese Session: Du hilfst ihren Spielern unten."
  override val notPlaying = "Du spielst in dieser Session nicht mit."
  override val slotsAppear = "Deine Slots erscheinen hier, sobald der Seed läuft."
  override val slot = "Slot"
  override val password = "Passwort"
  override val hide = "Verbergen"
  override val show = "Zeigen"
  override val patchFiles = "Patch-Dateien"
  override val noPatchFiles = "Keine für diesen Slot: Sein Spiel braucht keinen Patch."
  override val download = "Herunterladen"
  override val patchHowTo = "Die Datei deines Slots aus dem Seed: ein Patch oder bei manchen Spielen eine Mod. Einen " +
    "Patch öffnest du mit dem Archipelago Launcher (Open Patch) zusammen mit deiner eigenen Kopie des Spiels (dem ROM); " +
    "der Launcher legt das gepatchte Spiel daneben und kann es mit seinem Client starten. Mods kommen dorthin, wo es " +
    "die Archipelago-Anleitung des Spiels sagt."
  override val bridge = "Bridge"
  override fun downloads(version: String) = "Downloads · $version"
  override val bridgeLine = "für Spiele, die sich nicht sicher verbinden können"
  override val bridgeHowTo = "Randogeddon Bridge läuft auf deinem PC: Das Spiel verbindet sich mit localhost, und die " +
    "Bridge bringt es sicher zur Session, mit Name und Passwort deines Slots schon eingetragen. Lade sie herunter, mach " +
    "hier einen Schlüssel und füge ihn auf der Seite der Bridge ein."
  override val makeKey = "Schlüssel erstellen"
  override fun keyMade(until: String) =
    "Füge ihn in die Bridge ein. Er wird nur jetzt gezeigt und gilt bis $until; beenden kannst du ihn unter Dein Konto."
  override val bridgeUnsigned = "Die Bridge ist nicht signiert, deshalb fragt das System erst. Windows: Weitere " +
    "Informationen, dann Trotzdem ausführen. macOS: Rechtsklick, Öffnen (oder xattr -d com.apple.quarantine <Datei>). " +
    "Linux: chmod +x <Datei>."
}
