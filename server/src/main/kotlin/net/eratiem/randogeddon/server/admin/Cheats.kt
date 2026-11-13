package net.eratiem.randogeddon.server.admin

import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.database.CheatEntry
import net.eratiem.randogeddon.database.CheatLogStore
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.CheatAction
import net.eratiem.randogeddon.shared.CheatLogEntry
import net.eratiem.randogeddon.shared.CheatNames
import net.eratiem.randogeddon.shared.CheatRequest
import net.eratiem.randogeddon.shared.CheatView
import net.eratiem.randogeddon.shared.GameServerStatus
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.time.Instant

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

  suspend fun names(id: String, slot: String): CheatNames =
    session(id).tracker.read { cheatNames(slot) } ?: throw SessionProblem(HttpStatusCode.NotFound, "No slot $slot")

  /** [byRole]: `administrator`, `manager` or `support`; the log and the feed say so. */
  suspend fun run(id: String, request: CheatRequest, admin: String, byRole: String = "administrator") {
    val session = session(id)
    val tracker = session.tracker
    val seed = session.seedName
    if (session.finished) throw SessionProblem(HttpStatusCode.Conflict, "The session is finished")
    if (request.action in HINTS && session.options?.cheatHints != true) {
      throw SessionProblem(HttpStatusCode.Forbidden, "This session doesn't allow cheat hints")
    }
    val names = tracker.read { cheatNames(request.slot) } ?: throw SessionProblem(HttpStatusCode.BadRequest, "No slot ${request.slot}")
    if (request.slot !in tracker.read { cheatSlots() }) {
      throw SessionProblem(HttpStatusCode.Conflict, "${request.slot} has checked every location already")
    }
    val value = request.value?.trim()?.takeIf { it.isNotEmpty() }
    if (request.action in NEEDS_VALUE) {
      val known = if (request.action == CheatAction.ITEM || request.action == CheatAction.HINT) names.items else names.locations
      if (value == null || value !in known) {
        throw SessionProblem(HttpStatusCode.BadRequest, "${value ?: "Nothing"} isn't one of ${request.slot}'s ${
          if (known === names.items) "items" else "open locations"}")
      }
    }
    val command = cheatCommand(request.action, request.slot, value)
    if (!gameServer.command(id, command)) throw SessionProblem(HttpStatusCode.Conflict, "The game server isn't running")
    logger.info("{} cheated in {}: {}", admin, id, command)
    val shown = value.takeIf { request.action in NEEDS_VALUE }
    log.add(seed, CheatEntry(Instant.now(), admin, request.action.name, request.slot, shown, byRole))
    tracker.cheat(cheatText(admin, request.action, request.slot, shown, byRole))
  }

  private fun session(id: String): LiveSession =
    sessions.get(id) ?: throw SessionProblem(HttpStatusCode.NotFound, "No session $id")
}

/** Who ran a cheat as the log and the feed show it: an Administrator by name, the others with their role. */
internal fun who(name: String, byRole: String): String = when (byRole) {
  "manager" -> "$name (Session-Manager)"
  "support" -> "$name (support)"
  else -> name
}
