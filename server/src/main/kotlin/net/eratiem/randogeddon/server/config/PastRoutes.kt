package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.eratiem.randogeddon.server.admin.GameServerUnavailable
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.PastSessions
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.shared.ApiError

/**
 * Under the protected `/api` branch: the finished sessions a user may see (their own, all for admins), each as it was
 * when it finished, and the spoiler logs: of finished sessions for their players, of any session for admins.
 */
fun Route.pastRoutes(past: PastSessions, archives: Archives, spoilers: Spoilers) {
  get("/sessions") { call.respond(past.visibleTo(call.loggedIn.user)) }
  get("/sessions/{seed}") {
    val seed = call.parameters["seed"]!!
    val archived = archives.read(seed)?.takeIf { past.canSee(call.loggedIn.user, seed) }
      ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("No past session $seed"))
    call.respond(archived)
  }
  // A full spoiler: before a session is finished only admins see it
  get("/spoiler/{seed}") {
    val seed = call.parameters["seed"]!!
    if (!past.canSee(call.loggedIn.user, seed)) {
      return@get call.respond(HttpStatusCode.Forbidden, ApiError("The spoiler log is shown once the session is finished"))
    }
    try {
      val spoiler = spoilers.of(seed) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("No spoiler log for $seed"))
      call.respond(spoiler)
    } catch (e: GameServerUnavailable) {
      call.respond(e.status, ApiError(e.message ?: "The gateway can't be reached"))
    }
  }
}
