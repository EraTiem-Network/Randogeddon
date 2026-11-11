package net.eratiem.randogeddon.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.eratiem.randogeddon.shared.CommandRequest
import java.security.MessageDigest

/**
 * Only for calls with `Authorization: Bearer <token>`; with no token configured, nothing gets through. The internal
 * API is on its own port inside the compose network as well, but this way no other container can steer the server.
 */
class InternalAuthConfig {
  var token: String = ""
}

val InternalAuth = createRouteScopedPlugin("InternalAuth", ::InternalAuthConfig) {
  val expected = pluginConfig.token.toByteArray()
  onCall { call ->
    val given = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.toByteArray()
    if (expected.isEmpty() || given == null || !MessageDigest.isEqual(expected, given)) {
      call.respond(HttpStatusCode.Unauthorized)
    }
  }
}

/** The tracker's control of the game server: state, start/stop/restart, log, console. */
fun Route.internalApi(token: String, server: GameServer) {
  route("/internal") {
    install(InternalAuth) { this.token = token }
    route("/server") {
      get { call.respond(server.state) }
      post("/start") {
        server.start()
        call.respond(server.state)
      }
      post("/stop") {
        server.stop()
        call.respond(server.state)
      }
      post("/restart") {
        server.restart()
        call.respond(server.state)
      }
      get("/log") {
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
        call.respond(server.log(since))
      }
      post("/command") {
        val line = call.receive<CommandRequest>().line.trim()
        when {
          line.isEmpty() -> call.respond(HttpStatusCode.BadRequest)
          server.command(line) -> call.respond(HttpStatusCode.NoContent)
          // No server running to take it
          else -> call.respond(HttpStatusCode.Conflict)
        }
      }
    }
  }
}
