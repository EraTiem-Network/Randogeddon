package net.eratiem.randogeddon.server.admin

import io.ktor.http.HttpStatusCode
import java.lang.invoke.MethodHandles
import java.time.Instant
import net.eratiem.randogeddon.database.CheatEntry
import net.eratiem.randogeddon.database.CheatLogStore
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.server.session.placements
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.CheatAction
import net.eratiem.randogeddon.shared.CheatLogEntry
import net.eratiem.randogeddon.shared.CheatNames
import net.eratiem.randogeddon.shared.CheatRequest
import net.eratiem.randogeddon.shared.CheatView
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.IncomingCheck
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import org.koin.core.annotation.Single

private val logger = logger(MethodHandles.lookup().lookupClass())

private val HINTS = setOf(CheatAction.HINT, CheatAction.HINT_LOCATION)
private val NEEDS_VALUE = setOf(CheatAction.ITEM, CheatAction.LOCATION, CheatAction.HINT, CheatAction.HINT_LOCATION)

/**
 * A word for MultiServer's console, which splits most commands like a shell (shlex): in double quotes, `\` and `"`
 * escaped. `/release` and `/collect` take the rest of the line as it is instead, so the name goes there unquoted.
 */
internal fun consoleWord(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** The server console command of a cheat. */
internal fun cheatCommand(action: CheatAction, slot: String, value: String?): String {
  val target = consoleWord(slot)
  val what = value?.let(::consoleWord).orEmpty()
  return when (action) {
    CheatAction.ITEM -> "/send $target $what"
    CheatAction.LOCATION -> "/send_location $target $what"
    CheatAction.RELEASE -> "/release $slot"
    CheatAction.COLLECT -> "/collect $slot"
    CheatAction.HINT -> "/hint $target $what"
    CheatAction.HINT_LOCATION -> "/hint_location $target $what"
  }
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
}

/**
 * Cheats from the admin panel: items, checks, release and collect, and free hints if the session allows them. They
 * go to the session's server console; every one is logged with the admin's name and shown in its live feed.
 */
@Single
class Cheats(
  private val gameServer: GameServerClient,
  private val sessions: LiveSessions,
  private val log: CheatLogStore,
  private val spoilers: Spoilers,
) {
  suspend fun view(id: String): CheatView {
    val session = session(id)
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
    )
  }

  /**
   * The slot's items and open locations, and the open locations of other worlds holding one of its items (from the
   * spoiler log's placements, if it has them; checking one of those sends the item to the slot).
   */
  suspend fun names(id: String, slot: String): CheatNames {
    val session = session(id)
    val names = session.tracker.read { cheatNames(slot) } ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SLOT, "slot" to slot)
    val text = runCatching { spoilers.of(session.id, session.seedName)?.text }.getOrNull() ?: return names
    val open = session.tracker.read { openLocations() }
    val placed = placements(text, open.keys)
    if (placed.isEmpty()) return names
    val incoming = placed.filter { it.receiver == slot && it.finder != slot && it.location in open[it.finder].orEmpty() }
      .map { IncomingCheck(it.location, it.finder, it.item) }
      .sortedWith(compareBy({ it.item.lowercase() }, { it.world.lowercase() }, { it.location.lowercase() }))
    return names.copy(incoming = incoming, placementsKnown = true)
  }

  /**
   * [byRole]: `administrator`, `manager` or `support`; the log and the feed say so. [freeHints]: hints without the
   * session's cheat hints (support gives them).
   */
  suspend fun run(id: String, request: CheatRequest, admin: String, byRole: String = "administrator", freeHints: Boolean = false) {
    val session = session(id)
    val tracker = session.tracker
    val seed = session.seedName
    if (session.finished) throw SessionProblem(HttpStatusCode.Conflict, Problem.SESSION_FINISHED)
    if (request.action in HINTS && !freeHints && session.options?.cheatHints != true) {
      throw SessionProblem(HttpStatusCode.Forbidden, Problem.NO_CHEAT_HINTS)
    }
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
      if (!gameServer.command(id, command)) throw SessionProblem(HttpStatusCode.Conflict, Problem.SERVER_NOT_RUNNING)
      logger.info("{} cheated in {} for {}: {}", admin, id, request.slot, command)
      log.add(seed, CheatEntry(Instant.now(), admin, request.action.name, from, value, byRole))
      tracker.cheat(cheatText(admin, request.action, from, value, byRole), request.action, admin, byRole, from, value)
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
    val command = cheatCommand(request.action, request.slot, value)
    if (!gameServer.command(id, command)) throw SessionProblem(HttpStatusCode.Conflict, Problem.SERVER_NOT_RUNNING)
    logger.info("{} cheated in {}: {}", admin, id, command)
    val shown = value.takeIf { request.action in NEEDS_VALUE }
    log.add(seed, CheatEntry(Instant.now(), admin, request.action.name, request.slot, shown, byRole))
    tracker.cheat(cheatText(admin, request.action, request.slot, shown, byRole), request.action, admin, byRole, request.slot, shown)
  }

  private fun session(id: String): LiveSession =
    sessions.get(id) ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
}

/** Who ran a cheat as the log and the feed show it: an Administrator by name, the others with their role. */
internal fun who(name: String, byRole: String): String = when (byRole) {
  "manager" -> "$name (Session-Manager)"
  "support" -> "$name (support)"
  else -> name
}
