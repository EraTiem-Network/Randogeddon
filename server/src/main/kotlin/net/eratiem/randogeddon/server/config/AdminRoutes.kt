package net.eratiem.randogeddon.server.config

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.server.config.refuse
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.AppSettings
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.database.AppSettingsStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
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
import net.eratiem.randogeddon.shared.GameServerState
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
 * Under the protected `/api` branch, for Administrators: every session with its game server, the apworld library of
 * each release, and what applies to everyone (the settings). Every action is logged with their name.
 */
fun Route.adminRoutes(
  gameServer: GameServerClient,
  admin: SessionAdmin,
  settings: AppSettingsStore,
  logins: LoginCheck,
) {
  route("/admin") {
    install(RequireAdministrator) { this.logins = logins }
    // Every session with its game server, and what a new one needs (releases, logins, a free ID)
    get("/sessions") { gatewayCall { call.respond(admin.overview(call.loggedIn.user)) } }
    route("/settings") {
      get { call.respond(AppSettings(sessionsPerUser(settings))) }
      put {
        val request = call.receive<AppSettings>()
        if (request.sessionsPerUser !in 0..MAX_SESSIONS_PER_USER) {
          return@put call.refuse(HttpStatusCode.BadRequest, Refusal.of(Problem.SESSIONS_PER_USER, "max" to MAX_SESSIONS_PER_USER))
        }
        settings.set(SESSIONS_PER_USER, request.sessionsPerUser.toString())
        logger.info("{} set the sessions per user to {}", call.loggedIn.user.name, request.sessionsPerUser)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    // The apworld library of each Archipelago release
    route("/apworlds") {
      get { gatewayCall { call.respond(gameServer.archipelago()) } }
      post {
        val upload = call.receive<ApworldUpload>()
        if (upload.base64.length > MAX_APWORLD_BASE64) return@post call.refuse(HttpStatusCode.PayloadTooLarge, Refusal(Problem.APWORLD_TOO_LARGE))
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
internal fun RoutingContext.id(): String =
  call.parameters["id"]?.takeIf(SessionIds::valid) ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to call.parameters["id"])

/**
 * Answers 502 with the reason when the gateway doesn't answer, and what's wrong with a session request (409, 400,
 * 422), instead of failing the request.
 */
internal suspend fun RoutingContext.gatewayCall(block: suspend () -> Unit) {
  try {
    block()
  } catch (e: SessionProblem) {
    call.refuse(e.status, e.refusal)
  } catch (e: GameServerUnavailable) {
    logger.warn("Game server control failed: {}", e.message)
    call.refuse(e.status, Refusal(Problem.GATEWAY_UNREACHABLE, detail = e.message))
  }
}

/**
 * Under `/api/sessions/{id}/manage`, for the session's Session-Managers and Administrators: its state and actions,
 * cheats, game server, console and log (what the admin panel shows of one session). Every action is logged with the
 * name of who did it.
 */
fun Route.manageRoutes(
  gameServer: GameServerClient,
  admin: SessionAdmin,
  lifecycle: SessionLifecycle,
  cheats: Cheats,
  sessions: LiveSessions,
  list: SessionList,
) {
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
        cheats.run(id(), request, call.loggedIn.user.name, byRole(list))
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
        throw SessionProblem(HttpStatusCode.Conflict, Problem.REOPEN_TO_START)
      }
      logger.info("{} asked to {} the game server of {}", call.loggedIn.user.name, action, id)
      val state = gameServer.control(id, action!!)
      sessions.refresh()
      call.respond(state)
    }
  }
  post("/command") {
    val line = call.receive<CommandRequest>().line.trim()
    if (line.isEmpty()) return@post call.refuse(HttpStatusCode.BadRequest, Refusal(Problem.EMPTY_COMMAND))
    gatewayCall {
      val id = id()
      logger.info("{} ran on the game server of {}: {}", call.loggedIn.user.name, id, line)
      if (gameServer.command(id, line)) call.respond(HttpStatusCode.NoContent)
      else call.refuse(HttpStatusCode.Conflict, Refusal(Problem.SERVER_NOT_RUNNING))
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

/** As what the caller acts in this session: a Session-Manager, or an Administrator without a part in it. */
private fun RoutingContext.byRole(list: SessionList): String =
  if (list.roleIn(call.loggedIn.user, call.parameters["id"].orEmpty()) == SessionRole.MANAGER) "manager" else "administrator"

/** The key of the setting, and its highest value. */
const val SESSIONS_PER_USER = "sessions_per_user"
private const val MAX_SESSIONS_PER_USER = 20

/** How many running sessions one user (not an Administrator) may manage at once. */
suspend fun sessionsPerUser(settings: AppSettingsStore): Int = settings.get(SESSIONS_PER_USER)?.toIntOrNull() ?: 1
