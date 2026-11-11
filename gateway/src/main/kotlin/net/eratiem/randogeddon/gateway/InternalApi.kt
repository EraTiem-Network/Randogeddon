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
import net.eratiem.randogeddon.shared.GenerateRequest
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

/**
 * The tracker's control of the game server: state, start/stop/restart, log, console; and of the sessions: the
 * Archipelago releases, generating a seed, deleting the current one.
 */
fun Route.internalApi(token: String, server: GameServer, archipelago: Archipelago, generator: Generator) {
  route("/internal") {
    install(InternalAuth) { this.token = token }
    get("/versions") { call.respond(archipelago.versions()) }
    // Takes as long as Generate.py does (up to minutes); the new seed is served after a restart
    post("/generate") { call.respond(generator.generate(call.receive<GenerateRequest>())) }
    // Stops the game server and moves the seed, its save and room.json to data/old; then waits for the next seed
    post("/session/delete") {
      server.stop()
      val moved = generator.retire()
      server.start()
      call.respond(moved)
    }
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
