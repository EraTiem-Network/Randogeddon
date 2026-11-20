package net.eratiem.randogeddon.shared.i18n

/** A session's spoiler log: the playthrough and the full log. */
interface SpoilerTexts {
  val title: String
  fun spheres(n: Int): String
  val unreduced: String
  val everySlot: String
  val foldAll: String
  val unfoldAll: String
  val playthrough: String
  val playthroughSubtitle: String
  fun nothingFor(slot: String): String
  /** The playthrough's search found no step. */
  fun nothingMatches(query: String): String
  val searchSteps: String
  val sphereZero: String
  fun sphere(n: Int): String
  fun steps(n: Int): String
  /** A sphere's steps whose location is checked already: `3 / 5 checked`. */
  fun checked(done: Int, of: Int): String
  /** A step's mark: its location is checked. */
  val checkedTip: String
  val slot: String
  val item: String
  val check: String
  /** The search found nothing. */
  val none: String
  val fullLog: String
  val fullLogSubtitle: String
  /** Which lines of the log show: `Lines 301–600 of 12,345` (the numbers formatted already). */
  fun linesOf(from: String, to: String, total: String): String
  val earlierLines: String
  val laterLines: String
  val clearSearch: String
}

internal object EnglishSpoiler : SpoilerTexts {
  override val title = "Spoiler log"
  override fun spheres(n: Int) = if (n == 1) "1 sphere" else "$n spheres"
  override val unreduced = "This playthrough lists every progression item in the order it can be collected, not only " +
    "the ones needed: Archipelago couldn't reduce it, because a game's logic contradicts itself. That game's steps may be off."
  override fun linesOf(from: String, to: String, total: String) = "Lines $from–$to of $total"
  override val earlierLines = "Earlier lines"
  override val laterLines = "Later lines"
  override val clearSearch = "Clear the search"
  override val everySlot = "All slots"
  override val foldAll = "Collapse all"
  override val unfoldAll = "Expand all"
  override val playthrough = "Playthrough"
  override val playthroughSubtitle = "the way through the seed, sphere by sphere: what becomes reachable, and is needed, at each step"
  override fun nothingFor(slot: String) = "Nothing for $slot."
  override fun nothingMatches(query: String) = "No step matches “$query”."
  override val searchSteps = "Item, check or slot…"
  override val sphereZero = "Sphere 0 · what everyone starts with"
  override fun sphere(n: Int) = "Sphere $n"
  override fun steps(n: Int) = if (n == 1) "1 step" else "$n steps"
  override fun checked(done: Int, of: Int) = "$done / $of checked"
  override val checkedTip = "Checked already"
  override val slot = "Slot"
  override val item = "Item"
  override val check = "Check"
  override val none = "none"
  override val fullLog = "Full spoiler log"
  override val fullLogSubtitle = "every check of every slot, the options and the playthrough"
}

internal object GermanSpoiler : SpoilerTexts {
  override val title = "Spoiler-Log"
  override fun spheres(n: Int) = if (n == 1) "1 Sphäre" else "$n Sphären"
  override val unreduced = "Dieser Lösungsweg nennt jedes Fortschritts-Item in der Reihenfolge, in der es erreichbar ist, " +
    "nicht nur die nötigen: Archipelago konnte ihn nicht kürzen, weil sich die Logik eines Spiels widerspricht. " +
    "Die Schritte dieses Spiels können abweichen."
  override fun linesOf(from: String, to: String, total: String) = "Zeilen $from–$to von $total"
  override val earlierLines = "Frühere Zeilen"
  override val laterLines = "Spätere Zeilen"
  override val clearSearch = "Suche leeren"
  override val everySlot = "Alle Slots"
  override val foldAll = "Alle einklappen"
  override val unfoldAll = "Alle aufklappen"
  override val playthrough = "Lösungsweg"
  override val playthroughSubtitle =
    "der Weg durch den Seed, Sphäre für Sphäre: was bei jedem Schritt erreichbar wird und gebraucht wird"
  override fun nothingFor(slot: String) = "Nichts für $slot."
  override fun nothingMatches(query: String) = "Kein Schritt passt zu „$query“."
  override val searchSteps = "Item, Check oder Slot…"
  override val sphereZero = "Sphäre 0 · womit alle anfangen"
  override fun sphere(n: Int) = "Sphäre $n"
  override fun steps(n: Int) = if (n == 1) "1 Schritt" else "$n Schritte"
  override fun checked(done: Int, of: Int) = "$done / $of gecheckt"
  override val checkedTip = "Schon gecheckt"
  override val slot = "Slot"
  override val item = "Item"
  override val check = "Check"
  override val none = "keine"
  override val fullLog = "Ganzes Spoiler-Log"
  override val fullLogSubtitle = "jeder Check jedes Slots, die Optionen und der Lösungsweg"
}
