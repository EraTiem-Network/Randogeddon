package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import net.eratiem.randogeddon.server.admin.Cheats
import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.server.admin.GameServerUnavailable
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.admin.SessionLifecycle
import net.eratiem.randogeddon.server.admin.SessionProblem
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.CheatRequest
import net.eratiem.randogeddon.shared.CommandRequest
import net.eratiem.randogeddon.shared.CreateSessionRequest
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.InspectRequest
import net.eratiem.randogeddon.shared.KeepRequest
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.SessionIds
import java.lang.invoke.MethodHandles
import kotlin.time.Duration.Companion.milliseconds

private val logger = logger(MethodHandles.lookup().lookupClass())
private val LOG_POLL = 750.milliseconds

/** The largest apworld upload, as base64 (the gateway takes 64 MB files). */
private const val MAX_APWORLD_BASE64 = 64 * 1024 * 1024 / 3 * 4 + 4

/**
 * Under the protected `/api` branch: the admin panel's control of every session and its game server, which the
 * gateway runs, and of the apworld library. Every action is logged with the admin's name.
 */
fun Route.adminRoutes(
  gameServer: GameServerClient,
  admin: SessionAdmin,
  lifecycle: SessionLifecycle,
  cheats: Cheats,
  sessions: LiveSessions,
) {
  route("/admin") {
    install(RequireAdmin)
    route("/sessions") {
      // Every session with its game server, and what a new one needs (releases, logins, a free ID)
      get { gatewayCall { call.respond(admin.overview()) } }
      post { gatewayCall { call.respond(admin.create(call.receive<CreateSessionRequest>(), call.loggedIn.user.name)) } }
      // The slots of the uploaded YAMLs, before the admin assigns them; games the release lacks are problems
      post("/inspect") { call.respond(admin.inspect(call.receive<InspectRequest>())) }
      route("/{id}") {
        get { gatewayCall { call.respond(admin.view(id())) } }
        delete {
          gatewayCall {
            admin.delete(id(), call.loggedIn.user.name)
            call.respond(HttpStatusCode.NoContent)
          }
        }
        // Finishing it now, or stopping its countdown; reopening it; keeping it for good (or not)
        post("/finish") {
          gatewayCall {
            lifecycle.finishNow(id(), call.loggedIn.user.name)
            call.respond(HttpStatusCode.NoContent)
          }
        }
        post("/finish/cancel") {
          gatewayCall {
            lifecycle.cancel(id(), call.loggedIn.user.name)
            call.respond(HttpStatusCode.NoContent)
          }
        }
        post("/reopen") {
          gatewayCall {
            lifecycle.reopen(id(), call.loggedIn.user.name)
            call.respond(HttpStatusCode.NoContent)
          }
        }
        post("/keep") {
          val keep = call.receive<KeepRequest>().keep
          gatewayCall {
            admin.setKeep(id(), keep, call.loggedIn.user.name)
            call.respond(HttpStatusCode.NoContent)
          }
        }
        // Cheats: logged with the admin's name, shown in the live feed
        route("/cheat") {
          get { gatewayCall { call.respond(cheats.view(id())) } }
          get("/names") {
            val slot = call.request.queryParameters["slot"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            gatewayCall { call.respond(cheats.names(id(), slot)) }
          }
          post {
            val request = call.receive<CheatRequest>()
            gatewayCall {
              cheats.run(id(), request, call.loggedIn.user.name)
              call.respond(HttpStatusCode.NoContent)
            }
          }
        }
        get("/server") { gatewayCall { call.respond(gameServer.state(id())) } }
        post("/server/{action}") {
          val action = call.parameters["action"]
          if (action !in setOf("start", "stop", "restart")) return@post call.respond(HttpStatusCode.NotFound)
          gatewayCall {
            val id = id()
            if (action != "stop" && sessions.get(id)?.finished == true) {
              throw SessionProblem(HttpStatusCode.Conflict, "The session is finished; reopen it to start the game server")
            }
            logger.info("{} asked to {} the game server of {}", call.loggedIn.user.name, action, id)
            val state = gameServer.control(id, action!!)
            sessions.refresh()
            call.respond(state)
          }
        }
        post("/command") {
          val line = call.receive<CommandRequest>().line.trim()
          if (line.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Empty command"))
          gatewayCall {
            val id = id()
            logger.info("{} ran on the game server of {}: {}", call.loggedIn.user.name, id, line)
            if (gameServer.command(id, line)) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.Conflict, ApiError("The game server isn't running"))
          }
        }
        // The game server's state ("state", whenever it changes) and its new log lines ("log"), polled from the gateway
        sse("/stream") {
          val id = call.parameters["id"].orEmpty()
          var since = 0L
          var lastState: GameServerState? = null
          var reachable = true
          while (true) {
            try {
              val state = gameServer.state(id)
              if (state != lastState || !reachable) {
                send(ServerSentEvent(ApiJson.encodeToString(GameServerState.serializer(), state), event = "state"))
                lastState = state
                reachable = true
              }
              val lines = gameServer.log(id, since)
              // The gateway restarted and counts from 1 again
              if (lines.last < since) since = 0
              if (lines.lines.isNotEmpty()) {
                send(ServerSentEvent(ApiJson.encodeToString(LogLines.serializer(), lines), event = "log"))
              }
              since = lines.last
            } catch (e: GameServerUnavailable) {
              if (reachable) send(ServerSentEvent(e.message ?: "unreachable", event = "unreachable"))
              reachable = false
            }
            delay(LOG_POLL)
          }
        }
      }
    }
    // The apworld library of each Archipelago release
    route("/apworlds") {
      get { gatewayCall { call.respond(gameServer.archipelago()) } }
      post {
        val upload = call.receive<ApworldUpload>()
        if (upload.base64.length > MAX_APWORLD_BASE64) return@post call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Larger than 64 MB"))
        logger.info("{} uploads {} for Archipelago {}", call.loggedIn.user.name, upload.fileName, upload.release)
        gatewayCall { call.respond(gameServer.uploadApworld(upload)) }
      }
      post("/try") {
        val request = call.receive<ApworldTry>()
        logger.info("{} tries {} of Archipelago {} with {}", call.loggedIn.user.name, request.file, request.from, request.release)
        gatewayCall { call.respond(gameServer.tryApworld(request)) }
      }
      delete("/{release}/{file}") {
        val release = call.parameters["release"]!!
        val file = call.parameters["file"]!!
        logger.info("{} removes {} from Archipelago {}", call.loggedIn.user.name, file, release)
        gatewayCall {
          gameServer.removeApworld(release, file)
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
  }
}

/** The session ID of the path; a [SessionProblem] (404) if it isn't one. */
private fun RoutingContext.id(): String =
  call.parameters["id"]?.takeIf(SessionIds::valid) ?: throw SessionProblem(HttpStatusCode.NotFound, "No session ${call.parameters["id"]}")

/**
 * Answers 502 with the reason when the gateway doesn't answer, and what's wrong with a session request (409, 400,
 * 422), instead of failing the request.
 */
private suspend fun RoutingContext.gatewayCall(block: suspend () -> Unit) {
  try {
    block()
  } catch (e: SessionProblem) {
    call.respond(e.status, ApiError(e.message ?: "Not possible"))
  } catch (e: GameServerUnavailable) {
    logger.warn("Game server control failed: {}", e.message)
    call.respond(e.status, ApiError(e.message ?: "The gateway can't be reached"))
  }
}
