package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.GameServerStatus

/** A session in the panel: its state and actions, its players, the past sessions, a new session's form. */
interface SessionAdminTexts {
  // State and control
  val stateAndControl: String
  val deleted: String
  val deployedWithCli: String
  fun created(date: String): String
  fun pointHints(cost: Int): String
  val automaticHints: String
  fun releaseMode(mode: String): String
  fun sessionsRunning(running: Int, inUse: Int, capacity: Int): String
  fun server(status: GameServerStatus): String
  val autoDeletionDisabled: String
  fun deletedAutomaticallyOn(date: String): String
  // Its players
  fun playersSubtitle(players: Int, slots: Int): String
  val nobodyNamed: String
  val nobodyPlays: String
  val filesOfSlots: String
  val filesTip: String
  fun slotNumber(n: Int): String
  // The switch
  val noneRunning: String
  val pickOne: String
  val newSession: String
  // Past sessions
  val pastSessions: String
  val pastSubtitle: String
  val noneYet: String
  fun finishedOn(date: String, by: String?): String
  val reopen: String
  val enableAutoDeletion: String
  val disableAutoDeletion: String
  val delete: String
  val confirmDeleteFinished: String
  val yesDelete: String
  val confirmEnable: String
  val confirmDisable: String
  val yesEnable: String
  val yesDisable: String
  val createdLabel: String
  val finishedLabel: String
  /** When it finished: `14 Nov 2026, 18:21 by Alice`, or `not yet, when every slot was done`. */
  fun finishedValue(date: String?, by: String?): String
  val reopenLabel: String
  val reopenNotPossible: String
  fun reopenPossibleUntil(date: String): String
  val reopenNoLonger: String
  val deletionLabel: String
  val notAutomatic: String
  val playersHeading: String
  val nobodyWasNamed: String
  // A new session
  val taken: String
  fun newSessionSubtitle(capacity: String): String
  val everyServerInUse: String
  val atLimit: String
  fun mayCreate(n: Int): String
  val sessionId: String
  // The current session's line
  fun finishedSentence(complete: Boolean, date: String): String
  val completedNoReopen: String
  fun reopenUntilSentence(date: String): String
  val pausedTooLong: String
  fun onceNewer(deletion: String): String
  fun endsByItselfAt(time: String): String
  val confirmEnd: String
  val confirmPause: String
  val yesEnd: String
  val yesPause: String
  val confirmSpoiler: String
  val showIt: String
  val confirmDeleteRunning: String
  val endSession: String
  val pauseSession: String
  val deleteSession: String
  val dashboard: String
  val spoilerLog: String
  // The form
  val stepYamls: String
  val addYamls: String
  val clearAll: String
  fun removeAll(n: Int): String
  fun removeFile(file: String): String
  fun notAdded(problem: String): String
  val uploadMissing: String
  val checkAgain: String
  val stepPlayers: String
  val playersHint: String
  val locks: String
  val lockChoose: String
  val lockRandom: String
  val lockMixed: String
  val startOpen: String
  fun openOf(open: Int, n: Int): String
  val startsLocked: String
  val stepOptions: String
  val pointHintsCheck: String
  val automaticHintsCheck: String
  val keysEarly: String
  val cheatHints: String
  val hintCost: String
  val pointsPerCheck: String
  val release: String
  val collect: String
  val remaining: String
  val archipelago: String
  fun archipelagoVersion(version: String): String
  val generating: String
  val generateAndStart: String
  val generatesSeed: String
  val addPlayer: String
  val addAnother: String
  val inviting: String
  fun inviteAddress(address: String): String
  val noAccountMatches: String
  fun invitedName(name: String): String
  val sessionCreated: String
  fun startLocked(slots: String): String
  val done: String
  val lockOpen: String
  val lockChance: String
  val lockLocked: String
}

/** What each option of a new session and each action does, for their ⓘ. */
interface SessionTips {
  val players: String
  val invite: String
  val locks: String
  /** The lock modes' names with what they mean. */
  val lockModes: List<Pair<String, String>>
  val openSlots: String
  val startsLocked: String
  val lastOpen: String
  val lockSwitch: String
  val pointHints: String
  val autoHints: String
  val earlyKeys: String
  val hintCost: String
  val checkPoints: String
  val release: String
  /** Archipelago's values (as they are in the dropdown) with what they mean. */
  val releaseChoices: List<Pair<String, String>>
  val collect: String
  val collectChoices: List<Pair<String, String>>
  val remaining: String
  val remainingChoices: List<Pair<String, String>>
  val releaseVersion: String
  val cheatHints: String
  val sessionId: String
  val dashboard: String
  val pause: String
  val end: String
  val reopen: String
  val keep: String
  val spoiler: String
}

internal object EnglishSessionAdmin : SessionAdminTexts {
  override val stateAndControl = "State and Control"
  override val deleted = "Deleted."
  override val deployedWithCli = "deployed with the CLI"
  override fun created(date: String) = "created $date"
  override fun pointHints(cost: Int) = "point hints ($cost %)"
  override val automaticHints = "automatic hints"
  override fun releaseMode(mode: String) = "release $mode"
  override fun sessionsRunning(running: Int, inUse: Int, capacity: Int) =
    "$running ${if (running == 1) "session" else "sessions"} running · $inUse of $capacity game servers in use " +
      "(a finished session doesn't take one)"
  override fun server(status: GameServerStatus) = when (status) {
    GameServerStatus.RUNNING -> "server running"
    GameServerStatus.STARTING -> "server starting"
    GameServerStatus.CRASHED -> "server crashed"
    GameServerStatus.STOPPED -> "server stopped"
    GameServerStatus.NO_SEED -> "no seed"
  }
  override val autoDeletionDisabled = "auto-deletion disabled"
  override fun deletedAutomaticallyOn(date: String) = "deleted automatically on $date"
  override fun playersSubtitle(players: Int, slots: Int) =
    "$players ${if (players == 1) "player" else "players"}, $slots ${if (slots == 1) "slot" else "slots"}"
  override val nobodyNamed = "Nobody is named for its slots."
  override val nobodyPlays = "Nobody plays"
  override val filesOfSlots = "Files of the slots"
  override val filesTip = "Each player downloads their own under Your connection; here every slot's."
  override fun slotNumber(n: Int) = "slot $n"
  override val noneRunning = "none running"
  override val pickOne = "pick one…"
  override val newSession = "New session"
  override val pastSessions = "Past sessions"
  override val pastSubtitle = "deleted automatically after a while, unless that is disabled"
  override val noneYet = "None yet."
  override fun finishedOn(date: String, by: String?) = "finished $date${by?.let { " by $it" } ?: ""}"
  override val reopen = "Reopen"
  override val enableAutoDeletion = "Enable auto-deletion"
  override val disableAutoDeletion = "Disable auto-deletion"
  override val delete = "Delete"
  override val confirmDeleteFinished = "Delete it for good? Its seed, save and history go; the logins stay."
  override val yesDelete = "Yes, delete"
  override val confirmEnable = "Enable its auto-deletion again? Its time counts from now."
  override val confirmDisable = "Disable its auto-deletion? It stays until it is deleted by hand."
  override val yesEnable = "Yes, enable"
  override val yesDisable = "Yes, disable"
  override val createdLabel = "Created"
  override val finishedLabel = "Finished"
  override fun finishedValue(date: String?, by: String?) =
    (date ?: "not yet") + (by?.let { " by $it" } ?: ", when every slot was done")
  override val reopenLabel = "Reopen"
  override val reopenNotPossible = "not possible, it was completed"
  override fun reopenPossibleUntil(date: String) = "possible until $date"
  override val reopenNoLonger = "no longer possible"
  override val deletionLabel = "Deletion"
  override val notAutomatic = "not automatic"
  override val playersHeading = "PLAYERS"
  override val nobodyWasNamed = "Nobody was named for its slots."
  override val taken = "Taken"
  override fun newSessionSubtitle(capacity: String) = "as many run at once as the gateway has game servers ($capacity)"
  override val everyServerInUse = "Every game server is in use: finish or stop a session first."
  override val atLimit = "You manage as many running sessions as you may at once: finish one first."
  override fun mayCreate(n: Int) = "You may create $n more running ${if (n == 1) "session" else "sessions"}."
  override val sessionId = "Session ID"
  override fun finishedSentence(complete: Boolean, date: String) = "${if (complete) "Ended" else "Paused"} $date."
  override val completedNoReopen = "It was completed, so it can't be reopened."
  override fun reopenUntilSentence(date: String) = "It can be reopened until $date."
  override val pausedTooLong = "It was paused more than 14 days ago and can't be reopened any more."
  override fun onceNewer(deletion: String) = "Once a newer session exists: $deletion."
  override fun endsByItselfAt(time: String) = "Every slot is done: it ends by itself at $time."
  override val confirmEnd = "End it? The game server stops for good; a completed session can't be reopened."
  override val confirmPause = "Pause it? The game server stops; it can be reopened within 14 days."
  override val yesEnd = "Yes, end it"
  override val yesPause = "Yes, pause it"
  override val confirmSpoiler = "A full spoiler: where every item of the running session is. Show it?"
  override val showIt = "Show it"
  override val confirmDeleteRunning = "Delete it? The game server stops; the seed and its save go to data/old. The logins stay."
  override val endSession = "End session"
  override val pauseSession = "Pause session"
  override val deleteSession = "Delete session"
  override val dashboard = "Dashboard"
  override val spoilerLog = "Spoiler log…"
  override val stepYamls = "1. The YAMLs, one per slot"
  override val addYamls = "Add YAMLs…"
  override val clearAll = "Clear all"
  override fun removeAll(n: Int) = "Remove all $n YAMLs? The players and locks chosen so far go too."
  override fun removeFile(file: String) = "Remove $file?"
  override fun notAdded(problem: String) = "Not added: $problem"
  override val uploadMissing = "Upload the missing APWorlds below, then:"
  override val checkAgain = "Check again"
  override val stepPlayers = "2. Who plays which slot"
  override val playersHint =
    "One slot per YAML. A slot can have several players (they play it together). New names get a login when the " +
      "session is created."
  override val locks = "Locks"
  override val lockChoose = "Choose per slot"
  override val lockRandom = "Random"
  override val lockMixed = "Mixed"
  override val startOpen = "Start open"
  override fun openOf(open: Int, n: Int) = "$open of $n slots"
  override val startsLocked = "starts locked"
  override val stepOptions = "3. Options"
  override val pointHintsCheck = "Point hints"
  override val automaticHintsCheck = "Automatic hints"
  override val keysEarly = "Keys early"
  override val cheatHints = "Cheat hints"
  override val hintCost = "Hint cost %"
  override val pointsPerCheck = "Points per check"
  override val release = "Release"
  override val collect = "Collect"
  override val remaining = "Remaining"
  override val archipelago = "Archipelago"
  override fun archipelagoVersion(version: String) = "Archipelago $version"
  override val generating = "Generating…"
  override val generateAndStart = "Generate and start"
  override val generatesSeed = "Archipelago generates the seed; that can take a minute."
  override val addPlayer = "Add a player…"
  override val addAnother = "Add another…"
  override val inviting = "Inviting…"
  override fun inviteAddress(address: String) = "Invite $address"
  override val noAccountMatches = "No account matches; type their e-mail address to invite them"
  override fun invitedName(name: String) = "$name (invited)"
  override val sessionCreated = "Session created; the game server is starting."
  override fun startLocked(slots: String) = "Start locked: $slots"
  override val done = "Done"
  override val lockOpen = "open"
  override val lockChance = "random"
  override val lockLocked = "locked"
}

internal object GermanSessionAdmin : SessionAdminTexts {
  override val stateAndControl = "Zustand und Steuerung"
  override val deleted = "Gelöscht."
  override val deployedWithCli = "mit der CLI bereitgestellt"
  override fun created(date: String) = "erstellt $date"
  override fun pointHints(cost: Int) = "Punkte-Hinweise ($cost %)"
  override val automaticHints = "automatische Hinweise"
  override fun releaseMode(mode: String) = "Release $mode"
  override fun sessionsRunning(running: Int, inUse: Int, capacity: Int) =
    "$running ${if (running == 1) "Session läuft" else "Sessions laufen"} · $inUse von $capacity Spielservern belegt " +
      "(eine beendete Session belegt keinen)"
  override fun server(status: GameServerStatus) = when (status) {
    GameServerStatus.RUNNING -> "Server läuft"
    GameServerStatus.STARTING -> "Server startet"
    GameServerStatus.CRASHED -> "Server abgestürzt"
    GameServerStatus.STOPPED -> "Server gestoppt"
    GameServerStatus.NO_SEED -> "kein Seed"
  }
  override val autoDeletionDisabled = "automatisches Löschen aus"
  override fun deletedAutomaticallyOn(date: String) = "wird am $date automatisch gelöscht"
  override fun playersSubtitle(players: Int, slots: Int) = "$players Spieler, $slots ${if (slots == 1) "Slot" else "Slots"}"
  override val nobodyNamed = "Für die Slots ist niemand eingetragen."
  override val nobodyPlays = "Niemand spielt"
  override val filesOfSlots = "Dateien der Slots"
  override val filesTip = "Jeder Spieler lädt seine eigenen unter Deine Verbindung herunter; hier die aller Slots."
  override fun slotNumber(n: Int) = "Slot $n"
  override val noneRunning = "keine läuft"
  override val pickOne = "eine wählen…"
  override val newSession = "Neue Session"
  override val pastSessions = "Vergangene Sessions"
  override val pastSubtitle = "nach einer Weile automatisch gelöscht, wenn das nicht ausgeschaltet ist"
  override val noneYet = "Noch keine."
  override fun finishedOn(date: String, by: String?) = "beendet $date${by?.let { " von $it" } ?: ""}"
  override val reopen = "Wieder öffnen"
  override val enableAutoDeletion = "Automatisch löschen an"
  override val disableAutoDeletion = "Automatisch löschen aus"
  override val delete = "Löschen"
  override val confirmDeleteFinished =
    "Endgültig löschen? Ihr Seed, Spielstand und Verlauf verschwinden; die Anmeldungen bleiben."
  override val yesDelete = "Ja, löschen"
  override val confirmEnable = "Automatisches Löschen wieder einschalten? Die Zeit zählt ab jetzt."
  override val confirmDisable = "Automatisches Löschen ausschalten? Sie bleibt, bis sie von Hand gelöscht wird."
  override val yesEnable = "Ja, einschalten"
  override val yesDisable = "Ja, ausschalten"
  override val createdLabel = "Erstellt"
  override val finishedLabel = "Beendet"
  override fun finishedValue(date: String?, by: String?) =
    (date ?: "noch nicht") + (by?.let { " von $it" } ?: ", als jeder Slot fertig war")
  override val reopenLabel = "Wieder öffnen"
  override val reopenNotPossible = "nicht möglich, sie war komplett"
  override fun reopenPossibleUntil(date: String) = "möglich bis $date"
  override val reopenNoLonger = "nicht mehr möglich"
  override val deletionLabel = "Löschen"
  override val notAutomatic = "nicht automatisch"
  override val playersHeading = "SPIELER"
  override val nobodyWasNamed = "Für die Slots war niemand eingetragen."
  override val taken = "Vergeben"
  override fun newSessionSubtitle(capacity: String) = "so viele laufen gleichzeitig, wie das Gateway Spielserver hat ($capacity)"
  override val everyServerInUse = "Alle Spielserver sind belegt: Beende oder stoppe zuerst eine Session."
  override val atLimit = "Du verwaltest so viele laufende Sessions, wie du gleichzeitig darfst: Beende zuerst eine."
  override fun mayCreate(n: Int) = "Du darfst noch $n ${if (n == 1) "laufende Session" else "laufende Sessions"} erstellen."
  override val sessionId = "Session-ID"
  override fun finishedSentence(complete: Boolean, date: String) = "${if (complete) "Beendet" else "Pausiert"} $date."
  override val completedNoReopen = "Sie war komplett und kann deshalb nicht wieder geöffnet werden."
  override fun reopenUntilSentence(date: String) = "Sie kann bis $date wieder geöffnet werden."
  override val pausedTooLong = "Sie wurde vor mehr als 14 Tagen pausiert und kann nicht mehr geöffnet werden."
  override fun onceNewer(deletion: String) = "Sobald es eine neuere Session gibt: $deletion."
  override fun endsByItselfAt(time: String) = "Alle Slots sind fertig: Sie endet um $time von selbst."
  override val confirmEnd =
    "Beenden? Der Spielserver stoppt endgültig; eine komplette Session kann nicht wieder geöffnet werden."
  override val confirmPause = "Pausieren? Der Spielserver stoppt; sie kann innerhalb von 14 Tagen wieder geöffnet werden."
  override val yesEnd = "Ja, beenden"
  override val yesPause = "Ja, pausieren"
  override val confirmSpoiler = "Ein voller Spoiler: wo jedes Item der laufenden Session liegt. Anzeigen?"
  override val showIt = "Anzeigen"
  override val confirmDeleteRunning =
    "Löschen? Der Spielserver stoppt; der Seed und sein Spielstand kommen nach data/old. Die Anmeldungen bleiben."
  override val endSession = "Session beenden"
  override val pauseSession = "Session pausieren"
  override val deleteSession = "Session löschen"
  override val dashboard = "Dashboard"
  override val spoilerLog = "Spoiler-Log…"
  override val stepYamls = "1. Die YAMLs, eine pro Slot"
  override val addYamls = "YAMLs hinzufügen…"
  override val clearAll = "Alle entfernen"
  override fun removeAll(n: Int) = "Alle $n YAMLs entfernen? Die bisher gewählten Spieler und Sperren gehen mit."
  override fun removeFile(file: String) = "$file entfernen?"
  override fun notAdded(problem: String) = "Nicht hinzugefügt: $problem"
  override val uploadMissing = "Lade die fehlenden APWorlds unten hoch, dann:"
  override val checkAgain = "Erneut prüfen"
  override val stepPlayers = "2. Wer spielt welchen Slot"
  override val playersHint =
    "Ein Slot pro YAML. Ein Slot kann mehrere Spieler haben (sie spielen ihn zusammen). Neue Namen bekommen eine " +
      "Anmeldung, wenn die Session erstellt wird."
  override val locks = "Sperren"
  override val lockChoose = "Je Slot wählen"
  override val lockRandom = "Zufällig"
  override val lockMixed = "Gemischt"
  override val startOpen = "Offen starten"
  override fun openOf(open: Int, n: Int) = "$open von $n Slots"
  override val startsLocked = "startet gesperrt"
  override val stepOptions = "3. Optionen"
  override val pointHintsCheck = "Punkte-Hinweise"
  override val automaticHintsCheck = "Automatische Hinweise"
  override val keysEarly = "Schlüssel früh"
  override val cheatHints = "Cheat-Hinweise"
  override val hintCost = "Hinweiskosten %"
  override val pointsPerCheck = "Punkte pro Check"
  override val release = "Release"
  override val collect = "Collect"
  override val remaining = "Remaining"
  override val archipelago = "Archipelago"
  override fun archipelagoVersion(version: String) = "Archipelago $version"
  override val generating = "Wird generiert…"
  override val generateAndStart = "Generieren und starten"
  override val generatesSeed = "Archipelago generiert den Seed; das kann eine Minute dauern."
  override val addPlayer = "Spieler hinzufügen…"
  override val addAnother = "Weiteren hinzufügen…"
  override val inviting = "Wird eingeladen…"
  override fun inviteAddress(address: String) = "$address einladen"
  override val noAccountMatches = "Kein Konto passt; gib die E-Mail-Adresse ein, um die Person einzuladen"
  override fun invitedName(name: String) = "$name (eingeladen)"
  override val sessionCreated = "Session erstellt; der Spielserver startet."
  override fun startLocked(slots: String) = "Starten gesperrt: $slots"
  override val done = "Fertig"
  override val lockOpen = "offen"
  override val lockChance = "zufällig"
  override val lockLocked = "gesperrt"
}

internal object EnglishSessionTips : SessionTips {
  override val players = "Who plays this slot. A slot with several players is played together: they share it and its " +
    "slot password. Someone without an account: type their e-mail address to invite them."
  override val invite = "An invitation mail with a link, valid for 7 days: they choose their name and password themselves. " +
    "The name you give is only a suggestion; until they sign up, sessions show them by it."
  override val locks = "A locked slot can't be played until its key is found, somewhere in another slot (World Locks). " +
    "At least one slot starts open."
  override val lockModes = listOf(
    "Choose" to "Tick \"starts locked\" for each slot that waits for its key.",
    "Random" to "The given number of slots, picked at random, start open; all others start locked.",
    "Mixed" to "Each slot starts open, starts locked, or is left to chance (its switch: left, right, middle). The " +
      "slider says how many slots start open in all.",
  )
  override val openSlots = "How many slots start open in all; at least one. Which of the slots left to chance is " +
    "picked at random when the session is generated."
  override val startsLocked = "This slot waits for its key, which lies somewhere in another slot."
  override val lastOpen = "The last slot that starts open: someone has to start, and the keys lie in the open slots."
  override val lockSwitch = "Left: starts open. Middle: left to chance. Right: starts locked, as long as another slot " +
    "can still start open."
  override val pointHints = "Players buy hints with !hint in their client and pay with points they earn by checking " +
    "locations. Off: !hint and !hint_location are refused; the server console can still give hints."
  override val autoHints = "Games may create hints by themselves, e.g. a shop that shows whose item it sells. Off: " +
    "those only show the item to the game, no hint is made."
  override val earlyKeys = "The keys of the locked slots are placed early: somewhere reachable from the start, so " +
    "the locked slots open soon instead of whenever the logic needs them."
  override val hintCost = "What one hint costs, in % of the slot's locations. With 10 % and 200 locations a hint " +
    "costs 20 points. 0: hints are free."
  override val checkPoints = "Points a slot earns for each location it checks; hints are paid with them."
  override val release = "When a slot's remaining items are sent to their owners."
  override val releaseChoices = listOf(
    "auto" to "By itself when the slot reaches its goal.",
    "enabled" to "Any time, with !release.",
    "disabled" to "Never; only its Session-Managers can, with a cheat.",
    "goal" to "With !release, once the slot reached its goal.",
    "auto-enabled" to "By itself at the goal, and any time with !release.",
  )
  override val collect = "When a slot gets its own items that are still in other slots."
  override val collectChoices = listOf(
    "auto" to "By itself when the slot reaches its goal.",
    "enabled" to "Any time, with !collect.",
    "disabled" to "Never; only its Session-Managers can, with a cheat.",
    "goal" to "With !collect, once the slot reached its goal.",
    "auto-enabled" to "By itself at the goal, and any time with !collect.",
  )
  override val remaining = "Whether a player may ask which of their items are still out there."
  override val remainingChoices = listOf(
    "enabled" to "Any time.",
    "disabled" to "Never.",
    "goal" to "Once the slot reached its goal.",
  )
  override val releaseVersion = "The Archipelago release that generates and serves this session; the session stays on " +
    "it. Which games it has is listed under APWorlds."
  override val cheatHints = "Session-Managers may give free hints with the cheats (support always may). Every one is " +
    "logged and shown in the live feed. Giving items and checks works without this."
  override val sessionId = "The session's name in the links, and its address for game clients (<ID>.<domain>). " +
    "Lowercase letters, digits and -, starting with a letter; it can't be changed later."
  override val dashboard = "The session's dashboard as its players see it (slot passwords only for your own slots)."
  override val pause = "Pausing stops the game server: the session is read-only, the dashboard shows how far it got, " +
    "and its players see the spoiler log. It can be reopened within 14 days."
  override val end = "Every check is done. Ending stops the game server for good: the dashboard keeps showing how it " +
    "ended, and its players see the spoiler log. A completed session can't be reopened. It also ends by itself 2 " +
    "minutes after every slot is done."
  override val reopen = "A finished session can be reopened within 14 days, unless it was completed (100 %): the game " +
    "server serves it again, if one is free."
  override val keep = "A finished session is deleted by itself some time after it finished (SESSION_RETENTION_DAYS, 90 by " +
    "default). With its auto-deletion disabled it stays until it is deleted by hand; enabled again, the time counts " +
    "from then."
  override val spoiler = "The spoiler log shows where every item of the seed is, and the playthrough sphere by sphere. " +
    "Players see it once the session is finished."
}

internal object GermanSessionTips : SessionTips {
  override val players = "Wer diesen Slot spielt. Ein Slot mit mehreren Spielern wird zusammen gespielt: Sie teilen ihn " +
    "und sein Slot-Passwort. Jemand ohne Konto: Gib die E-Mail-Adresse ein, um die Person einzuladen."
  override val invite = "Eine Einladungsmail mit einem Link, 7 Tage gültig: Namen und Passwort wählt die Person selbst. " +
    "Der Name, den du angibst, ist nur ein Vorschlag; bis zur Registrierung zeigen Sessions sie damit."
  override val locks = "Ein gesperrter Slot kann erst gespielt werden, wenn sein Schlüssel gefunden ist, irgendwo in " +
    "einem anderen Slot (World Locks). Mindestens ein Slot startet offen."
  override val lockModes = listOf(
    "Wählen" to "Hake \"startet gesperrt\" bei jedem Slot an, der auf seinen Schlüssel wartet.",
    "Zufällig" to "Die angegebene Zahl an Slots, zufällig gewählt, startet offen; alle anderen starten gesperrt.",
    "Gemischt" to "Jeder Slot startet offen, startet gesperrt oder bleibt dem Zufall überlassen (sein Schalter: " +
      "links, rechts, Mitte). Der Regler sagt, wie viele Slots insgesamt offen starten.",
  )
  override val openSlots = "Wie viele Slots insgesamt offen starten; mindestens einer. Welche der dem Zufall " +
    "überlassenen das sind, wird beim Generieren der Session zufällig gewählt."
  override val startsLocked = "Dieser Slot wartet auf seinen Schlüssel, der irgendwo in einem anderen Slot liegt."
  override val lastOpen = "Der letzte Slot, der offen startet: Jemand muss anfangen, und die Schlüssel liegen in den " +
    "offenen Slots."
  override val lockSwitch = "Links: startet offen. Mitte: dem Zufall überlassen. Rechts: startet gesperrt, solange " +
    "noch ein anderer Slot offen starten kann."
  override val pointHints = "Spieler kaufen Hinweise mit !hint in ihrem Client und zahlen mit Punkten, die sie fürs " +
    "Checken von Orten bekommen. Aus: !hint und !hint_location werden abgelehnt; die Server-Konsole kann trotzdem " +
    "Hinweise geben."
  override val autoHints = "Spiele können selbst Hinweise erzeugen, z. B. ein Laden, der zeigt, wessen Item er " +
    "verkauft. Aus: Diese zeigen das Item nur dem Spiel, ein Hinweis entsteht nicht."
  override val earlyKeys = "Die Schlüssel der gesperrten Slots werden früh platziert: irgendwo, das vom Start aus " +
    "erreichbar ist, damit die gesperrten Slots bald aufgehen statt erst, wenn die Logik sie braucht."
  override val hintCost = "Was ein Hinweis kostet, in % der Orte des Slots. Mit 10 % und 200 Orten kostet ein Hinweis " +
    "20 Punkte. 0: Hinweise sind gratis."
  override val checkPoints = "Punkte, die ein Slot für jeden gecheckten Ort bekommt; mit ihnen werden Hinweise bezahlt."
  override val release = "Wann die restlichen Items eines Slots an ihre Besitzer gehen."
  override val releaseChoices = listOf(
    "auto" to "Von selbst, wenn der Slot sein Ziel erreicht.",
    "enabled" to "Jederzeit, mit !release.",
    "disabled" to "Nie; nur seine Session-Manager können es, mit einem Cheat.",
    "goal" to "Mit !release, sobald der Slot sein Ziel erreicht hat.",
    "auto-enabled" to "Von selbst beim Ziel, und jederzeit mit !release.",
  )
  override val collect = "Wann ein Slot seine eigenen Items bekommt, die noch in anderen Slots liegen."
  override val collectChoices = listOf(
    "auto" to "Von selbst, wenn der Slot sein Ziel erreicht.",
    "enabled" to "Jederzeit, mit !collect.",
    "disabled" to "Nie; nur seine Session-Manager können es, mit einem Cheat.",
    "goal" to "Mit !collect, sobald der Slot sein Ziel erreicht hat.",
    "auto-enabled" to "Von selbst beim Ziel, und jederzeit mit !collect.",
  )
  override val remaining = "Ob ein Spieler fragen darf, welche seiner Items noch irgendwo liegen."
  override val remainingChoices = listOf(
    "enabled" to "Jederzeit.",
    "disabled" to "Nie.",
    "goal" to "Sobald der Slot sein Ziel erreicht hat.",
  )
  override val releaseVersion = "Das Archipelago-Release, das diese Session generiert und bereitstellt; die Session " +
    "bleibt dabei. Welche Spiele es hat, steht unter APWorlds."
  override val cheatHints = "Session-Manager dürfen mit den Cheats Gratis-Hinweise geben (Support darf das immer). " +
    "Jeder wird protokolliert und im Live-Feed gezeigt. Items und Checks geben geht auch ohne das."
  override val sessionId = "Der Name der Session in den Links und ihre Adresse für Spiel-Clients (<ID>.<Domain>). " +
    "Kleinbuchstaben, Ziffern und -, mit einem Buchstaben am Anfang; später nicht mehr änderbar."
  override val dashboard = "Das Dashboard der Session, wie ihre Spieler es sehen (Slot-Passwörter nur für deine eigenen Slots)."
  override val pause = "Pausieren stoppt den Spielserver: Die Session ist schreibgeschützt, das Dashboard zeigt, wie " +
    "weit sie kam, und ihre Spieler sehen das Spoiler-Log. Sie kann innerhalb von 14 Tagen wieder geöffnet werden."
  override val end = "Alle Checks sind erledigt. Beenden stoppt den Spielserver endgültig: Das Dashboard zeigt weiter, " +
    "wie sie ausging, und ihre Spieler sehen das Spoiler-Log. Eine komplette Session kann nicht wieder geöffnet " +
    "werden. Sie endet auch von selbst 2 Minuten, nachdem jeder Slot fertig ist."
  override val reopen = "Eine beendete Session kann innerhalb von 14 Tagen wieder geöffnet werden, wenn sie nicht " +
    "komplett war (100 %): Der Spielserver stellt sie wieder bereit, wenn einer frei ist."
  override val keep = "Eine beendete Session wird einige Zeit nach ihrem Ende von selbst gelöscht " +
    "(SESSION_RETENTION_DAYS, standardmäßig 90). Ist ihr automatisches Löschen aus, bleibt sie, bis sie von Hand " +
    "gelöscht wird; wieder eingeschaltet, zählt die Zeit ab dann."
  override val spoiler = "Das Spoiler-Log zeigt, wo jedes Item des Seeds liegt, und den Durchspielweg Sphäre für " +
    "Sphäre. Spieler sehen es, sobald die Session beendet ist."
}
