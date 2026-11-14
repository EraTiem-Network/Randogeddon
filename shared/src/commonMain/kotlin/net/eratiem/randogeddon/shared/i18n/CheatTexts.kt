package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.CheatAction

/** Cheats (in the panel) and help (support's section on the dashboard). */
interface CheatTexts {
  fun action(action: CheatAction): String
  fun tip(action: CheatAction): String
  val support: String
  val cheats: String
  fun subtitle(support: Boolean): String
  val supportTip: String
  val cheatsTip: String
  val everyCheckDone: String
  val noSlots: String
  val onlyWhileRunning: String
  val slot: String
  val pickSlotFirst: String
  val noItems: String
  val noLocations: String
  val item: String
  val location: String
  val sending: String
  val send: String
  val cheat: String
  /** After it was sent: `Done: gave Mantis Claw to Alice-HK`. */
  fun done(action: CheatAction, slot: String, value: String?): String
  fun logTitle(support: Boolean): String
  /** A cheat in the session's list: `18:21 · Bob: gave Mantis Claw to Alice-HK`. */
  fun logLine(time: String, by: String, action: CheatAction, slot: String, value: String?): String
  /** In the live feed: `Bob (support) gave Mantis Claw to Alice-HK`; [role] `manager`, `support` or none. */
  fun feedLine(by: String, role: String?, action: CheatAction, slot: String, value: String?): String
}

internal object EnglishCheats : CheatTexts {
  override fun action(action: CheatAction) = when (action) {
    CheatAction.ITEM -> "Give an item"
    CheatAction.LOCATION -> "Check a location"
    CheatAction.RELEASE -> "Release"
    CheatAction.COLLECT -> "Collect"
    CheatAction.HINT -> "Hint an item"
    CheatAction.HINT_LOCATION -> "Hint a location"
  }
  override fun tip(action: CheatAction) = when (action) {
    CheatAction.ITEM -> "The slot gets the item from the server, as if it was found."
    CheatAction.LOCATION -> "The location of the slot counts as checked: its item goes to whoever it belongs to."
    CheatAction.RELEASE -> "The slot's remaining items are sent to their owners, as if it released."
    CheatAction.COLLECT -> "The slot gets its items that are still in other slots."
    CheatAction.HINT -> "A free hint: where the slot's item is."
    CheatAction.HINT_LOCATION -> "A free hint: what is at the slot's location."
  }
  override val support = "Support"
  override val cheats = "Cheats"
  override fun subtitle(support: Boolean) =
    (if (support) "you help as support: " else "") + "logged with your name and shown in the live feed"
  override val supportTip =
    "When a player is stuck: give an item, check a location or a free hint. Everyone sees in the live feed who helped how."
  override val cheatsTip =
    "For when something went wrong: a game bug, a missed item, someone has to drop out. Everyone sees in the live " +
      "feed who cheated what. Hints are only possible if the session was created with cheat hints."
  override val everyCheckDone = "Every check is done: nothing is left to cheat."
  override val noSlots = "No session with slots yet."
  override val onlyWhileRunning = "Only while the game server runs a session that isn't finished."
  override val slot = "Slot…"
  override val pickSlotFirst = "Pick a slot first"
  override val noItems = "No items"
  override val noLocations = "No open locations"
  override val item = "Item…"
  override val location = "Location…"
  override val sending = "Sending…"
  override val send = "Send"
  override val cheat = "Cheat"
  private fun what(action: CheatAction, slot: String, value: String?) = when (action) {
    CheatAction.ITEM -> "gave $value to $slot"
    CheatAction.LOCATION -> "checked $value in $slot"
    CheatAction.RELEASE -> "released $slot"
    CheatAction.COLLECT -> "collected for $slot"
    CheatAction.HINT -> "hinted $value for $slot"
    CheatAction.HINT_LOCATION -> "hinted what is at $value in $slot"
  }
  override fun done(action: CheatAction, slot: String, value: String?) = "Done: ${what(action, slot, value)}"
  override fun logTitle(support: Boolean) = if (support) "Help and cheats of this session" else "Cheats of this session"
  override fun logLine(time: String, by: String, action: CheatAction, slot: String, value: String?) =
    "$time · $by: ${what(action, slot, value)}"
  override fun feedLine(by: String, role: String?, action: CheatAction, slot: String, value: String?) =
    when (role) {
      "manager" -> "$by (Session-Manager)"
      "support" -> "$by (support)"
      else -> by
    } + " " + what(action, slot, value)
}

internal object GermanCheats : CheatTexts {
  override fun action(action: CheatAction) = when (action) {
    CheatAction.ITEM -> "Item geben"
    CheatAction.LOCATION -> "Ort checken"
    CheatAction.RELEASE -> "Freigeben"
    CheatAction.COLLECT -> "Einsammeln"
    CheatAction.HINT -> "Hinweis auf ein Item"
    CheatAction.HINT_LOCATION -> "Hinweis auf einen Ort"
  }
  override fun tip(action: CheatAction) = when (action) {
    CheatAction.ITEM -> "Der Slot bekommt das Item vom Server, als wäre es gefunden worden."
    CheatAction.LOCATION -> "Der Ort des Slots gilt als gecheckt: Sein Item geht an den, dem es gehört."
    CheatAction.RELEASE -> "Die restlichen Items des Slots gehen an ihre Besitzer, als hätte er freigegeben."
    CheatAction.COLLECT -> "Der Slot bekommt seine Items, die noch in anderen Slots liegen."
    CheatAction.HINT -> "Ein Gratis-Hinweis: wo das Item des Slots liegt."
    CheatAction.HINT_LOCATION -> "Ein Gratis-Hinweis: was am Ort des Slots liegt."
  }
  override val support = "Support"
  override val cheats = "Cheats"
  override fun subtitle(support: Boolean) =
    (if (support) "du hilfst als Support: " else "") + "mit deinem Namen protokolliert und im Live-Feed gezeigt"
  override val supportTip =
    "Wenn ein Spieler feststeckt: ein Item geben, einen Ort checken oder einen Gratis-Hinweis. Alle sehen im " +
      "Live-Feed, wer wie geholfen hat."
  override val cheatsTip =
    "Für den Fall, dass etwas schiefging: ein Spielfehler, ein verpasstes Item, jemand muss aussteigen. Alle sehen im " +
      "Live-Feed, wer was gecheatet hat. Hinweise gehen nur, wenn die Session mit Cheat-Hinweisen erstellt wurde."
  override val everyCheckDone = "Alle Checks sind erledigt: Es gibt nichts mehr zu cheaten."
  override val noSlots = "Noch keine Session mit Slots."
  override val onlyWhileRunning = "Nur, während der Spielserver eine nicht beendete Session bereitstellt."
  override val slot = "Slot…"
  override val pickSlotFirst = "Wähle zuerst einen Slot"
  override val noItems = "Keine Items"
  override val noLocations = "Keine offenen Orte"
  override val item = "Item…"
  override val location = "Ort…"
  override val sending = "Wird gesendet…"
  override val send = "Senden"
  override val cheat = "Cheaten"
  private fun what(action: CheatAction, slot: String, value: String?) = when (action) {
    CheatAction.ITEM -> "$slot hat $value bekommen"
    CheatAction.LOCATION -> "$value in $slot gecheckt"
    CheatAction.RELEASE -> "$slot freigegeben"
    CheatAction.COLLECT -> "für $slot eingesammelt"
    CheatAction.HINT -> "Hinweis auf $value für $slot"
    CheatAction.HINT_LOCATION -> "Hinweis, was bei $value in $slot liegt"
  }
  override fun done(action: CheatAction, slot: String, value: String?) = "Erledigt: ${what(action, slot, value)}"
  override fun logTitle(support: Boolean) = if (support) "Hilfe und Cheats dieser Session" else "Cheats dieser Session"
  override fun logLine(time: String, by: String, action: CheatAction, slot: String, value: String?) =
    "$time · $by: ${what(action, slot, value)}"
  override fun feedLine(by: String, role: String?, action: CheatAction, slot: String, value: String?) =
    when (role) {
      "manager" -> "$by (Session-Manager)"
      "support" -> "$by (Support)"
      else -> by
    } + ": " + what(action, slot, value)
}
