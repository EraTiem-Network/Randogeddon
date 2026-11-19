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
  val upload: String
  val uploadedTip: String
  val supportedVersions: String
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
  fun needsAp(version: String): String
}

internal object EnglishApworlds : ApworldTexts {
  override val title = "Archipelago"
  override val subtitle = "the releases the gateway runs, and their games"
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
  override val upload = "upload apworld"
  override val uploadedTip =
    "The bundled games, and the custom ones: each APWorld with its game and version; its tip says its file name and size."
  override val supportedVersions = "Supported versions"
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
  override val removeSome = "remove"
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
  override val fromApworld = "custom games"
  override val noGame = "no game"
  override fun from(release: String) = "from $release"
  override fun needsAp(version: String) = "needs Archipelago $version or newer"
}

internal object GermanApworlds : ApworldTexts {
  override val title = "Archipelago"
  override val subtitle = "die Releases, die das Gateway ausführt, und ihre Spiele"
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
  override val upload = "apworld hochladen"
  override val uploadedTip =
    "Die mitgelieferten Spiele und die eigenen: jede APWorld mit ihrem Spiel und ihrer Version; ihr Tipp nennt Dateiname und Größe."
  override val supportedVersions = "Unterstützte Versionen"
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
  override val removeSome = "entfernen"
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
  override val fromApworld = "eigene Spiele"
  override val noGame = "kein Spiel"
  override fun from(release: String) = "aus $release"
  override fun needsAp(version: String) = "braucht Archipelago $version oder neuer"
}
