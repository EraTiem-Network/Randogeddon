package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.TimeOut

/** The panel's APWorlds: the games of each Archipelago release, uploads, tries, removals. */
interface ApworldTexts {
  val title: String
  val subtitle: String
  val tip: String
  fun notAdded(file: String, error: String): String
  fun brings(file: String, games: String): String
  /** [files] (the [n]th of [of] parts of what was picked) being uploaded. */
  fun uploading(files: String, n: Int, of: Int): String
  /** An upload whose file is in the library already. */
  fun replacesFile(file: String): String
  /** An upload whose [game] comes from [other] now. */
  fun replacesGame(file: String, game: String, other: String): String
  val replace: String
  val skip: String
  fun skipped(file: String): String
  /** An uploaded file's pill while it isn't done: its turn hasn't come, it is checked, it waits for an answer. */
  val pillWaiting: String
  val pillChecking: String
  val pillAsking: String
  /** Takes away what the last upload, try or removal said. */
  val clearOutcome: String
  fun removing(files: String): String
  fun removed(file: String, release: String): String
  fun trying(file: String, release: String): String
  /** After [what] ("Uploading x.apworld…"): the release lists its games again. */
  fun relisting(what: String): String
  val listing: String
  val noRelease: String
  fun release(version: String): String
  val upload: String
  val uploaded: String
  val uploadedTip: String
  val gold: String
  val goldMeaning: String
  val orange: String
  val orangeMeaning: String
  val red: String
  val redMeaning: String
  val grey: String
  val greyMeaning: String
  val noneYet: String
  /** [files]: the picked ones, by name. */
  fun removeQuestion(files: String, release: String): String
  val yesRemove: String
  val removeSome: String
  val pickToRemove: String
  fun removeCount(n: Int): String
  val selectAll: String
  val selectNone: String
  fun didntLoad(name: String, reason: String): String
  val fromOtherReleases: String
  fun fromRelease(file: String, release: String): String
  fun tryWith(release: String): String
  fun supportedGames(n: Int): String
  val bundled: String
  val fromApworld: String
  val noGame: String
  fun from(release: String): String
}

/** The panel's users (Administrators): invitations, the accounts; and the settings for everyone. */
interface UserAdminTexts {
  val title: String
  fun subtitle(accounts: Int, invited: Int): String
  val email: String
  val suggestedName: String
  val invite: String
  fun invited(address: String): String
  val invitedPill: String
  val administrator: String
  val noSession: String
  fun inSession(id: String): String
  fun inSessions(n: Int): String
  val linkExpired: String
  fun linkWorksUntil(date: String): String
  fun deletedOn(date: String): String
  fun autoDeletion(period: String): String
  // An account's information panel: its labels, and what the values say
  val nameLabel: String
  val emailLabel: String
  val idLabel: String
  val stateLabel: String
  val active: String
  val groupLabel: String
  val userGroup: String
  val loginLabel: String
  val passwordLogin: String
  /** After an address that came from the provider: whether [provider] calls it verified. */
  fun emailVerifiedBy(provider: String, verified: Boolean): String
  /** The login through the provider ([provider]: its name, e.g. Authentik). */
  fun ssoLogin(provider: String?): String
  val twoFactorLabel: String
  val on: String
  val off: String
  val languageLabel: String
  val browserLanguage: String
  val madeLabel: String
  val invitationLabel: String
  val deletionLabel: String
  val sessionsLabel: String
  /** What an account doesn't have (an invited one's name, a deletion that isn't planned). */
  val notAvailable: String
  val sendAgain: String
  fun newLinkWent(address: String): String
  val otherAddress: String
  val sendThere: String
  fun linkWentTo(address: String): String
  /** What runs now (after a deploy): the tracker, the gateway, this page, the Archipelago releases. */
  val versions: String
  val versionsSubtitle: String
  val trackerVersion: String
  val gatewayVersion: String
  val pageVersion: String
  val releasesLabel: String
  val notReachable: String
  val sessionsPerUser: String
  val sessionsPerUserTip: String
  /** In an account's panel: its running sessions against its limit, and its own number. */
  val ownSessionsLabel: String
  fun sessionsOf(running: Int, limit: Int): String
  val theDefault: String
  val noLimit: String
  val ownLimit: String
  val useDefault: String
  fun limitSaved(name: String): String
  val saved: String
  val zeroToTwenty: String
  // Keeping an account out, deleting it
  val timedOutPill: String
  val suspendedPill: String
  val accessLabel: String
  val mayLogIn: String
  fun timedOutUntil(date: String): String
  val suspended: String
  /** After the state: who did it, and why if they said. */
  fun keptOutBy(name: String): String
  fun keptOutFor(reason: String): String
  val reason: String
  fun timeOut(time: TimeOut): String
  val timeOutButton: String
  val suspendButton: String
  val letInButton: String
  val deleteButton: String
  fun reallyDelete(name: String): String
  val deleteNow: String
  fun timedOutDone(name: String): String
  fun suspendedDone(name: String): String
  fun letInDone(name: String): String
  fun deletedDone(name: String): String
  val keptOutTip: String
  val managedElsewhere: String
}

internal object EnglishApworlds : ApworldTexts {
  override val title = "APWorlds"
  override val subtitle = "the games of each Archipelago release"
  override val tip = "The games each Archipelago release of the gateway can generate and serve: the bundled ones, plus " +
    "APWorlds. APWorlds uploaded here are kept per release; ones put into the server's custom_worlds/ count for every " +
    "release. An APWorld uploaded for another release can be tried with this one; it may or may not work."
  override fun notAdded(file: String, error: String) = "$file wasn't added because it doesn't load: $error"
  override fun brings(file: String, games: String) = "$file brings $games"
  override fun uploading(files: String, n: Int, of: Int) = if (of == 1) "Uploading $files…" else "Uploading $files ($n of $of)…"
  override fun replacesFile(file: String) = "$file is in the library already. Replace it?"
  override fun replacesGame(file: String, game: String, other: String) =
    "$file brings $game, which comes from $other now. Replace $other with it?"
  override val replace = "Replace"
  override val skip = "Skip"
  override fun skipped(file: String) = "$file was skipped; nothing changed"
  override val pillWaiting = "Waiting for its turn"
  override val pillChecking = "Uploading, and Archipelago checks it"
  override val pillAsking = "Waiting for your answer above"
  override val clearOutcome = "Clear"
  override fun removing(files: String) = "Removing $files…"
  override fun removed(file: String, release: String) = "$file removed from Archipelago $release"
  override fun trying(file: String, release: String) = "Trying $file with Archipelago $release…"
  override fun relisting(what: String) = "$what Archipelago lists its games again; that takes a moment."
  override val listing = "Listing the games of each release…"
  override val noRelease = "The gateway reports no Archipelago release."
  override fun release(version: String) = "Archipelago $version"
  override val upload = "Upload APWorld…"
  override val uploaded = "Uploaded APWorlds"
  override val uploadedTip = "Each file with the games it brings."
  override val gold = "gold"
  override val goldMeaning = "Loaded with this release."
  override val orange = "orange"
  override val orangeMeaning = "Uploaded for another release and tried here: untested."
  override val red = "red"
  override val redMeaning = "No game loaded from it (see below why)."
  override val grey = "grey"
  override val greyMeaning = "Put into the server's custom_worlds/ by hand; it can't be removed here."
  override val noneYet = "None yet: upload one for a game that isn't bundled."
  override fun removeQuestion(files: String, release: String) =
    "Remove $files? Their games can't be used for new sessions of Archipelago $release then."
  override val yesRemove = "Yes, remove"
  override val removeSome = "Remove…"
  override val pickToRemove = "Pick the files to remove (grey ones were placed by hand and can't be removed here)."
  override fun removeCount(n: Int) = "Remove ($n)"
  override val selectAll = "Select all"
  override val selectNone = "Select none"
  override fun didntLoad(name: String, reason: String) = "Didn't load: $name: $reason"
  override val fromOtherReleases = "From other releases"
  override fun fromRelease(file: String, release: String) = "$file (Archipelago $release)"
  override fun tryWith(release: String) = "Try with $release"
  override fun supportedGames(n: Int) = "Supported games ($n)"
  override val bundled = "bundled"
  override val fromApworld = "from an APWorld"
  override val noGame = "no game"
  override fun from(release: String) = "from $release"
}

internal object GermanApworlds : ApworldTexts {
  override val title = "APWorlds"
  override val subtitle = "die Spiele jedes Archipelago-Release"
  override val tip = "Die Spiele, die jedes Archipelago-Release des Gateways generieren und bereitstellen kann: die " +
    "mitgelieferten, dazu APWorlds. Hier hochgeladene APWorlds gelten je Release; die im Ordner custom_worlds/ des " +
    "Servers gelten für jedes Release. Eine für ein anderes Release hochgeladene APWorld kann mit diesem ausprobiert " +
    "werden; das kann klappen oder nicht."
  override fun notAdded(file: String, error: String) = "$file wurde nicht hinzugefügt, weil sie sich nicht laden lässt: $error"
  override fun brings(file: String, games: String) = "$file bringt $games"
  override fun uploading(files: String, n: Int, of: Int) =
    if (of == 1) "Lade $files hoch…" else "Lade $files hoch ($n von $of)…"
  override fun replacesFile(file: String) = "$file ist schon in der Bibliothek. Ersetzen?"
  override fun replacesGame(file: String, game: String, other: String) =
    "$file bringt $game, das jetzt aus $other kommt. $other dadurch ersetzen?"
  override val replace = "Ersetzen"
  override val skip = "Überspringen"
  override fun skipped(file: String) = "$file wurde übersprungen; nichts geändert"
  override val pillWaiting = "Wartet, bis sie dran ist"
  override val pillChecking = "Wird hochgeladen, Archipelago prüft sie"
  override val pillAsking = "Wartet auf deine Antwort oben"
  override val clearOutcome = "Leeren"
  override fun removing(files: String) = "Entferne $files…"
  override fun removed(file: String, release: String) = "$file aus Archipelago $release entfernt"
  override fun trying(file: String, release: String) = "$file wird mit Archipelago $release ausprobiert…"
  override fun relisting(what: String) = "$what Archipelago listet seine Spiele neu auf; das dauert einen Moment."
  override val listing = "Die Spiele jedes Release werden aufgelistet…"
  override val noRelease = "Das Gateway meldet kein Archipelago-Release."
  override fun release(version: String) = "Archipelago $version"
  override val upload = "APWorld hochladen…"
  override val uploaded = "Hochgeladene APWorlds"
  override val uploadedTip = "Jede Datei mit den Spielen, die sie mitbringt."
  override val gold = "gold"
  override val goldMeaning = "Mit diesem Release geladen."
  override val orange = "orange"
  override val orangeMeaning = "Für ein anderes Release hochgeladen und hier ausprobiert: ungetestet."
  override val red = "rot"
  override val redMeaning = "Kein Spiel daraus geladen (warum, steht unten)."
  override val grey = "grau"
  override val greyMeaning = "Von Hand ins custom_worlds/ des Servers gelegt; hier nicht entfernbar."
  override val noneYet = "Noch keine: Lade eine für ein Spiel hoch, das nicht mitgeliefert wird."
  override fun removeQuestion(files: String, release: String) =
    "$files entfernen? Ihre Spiele sind dann für neue Sessions mit Archipelago $release nicht mehr nutzbar."
  override val yesRemove = "Ja, entfernen"
  override val removeSome = "Entfernen…"
  override val pickToRemove = "Wähle die Dateien zum Entfernen (graue wurden von Hand abgelegt und lassen sich hier nicht entfernen)."
  override fun removeCount(n: Int) = "Entfernen ($n)"
  override val selectAll = "Alle auswählen"
  override val selectNone = "Keine auswählen"
  override fun didntLoad(name: String, reason: String) = "Nicht geladen: $name: $reason"
  override val fromOtherReleases = "Aus anderen Releases"
  override fun fromRelease(file: String, release: String) = "$file (Archipelago $release)"
  override fun tryWith(release: String) = "Mit $release ausprobieren"
  override fun supportedGames(n: Int) = "Unterstützte Spiele ($n)"
  override val bundled = "mitgeliefert"
  override val fromApworld = "aus einer APWorld"
  override val noGame = "kein Spiel"
  override fun from(release: String) = "aus $release"
}

internal object EnglishUserAdmin : UserAdminTexts {
  override val title = "Users"
  override fun subtitle(accounts: Int, invited: Int) =
    "$accounts ${if (accounts == 1) "account" else "accounts"}${if (invited > 0) ", $invited invited" else ""}"
  override val email = "E-mail address"
  override val suggestedName = "Suggested name"
  override val invite = "Invite"
  override fun invited(address: String) = "Invited $address: the link works for 7 days."
  override val invitedPill = "invited"
  override val administrator = "Administrator"
  override val noSession = "no session"
  override fun inSession(id: String) = "in $id"
  override fun inSessions(n: Int) = "in $n sessions"
  override val linkExpired = "the link has expired"
  override fun linkWorksUntil(date: String) = "the link works until $date"
  override fun deletedOn(date: String) = "will be deleted on $date"
  override fun autoDeletion(period: String) = "auto-deletion: $period"
  override val nameLabel = "Name"
  override val emailLabel = "E-mail"
  override val idLabel = "ID"
  override val stateLabel = "State"
  override val active = "active"
  override val groupLabel = "Group"
  override val userGroup = "User"
  override val loginLabel = "Login"
  override val passwordLogin = "password"
  override fun emailVerifiedBy(provider: String, verified: Boolean) =
    if (verified) "verified in $provider" else "not verified in $provider"
  override fun ssoLogin(provider: String?) = if (provider != null) "SSO ($provider)" else "SSO"
  override val twoFactorLabel = "Two-factor"
  override val on = "on"
  override val off = "off"
  override val languageLabel = "Language"
  override val browserLanguage = "the browser's"
  override val madeLabel = "Created"
  override val invitationLabel = "Invitation"
  override val deletionLabel = "Deletion"
  override val sessionsLabel = "Sessions"
  override val notAvailable = "n/a"
  override val sendAgain = "Send again"
  override fun newLinkWent(address: String) = "A new link went to $address; the old one stops working."
  override val otherAddress = "Other address"
  override val sendThere = "Send the link there"
  override fun linkWentTo(address: String) = "A new link went to $address."
  override val versions = "Versions"
  override val versionsSubtitle = "currently running"
  override val trackerVersion = "Tracker"
  override val gatewayVersion = "Gateway"
  override val pageVersion = "This page"
  override val releasesLabel = "Archipelago"
  override val notReachable = "not reachable"
  override val sessionsPerUser = "Sessions per user (default)"
  override val sessionsPerUserTip =
    "How many running sessions of their own a user may have at once (0 to 20; 0: only Administrators create " +
      "sessions). Only sessions they created count, not ones they manage for others. An account can get its own " +
      "limit in its panel; this default applies to all others. Administrators have no limit; the number of game " +
      "servers applies to everyone."
  override val ownSessionsLabel = "Self-created sessions"
  override fun sessionsOf(running: Int, limit: Int) = "$running running, limit $limit"
  override val theDefault = "(default)"
  override val noLimit = "no limit"
  override val ownLimit = "Self-created sessions limit"
  override val useDefault = "Use default"
  override fun limitSaved(name: String) = "Saved for $name."
  override val saved = "Saved."
  override val zeroToTwenty = "0 to 20"
  override val timedOutPill = "timed out"
  override val suspendedPill = "suspended"
  override val accessLabel = "Access"
  override val mayLogIn = "can log in"
  override fun timedOutUntil(date: String) = "timed out until $date"
  override val suspended = "suspended until lifted"
  override fun keptOutBy(name: String) = "by $name"
  override fun keptOutFor(reason: String) = "Reason: $reason"
  override val reason = "Reason (optional; shown to them)"
  override fun timeOut(time: TimeOut) = when (time) {
    TimeOut.HOUR -> "1 hour"
    TimeOut.HOURS_3 -> "3 hours"
    TimeOut.HOURS_6 -> "6 hours"
    TimeOut.HOURS_12 -> "12 hours"
    TimeOut.DAY -> "1 day"
    TimeOut.DAYS_2 -> "2 days"
    TimeOut.DAYS_3 -> "3 days"
    TimeOut.WEEK -> "1 week"
    TimeOut.WEEKS_2 -> "2 weeks"
    TimeOut.MONTH -> "30 days"
  }
  override val timeOutButton = "Time out"
  override val suspendButton = "Suspend"
  override val letInButton = "Let in again"
  override val deleteButton = "Delete account"
  override fun reallyDelete(name: String) = "Really delete $name? Their sessions keep their name. This can't be undone."
  override val deleteNow = "Delete now"
  override fun timedOutDone(name: String) = "$name is timed out and was told by mail."
  override fun suspendedDone(name: String) = "$name is suspended and was told by mail."
  override fun letInDone(name: String) = "$name can log in again and was told by mail."
  override fun deletedDone(name: String) = "$name is deleted."
  override val keptOutTip =
    "A timed-out or suspended account can't log in, and its logins (Randogeddon Bridge's too) end at once. Its " +
      "sessions go on and its games stay connected. A time-out ends by itself."
  override val managedElsewhere = "Administrators are managed where they log in."
}

internal object GermanUserAdmin : UserAdminTexts {
  override val title = "Benutzer"
  override fun subtitle(accounts: Int, invited: Int) =
    "$accounts ${if (accounts == 1) "Konto" else "Konten"}${if (invited > 0) ", $invited eingeladen" else ""}"
  override val email = "E-Mail-Adresse"
  override val suggestedName = "Namensvorschlag"
  override val invite = "Einladen"
  override fun invited(address: String) = "$address eingeladen: Der Link funktioniert 7 Tage."
  override val invitedPill = "eingeladen"
  override val administrator = "Administrator"
  override val noSession = "keine Session"
  override fun inSession(id: String) = "in $id"
  override fun inSessions(n: Int) = "in $n Sessions"
  override val linkExpired = "der Link ist abgelaufen"
  override fun linkWorksUntil(date: String) = "der Link funktioniert bis $date"
  override fun deletedOn(date: String) = "wird am $date gelöscht"
  override fun autoDeletion(period: String) = "automatisch löschen: $period"
  override val nameLabel = "Name"
  override val emailLabel = "E-Mail"
  override val idLabel = "ID"
  override val stateLabel = "Status"
  override val active = "aktiv"
  override val groupLabel = "Gruppe"
  override val userGroup = "Benutzer"
  override val loginLabel = "Anmeldung"
  override val passwordLogin = "Passwort"
  override fun emailVerifiedBy(provider: String, verified: Boolean) =
    if (verified) "in $provider bestätigt" else "in $provider nicht bestätigt"
  override fun ssoLogin(provider: String?) = if (provider != null) "SSO ($provider)" else "SSO"
  override val twoFactorLabel = "Zwei-Faktor"
  override val on = "an"
  override val off = "aus"
  override val languageLabel = "Sprache"
  override val browserLanguage = "die des Browsers"
  override val madeLabel = "Erstellt"
  override val invitationLabel = "Einladung"
  override val deletionLabel = "Löschung"
  override val sessionsLabel = "Sessions"
  override val notAvailable = "k. A."
  override val sendAgain = "Erneut senden"
  override fun newLinkWent(address: String) = "Ein neuer Link ging an $address; der alte funktioniert nicht mehr."
  override val otherAddress = "Andere Adresse"
  override val sendThere = "Den Link dorthin senden"
  override fun linkWentTo(address: String) = "Ein neuer Link ging an $address."
  override val versions = "Versionen"
  override val versionsSubtitle = "aktuell laufend"
  override val trackerVersion = "Tracker"
  override val gatewayVersion = "Gateway"
  override val pageVersion = "Diese Seite"
  override val releasesLabel = "Archipelago"
  override val notReachable = "nicht erreichbar"
  override val sessionsPerUser = "Sessions pro Benutzer (Standard)"
  override val sessionsPerUserTip =
    "Wie viele eigene laufende Sessions ein Benutzer gleichzeitig haben darf (0 bis 20; 0: nur Administratoren " +
      "erstellen Sessions). Es zählen nur selbst erstellte Sessions, nicht die, die jemand für andere verwaltet. Ein " +
      "Konto kann in seinem Fenster ein eigenes Limit bekommen; dieser Standard gilt für alle anderen. " +
      "Administratoren haben kein Limit; die Zahl der Spielserver gilt für alle."
  override val ownSessionsLabel = "Selbst erstellte Sessions"
  override fun sessionsOf(running: Int, limit: Int) = "$running laufend, Limit $limit"
  override val theDefault = "(Standard)"
  override val noLimit = "kein Limit"
  override val ownLimit = "Limit selbst erstellter Sessions"
  override val useDefault = "Standard verwenden"
  override fun limitSaved(name: String) = "Für $name gespeichert."
  override val saved = "Gespeichert."
  override val zeroToTwenty = "0 bis 20"
  override val timedOutPill = "Auszeit"
  override val suspendedPill = "gesperrt"
  override val accessLabel = "Zugang"
  override val mayLogIn = "kann sich anmelden"
  override fun timedOutUntil(date: String) = "Auszeit bis $date"
  override val suspended = "gesperrt bis zur Aufhebung"
  override fun keptOutBy(name: String) = "von $name"
  override fun keptOutFor(reason: String) = "Grund: $reason"
  override val reason = "Grund (optional; wird der Person mitgeteilt)"
  override fun timeOut(time: TimeOut) = when (time) {
    TimeOut.HOUR -> "1 Stunde"
    TimeOut.HOURS_3 -> "3 Stunden"
    TimeOut.HOURS_6 -> "6 Stunden"
    TimeOut.HOURS_12 -> "12 Stunden"
    TimeOut.DAY -> "1 Tag"
    TimeOut.DAYS_2 -> "2 Tage"
    TimeOut.DAYS_3 -> "3 Tage"
    TimeOut.WEEK -> "1 Woche"
    TimeOut.WEEKS_2 -> "2 Wochen"
    TimeOut.MONTH -> "30 Tage"
  }
  override val timeOutButton = "Auszeit geben"
  override val suspendButton = "Sperren"
  override val letInButton = "Wieder zulassen"
  override val deleteButton = "Konto löschen"
  override fun reallyDelete(name: String) = "$name wirklich löschen? Die Sessions behalten den Namen. Das lässt sich nicht rückgängig machen."
  override val deleteNow = "Jetzt löschen"
  override fun timedOutDone(name: String) = "$name hat eine Auszeit und wurde per Mail informiert."
  override fun suspendedDone(name: String) = "$name ist gesperrt und wurde per Mail informiert."
  override fun letInDone(name: String) = "$name kann sich wieder anmelden und wurde per Mail informiert."
  override fun deletedDone(name: String) = "$name ist gelöscht."
  override val keptOutTip =
    "Ein Konto mit Auszeit oder Sperre kann sich nicht anmelden, und seine Anmeldungen (auch die von Randogeddon " +
      "Bridge) enden sofort. Seine Sessions laufen weiter, und seine Spiele bleiben verbunden. Eine Auszeit endet von selbst."
  override val managedElsewhere = "Administratoren werden dort verwaltet, wo sie sich anmelden."
}
