package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CheatAction
import net.eratiem.randogeddon.shared.CheatNames
import net.eratiem.randogeddon.shared.CheatRequest
import net.eratiem.randogeddon.shared.CheatView
import net.eratiem.randogeddon.shared.GameServerState

private val CHEAT_LABELS = mapOf(
  CheatAction.ITEM to "Give an item",
  CheatAction.LOCATION to "Check a location",
  CheatAction.RELEASE to "Release",
  CheatAction.COLLECT to "Collect",
  CheatAction.HINT to "Hint an item",
  CheatAction.HINT_LOCATION to "Hint a location",
)

private val CHEAT_TIPS = mapOf(
  CheatAction.ITEM to "The slot gets the item from the server, as if it was found.",
  CheatAction.LOCATION to "The location of the slot counts as checked: its item goes to whoever it belongs to.",
  CheatAction.RELEASE to "The slot's remaining items are sent to their owners, as if it released.",
  CheatAction.COLLECT to "The slot gets its items that are still in other slots.",
  CheatAction.HINT to "A free hint: where the slot's item is.",
  CheatAction.HINT_LOCATION to "A free hint: what is at the slot's location.",
)

/** The pickers are as tall as the button next to them. */
private val CONTROL_HEIGHT = 34.dp

private val WITH_ITEM = setOf(CheatAction.ITEM, CheatAction.HINT)
private val HINTS = setOf(CheatAction.HINT, CheatAction.HINT_LOCATION)
private val WITH_LOCATION = setOf(CheatAction.LOCATION, CheatAction.HINT_LOCATION)

/**
 * Cheats: give an item, check a location, release or collect for a slot, and free hints if the session allows them.
 * Every cheat is logged with the admin's name and shown in the live feed; the latest ones are listed here. Loaded
 * again whenever the game server changes ([server]: started, stopped, another seed). Kept in [cache].
 */
@Composable
fun CheatSection(id: String, server: GameServerState?, cache: AdminCache) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  // The last one loaded at once, loaded again behind
  val view = cache.cheats[id]
  var action by remember { mutableStateOf(CheatAction.ITEM) }
  var slot by remember { mutableStateOf<String?>(null) }
  var names by remember { mutableStateOf<CheatNames?>(null) }
  var value by remember { mutableStateOf<String?>(null) }
  var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
  var busy by remember { mutableStateOf(false) }

  fun load() = scope.launch {
    when (val answer = call("GET", "api/admin/sessions/$id/cheat", null) { ApiJson.decodeFromString(CheatView.serializer(), it) }) {
      is Answer.Ok -> cache.cheats[id] = answer.value
      is Answer.Failed -> outcome = false to answer.message
    }
  }
  // Also every 10 s: the tracker learns a new seed's slots a moment after the game server runs
  LaunchedEffect(server?.status, server?.seed) {
    while (true) {
      load()
      delay(10_000)
    }
  }
  // Another session: the slot may not exist any more
  LaunchedEffect(view?.slots) { if (slot != null && view?.slots?.contains(slot) == false) slot = null }
  // The slot's items and open locations, for the second picker; another slot starts empty
  LaunchedEffect(slot) {
    names = null
    value = null
  }
  // Loaded again with the view (after each cheat, and every 10 s): a location checked meanwhile drops out
  LaunchedEffect(slot, view) {
    val s = slot ?: return@LaunchedEffect
    val answer = call("GET", "api/admin/sessions/$id/cheat/names?slot=${s.encodeQuery()}", null) { ApiJson.decodeFromString(CheatNames.serializer(), it) }
    if (answer is Answer.Ok) {
      names = answer.value
      if (action in WITH_LOCATION && value != null && value !in answer.value.locations) value = null
    }
  }

  val current = view
  Section(
    "Cheats", subtitle = "logged with your name and shown in the live feed",
    actions = {
      InfoTip(
        "For when something went wrong: a game bug, a missed item, someone has to drop out. Everyone sees in the " +
          "live feed who cheated what. Hints are only possible if the session was created with cheat hints."
      )
    },
  ) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      when {
        current == null -> Txt("…", color = p.muted)
        // Once every check is done there is nothing to cheat either
        current.done -> Txt("Every check is done: nothing is left to cheat.", size = 13.sp, color = p.muted)
        current.slots.isEmpty() -> Txt("No session with slots yet.", size = 13.sp, color = p.muted)
        else -> {
          if (!current.available) Txt("Only while the game server runs a session that isn't finished.", size = 12.5.sp, color = p.muted)
          val actions = CheatAction.entries.filter { current.cheatHints || it !in HINTS }
          val ready = current.available && slot != null && !busy &&
            (value != null || (action !in WITH_ITEM && action !in WITH_LOCATION))
          val choices = when (action) {
            in WITH_ITEM -> names?.items
            in WITH_LOCATION -> names?.locations
            else -> null
          }
          val what: @Composable (Modifier) -> Unit = { modifier ->
            Tip(CHEAT_TIPS.getValue(action)) {
              SearchableSelect(
                actions.map { Triple(it, CHEAT_LABELS.getValue(it), "") }, action, { action = it; value = null },
                modifier, chip = false, minHeight = CONTROL_HEIGHT,
              )
            }
          }
          val forSlot: @Composable (Modifier) -> Unit = { modifier ->
            SearchableSelect(
              current.slots.map { Triple<String?, String, String>(it, it, "") }, slot, { slot = it },
              modifier, chip = false, placeholder = "Slot…", minHeight = CONTROL_HEIGHT,
            )
          }
          val needsValue = action in WITH_ITEM || action in WITH_LOCATION
          val which: @Composable (Modifier) -> Unit = { modifier ->
            SearchableSelect(
              choices.orEmpty().map { Triple<String?, String, String>(it, it, "") }, value, { value = it },
              modifier, chip = false, minHeight = CONTROL_HEIGHT,
              placeholder = when {
                slot == null -> "Pick a slot first"
                choices == null -> "…"
                choices.isEmpty() -> if (action in WITH_ITEM) "No items" else "No open locations"
                action in WITH_ITEM -> "Item…"
                else -> "Location…"
              },
            )
          }
          val send: @Composable () -> Unit = {
            Button(if (busy) "Sending…" else "Cheat", enabled = ready, danger = true) {
              busy = true
              outcome = null
              scope.launch {
                val body = ApiJson.encodeToString(CheatRequest.serializer(), CheatRequest(action, slot!!, value))
                outcome = when (val answer = call("POST", "api/admin/sessions/$id/cheat", body) { }) {
                  is Answer.Ok -> true to "Done: ${CHEAT_LABELS.getValue(action).lowercase()} for $slot" + (value?.let { ": $it" } ?: "")
                  is Answer.Failed -> false to answer.message
                }
                busy = false
                load()
              }
            }
          }
          BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < NARROW) {
              // A phone: what, the slot, the item or location one below the other, each as wide as the card
              Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                what(Modifier.fillMaxWidth())
                forSlot(Modifier.fillMaxWidth())
                if (needsValue) which(Modifier.fillMaxWidth())
                send()
              }
            } else {
              // One line: what, for which slot, which item or location, and the button; all as tall as the button
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                what(Modifier.width(170.dp))
                forSlot(Modifier.width(200.dp))
                if (needsValue) which(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                send()
              }
            }
          }
          outcome?.let { (ok, text) -> Txt(text, size = 12.5.sp, color = if (ok) p.ok else p.trap) }
          if (current.log.isNotEmpty()) {
            Divider()
            Txt("Cheats of this session", size = 12.5.sp, weight = FontWeight.SemiBold)
            current.log.take(15).forEach { entry ->
              Txt(
                "${Platform.clock(entry.ts)} · ${entry.admin}: ${CHEAT_LABELS.getValue(entry.action).lowercase()} for ${entry.slot}" +
                  (entry.value?.let { " · $it" } ?: ""),
                size = 12.sp, color = p.muted, maxLines = 2,
              )
            }
          }
        }
      }
    }
  }
}

/** For a query parameter: letters and digits as they are, everything else percent-encoded (UTF-8). */
private fun String.encodeQuery(): String = encodeToByteArray().joinToString("") { b ->
  val c = (b.toInt() and 0xff)
  if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.~")) c.toChar().toString()
  else "%" + c.toString(16).uppercase().padStart(2, '0')
}
