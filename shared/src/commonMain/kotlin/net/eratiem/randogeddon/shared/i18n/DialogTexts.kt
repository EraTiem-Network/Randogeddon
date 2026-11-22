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
  /** A long list's page: which of how many, and its arrows. */
  fun rangeOf(from: String, to: String, total: String): String
  val previousPage: String
  val nextPage: String
  /** After the item: `from Bob-HK · Crossroads`. */
  fun from(sender: String, location: String?): String
  fun remainingChecks(n: Int): String
  /** The slot panel's tabs, by key: checks, transported, connections (and map). */
  fun tab(key: String): String
  fun availableChecks(n: Int): String
  /** Its logic isn't known: not running for it (a seed from elsewhere, a race), or not yet. */
  val availableUnknown: String
  fun sentItems(n: Int): String
  /** A sent item's receiver and the location it was at. */
  fun to(receiver: String, location: String): String
  /** The map's filter that shows every location; [mapOpen] and [mapAvailable] are its states (and its other filters). */
  val mapAll: String
  val mapChecked: String
  val mapOpen: String
  val mapAvailable: String
  /** What a checked location held, and whom it went to. */
  fun mapFound(item: String, receiver: String): String
  /** A box of the map: how many of its locations are checked. */
  fun mapRegion(checked: Int, total: Int): String
  /** What a box connects to besides the boxes its lines go to. */
  fun mapAlso(names: String): String
  /** No map known for the slot. */
  val mapNone: String
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
  /** A hosted slot in *Your connection*: for its players, and for its Session-Managers (who see its connection). */
  val hostedNote: String
  /** A slot still locked: nothing to connect until its key is found. */
  val lockedNote: String
  val hostedManagerNote: String
  val download: String
  /** How to use a slot's file (the ⓘ next to it). */
  val patchHowTo: String
  // Randogeddon Bridge
  val bridge: String
  /** The heading of the bridge's downloads, of [version]. */
  fun downloads(version: String): String
  /** The button for the builds' checksums (the file `SHA256SUMS`). */
  val checksums: String
  /** The buttons for the checksums' signature and the key it is checked with. */
  val signature: String
  val signingKey: String
  /** The bridge's ⓘ, in parts: what it is, what it does, how to use it, then starting it on each system. */
  val bridgeWhatTitle: String
  val bridgeWhat: String
  val bridgeDoesTitle: String
  val bridgeDoes: String
  val bridgeHowTitle: String
  val bridgeHow: String
  val bridgeWindows: String
  val bridgeMacos: String
  val bridgeLinux: String
  val makeKey: String
  /** Under a new key: shown once, works until [until]. */
  fun keyMade(until: String): String
  /** Folds the bridge away, or opens it again. */
  val foldBridge: String
  val openBridge: String
  /** The server copied as shown (host:port), its host, its port, or as a wss:// address. */
  val copyAsShown: String
  val copyHost: String
  val copyPort: String
  val copyWss: String
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
  override fun rangeOf(from: String, to: String, total: String) = "$from–$to of $total"
  override val previousPage = "Previous"
  override val nextPage = "Next"
  override fun from(sender: String, location: String?) = "  from $sender${location?.let { " · $it" } ?: ""}"
  override fun remainingChecks(n: Int) = "Remaining checks ($n)"
  override fun tab(key: String) = when (key) {
    "checks" -> "Checks"
    "transported" -> "Transported items"
    "connections" -> "Connections"
    "map" -> "Map"
    else -> key
  }
  override fun availableChecks(n: Int) = "Available now ($n)"
  override val availableUnknown = "Not known: the session's logic doesn't run for this slot."
  override fun sentItems(n: Int) = "Sent items ($n)"
  override fun to(receiver: String, location: String) = "  to $receiver · $location"
  override val mapAll = "All"
  override val mapChecked = "Checked"
  override val mapOpen = "Open"
  override val mapAvailable = "Available now"
  override fun mapFound(item: String, receiver: String) = "Held $item for $receiver"
  override fun mapRegion(checked: Int, total: Int) = if (total == 0) "No locations: a way through" else "$checked of $total checked"
  override fun mapAlso(names: String) = "Also connects to: $names"
  override val mapNone =
    "No map yet: it comes from the session's logic while that runs (not for a seed from elsewhere or a race)."
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
  override val sessions = "Sessions"
  override val status = "Status"
  override val online = "online"
  override val offline = "offline"
  override fun receivedItems(n: Int) = "Erhaltene Items ($n)"
  override val filter = "Filtern…"
  override fun rangeOf(from: String, to: String, total: String) = "$from–$to von $total"
  override val previousPage = "Zurück"
  override val nextPage = "Weiter"
  override fun from(sender: String, location: String?) = "  von $sender${location?.let { " · $it" } ?: ""}"
  override fun remainingChecks(n: Int) = "Offene Checks ($n)"
  override fun tab(key: String) = when (key) {
    "checks" -> "Checks"
    "transported" -> "Transportierte Items"
    "connections" -> "Verbindungen"
    "map" -> "Karte"
    else -> key
  }
  override fun availableChecks(n: Int) = "Jetzt erreichbar ($n)"
  override val availableUnknown = "Nicht bekannt: Die Logik der Session läuft für diesen Slot nicht."
  override fun sentItems(n: Int) = "Gesendete Items ($n)"
  override fun to(receiver: String, location: String) = "  an $receiver · $location"
  override val mapAll = "Alle"
  override val mapChecked = "Gecheckt"
  override val mapOpen = "Offen"
  override val mapAvailable = "Jetzt erreichbar"
  override fun mapFound(item: String, receiver: String) = "Enthielt $item für $receiver"
  override fun mapRegion(checked: Int, total: Int) = if (total == 0) "Keine Orte: ein Durchgang" else "$checked von $total gecheckt"
  override fun mapAlso(names: String) = "Auch verbunden mit: $names"
  override val mapNone =
    "Noch keine Karte: Sie kommt von der Logik der Session, solange diese läuft (nicht bei einem Seed von anderswo oder einem Race)."
  override val allDone = "Alles erledigt!"
}

internal object EnglishConnection : ConnectionTexts {
  override val title = "Your connection"
  override fun loggedInAs(name: String) = "Logged in as $name"
  override val watching = "Watching"
  override val support = "Support"
  override val sessionManager = "Session-Manager"
  override val server = "Server"
  override val watcherNote = "You're watching this session: no slots; the spoiler log opens once it has finished."
  override val supportNote = "You support this session: you help its players below."
  override val notPlaying = "You don't play in this session."
  override val slotsAppear = "Your slots appear here once the seed is running."
  override val slot = "Slot"
  override val password = "Password"
  override val hide = "Hide"
  override val show = "Show"
  override val patchFiles = "Patch files"
  override val noPatchFiles = "None for this slot: its game needs no patch."
  override val hostedNote = "This slot is hosted: its game runs as a server of its own. Join that server with your " +
    "game; its patch files are below."
  override val lockedNote = "This slot is still locked: it can connect once its key is found (the dashboard shows " +
    "where). Until then there is nothing to enter."
  override val hostedManagerNote = "This slot is hosted: its game runs as a server of its own, which the players join. " +
    "Set that server up with this connection."
  override val download = "Download"
  override val patchHowTo = "Your slot's file from the seed: a patch, or a mod for some games. A patch is opened " +
    "with the Archipelago Launcher (Open Patch) together with your own copy of the game (the ROM); the Launcher writes " +
    "the patched game next to it and can start it with its client. Mods go where the game's Archipelago guide says."
  override val bridge = "Randogeddon Bridge"
  override fun downloads(version: String) = "Downloads · $version"
  override val checksums = "Checksums"
  override val signature = "Signature"
  override val signingKey = "Key"
  override val bridgeWhatTitle = "What it is"
  override val bridgeWhat = "A small app for your PC, for games whose client can't connect securely (TLS). Randogeddon " +
    "only lets secure connections in."
  override val bridgeDoesTitle = "What it does"
  override val bridgeDoes = "The game connects to localhost on your PC; the bridge takes it to the session securely and " +
    "fills in your slot's name and password, so nothing has to be typed in the game."
  override val bridgeHowTitle = "How to use it"
  override val bridgeHow = "Download it for your system and start it: its page opens in your browser. Create a key " +
    "here and paste it there. Pick the session and the slot, Open, and enter the address it shows (localhost:<port>) " +
    "in the game."
  override val bridgeWindows = "The app has no Windows code signature, so Windows asks first: More info, then Run anyway."
  override val bridgeMacos = "The app isn't notarised by Apple: right-click it, Open, then Open again. Or in the Terminal: " +
    "xattr -d com.apple.quarantine <file>"
  override val bridgeLinux = "Make it executable first: chmod +x <file>"
  override val makeKey = "Create key"
  override fun keyMade(until: String) =
    "Paste it into the bridge. It is shown only now and works until $until; you can end it under Your account."
  override val foldBridge = "Collapse"
  override val openBridge = "Expand"
  override val copyAsShown = "As shown"
  override val copyHost = "Host"
  override val copyPort = "Port"
  override val copyWss = "wss:// address"
}

internal object GermanConnection : ConnectionTexts {
  override val title = "Deine Verbindung"
  override fun loggedInAs(name: String) = "Angemeldet als $name"
  override val watching = "Zuschauer"
  override val support = "Support"
  override val sessionManager = "Session-Manager"
  override val server = "Server"
  override val watcherNote = "Du schaust bei dieser Session zu: keine Slots; das Spoiler-Log gibt es, sobald sie beendet ist."
  override val supportNote = "Du unterstützt diese Session: Du hilfst ihren Spielern unten."
  override val notPlaying = "Du spielst in dieser Session nicht mit."
  override val slotsAppear = "Deine Slots erscheinen hier, sobald der Seed läuft."
  override val slot = "Slot"
  override val password = "Passwort"
  override val hide = "Verbergen"
  override val show = "Zeigen"
  override val patchFiles = "Patch-Dateien"
  override val noPatchFiles = "Keine für diesen Slot: Sein Spiel braucht keinen Patch."
  override val hostedNote = "Dieser Slot wird gehostet: Sein Spiel läuft als eigener Server. Tritt diesem Server mit " +
    "deinem Spiel bei; seine Patch-Dateien stehen unten."
  override val lockedNote = "Dieser Slot ist noch gesperrt: Er kann sich verbinden, sobald sein Schlüssel gefunden ist " +
    "(das Dashboard zeigt, wo). Bis dahin gibt es nichts einzutragen."
  override val hostedManagerNote = "Dieser Slot wird gehostet: Sein Spiel läuft als eigener Server, dem die Spieler " +
    "beitreten. Richte diesen Server mit dieser Verbindung ein."
  override val download = "Herunterladen"
  override val patchHowTo = "Die Datei deines Slots aus dem Seed: ein Patch oder bei manchen Spielen eine Mod. Einen " +
    "Patch öffnest du mit dem Archipelago Launcher (Open Patch) zusammen mit deiner eigenen Kopie des Spiels (dem ROM); " +
    "der Launcher legt das gepatchte Spiel daneben und kann es mit seinem Client starten. Mods kommen dorthin, wo es " +
    "die Archipelago-Anleitung des Spiels sagt."
  override val bridge = "Randogeddon Bridge"
  override fun downloads(version: String) = "Downloads · $version"
  override val checksums = "Prüfsummen"
  override val signature = "Signatur"
  override val signingKey = "Schlüssel"
  override val bridgeWhatTitle = "Was sie ist"
  override val bridgeWhat = "Eine kleine App für deinen PC, für Spiele, deren Client sich nicht sicher (TLS) verbinden " +
    "kann. Randogeddon lässt nur sichere Verbindungen herein."
  override val bridgeDoesTitle = "Was sie tut"
  override val bridgeDoes = "Das Spiel verbindet sich mit localhost auf deinem PC; die Bridge bringt es sicher zur Session " +
    "und trägt Name und Passwort deines Slots ein; im Spiel musst du nichts eingeben."
  override val bridgeHowTitle = "So nutzt du sie"
  override val bridgeHow = "Lade sie für dein System herunter und starte sie: Ihre Seite öffnet sich im Browser. Erstelle " +
    "hier einen Schlüssel und füge ihn dort ein. Wähle Session und Slot, Öffnen, und gib die gezeigte Adresse " +
    "(localhost:<Port>) im Spiel ein."
  override val bridgeWindows = "Die App hat keine Windows-Signatur, deshalb fragt Windows erst nach: Weitere Informationen, dann Trotzdem " +
    "ausführen."
  override val bridgeMacos = "Die App ist nicht von Apple beglaubigt: Rechtsklick, Öffnen, dann noch einmal Öffnen. Oder im Terminal: " +
    "xattr -d com.apple.quarantine <Datei>"
  override val bridgeLinux = "Erst ausführbar machen: chmod +x <Datei>"
  override val makeKey = "Schlüssel erstellen"
  override fun keyMade(until: String) =
    "Füge ihn in die Bridge ein. Er wird nur jetzt gezeigt und gilt bis $until; beenden kannst du ihn unter Dein Konto."
  override val foldBridge = "Einklappen"
  override val openBridge = "Aufklappen"
  override val copyAsShown = "Wie angezeigt"
  override val copyHost = "Host"
  override val copyPort = "Port"
  override val copyWss = "wss://-Adresse"
}
