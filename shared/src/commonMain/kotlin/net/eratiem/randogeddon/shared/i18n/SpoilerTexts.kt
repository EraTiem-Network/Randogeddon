package net.eratiem.randogeddon.shared.i18n

/** A session's spoiler log: the playthrough and the full log. */
interface SpoilerTexts {
  val title: String
  fun spheres(n: Int): String
  val everySlot: String
  val foldAll: String
  val unfoldAll: String
  val playthrough: String
  val playthroughSubtitle: String
  fun nothingFor(slot: String): String
  val sphereZero: String
  fun sphere(n: Int): String
  fun steps(n: Int): String
  val slot: String
  val item: String
  val check: String
  /** The search found nothing. */
  val none: String
  val fullLog: String
  val fullLogSubtitle: String
}

internal object EnglishSpoiler : SpoilerTexts {
  override val title = "Spoiler log"
  override fun spheres(n: Int) = if (n == 1) "1 sphere" else "$n spheres"
  override val everySlot = "Every slot"
  override val foldAll = "Fold all"
  override val unfoldAll = "Unfold all"
  override val playthrough = "Playthrough"
  override val playthroughSubtitle = "the way through the seed, sphere by sphere: what becomes reachable, and is needed, at each step"
  override fun nothingFor(slot: String) = "Nothing for $slot."
  override val sphereZero = "Sphere 0 · what everyone starts with"
  override fun sphere(n: Int) = "Sphere $n"
  override fun steps(n: Int) = if (n == 1) "1 step" else "$n steps"
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
  override val everySlot = "Alle Slots"
  override val foldAll = "Alle zuklappen"
  override val unfoldAll = "Alle aufklappen"
  override val playthrough = "Durchspielweg"
  override val playthroughSubtitle =
    "der Weg durch den Seed, Sphäre für Sphäre: was bei jedem Schritt erreichbar wird und gebraucht wird"
  override fun nothingFor(slot: String) = "Nichts für $slot."
  override val sphereZero = "Sphäre 0 · womit alle anfangen"
  override fun sphere(n: Int) = "Sphäre $n"
  override fun steps(n: Int) = if (n == 1) "1 Schritt" else "$n Schritte"
  override val slot = "Slot"
  override val item = "Item"
  override val check = "Check"
  override val none = "keine"
  override val fullLog = "Ganzes Spoiler-Log"
  override val fullLogSubtitle = "jeder Check jedes Slots, die Optionen und der Durchspielweg"
}
