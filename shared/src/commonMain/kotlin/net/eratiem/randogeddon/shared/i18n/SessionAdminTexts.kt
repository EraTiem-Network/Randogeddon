package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.GenerationStage
import net.eratiem.randogeddon.shared.GenerationStep
import net.eratiem.randogeddon.shared.PlandoModule

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
  val patchFiles: String
  fun patchFilesSubtitle(files: Int): String
  val noPatchFiles: String
  val filesTip: String
  fun slotNumber(n: Int): String
  // The switch
  val noneRunning: String
  /** The panel without a running session, below the switch. */
  val nothingRunning: String
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
  val keepRunning: String
  val keepRunningTip: String
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
  /** [capacity]: how many game servers may run at once; 0: no limit. */
  fun newSessionSubtitle(capacity: Int): String
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
  val plando: String
  fun plandoModule(module: PlandoModule): String
  val skipBalancing: String
  val raceMode: String
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
  val generatesInBackground: String
  val spoilerSection: String
  val spoilerSectionSubtitle: String
  fun spoilerLevel(level: Int): String
  fun spoilerWorking(level: Int): String
  val spoilerWaiting: String
  val spoilerPaused: String
  fun spoilerStopped(level: Int, why: String): String
  fun generationTime(time: String): String
  val generationWorking: String
  fun generationWorkingCpu(cpu: Int): String
  val generationWaitingTurn: String
  fun generationQuiet(minutes: Int): String
  val cancelGeneration: String
  val generationCancelled: String
  val generationLog: String
  val downloadLog: String
  val backToForm: String
  /** What the generation does now (its stage), for the bar while it runs. */
  fun generationStage(stage: GenerationStage): String
  fun generationStep(step: GenerationStep): String
  /** The slots that still need a player (or to be hosted) before generating. */
  fun slotsWithoutPlayer(slots: String): String
  val addPlayer: String
  val addAnother: String
  val inviting: String
  fun inviteAddress(address: String): String
  val noAccountMatches: String
  /** A slot's *Hosted* checkbox, what it means (its tip), the question before it clears the players, the slot's line. */
  val hostedCheck: String
  val hostedTip: String
  val hostedQuestion: String
  val yesHosted: String
  val hostedLine: String
  /** YAMLs whose games the release has no APWorld for ([files]: each with its games), then what to do. */
  fun missingApworlds(files: String, version: String): String
  val uploadThem: String
  val askAdministrator: String
  fun invitedName(name: String): String
  val sessionCreated: String
  fun startLocked(slots: String): String
  val done: String
  val lockOpen: String
  val lockChance: String
  val lockLocked: String
}

internal object EnglishSessionAdmin : SessionAdminTexts {
  override val stateAndControl = "State and control"
  override val deleted = "Deleted."
  override val deployedWithCli = "deployed with the CLI"
  override fun created(date: String) = "created $date"
  override fun pointHints(cost: Int) = "point hints ($cost %)"
  override val automaticHints = "automatic hints"
  override fun releaseMode(mode: String) = "release $mode"
  override fun sessionsRunning(running: Int, inUse: Int, capacity: Int) =
    "$running ${if (running == 1) "session" else "sessions"} running · " +
      (if (capacity == 0) "$inUse game servers in use (no limit)" else "$inUse of $capacity game servers in use") +
      " (a sleeping or finished session doesn't take one)"
  override fun server(status: GameServerStatus) = when (status) {
    GameServerStatus.RUNNING -> "server running"
    GameServerStatus.STARTING -> "server starting"
    GameServerStatus.CRASHED -> "server crashed"
    GameServerStatus.FAILED -> "server gave up"
    GameServerStatus.IDLE -> "server asleep"
    GameServerStatus.STOPPED -> "server stopped"
    GameServerStatus.NO_SEED -> "no seed"
  }
  override val autoDeletionDisabled = "auto-deletion disabled"
  override fun deletedAutomaticallyOn(date: String) = "deleted automatically on $date"
  override val patchFiles = "Patch files"
  override fun patchFilesSubtitle(files: Int) = "$files ${if (files == 1) "file" else "files"} from the seed"
  override val noPatchFiles = "The seed brings no files to download."
  override val filesTip = "Players download their own under Your connection; this lists every slot's."
  override fun slotNumber(n: Int) = "slot $n"
  override val noneRunning = "none running"
  override val nothingRunning = "No session is running right now."
  override val pickOne = "pick one…"
  override val newSession = "New session"
  override val pastSessions = "Past sessions"
  override val pastSubtitle = "will be deleted automatically, unless deletion was disabled"
  override val noneYet = "None yet."
  override fun finishedOn(date: String, by: String?) = "finished $date${by?.let { " by $it" } ?: ""}"
  override val reopen = "Reopen"
  override val enableAutoDeletion = "Enable auto-deletion"
  override val disableAutoDeletion = "Disable auto-deletion"
  override val keepRunning = "Keep running without players"
  override val keepRunningTip =
    "Its game server doesn't go to sleep when nobody plays (else it stops after the time set under Users, and starts " +
      "again when a player connects). For a game that only connects now and then."
  override val delete = "Delete"
  override val confirmDeleteFinished = "Delete it for good? Its seed, save and history are removed; the accounts stay."
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
  override fun newSessionSubtitle(capacity: Int) =
    if (capacity == 0) "as many run at once as are started" else "up to $capacity game servers run at once"
  override val everyServerInUse = "Every game server is in use: finish or stop a session first."
  override val atLimit = "You have as many running sessions of your own as you may: finish one first."
  override fun mayCreate(n: Int) = "You may create $n more running ${if (n == 1) "session" else "sessions"}."
  override val sessionId = "Session ID"
  override fun finishedSentence(complete: Boolean, date: String) = "${if (complete) "Ended" else "Paused"} $date."
  override val completedNoReopen = "It was completed, so it can't be reopened."
  override fun reopenUntilSentence(date: String) = "It can be reopened until $date."
  override val pausedTooLong = "It was paused more than 14 days ago and can no longer be reopened."
  override fun onceNewer(deletion: String) = "Once a newer session exists: $deletion."
  override fun endsByItselfAt(time: String) = "Every slot is done: it ends by itself at $time."
  override val confirmEnd = "End it? The game server stops for good; a completed session can't be reopened."
  override val confirmPause = "Pause it? The game server stops; it can be reopened within 14 days."
  override val yesEnd = "Yes, end it"
  override val yesPause = "Yes, pause it"
  override val confirmSpoiler = "A full spoiler: where every item of the running session is. Show it?"
  override val showIt = "Show it"
  override val confirmDeleteRunning = "Delete it? The game server stops; the seed and its save go to data/old. The accounts stay."
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
  override val stepPlayers = "2. Who plays which slot"
  override val playersHint =
    "One slot per YAML. A slot can have several players (they play it together). Someone without an account is " +
      "invited by e-mail."
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
  override val plando = "YAMLs may plan"
  override fun plandoModule(module: PlandoModule) = when (module) {
    PlandoModule.BOSSES -> "bosses"
    PlandoModule.CONNECTIONS -> "connections"
    PlandoModule.TEXTS -> "texts"
    PlandoModule.ITEMS -> "items"
  }
  override val skipBalancing = "No progression balancing"
  override val raceMode = "Race mode"
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
  override val generatesInBackground =
    "It goes on without this page: you can close it, and find it here again until it is done."
  override fun generationTime(time: String) = "running for $time"
  override val spoilerSection = "Spoiler log"
  override val spoilerSectionSubtitle = "completed in the background, after the seed"
  override fun spoilerLevel(level: Int) = when (level) {
    1 -> "Level 1 of 3: the seed's spoiler log, without the playthrough"
    2 -> "Level 2 of 3: with the playthrough (the spoiler log, the hints and the cheats are there)"
    else -> "Level $level of 3: complete"
  }
  override fun spoilerWorking(level: Int) =
    if (level == 2) "working out the playthrough (level 2)" else "working out the paths to every item (level $level)"
  override val spoilerWaiting = "waiting for its turn (a new session's generation goes first)"
  override val spoilerPaused = "paused: a new session's generation goes first; it goes on after, where it was"
  override fun spoilerStopped(level: Int, why: String) = "Stays at level $level: $why"
  override val generationWorking = "working"
  override fun generationWorkingCpu(cpu: Int) = "working · $cpu % CPU"
  override val generationWaitingTurn = "waiting for its turn"
  override fun generationQuiet(minutes: Int) = "no response for ${if (minutes < 1) "a minute" else "$minutes min"}"
  override val cancelGeneration = "Cancel"
  override val generationCancelled = "The generation was cancelled; the ID is free again."
  override val generationLog = "Generate.py's output, newest on top"
  override val downloadLog = "Download the log"
  override val backToForm = "Back to the form"
  override fun generationStep(step: GenerationStep) = when (step) {
    GenerationStep.CHECK -> "Check"
    GenerationStep.GENERATE -> "Generate"
    GenerationStep.SET_UP -> "Set up"
    GenerationStep.START -> "Start"
  }
  override fun generationStage(stage: GenerationStage) = when (stage) {
    GenerationStage.CHECKING -> "Checking the YAMLs, players and locks…"
    GenerationStage.WAITING -> "Waiting for another session's generation to finish…"
    GenerationStage.STARTING -> "Starting Archipelago…"
    GenerationStage.CREATING -> "Creating the worlds…"
    GenerationStage.ITEMS -> "Creating the items…"
    GenerationStage.RULES -> "Working out the rules…"
    GenerationStage.PLANDO -> "Placing the planned items…"
    GenerationStage.PREFILL -> "Placing the worlds' own items…"
    GenerationStage.FILLING -> "Filling the items…"
    GenerationStage.BALANCING -> "Balancing the progression…"
    GenerationStage.PREOUTPUT -> "Getting ready to write…"
    GenerationStage.OUTPUT -> "Writing the files…"
    GenerationStage.PLAYTHROUGH -> "Working out the playthrough (in a big multiworld the longest part; Archipelago says nothing meanwhile)…"
    GenerationStage.ARCHIVE -> "Packing the seed…"
    GenerationStage.DONE -> "The seed is done…"
    GenerationStage.SAVING -> "Setting up the slots, players and passwords…"
    GenerationStage.SERVER -> "Starting the game server…"
  }
  override fun slotsWithoutPlayer(slots: String) = "Every slot needs a player or must be hosted: $slots"
  override val addPlayer = "Add a player…"
  override val addAnother = "Add another…"
  override val inviting = "Inviting…"
  override fun inviteAddress(address: String) = "Invite $address"
  override val noAccountMatches = "No account matches; type their e-mail address to invite them"
  override val hostedCheck = "Hosted"
  override val hostedQuestion = "Make this slot hosted? Its players are removed: its game runs as a server of its own."
  override val yesHosted = "Yes, hosted"
  override val hostedLine = "No players: the game runs as a server of its own, which they join with their games."
  override fun missingApworlds(files: String, version: String) =
    "No slots yet for $files: Archipelago $version has no APWorld for their games. They appear once it does."
  override val uploadThem = "Upload them under APWorlds."
  override val askAdministrator = "Ask an Administrator to upload them."
  override val hostedTip = "A hosted slot: its game runs as a server of its own, which the players join with their games; " +
    "nobody plays it from here. The Session-Managers see its connection, to set the server up; the players its patch files."
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
    "$running ${if (running == 1) "Session läuft" else "Sessions laufen"} · " +
      (if (capacity == 0) "$inUse Spielserver belegt (ohne Grenze)" else "$inUse von $capacity Spielservern belegt") +
      " (eine schlafende oder beendete Session belegt keinen)"
  override fun server(status: GameServerStatus) = when (status) {
    GameServerStatus.RUNNING -> "Server läuft"
    GameServerStatus.STARTING -> "Server startet"
    GameServerStatus.CRASHED -> "Server abgestürzt"
    GameServerStatus.FAILED -> "Server aufgegeben"
    GameServerStatus.IDLE -> "Server schläft"
    GameServerStatus.STOPPED -> "Server gestoppt"
    GameServerStatus.NO_SEED -> "kein Seed"
  }
  override val autoDeletionDisabled = "automatisches Löschen aus"
  override fun deletedAutomaticallyOn(date: String) = "wird am $date automatisch gelöscht"
  override val patchFiles = "Patch-Dateien"
  override fun patchFilesSubtitle(files: Int) = "$files ${if (files == 1) "Datei" else "Dateien"} aus dem Seed"
  override val noPatchFiles = "Der Seed bringt keine Dateien zum Herunterladen mit."
  override val filesTip = "Spieler laden ihre eigenen unter Deine Verbindung herunter; hier stehen die aller Slots."
  override fun slotNumber(n: Int) = "Slot $n"
  override val noneRunning = "keine läuft"
  override val nothingRunning = "Gerade läuft keine Session."
  override val pickOne = "eine wählen…"
  override val newSession = "Neue Session"
  override val pastSessions = "Vergangene Sessions"
  override val pastSubtitle = "werden automatisch gelöscht, sofern das Löschen nicht ausgeschaltet wurde"
  override val noneYet = "Noch keine."
  override fun finishedOn(date: String, by: String?) = "beendet $date${by?.let { " von $it" } ?: ""}"
  override val reopen = "Wieder öffnen"
  override val enableAutoDeletion = "Automatisch löschen an"
  override val disableAutoDeletion = "Automatisch löschen aus"
  override val keepRunning = "Ohne Spieler weiterlaufen"
  override val keepRunningTip =
    "Ihr Spielserver schläft nicht ein, wenn niemand spielt (sonst stoppt er nach der unter Benutzer eingestellten Zeit " +
      "und startet wieder, sobald sich jemand verbindet). Für ein Spiel, das sich nur ab und zu verbindet."
  override val delete = "Löschen"
  override val confirmDeleteFinished =
    "Endgültig löschen? Ihr Seed, Spielstand und Verlauf werden entfernt; die Konten bleiben."
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
  override val deletionLabel = "Löschung"
  override val notAutomatic = "nicht automatisch"
  override val playersHeading = "SPIELER"
  override val nobodyWasNamed = "Für die Slots war niemand eingetragen."
  override val taken = "Vergeben"
  override fun newSessionSubtitle(capacity: Int) =
    if (capacity == 0) "so viele laufen gleichzeitig, wie gestartet werden" else "bis zu $capacity Spielserver laufen gleichzeitig"
  override val everyServerInUse = "Alle Spielserver sind belegt: Beende oder stoppe zuerst eine Session."
  override val atLimit = "Du hast so viele eigene laufende Sessions, wie du darfst: Beende zuerst eine."
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
    "Löschen? Der Spielserver stoppt; der Seed und sein Spielstand kommen nach data/old. Die Konten bleiben."
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
  override val stepPlayers = "2. Wer spielt welchen Slot"
  override val playersHint =
    "Ein Slot pro YAML. Ein Slot kann mehrere Spieler haben (sie spielen ihn zusammen). Wer noch kein Konto hat, " +
      "wird per E-Mail eingeladen."
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
  override val plando = "YAMLs dürfen planen"
  override fun plandoModule(module: PlandoModule) = when (module) {
    PlandoModule.BOSSES -> "Bosse"
    PlandoModule.CONNECTIONS -> "Verbindungen"
    PlandoModule.TEXTS -> "Texte"
    PlandoModule.ITEMS -> "Items"
  }
  override val skipBalancing = "Ohne Progression-Balancing"
  override val raceMode = "Race-Modus"
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
  override val generatesInBackground =
    "Es geht ohne diese Seite weiter: Du kannst sie schließen und findest es hier wieder, bis es fertig ist."
  override fun generationTime(time: String) = "läuft seit $time"
  override val spoilerSection = "Spoiler-Log"
  override val spoilerSectionSubtitle = "im Hintergrund vervollständigt, nach dem Seed"
  override fun spoilerLevel(level: Int) = when (level) {
    1 -> "Stufe 1 von 3: das Spoiler-Log des Seeds, ohne Lösungsweg"
    2 -> "Stufe 2 von 3: mit Lösungsweg (Spoiler-Log, Hinweise und Cheats sind da)"
    else -> "Stufe $level von 3: vollständig"
  }
  override fun spoilerWorking(level: Int) =
    if (level == 2) "berechnet den Lösungsweg (Stufe 2)" else "berechnet die Wege zu jedem Item (Stufe $level)"
  override val spoilerWaiting = "wartet, bis es dran ist (die Generierung einer neuen Session geht vor)"
  override val spoilerPaused = "pausiert: Die Generierung einer neuen Session geht vor; danach geht es dort weiter, wo es war"
  override fun spoilerStopped(level: Int, why: String) = "Bleibt auf Stufe $level: $why"
  override val generationWorking = "arbeitet"
  override fun generationWorkingCpu(cpu: Int) = "arbeitet · $cpu % CPU"
  override val generationWaitingTurn = "wartet, bis es dran ist"
  override fun generationQuiet(minutes: Int) = "keine Reaktion seit ${if (minutes < 1) "einer Minute" else "$minutes min"}"
  override val cancelGeneration = "Abbrechen"
  override val generationCancelled = "Die Generierung wurde abgebrochen; die ID ist wieder frei."
  override val generationLog = "Ausgabe von Generate.py, neueste oben"
  override val downloadLog = "Log herunterladen"
  override val backToForm = "Zurück zum Formular"
  override fun generationStep(step: GenerationStep) = when (step) {
    GenerationStep.CHECK -> "Prüfen"
    GenerationStep.GENERATE -> "Generieren"
    GenerationStep.SET_UP -> "Einrichten"
    GenerationStep.START -> "Starten"
  }
  override fun generationStage(stage: GenerationStage) = when (stage) {
    GenerationStage.CHECKING -> "Prüft YAMLs, Spieler und Sperren…"
    GenerationStage.WAITING -> "Wartet, bis die Generierung einer anderen Session fertig ist…"
    GenerationStage.STARTING -> "Startet Archipelago…"
    GenerationStage.CREATING -> "Erstellt die Welten…"
    GenerationStage.ITEMS -> "Erstellt die Items…"
    GenerationStage.RULES -> "Berechnet die Regeln…"
    GenerationStage.PLANDO -> "Platziert die geplanten Items…"
    GenerationStage.PREFILL -> "Platziert die eigenen Items der Welten…"
    GenerationStage.FILLING -> "Verteilt die Items…"
    GenerationStage.BALANCING -> "Balanciert die Progression…"
    GenerationStage.PREOUTPUT -> "Bereitet das Schreiben vor…"
    GenerationStage.OUTPUT -> "Schreibt die Dateien…"
    GenerationStage.PLAYTHROUGH -> "Berechnet den Lösungsweg (in einer großen Multiworld der längste Teil; Archipelago meldet währenddessen nichts)…"
    GenerationStage.ARCHIVE -> "Packt den Seed…"
    GenerationStage.DONE -> "Der Seed ist fertig…"
    GenerationStage.SAVING -> "Richtet Slots, Spieler und Passwörter ein…"
    GenerationStage.SERVER -> "Startet den Spielserver…"
  }
  override fun slotsWithoutPlayer(slots: String) = "Jeder Slot braucht einen Spieler oder muss gehostet sein: $slots"
  override val addPlayer = "Spieler hinzufügen…"
  override val addAnother = "Weiteren hinzufügen…"
  override val inviting = "Wird eingeladen…"
  override fun inviteAddress(address: String) = "$address einladen"
  override val noAccountMatches = "Kein Konto passt; gib die E-Mail-Adresse ein, um die Person einzuladen"
  override val hostedCheck = "Gehostet"
  override val hostedQuestion = "Diesen Slot hosten? Seine Spieler werden entfernt: Sein Spiel läuft als eigener Server."
  override val yesHosted = "Ja, hosten"
  override val hostedLine = "Keine Spieler: Das Spiel läuft als eigener Server, dem sie mit ihren Spielen beitreten."
  override fun missingApworlds(files: String, version: String) =
    "Noch keine Slots für $files: Archipelago $version hat keine APWorld für ihre Spiele. Sie erscheinen, " +
      "sobald es eine gibt."
  override val uploadThem = "Lade sie unter APWorlds hoch."
  override val askAdministrator = "Bitte einen Administrator, sie hochzuladen."
  override val hostedTip = "Ein gehosteter Slot: Sein Spiel läuft als eigener Server, dem die Spieler mit ihren Spielen " +
    "beitreten; von hier spielt ihn niemand. Die Session-Manager sehen seine Verbindung, um den Server einzurichten; die " +
    "Spieler seine Patch-Dateien."
  override fun invitedName(name: String) = "$name (eingeladen)"
  override val sessionCreated = "Session erstellt; der Spielserver startet."
  override fun startLocked(slots: String) = "Gesperrt am Start: $slots"
  override val done = "Fertig"
  override val lockOpen = "offen"
  override val lockChance = "zufällig"
  override val lockLocked = "gesperrt"
}
