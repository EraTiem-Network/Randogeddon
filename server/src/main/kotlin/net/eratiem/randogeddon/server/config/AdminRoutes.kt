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
import net.eratiem.randogeddon.server.session.CurrentSession
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
import java.lang.invoke.MethodHandles
import kotlin.time.Duration.Companion.milliseconds

private val logger = logger(MethodHandles.lookup().lookupClass())
private val LOG_POLL = 750.milliseconds

/** The largest apworld upload, as base64 (the gateway takes 64 MB files). */
private const val MAX_APWORLD_BASE64 = 64 * 1024 * 1024 / 3 * 4 + 4

/**
 * Under the protected `/api` branch: the admin panel's control of the game server, which the gateway runs. Every
 * action is logged with the admin's name.
 */
fun Route.adminRoutes(
  gameServer: GameServerClient,
  sessions: SessionAdmin,
  lifecycle: SessionLifecycle,
  cheats: Cheats,
  session: CurrentSession,
) {
  route("/admin") {
    install(RequireAdmin)
    route("/session") {
      get { gatewayCall { call.respond(sessions.view()) } }
      // The slots of the uploaded YAMLs, before the admin assigns them; games the release lacks are problems
      post("/inspect") { call.respond(sessions.inspect(call.receive<InspectRequest>())) }
      post { gatewayCall { call.respond(sessions.create(call.receive<CreateSessionRequest>(), call.loggedIn.user.name)) } }
      delete {
        gatewayCall {
          sessions.delete(call.loggedIn.user.name)
          call.respond(HttpStatusCode.NoContent)
        }
      }
      // Finishing the current session now, or stopping its countdown
      post("/finish") {
        gatewayCall {
          lifecycle.finishNow(call.loggedIn.user.name)
          call.respond(HttpStatusCode.NoContent)
        }
      }
      post("/finish/cancel") {
        gatewayCall {
          lifecycle.cancel(call.loggedIn.user.name)
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
    // Finished sessions (the current one, or past ones): reopen, keep for good (or not), delete for good
    route("/sessions/{seed}") {
      post("/reopen") {
        gatewayCall {
          lifecycle.reopen(call.parameters["seed"]!!, call.loggedIn.user.name)
          call.respond(HttpStatusCode.NoContent)
        }
      }
      post("/keep") {
        val keep = call.receive<KeepRequest>().keep
        gatewayCall {
          sessions.setKeep(call.parameters["seed"]!!, keep, call.loggedIn.user.name)
          call.respond(HttpStatusCode.NoContent)
        }
      }
      delete {
        gatewayCall {
          sessions.deletePast(call.parameters["seed"]!!, call.loggedIn.user.name)
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
    // Cheats: logged with the admin's name, shown in the live feed
    route("/cheat") {
      get { gatewayCall { call.respond(cheats.view()) } }
      get("/names") {
        val slot = call.request.queryParameters["slot"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        gatewayCall { call.respond(cheats.names(slot)) }
      }
      post {
        val request = call.receive<CheatRequest>()
        gatewayCall {
          cheats.run(request, call.loggedIn.user.name)
          call.respond(HttpStatusCode.NoContent)
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
    get("/server") { gatewayCall { call.respond(gameServer.state()) } }
    post("/server/{action}") {
      val action = call.parameters["action"]
      if (action !in setOf("start", "stop", "restart")) return@post call.respond(HttpStatusCode.NotFound)
      if (action != "stop" && session.finish?.finishedAt != null) {
        return@post call.respond(HttpStatusCode.Conflict, ApiError("The session is finished; reopen it to start the game server"))
      }
      logger.info("{} asked to {} the game server", call.loggedIn.user.name, action)
      gatewayCall { call.respond(gameServer.control(action!!)) }
    }
    post("/command") {
      val line = call.receive<CommandRequest>().line.trim()
      if (line.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ApiError("Empty command"))
      logger.info("{} ran on the game server: {}", call.loggedIn.user.name, line)
      gatewayCall {
        if (gameServer.command(line)) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.Conflict, ApiError("The game server isn't running"))
      }
    }
    // The game server's state ("state", whenever it changes) and its new log lines ("log"), polled from the gateway
    sse("/stream") {
      var since = 0L
      var lastState: GameServerState? = null
      var reachable = true
      while (true) {
        try {
          val state = gameServer.state()
          if (state != lastState || !reachable) {
            send(ServerSentEvent(ApiJson.encodeToString(GameServerState.serializer(), state), event = "state"))
            lastState = state
            reachable = true
          }
          val lines = gameServer.log(since)
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
