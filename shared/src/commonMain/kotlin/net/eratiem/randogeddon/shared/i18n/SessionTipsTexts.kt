package net.eratiem.randogeddon.shared.i18n

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
  val plando: String
  val skipBalancing: String
  val raceMode: String
  val sessionId: String
  val dashboard: String
  val pause: String
  val end: String
  val reopen: String
  val keep: String
  val spoiler: String
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
    "those only show the item to the game; no hint is created."
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
    "goal" to "With !release, once the slot has reached its goal.",
    "auto-enabled" to "By itself at the goal, and any time with !release.",
  )
  override val collect = "When a slot gets its own items that are still in other slots."
  override val collectChoices = listOf(
    "auto" to "By itself when the slot reaches its goal.",
    "enabled" to "Any time, with !collect.",
    "disabled" to "Never; only its Session-Managers can, with a cheat.",
    "goal" to "With !collect, once the slot has reached its goal.",
    "auto-enabled" to "By itself at the goal, and any time with !collect.",
  )
  override val remaining = "Whether a player may ask which of their items are still out there."
  override val remainingChoices = listOf(
    "enabled" to "Any time.",
    "disabled" to "Never.",
    "goal" to "Once the slot has reached its goal.",
  )
  override val releaseVersion = "The Archipelago release that generates and serves this session; the session stays on " +
    "it. Which games it has is listed under APWorlds."
  override val cheatHints = "Session-Managers may give free hints with the cheats (support always may). Every one is " +
    "logged and shown in the live feed. Giving items and checks works without this."
  override val plando =
    "Archipelago's plando: a YAML can fix parts of its game by hand instead of leaving them to chance. A " +
      "ticked kind is used when a YAML sets it; an unticked one is ignored (a game that insists on it refuses " +
      "the YAML). Archipelago's default: all but items."
  override val skipBalancing =
    "Archipelago leaves out its progression balancing: the step that moves progression items to earlier " +
      "spheres for players who would wait long. Generating a big multiworld gets faster; some may wait " +
      "longer for their items. The YAMLs' own progression_balancing doesn't count then."
  override val raceMode =
    "Archipelago's race mode: the seed can't be reproduced, games that can do it protect their patch, and " +
      "there is no spoiler log (none in the panel either; hints and cheats work without it)."
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
  override val plando =
    "Archipelagos Plando: Eine YAML kann Teile ihres Spiels von Hand festlegen, statt sie dem Zufall zu " +
      "überlassen. Eine angehakte Art wird verwendet, wenn eine YAML sie setzt; eine nicht angehakte wird " +
      "ignoriert (ein Spiel, das darauf besteht, lehnt die YAML ab). Archipelagos Standard: alles außer Items."
  override val skipBalancing =
    "Archipelago lässt sein Progression-Balancing weg: den Schritt, der Progression-Items für Spieler, die lange " +
      "warten würden, in frühere Sphären schiebt. Eine große Multiworld generiert schneller; manche warten " +
      "länger auf ihre Items. Das progression_balancing der YAMLs zählt dann nicht."
  override val raceMode =
    "Archipelagos Race-Modus: Der Seed lässt sich nicht nachstellen, Spiele, die es können, schützen ihren " +
      "Patch, und es gibt kein Spoiler-Log (auch nicht im Panel; Hinweise und Cheats funktionieren ohne)."
  override val sessionId = "Der Name der Session in den Links und ihre Adresse für Spiel-Clients (<ID>.<Domain>). " +
    "Kleinbuchstaben, Ziffern und -, mit einem Buchstaben am Anfang; später nicht mehr änderbar."
  override val dashboard = "Das Dashboard der Session, wie ihre Spieler es sehen (Slot-Passwörter nur für deine eigenen Slots)."
  override val pause = "Pausieren stoppt den Spielserver: Die Session ist nur noch lesbar, das Dashboard zeigt, wie " +
    "weit sie kam, und ihre Spieler sehen das Spoiler-Log. Sie kann innerhalb von 14 Tagen wieder geöffnet werden."
  override val end = "Alle Checks sind erledigt. Beenden stoppt den Spielserver endgültig: Das Dashboard zeigt weiter, " +
    "wie sie ausging, und ihre Spieler sehen das Spoiler-Log. Eine komplette Session kann nicht wieder geöffnet " +
    "werden. Sie endet auch von selbst 2 Minuten, nachdem jeder Slot fertig ist."
  override val reopen = "Eine beendete Session kann innerhalb von 14 Tagen wieder geöffnet werden, wenn sie nicht " +
    "komplett war (100 %): Der Spielserver stellt sie wieder bereit, wenn einer frei ist."
  override val keep = "Eine beendete Session wird einige Zeit nach ihrem Ende von selbst gelöscht " +
    "(SESSION_RETENTION_DAYS, standardmäßig 90). Ist ihr automatisches Löschen aus, bleibt sie, bis sie von Hand " +
    "gelöscht wird; wieder eingeschaltet, zählt die Zeit ab dann."
  override val spoiler = "Das Spoiler-Log zeigt, wo jedes Item des Seeds liegt, und den Lösungsweg Sphäre für " +
    "Sphäre. Spieler sehen es, sobald die Session beendet ist."
}
