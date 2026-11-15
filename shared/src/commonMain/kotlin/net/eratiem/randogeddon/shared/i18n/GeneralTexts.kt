package net.eratiem.randogeddon.shared.i18n

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
  val ssoLogin: String
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
  val settings: String
  val settingsSubtitle: String
  val sessionsPerUser: String
  val sessionsPerUserTip: String
  val saved: String
  val zeroToTwenty: String
}

internal object EnglishApworlds : ApworldTexts {
  override val title = "APWorlds"
  override val subtitle = "the games of each Archipelago release"
  override val tip = "The games each Archipelago release of the gateway can generate and serve: the bundled ones, plus " +
    "APWorlds. APWorlds uploaded here are kept per release; ones put into the server's custom_worlds/ count for every " +
    "release. An APWorld uploaded for another release can be tried with this one, it may or may not work."
  override fun notAdded(file: String, error: String) = "$file was not added, it doesn't load: $error"
  override fun brings(file: String, games: String) = "$file brings $games"
  override fun uploading(files: String, n: Int, of: Int) = if (of == 1) "Uploading $files…" else "Uploading $files ($n of $of)…"
  override fun replacesFile(file: String) = "$file is in the library already. Replace it?"
  override fun replacesGame(file: String, game: String, other: String) =
    "$file brings $game, which comes from $other now. Replace $other with it?"
  override val replace = "Replace"
  override val skip = "Skip"
  override fun skipped(file: String) = "$file was skipped, nothing changed"
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
  override val pickToRemove = "Pick the files to remove (hand-placed ones, grey, can't be)."
  override fun removeCount(n: Int) = "Remove ($n)"
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
    "mitgelieferten, dazu APWorlds. Hier hochgeladene APWorlds gelten je Release; welche im custom_worlds/ des " +
    "Servers gelten für jedes Release. Eine für ein anderes Release hochgeladene APWorld kann mit diesem ausprobiert " +
    "werden; das kann klappen oder nicht."
  override fun notAdded(file: String, error: String) = "$file wurde nicht hinzugefügt, sie lädt nicht: $error"
  override fun brings(file: String, games: String) = "$file bringt $games"
  override fun uploading(files: String, n: Int, of: Int) =
    if (of == 1) "Lade $files hoch…" else "Lade $files hoch ($n von $of)…"
  override fun replacesFile(file: String) = "$file ist schon in der Bibliothek. Ersetzen?"
  override fun replacesGame(file: String, game: String, other: String) =
    "$file bringt $game, das jetzt aus $other kommt. $other dadurch ersetzen?"
  override val replace = "Ersetzen"
  override val skip = "Überspringen"
  override fun skipped(file: String) = "$file wurde übersprungen, nichts geändert"
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
  override val pickToRemove = "Wähle die Dateien zum Entfernen (von Hand gelegte, graue, gehen nicht)."
  override fun removeCount(n: Int) = "Entfernen ($n)"
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
  override val suggestedName = "Their name (a suggestion)"
  override val invite = "Invite"
  override fun invited(address: String) = "Invited $address: the link works for 7 days."
  override val invitedPill = "invited"
  override val administrator = "Administrator"
  override val noSession = "no session"
  override fun inSession(id: String) = "in $id"
  override fun inSessions(n: Int) = "in $n sessions"
  override val linkExpired = "the link has expired"
  override fun linkWorksUntil(date: String) = "the link works until $date"
  override fun deletedOn(date: String) = "deleted on $date"
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
  override val ssoLogin = "single sign-on"
  override val twoFactorLabel = "Two-factor"
  override val on = "on"
  override val off = "off"
  override val languageLabel = "Language"
  override val browserLanguage = "the browser's"
  override val madeLabel = "Made"
  override val invitationLabel = "Invitation"
  override val deletionLabel = "Deletion"
  override val sessionsLabel = "Sessions"
  override val notAvailable = "n/a"
  override val sendAgain = "Send again"
  override fun newLinkWent(address: String) = "A new link went to $address; the old one stops working."
  override val otherAddress = "Other address"
  override val sendThere = "Send the link there"
  override fun linkWentTo(address: String) = "A new link went to $address."
  override val settings = "Settings"
  override val settingsSubtitle = "for everyone"
  override val sessionsPerUser = "Sessions per user"
  override val sessionsPerUserTip =
    "How many running sessions one user may create and manage at once (0 to 20; 0: only Administrators create " +
      "sessions). Administrators have no limit; the game servers' number applies to everyone."
  override val saved = "Saved."
  override val zeroToTwenty = "0 to 20"
}

internal object GermanUserAdmin : UserAdminTexts {
  override val title = "Benutzer"
  override fun subtitle(accounts: Int, invited: Int) =
    "$accounts ${if (accounts == 1) "Konto" else "Konten"}${if (invited > 0) ", $invited eingeladen" else ""}"
  override val email = "E-Mail-Adresse"
  override val suggestedName = "Ihr Name (ein Vorschlag)"
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
  override val ssoLogin = "Single Sign-on"
  override val twoFactorLabel = "Zwei-Faktor"
  override val on = "an"
  override val off = "aus"
  override val languageLabel = "Sprache"
  override val browserLanguage = "die des Browsers"
  override val madeLabel = "Angelegt"
  override val invitationLabel = "Einladung"
  override val deletionLabel = "Löschung"
  override val sessionsLabel = "Sessions"
  override val notAvailable = "k. A."
  override val sendAgain = "Erneut senden"
  override fun newLinkWent(address: String) = "Ein neuer Link ging an $address; der alte funktioniert nicht mehr."
  override val otherAddress = "Andere Adresse"
  override val sendThere = "Den Link dorthin senden"
  override fun linkWentTo(address: String) = "Ein neuer Link ging an $address."
  override val settings = "Einstellungen"
  override val settingsSubtitle = "für alle"
  override val sessionsPerUser = "Sessions pro Benutzer"
  override val sessionsPerUserTip =
    "Wie viele laufende Sessions ein Benutzer gleichzeitig erstellen und verwalten darf (0 bis 20; 0: nur " +
      "Administratoren erstellen Sessions). Administratoren haben keine Grenze; die Zahl der Spielserver gilt für alle."
  override val saved = "Gespeichert."
  override val zeroToTwenty = "0 bis 20"
}
