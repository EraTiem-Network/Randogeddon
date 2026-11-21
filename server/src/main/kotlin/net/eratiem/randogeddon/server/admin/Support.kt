package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.shared.CheatAction
import net.eratiem.randogeddon.shared.CheatNames
import net.eratiem.randogeddon.shared.CheatRequest
import net.eratiem.randogeddon.shared.CheatView
import org.koin.core.annotation.Single

/** What support may do of the cheats: items, checks and hints; no release or collect. */
private val SUPPORT_ACTIONS =
  setOf(CheatAction.ITEM, CheatAction.LOCATION, CheatAction.HINT, CheatAction.HINT_LOCATION, CheatAction.KEY_LOCATION, CheatAction.UNLOCK)

/**
 * Support's help in a session: giving items, checking locations and free hints, even if the session has no cheat
 * hints. Not release or collect, the game server or its console. Every action is logged with their name and shown in
 * the live feed ("Bob (support) gave …").
 */
@Single
class Support(private val cheats: Cheats) {
  /** The slots to help, as for the cheats; hints are always there. */
  suspend fun view(id: String): CheatView = cheats.view(id).copy(cheatHints = true)

  suspend fun names(id: String, slot: String): CheatNames = cheats.names(id, slot)

  suspend fun run(id: String, request: CheatRequest, name: String) {
    if (request.action !in SUPPORT_ACTIONS) {
      throw SessionProblem(HttpStatusCode.Forbidden, Problem.SUPPORT_CANT, "action" to request.action.name)
    }
    cheats.run(id, request, name, byRole = "support", freeHints = true)
  }
}
