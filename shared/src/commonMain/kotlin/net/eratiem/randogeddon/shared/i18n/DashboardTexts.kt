package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.PlayerStatus

/** A session's dashboard: the overview tiles, the players and their slots, the hints. */
interface DashboardTexts {
  /** A Session-Manager of the session can't act for it at the moment. */
  val managerUnavailable: String
  // The overview
  val overview: String
  fun overviewSubtitle(slots: Int, checked: String, total: String): String
  val goals: String
  val slotsFinished: String
  val online: String
  val clientsConnected: String
  val unlocked: String
  val slotsOpened: String
  val hints: String
  fun notFoundYet(n: Int): String
  val playTime: String
  val allSlotsTogether: String
  val session: String
  val serverRunning: String
  val serverStopped: String
  // The players
  val players: String
  fun playersSubtitle(players: Int, slots: Int): String
  val sortPlayer: String
  val sortProgress: String
  val sortActivity: String
  val waitingForServer: String
  fun deleted(name: String): String
  fun account(id: String, deleted: Boolean): String
  fun locked(n: Int): String
  fun done(n: Int): String
  fun played(duration: String): String
  /** A slot's state; [started]: some of its checks are done. */
  fun status(status: PlayerStatus, started: Boolean): String
  val playing: String
  fun slot(name: String): String
  fun progress(checked: Int, total: Int, progression: Int): String
  fun doneAt(time: String): String
  fun unlockedBy(name: String): String
  fun keyHinted(where: String): String
  /** Where a hinted key is: `Crossroads (in Bob-HK)`. */
  fun hintPlace(location: String, slot: String): String
  fun waitingForKey(key: String): String
  fun points(n: Int): String
  val hintsFree: String
  fun hintsForPoints(hints: Int, points: Int): String
  // The hints
  fun hintsSubtitle(open: Int, total: Int): String
  val allPlayers: String
  val allSlots: String
  val hintNotFound: String
  val hintAllStates: String
  val hintFound: String
  val hintPriority: String
  val hintAvoid: String
  val hintNoPriority: String
  val hintUnmarked: String
  val noHintsYet: String
  val noHintsMatch: String
  val columnFor: String
  val columnItem: String
  val columnCheck: String
  val columnInSlot: String
  /** A hint in one line, e.g. on a phone: `{receiver}'s {item}`. */
  val hintItem: String
  /** `{location} · in {finder}`. */
  val hintWhere: String
  val pillFound: String
  val pillPriority: String
  val pillAvoid: String
  val pillNoPriority: String
  val pillOpen: String
}

/**
 * The live feed. Its lines are templates: `{name}` marks where a name goes (in its own style), so each language puts
 * them where its grammar wants them; every language has the same marks in a line (checked by a test).
 */
interface FeedTexts {
  val title: String
  val newestFirst: String
  val everyone: String
  val nothingYet: String
  val allEvents: String
  val progression: String
  val items: String
  val keysAndGoals: String
  val hints: String
  val connectionsAndChat: String
  val placeRight: String
  val placeLeft: String
  val placeBelow: String
  /** `{sender} found their {item}`. */
  val foundOwn: String
  /** `{sender} sent {item} to {receiver}`. */
  val sent: String
  val cheat: String
  /** `{sender} found the key: {slot} is unlocked!` */
  val unlocked: String
  val aSlot: String
  val hint: String
  /** `{receiver}'s {item} is at {location} in {finder}`. */
  val hintLine: String
  val found: String
  /** `{player} completed their goal!` */
  val goal: String
  /** `{player} released their remaining items`. */
  val released: String
  /** `{player} collected their items`. */
  val collected: String
  /** `{player} joined`. */
  val joined: String
  /** `{player} left`. */
  val left: String
  /** After an item a client took with `!getitem`. */
  val cheatMark: String
}

/** Under the top card while a session finishes, and once it has. */
interface FinishTexts {
  val ended: String
  val paused: String
  /** `Ended 14 Nov 2026, 18:21. This is how it ended; the spoiler log shows the whole seed.` */
  fun finishedAt(ended: Boolean, date: String): String
  val everySlotDone: String
  /** [left] as `m:ss`. */
  fun endsIn(left: String): String
  val spoilerLog: String
  val endNow: String
  val tip: String
}

internal object EnglishDashboard : DashboardTexts {
  override val managerUnavailable =
    "A Session-Manager of this session isn't available at the moment. If you need help with managing the session, reach out to an Administrator."
  override val overview = "Overview"
  override fun overviewSubtitle(slots: Int, checked: String, total: String) = "$slots slots · $checked of $total checks"
  override val goals = "Goals"
  override val slotsFinished = "slots finished"
  override val online = "Online"
  override val clientsConnected = "clients connected"
  override val unlocked = "Unlocked"
  override val slotsOpened = "slots opened"
  override val hints = "Hints"
  override fun notFoundYet(n: Int) = "$n not found yet"
  override val playTime = "Play time"
  override val allSlotsTogether = "all slots together"
  override val session = "Session"
  override val serverRunning = "game server running"
  override val serverStopped = "game server stopped"
  override val players = "Players"
  override fun playersSubtitle(players: Int, slots: Int) =
    "$players ${if (players == 1) "player" else "players"} · $slots ${if (slots == 1) "slot" else "slots"}"
  override val sortPlayer = "Sort: player"
  override val sortProgress = "Sort: progress"
  override val sortActivity = "Sort: last activity"
  override val waitingForServer = "Waiting for the server…"
  override fun deleted(name: String) = "$name (deleted)"
  override fun account(id: String, deleted: Boolean) = "Account $id${if (deleted) " (deleted)" else ""}"
  override fun locked(n: Int) = "$n locked"
  override fun done(n: Int) = "$n done"
  override fun played(duration: String) = "$duration played"
  override fun status(status: PlayerStatus, started: Boolean) = when (status) {
    PlayerStatus.IDLE -> if (started) "idle" else "not started"
    PlayerStatus.LOCKED -> "locked"
    PlayerStatus.PLAYING -> "playing"
    PlayerStatus.GOAL -> "goal"
    PlayerStatus.RELEASED -> "released"
    PlayerStatus.COMPLETE -> "complete"
  }
  override val playing = "playing"
  override fun slot(name: String) = "Slot: $name"
  override fun progress(checked: Int, total: Int, progression: Int) = "$checked / $total · $progression progression"
  override fun doneAt(time: String) = "done $time"
  override fun unlockedBy(name: String) = "Unlocked by $name"
  override fun keyHinted(where: String) = "Locked · key hinted: $where"
  override fun hintPlace(location: String, slot: String) = "$location (in $slot)"
  override fun waitingForKey(key: String) = "Locked · waiting for $key"
  override fun points(n: Int) = "$n pts"
  override val hintsFree = "hints free"
  override fun hintsForPoints(hints: Int, points: Int) = "$hints ${if (hints == 1) "hint" else "hints"} · $points pts"
  override fun hintsSubtitle(open: Int, total: Int) = "$open open · $total total"
  override val allPlayers = "All players"
  override val allSlots = "All slots"
  override val hintNotFound = "Not found"
  override val hintAllStates = "All states"
  override val hintFound = "Found"
  override val hintPriority = "Priority"
  override val hintAvoid = "Avoid"
  override val hintNoPriority = "No priority"
  override val hintUnmarked = "Unmarked"
  override val noHintsYet = "No hints yet."
  override val noHintsMatch = "No hints match the filter."
  override val columnFor = "For"
  override val columnItem = "Item"
  override val columnCheck = "Check"
  override val columnInSlot = "In slot"
  override val hintItem = "{receiver}'s {item}"
  override val hintWhere = "{location} · in {finder}"
  override val pillFound = "found"
  override val pillPriority = "priority"
  override val pillAvoid = "avoid"
  override val pillNoPriority = "no priority"
  override val pillOpen = "open"
}

internal object GermanDashboard : DashboardTexts {
  override val managerUnavailable =
    "Ein Session-Manager dieser Session ist im Moment nicht erreichbar. Wenn du Hilfe beim Verwalten der Session brauchst, wende dich an einen Administrator."
  override val overview = "Überblick"
  override fun overviewSubtitle(slots: Int, checked: String, total: String) = "$slots Slots · $checked von $total Checks"
  override val goals = "Ziele"
  override val slotsFinished = "Slots fertig"
  override val online = "Online"
  override val clientsConnected = "Clients verbunden"
  override val unlocked = "Entsperrt"
  override val slotsOpened = "Slots geöffnet"
  override val hints = "Hinweise"
  override fun notFoundYet(n: Int) = "$n noch nicht gefunden"
  override val playTime = "Spielzeit"
  override val allSlotsTogether = "alle Slots zusammen"
  override val session = "Session"
  override val serverRunning = "Spielserver läuft"
  override val serverStopped = "Spielserver gestoppt"
  override val players = "Spieler"
  override fun playersSubtitle(players: Int, slots: Int) = "$players Spieler · $slots ${if (slots == 1) "Slot" else "Slots"}"
  override val sortPlayer = "Sortieren: Spieler"
  override val sortProgress = "Sortieren: Fortschritt"
  override val sortActivity = "Sortieren: letzte Aktivität"
  override val waitingForServer = "Warte auf den Server…"
  override fun deleted(name: String) = "$name (gelöscht)"
  override fun account(id: String, deleted: Boolean) = "Konto $id${if (deleted) " (gelöscht)" else ""}"
  override fun locked(n: Int) = "$n gesperrt"
  override fun done(n: Int) = "$n fertig"
  override fun played(duration: String) = "$duration gespielt"
  override fun status(status: PlayerStatus, started: Boolean) = when (status) {
    PlayerStatus.IDLE -> if (started) "inaktiv" else "nicht gestartet"
    PlayerStatus.LOCKED -> "gesperrt"
    PlayerStatus.PLAYING -> "spielt"
    PlayerStatus.GOAL -> "Ziel"
    PlayerStatus.RELEASED -> "freigegeben"
    PlayerStatus.COMPLETE -> "komplett"
  }
  override val playing = "spielt"
  override fun slot(name: String) = "Slot: $name"
  override fun progress(checked: Int, total: Int, progression: Int) = "$checked / $total · $progression Progression"
  override fun doneAt(time: String) = "fertig $time"
  override fun unlockedBy(name: String) = "Entsperrt durch $name"
  override fun keyHinted(where: String) = "Gesperrt · Schlüssel-Hinweis: $where"
  override fun hintPlace(location: String, slot: String) = "$location (in $slot)"
  override fun waitingForKey(key: String) = "Gesperrt · wartet auf $key"
  override fun points(n: Int) = "$n Pkt."
  override val hintsFree = "Hinweise gratis"
  override fun hintsForPoints(hints: Int, points: Int) = "$hints ${if (hints == 1) "Hinweis" else "Hinweise"} · $points Pkt."
  override fun hintsSubtitle(open: Int, total: Int) = "$open offen · $total insgesamt"
  override val allPlayers = "Alle Spieler"
  override val allSlots = "Alle Slots"
  override val hintNotFound = "Nicht gefunden"
  override val hintAllStates = "Alle Zustände"
  override val hintFound = "Gefunden"
  override val hintPriority = "Priorität"
  override val hintAvoid = "Vermeiden"
  override val hintNoPriority = "Keine Priorität"
  override val hintUnmarked = "Unmarkiert"
  override val noHintsYet = "Noch keine Hinweise."
  override val noHintsMatch = "Kein Hinweis passt zum Filter."
  override val columnFor = "Für"
  override val columnItem = "Item"
  override val columnCheck = "Check"
  override val columnInSlot = "In Slot"
  override val hintItem = "{item} von {receiver}"
  override val hintWhere = "{location} · in {finder}"
  override val pillFound = "gefunden"
  override val pillPriority = "Priorität"
  override val pillAvoid = "vermeiden"
  override val pillNoPriority = "keine Priorität"
  override val pillOpen = "offen"
}

internal object EnglishFeed : FeedTexts {
  override val title = "Live feed"
  override val newestFirst = "newest first"
  override val everyone = "Everyone"
  override val nothingYet = "Nothing has happened yet."
  override val allEvents = "All events"
  override val progression = "Progression"
  override val items = "Items"
  override val keysAndGoals = "Keys & goals"
  override val hints = "Hints"
  override val connectionsAndChat = "Connections & chat"
  override val placeRight = "Right"
  override val placeLeft = "Left"
  override val placeBelow = "Below"
  override val foundOwn = "{sender} found their {item}"
  override val sent = "{sender} sent {item} to {receiver}"
  override val cheat = "Cheat: "
  override val unlocked = "{sender} found the key: {slot} is unlocked!"
  override val aSlot = "a slot"
  override val hint = "Hint: "
  override val hintLine = "{receiver}'s {item} is at {location} in {finder}"
  override val found = " (found)"
  override val goal = "{player} completed their goal!"
  override val released = "{player} released their remaining items"
  override val collected = "{player} collected their items"
  override val joined = "{player} joined"
  override val left = "{player} left"
  override val cheatMark = " · cheat"
}

internal object GermanFeed : FeedTexts {
  override val title = "Live-Feed"
  override val newestFirst = "neueste zuerst"
  override val everyone = "Alle"
  override val nothingYet = "Bisher ist nichts passiert."
  override val allEvents = "Alle Ereignisse"
  override val progression = "Progression"
  override val items = "Items"
  override val keysAndGoals = "Schlüssel & Ziele"
  override val hints = "Hinweise"
  override val connectionsAndChat = "Verbindungen & Chat"
  override val placeRight = "Rechts"
  override val placeLeft = "Links"
  override val placeBelow = "Unten"
  override val foundOwn = "{sender} hat das eigene {item} gefunden"
  override val sent = "{sender} hat {item} an {receiver} geschickt"
  override val cheat = "Cheat: "
  override val unlocked = "{sender} hat den Schlüssel gefunden: {slot} ist entsperrt!"
  override val aSlot = "ein Slot"
  override val hint = "Hinweis: "
  override val hintLine = "{item} von {receiver} liegt bei {location} in {finder}"
  override val found = " (gefunden)"
  override val goal = "{player} hat das Ziel erreicht!"
  override val released = "{player} hat die restlichen Items freigegeben"
  override val collected = "{player} hat die eigenen Items eingesammelt"
  override val joined = "{player} ist beigetreten"
  override val left = "{player} ist gegangen"
  override val cheatMark = " · Cheat"
}

internal object EnglishFinish : FinishTexts {
  override val ended = "The session has ended"
  override val paused = "The session is paused"
  override fun finishedAt(ended: Boolean, date: String) =
    "${if (ended) "Ended" else "Paused"} $date. ${if (ended) "This is how it ended" else "This is how far it got"}; " +
      "the spoiler log shows the whole seed."
  override val everySlotDone = "Every slot is done!"
  override fun endsIn(left: String) = "The session ends in $left; then the game server stops and the spoiler log is revealed."
  override val spoilerLog = "Spoiler log"
  override val endNow = "End now"
  override val tip =
    "A session ends 2 minutes after every slot reached its goal or was released; its Session-Managers can also " +
      "pause or end it earlier. Then it is read-only: the game server stops, and its players see the spoiler log. " +
      "Unless it was completed (100 %), its Session-Managers can reopen it within 14 days."
}

internal object GermanFinish : FinishTexts {
  override val ended = "Die Session ist beendet"
  override val paused = "Die Session ist pausiert"
  override fun finishedAt(ended: Boolean, date: String) =
    "${if (ended) "Beendet" else "Pausiert"} $date. ${if (ended) "So ging sie aus" else "So weit kam sie"}; " +
      "das Spoiler-Log zeigt den ganzen Seed."
  override val everySlotDone = "Alle Slots sind fertig!"
  override fun endsIn(left: String) =
    "Die Session endet in $left; dann stoppt der Spielserver und das Spoiler-Log wird aufgedeckt."
  override val spoilerLog = "Spoiler-Log"
  override val endNow = "Jetzt beenden"
  override val tip =
    "Eine Session endet 2 Minuten, nachdem jeder Slot sein Ziel erreicht hat oder freigegeben wurde; ihre " +
      "Session-Manager können sie auch früher pausieren oder beenden. Danach ist sie schreibgeschützt: Der " +
      "Spielserver stoppt, und ihre Spieler sehen das Spoiler-Log. Wenn sie nicht komplett war (100 %), können ihre " +
      "Session-Manager sie innerhalb von 14 Tagen wieder öffnen."
}
