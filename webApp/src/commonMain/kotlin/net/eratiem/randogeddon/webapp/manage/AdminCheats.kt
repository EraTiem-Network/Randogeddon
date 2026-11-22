package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import net.eratiem.randogeddon.shared.HintPick
import net.eratiem.randogeddon.shared.LOCK_CHEATS
import net.eratiem.randogeddon.webapp.api.AdminCache
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.clock
import net.eratiem.randogeddon.webapp.dashboard.percentEncoded
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.NARROW
import net.eratiem.randogeddon.webapp.ui.SearchableSelect
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Select
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card

/** The pickers are as tall as the button next to them. */
private val CONTROL_HEIGHT = 34.dp

/** Which copy an item hint names, remembered in this browser. */
private const val HINT_PICK_KEY = "ap-hint-pick"

private val WITH_ITEM = setOf(CheatAction.ITEM, CheatAction.HINT)
private val HINTS = setOf(CheatAction.HINT, CheatAction.HINT_LOCATION)
private val WITH_LOCATION = setOf(CheatAction.LOCATION, CheatAction.HINT_LOCATION)

/** What support does: no release or collect. */
private val SUPPORT_ACTIONS = WITH_ITEM + WITH_LOCATION + LOCK_CHEATS

/**
 * Cheats: give an item, check a location, release or collect for a slot, and free hints if the session allows them.
 * Every cheat is logged with the name of who did it and shown in the live feed; the latest ones are listed here. Loaded
 * again whenever the game server changes ([server]: started, stopped, another seed). Kept in [cache]. [support]: the
 * session's support helps (items, checks and hints, no release or collect).
 */
@Composable
fun CheatSection(
  id: String,
  server: GameServerState?,
  cache: AdminCache,
  support: Boolean = false,
  /** Support: their spoiler log (while it runs too). */
  onSpoiler: (() -> Unit)? = null,
  /** The spoiler log has its playthrough: an item hint can name the next copy in it (else only one by chance). */
  playthrough: Boolean = true,
) {
  val path = if (support) "api/sessions/$id/support" else "api/sessions/$id/manage/cheat"
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  // The last one loaded at once, loaded again behind
  val view = cache.cheats[id]
  var action by remember { mutableStateOf(CheatAction.ITEM) }
  var slot by remember { mutableStateOf<String?>(null) }
  var names by remember { mutableStateOf<CheatNames?>(null) }
  var value by remember { mutableStateOf<String?>(null) }
  // Check a location to the slot: one of another world's (value its location, from that world)
  var toSlot by remember { mutableStateOf(false) }
  var from by remember { mutableStateOf<String?>(null) }
  var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
  var hintPick by remember { mutableStateOf(HintPick.entries.firstOrNull { it.name == Platform.loadSetting(HINT_PICK_KEY) } ?: HintPick.RANDOM) }
  var busy by remember { mutableStateOf(false) }

  fun load() = scope.launch {
    when (val answer = call("GET", path, null, CheatView.serializer())) {
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
  // Another session, or a slot opened meanwhile: the slot may not be one to pick any more
  val pickable = if (action in LOCK_CHEATS) view?.locked?.map { it.slot } else view?.slots
  LaunchedEffect(pickable) { if (slot != null && pickable?.contains(slot) == false) slot = null }
  // The slot's items and open locations, for the second picker; another slot starts empty
  LaunchedEffect(slot) {
    names = null
    value = null
    from = null
  }
  // Loaded again with the view (after each cheat, and every 10 s): a location checked meanwhile drops out
  LaunchedEffect(slot, view) {
    val s = slot ?: return@LaunchedEffect
    val answer = call("GET", "$path/names?slot=${s.percentEncoded()}", null, CheatNames.serializer())
    if (answer is Answer.Ok) {
      names = answer.value
      if (toSlot && action == CheatAction.LOCATION) {
        if (answer.value.incoming.none { it.location == value && it.world == from }) { value = null; from = null }
      } else if (action in WITH_LOCATION && value != null && value !in answer.value.locations) value = null
      else if (action == CheatAction.HINT_LOCATION && value != null && value !in answer.value.hintLocations) value = null
      else if (action == CheatAction.HINT && value != null && value !in answer.value.hintItems) value = null
    }
  }

  val current = view
  Section(
    if (support) t.cheats.support else t.cheats.cheats,
    subtitle = t.cheats.subtitle(support),
    actions = {
      InfoTip(if (support) t.cheats.supportTip else t.cheats.cheatsTip)
      onSpoiler?.let { SmallButton(t.finish.spoilerLog, it) }
    },
  ) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      when {
        current == null -> Txt("…", color = p.muted)
        // Once every check is done there is nothing to cheat either
        current.done -> Txt(t.cheats.everyCheckDone, size = 13.sp, color = p.muted)
        current.slots.isEmpty() -> Txt(t.cheats.noSlots, size = 13.sp, color = p.muted)
        else -> {
          if (!current.available) Txt(t.cheats.onlyWhileRunning, size = 12.5.sp, color = p.muted)
          // The lock cheats while a slot is locked
          val actions = CheatAction.entries.filter {
            (current.cheatHints || it !in HINTS) && (!support || it in SUPPORT_ACTIONS) && (it !in LOCK_CHEATS || current.locked.isNotEmpty()) &&
              // Unlock checks the key's location since 0.21.7: the old action isn't offered any more
              it != CheatAction.KEY_LOCATION
          }
          val lock = current.locked.firstOrNull { it.slot == slot }.takeIf { action in LOCK_CHEATS }
          val ready = current.available && slot != null && !busy &&
            (value != null || (action !in WITH_ITEM && action !in WITH_LOCATION)) &&
            (action != CheatAction.KEY_HINT || lock?.keyLocation != null)
          val incoming = toSlot && action == CheatAction.LOCATION
          // A hint names one place: only locations not hinted yet, and items with a copy neither checked nor hinted
          val choices = when (action) {
            CheatAction.HINT -> names?.hintItems
            CheatAction.HINT_LOCATION -> names?.hintLocations
            in WITH_ITEM -> names?.items
            in WITH_LOCATION -> names?.locations
            else -> null
          }
          val what: @Composable (Modifier) -> Unit = { modifier ->
            Tip(t.cheats.tip(action)) {
              SearchableSelect(
                actions.map { Triple(it, t.cheats.action(it), "") }, action,
                { if ((it in LOCK_CHEATS) != (action in LOCK_CHEATS)) slot = null; action = it; value = null; from = null },
                modifier, chip = false, minHeight = CONTROL_HEIGHT,
              )
            }
          }
          val forSlot: @Composable (Modifier) -> Unit = { modifier ->
            // A lock cheat: the locked slots only
            val slots = if (action in LOCK_CHEATS) current.locked.map { it.slot } else current.slots
            SearchableSelect(
              slots.map { Triple<String?, String, String>(it, it, "") }, slot, { slot = it },
              modifier, chip = false, placeholder = if (slots.isEmpty()) t.cheats.noLocked else t.cheats.slot, minHeight = CONTROL_HEIGHT,
            )
          }
          // Check a location: from the slot (its own) or to it (another world's, holding the slot's item)
          val direction: @Composable (Modifier) -> Unit = { modifier ->
            Select(
              listOf(false to t.cheats.fromSlot, true to t.cheats.toSlot), toSlot,
              { toSlot = it; value = null; from = null }, modifier, minHeight = CONTROL_HEIGHT,
            )
          }
          // An item hint: which of its copies (with the placements known), remembered in this browser
          val copy: @Composable (Modifier) -> Unit = { modifier ->
            Tip(t.cheats.copyTip) {
              Select(
                listOf(HintPick.RANDOM to t.cheats.randomCopy, HintPick.NEXT to t.cheats.nextCopy), hintPick,
                { hintPick = it; Platform.saveSetting(HINT_PICK_KEY, it.name) }, modifier, minHeight = CONTROL_HEIGHT,
              )
            }
          }
          val picksCopy = action == CheatAction.HINT && names?.placementsKnown == true && playthrough
          val needsValue = action in WITH_ITEM || action in WITH_LOCATION
          val which: @Composable (Modifier) -> Unit = { modifier ->
            if (incoming) {
              // Another world's location holding one of the slot's items: picked as "world\u0000location"
              val options = names?.incoming.orEmpty()
              SearchableSelect(
                options.map { Triple<String?, String, String>("${it.world}\u0000${it.location}", t.cheats.incoming(it.item, it.location, it.world), "") },
                from?.let { w -> value?.let { "$w\u0000$it" } },
                { key -> val (w, l) = key?.split('\u0000', limit = 2).orEmpty().let { it.getOrNull(0) to it.getOrNull(1) }; from = w; value = l },
                modifier, chip = false, minHeight = CONTROL_HEIGHT,
                placeholder = when {
                  slot == null -> t.cheats.pickSlotFirst
                  names == null -> "…"
                  names?.placementsKnown != true -> t.cheats.noPlacements
                  options.isEmpty() -> t.cheats.noIncoming
                  else -> t.cheats.location
                },
              )
            } else SearchableSelect(
              choices.orEmpty().map { Triple<String?, String, String>(it, it, "") }, value, { value = it },
              modifier, chip = false, minHeight = CONTROL_HEIGHT,
              placeholder = when {
                slot == null -> t.cheats.pickSlotFirst
                choices == null -> "…"
                choices.isEmpty() -> if (action in WITH_ITEM) t.cheats.noItems else t.cheats.noLocations
                action in WITH_ITEM -> t.cheats.item
                else -> t.cheats.location
              },
            )
          }
          val send: @Composable () -> Unit = {
            Button(if (busy) t.cheats.sending else if (support) t.cheats.send else t.cheats.cheat, enabled = ready, danger = !support) {
              busy = true
              outcome = null
              scope.launch {
                val world = from.takeIf { incoming }
                val body = ApiJson.encodeToString(CheatRequest.serializer(), CheatRequest(action, slot!!, value, world, if (playthrough) hintPick else HintPick.RANDOM))
                outcome = when (val answer = call("POST", path, body) { }) {
                  // A key's location as the log says it: "Location (in World)"
                  is Answer.Ok -> true to t.cheats.done(
                    action, world ?: slot.orEmpty(),
                    if (action in LOCK_CHEATS) lock?.keyLocation?.let { "$it (in ${lock.keyWorld})" } else value,
                  )
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
                if (action == CheatAction.LOCATION) direction(Modifier.fillMaxWidth())
                if (picksCopy) copy(Modifier.fillMaxWidth())
                forSlot(Modifier.fillMaxWidth())
                if (needsValue) which(Modifier.fillMaxWidth())
                send()
              }
            } else {
              // One line: what, for which slot, which item or location, and the button; all as tall as the button
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                what(Modifier.width(170.dp))
                if (action == CheatAction.LOCATION) direction(Modifier.width(140.dp))
                if (picksCopy) copy(Modifier)
                forSlot(Modifier.width(200.dp))
                if (needsValue) which(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                send()
              }
            }
          }
          // Where the picked locked slot's key lies, for checking or hinting that location
          if (action in LOCK_CHEATS && lock != null) {
            val location = lock.keyLocation
            val world = lock.keyWorld
            Txt(
              if (location != null && world != null) t.cheats.keyAt(location, world) else t.cheats.keyUnknown,
              size = 12.5.sp, color = if (location != null) p.muted else p.trap,
            )
          }
          outcome?.let { (ok, text) -> Txt(text, size = 12.5.sp, color = if (ok) p.ok else p.trap) }
          if (current.log.isNotEmpty()) {
            Divider()
            Txt(t.cheats.logTitle(support), size = 12.5.sp, weight = FontWeight.SemiBold)
            current.log.take(15).forEach { entry ->
              Txt(
                t.cheats.logLine(t.clock(entry.ts), entry.admin, entry.action, entry.slot, entry.value),
                size = 12.sp, color = p.muted, maxLines = 2,
              )
            }
          }
        }
      }
    }
  }
}
