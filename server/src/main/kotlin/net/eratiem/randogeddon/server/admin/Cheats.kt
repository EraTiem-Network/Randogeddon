package net.eratiem.randogeddon.server.admin

import io.ktor.http.HttpStatusCode
import java.lang.invoke.MethodHandles
import java.time.Instant
import kotlin.random.asKotlinRandom
import kotlin.random.Random
import net.eratiem.randogeddon.server.session.Placement
import net.eratiem.randogeddon.database.CheatEntry
import net.eratiem.randogeddon.database.CheatLogStore
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.require
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.server.util.secureRandom
import net.eratiem.randogeddon.shared.CheatAction
import net.eratiem.randogeddon.server.session.stepLocation
import net.eratiem.randogeddon.shared.HintPick
import net.eratiem.randogeddon.shared.CheatLogEntry
import net.eratiem.randogeddon.shared.CheatNames
import net.eratiem.randogeddon.shared.CheatRequest
import net.eratiem.randogeddon.shared.CheatView
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.IncomingCheck
import net.eratiem.randogeddon.shared.LOCK_CHEATS
import net.eratiem.randogeddon.shared.LockedSlot
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import org.koin.core.annotation.Single

private val logger = logger(MethodHandles.lookup().lookupClass())

private val HINTS = setOf(CheatAction.HINT, CheatAction.HINT_LOCATION)
private val NEEDS_VALUE = setOf(CheatAction.ITEM, CheatAction.LOCATION, CheatAction.HINT, CheatAction.HINT_LOCATION)

/**
 * A word for MultiServer's console, which splits most commands like a shell (shlex): in double quotes, `\` and `"`
 * escaped, without control characters (a line break would start another command). `/release` and `/collect` take
 * the rest of the line as it is instead, so the name goes there unquoted ([consoleText]).
 */
internal fun consoleWord(text: String): String = "\"" + consoleText(text).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** [text] for the console as it is, without control characters. */
internal fun consoleText(text: String): String = text.filterNot { it.isISOControl() }

/** The server console command of a cheat. */
internal fun cheatCommand(action: CheatAction, slot: String, value: String?): String {
  val target = consoleWord(slot)
  val what = value?.let(::consoleWord).orEmpty()
  return when (action) {
    CheatAction.ITEM -> "/send $target $what"
    CheatAction.LOCATION -> "/send_location $target $what"
    CheatAction.RELEASE -> "/release ${consoleText(slot)}"
    CheatAction.COLLECT -> "/collect ${consoleText(slot)}"
    CheatAction.HINT -> "/hint $target $what"
    CheatAction.HINT_LOCATION -> "/hint_location $target $what"
    // [slot]: the world the key's location is in (Unlock without the placements is a /send, see runLock)
    CheatAction.KEY_LOCATION -> "/send_location $target $what"
    CheatAction.KEY_HINT -> "/hint_location $target $what"
    CheatAction.UNLOCK -> "/send_location $target $what"
  }
}

/**
 * One copy of [item] for the slot [slot] to hint: a placement of it whose location is neither checked ([checked]) nor
 * hinted ([hinted]) in its world; `null` when none is left. Archipelago's `/hint` would name every copy. [pick]: by
 * chance, or the first in [playthrough] (its steps' locations in order, as world to location); a copy the
 * playthrough doesn't have comes after those, in the spoiler log's order.
 */
internal fun pickHint(
  placed: List<Placement>,
  slot: String,
  item: String,
  checked: Map<String, Set<String>>,
  hinted: Map<String, Set<String>>,
  random: Random,
  pick: HintPick = HintPick.RANDOM,
  playthrough: List<Pair<String, String>> = emptyList(),
): Placement? {
  val copies = unhinted(placed, slot, checked, hinted).filter { it.item == item }
  if (pick == HintPick.RANDOM) return copies.randomOrNull(random)
  val order = playthrough.withIndex().associate { (n, step) -> step to n }
  return copies.minByOrNull { order[it.finder to it.location] ?: Int.MAX_VALUE }
}

/** The placements of the slot's items that are neither checked nor hinted yet. */
internal fun unhinted(placed: List<Placement>, slot: String, checked: Map<String, Set<String>>, hinted: Map<String, Set<String>>) =
  placed.filter {
    it.receiver == slot && it.location !in checked[it.finder].orEmpty() && it.location !in hinted[it.finder].orEmpty()
  }

/** For the live feed: "Alice gave Mantis Claw to Alice-HK", "Bob (support) …" for anyone but an Administrator. */
internal fun cheatText(admin: String, action: CheatAction, slot: String, value: String?, byRole: String = "administrator"): String =
  who(admin, byRole) + " " + when (action) {
  CheatAction.ITEM -> "gave $value to $slot"
  CheatAction.LOCATION -> "checked $value in $slot"
  CheatAction.RELEASE -> "released $slot"
  CheatAction.COLLECT -> "collected for $slot"
  CheatAction.HINT -> "hinted $value for $slot"
  CheatAction.HINT_LOCATION -> "hinted what is at $value in $slot"
  CheatAction.KEY_LOCATION -> "checked the key to $slot at $value"
  CheatAction.KEY_HINT -> "hinted where the key to $slot is: $value"
  CheatAction.UNLOCK -> if (value != null) "unlocked $slot (its key at $value)" else "unlocked $slot"
}

/**
 * Cheats from the admin panel: items, checks, release and collect, and free hints if the session allows them. They
 * go to the session's server console; every one is logged with the admin's name and shown in its live feed. A hint
 * names one place: a location not hinted yet, or one copy of an item that is neither checked nor hinted.
 */
@Single
class Cheats(
  private val gameServer: GameServerClient,
  private val sessions: LiveSessions,
  private val log: CheatLogStore,
  private val spoilers: Spoilers,
) {
  suspend fun view(id: String): CheatView {
    val session = sessions.require(id)
    val tracker = session.tracker
    val running = runCatching { gameServer.state(id).status == GameServerStatus.RUNNING }.getOrDefault(false)
    return CheatView(
      slots = tracker.read { cheatSlots() },
      done = tracker.read { everyCheckDone() },
      cheatHints = session.options?.cheatHints == true,
      available = running && !session.finished,
      log = log.of(session.seedName).map {
        CheatLogEntry(it.at.toEpochMilli(), who(it.admin, it.byRole), CheatAction.valueOf(it.action), it.slotName, it.value)
      },
      locked = locked(session),
    )
  }

  /** The slots still locked, each with where its key lies (not checked yet), if the spoiler log says. */
  private suspend fun locked(session: LiveSession): List<LockedSlot> {
    val locked = session.tracker.read { lockedSlots() }
    if (locked.isEmpty()) return emptyList()
    val keys = keyPlacements(session, locked)
    return locked.map { (slot, key, _) ->
      val at = keys[slot]
      LockedSlot(slot, key, at?.location, at?.finder)
    }
  }

  /**
   * Where each of [locked]'s keys lies, by the locked slot: the placement of its key for the World Locks slot, at a
   * location not checked yet (none without the spoiler log's placements).
   */
  private suspend fun keyPlacements(session: LiveSession, locked: List<Triple<String, String, String>>): Map<String, Placement> {
    val log = runCatching { spoilers.of(session.id, session.seedName) }.getOrNull() ?: return emptyMap()
    val open = session.tracker.read { openLocations() }
    val placed = log.placements(open.keys + locked.map { it.third }.toSet())
    return locked.mapNotNull { (slot, key, lockSlot) ->
      placed.firstOrNull { it.item == key && it.receiver == lockSlot && it.location in open[it.finder].orEmpty() }?.let { slot to it }
    }.toMap()
  }

  /**
   * A lock cheat for the locked slot [slot]: its key's location checked (*Unlock*: the key comes from that world, the
   * lock says the server did it; without the placements the key given to the World Locks slot), or hinted. Logged and
   * shown under the locked slot, with where the key was.
   */
  private suspend fun runLock(session: LiveSession, action: CheatAction, slot: String, admin: String, byRole: String) {
    val tracker = session.tracker
    val lock = tracker.read { lockedSlots() }.firstOrNull { it.first == slot }
      ?: throw SessionProblem(HttpStatusCode.Conflict, Problem.NO_SLOT, "slot" to slot)
    val (_, key, lockSlot) = lock
    val at = keyPlacements(session, listOf(lock))[slot]
    val (command, shown) = when {
      at != null -> cheatCommand(action, at.finder, at.location) to "${at.location} (in ${at.finder})"
      // Unlock without the placements: the key straight to the World Locks slot
      action == CheatAction.UNLOCK -> "/send ${consoleWord(lockSlot)} ${consoleWord(key)}" to null
      else -> throw SessionProblem(HttpStatusCode.Conflict, Problem.NOT_A_LOCATION, "value" to key, "slot" to slot)
    }
    carryOut(session, command, action, slot, shown, admin, byRole, about = slot)
  }

  /**
   * Runs [command] on [session]'s console and records it as [action] on [slot] with [shown] by [admin] ([byRole]): in
   * the log (for the slot [about], if it is said), its cheat log and its live feed. Refused when no server runs.
   */
  private suspend fun carryOut(
    session: LiveSession,
    command: String,
    action: CheatAction,
    slot: String,
    shown: String?,
    admin: String,
    byRole: String,
    about: String? = null,
  ) {
    if (!gameServer.command(session.id, command)) throw SessionProblem(HttpStatusCode.Conflict, Problem.SERVER_NOT_RUNNING)
    logger.info("{} cheated in {}{}: {}", admin, session.id, about?.let { " for $it" }.orEmpty(), command)
    log.add(session.seedName, CheatEntry(Instant.now(), admin, action.name, slot, shown, byRole))
    session.tracker.cheat(cheatText(admin, action, slot, shown, byRole), action, admin, byRole, slot, shown)
  }

  /**
   * The slot's items and open locations, and the open locations of other worlds holding one of its items (from the
   * spoiler log's placements, if it has them; checking one of those sends the item to the slot).
   */
  suspend fun names(id: String, slot: String): CheatNames {
    val session = sessions.require(id)
    val found = session.tracker.read { cheatNames(slot) } ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SLOT, "slot" to slot)
    val hinted = session.tracker.read { hintedLocations() }
    // A hinted location isn't hinted again
    val names = found.copy(hintLocations = found.locations.filter { it !in hinted[slot].orEmpty() }, hintItems = found.items)
    val placed = placed(session)
    if (placed.isEmpty()) return names
    val open = session.tracker.read { openLocations() }
    val checked = session.tracker.read { checkedLocations() }
    val incoming = placed.filter { it.receiver == slot && it.finder != slot && it.location in open[it.finder].orEmpty() }
      .map { IncomingCheck(it.location, it.finder, it.item) }
      .sortedWith(compareBy({ it.item.lowercase() }, { it.world.lowercase() }, { it.location.lowercase() }))
    // An item is hinted while a copy of it is neither checked nor hinted
    val hintItems = unhinted(placed, slot, checked, hinted).map { it.item }.distinct().sortedBy { it.lowercase() }
    return names.copy(incoming = incoming, placementsKnown = true, hintItems = hintItems)
  }

  /** The seed's placements from its spoiler log; empty without them (or without a log). */
  private suspend fun placed(session: LiveSession): List<Placement> {
    val log = runCatching { spoilers.of(session.id, session.seedName) }.getOrNull() ?: return emptyList()
    return log.placements(session.tracker.read { openLocations() }.keys)
  }

  /**
   * [byRole]: `administrator`, `manager` or `support`; the log and the feed say so. [freeHints]: hints without the
   * session's cheat hints (support gives them).
   */
  suspend fun run(id: String, request: CheatRequest, admin: String, byRole: String = "administrator", freeHints: Boolean = false) {
    val session = sessions.require(id)
    val tracker = session.tracker
    if (session.finished) throw SessionProblem(HttpStatusCode.Conflict, Problem.SESSION_FINISHED)
    if (request.action in HINTS && !freeHints && session.options?.cheatHints != true) {
      throw SessionProblem(HttpStatusCode.Forbidden, Problem.NO_CHEAT_HINTS)
    }
    if (request.action in LOCK_CHEATS) return runLock(session, request.action, request.slot, admin, byRole)
    val names = tracker.read { cheatNames(request.slot) } ?: throw SessionProblem(HttpStatusCode.BadRequest, Problem.NO_SLOT, "slot" to request.slot)
    if (request.slot !in tracker.read { cheatSlots() }) {
      throw SessionProblem(HttpStatusCode.Conflict, Problem.SLOT_ALL_CHECKED, "slot" to request.slot)
    }
    val value = request.value?.trim()?.takeIf { it.isNotEmpty() }
    // A location checked to the slot: one of another world's that holds an item for it; logged as that world's check
    val from = request.from?.takeIf { request.action == CheatAction.LOCATION && it != request.slot }
    if (from != null) {
      val incoming = names(id, request.slot).incoming
      if (value == null || incoming.none { it.world == from && it.location == value }) {
        throw SessionProblem(HttpStatusCode.BadRequest, Problem.NOT_A_LOCATION, "value" to (value ?: "–"), "slot" to from)
      }
      val command = cheatCommand(CheatAction.LOCATION, from, value)
      carryOut(session, command, request.action, from, value, admin, byRole, about = request.slot)
      return
    }
    if (request.action in NEEDS_VALUE) {
      val known = if (request.action == CheatAction.ITEM || request.action == CheatAction.HINT) names.items else names.locations
      if (value == null || value !in known) {
        throw SessionProblem(
          HttpStatusCode.BadRequest, if (known === names.items) Problem.NOT_AN_ITEM else Problem.NOT_A_LOCATION,
          "value" to (value ?: "–"), "slot" to request.slot,
        )
      }
    }
    if (request.action == CheatAction.HINT_LOCATION && value in tracker.read { hintedLocations() }[request.slot].orEmpty()) {
      throw SessionProblem(HttpStatusCode.Conflict, Problem.ALREADY_HINTED, "value" to value!!, "slot" to request.slot)
    }
    val command = if (request.action == CheatAction.HINT) itemHint(session, request.slot, value!!, request.pick) else cheatCommand(request.action, request.slot, value)
    carryOut(session, command, request.action, request.slot, value.takeIf { request.action in NEEDS_VALUE }, admin, byRole)
  }

  /**
   * The command of a hint for [item] of [slot]: one copy of it ([pick]), neither checked nor hinted, by its location
   * (`/hint_location` in the world that holds it); Archipelago's own `/hint`, which names every copy, only without
   * the spoiler log's placements.
   */
  private suspend fun itemHint(session: LiveSession, slot: String, item: String, pick: HintPick): String {
    val placed = placed(session)
    if (placed.isEmpty()) return cheatCommand(CheatAction.HINT, slot, item)
    // The next copy is the playthrough's: not before the spoiler log has it (the page doesn't offer it until then)
    if (pick == HintPick.NEXT && !session.playthroughReady) throw SessionProblem(HttpStatusCode.Conflict, Problem.PLAYTHROUGH_NOT_YET)
    val checked = session.tracker.read { checkedLocations() }
    val hinted = session.tracker.read { hintedLocations() }
    // The playthrough's steps in order, sphere by sphere (world to location)
    val playthrough = if (pick != HintPick.NEXT) emptyList() else runCatching {
      spoilers.of(session.id, session.seedName)?.spoiler?.spheres.orEmpty()
        .flatMap { sphere -> sphere.entries.mapNotNull { stepLocation(it)?.let { (location, world) -> world to location } } }
    }.getOrDefault(emptyList())
    val copy = pickHint(placed, slot, item, checked, hinted, random, pick, playthrough)
      ?: throw SessionProblem(HttpStatusCode.Conflict, Problem.NO_UNHINTED_COPY, "value" to item, "slot" to slot)
    return cheatCommand(CheatAction.HINT_LOCATION, copy.finder, copy.location)
  }

  /** Which copy a hint names. */
  private val random: Random = secureRandom.asKotlinRandom()
}

/** Who ran a cheat as the log and the feed show it: an Administrator by name, the others with their role. */
internal fun who(name: String, byRole: String): String = when (byRole) {
  "manager" -> "$name (Session-Manager)"
  "support" -> "$name (support)"
  else -> name
}
