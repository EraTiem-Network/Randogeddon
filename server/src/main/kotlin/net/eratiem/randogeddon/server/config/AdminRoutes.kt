package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import java.lang.invoke.MethodHandles
import kotlin.time.Duration.Companion.milliseconds
import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.database.GAME_SERVERS
import net.eratiem.randogeddon.database.IDLE_MINUTES
import net.eratiem.randogeddon.database.MAX_GAME_SERVERS
import net.eratiem.randogeddon.database.MAX_IDLE_MINUTES
import net.eratiem.randogeddon.database.MAX_WATCH_STREAMS
import net.eratiem.randogeddon.database.WATCH_STREAMS
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.admin.Cheats
import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.server.admin.GameServerLimit
import net.eratiem.randogeddon.server.admin.GameServerUnavailable
import net.eratiem.randogeddon.server.admin.LogicRuns
import net.eratiem.randogeddon.server.admin.MinecraftRuns
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.admin.SessionLifecycle
import net.eratiem.randogeddon.server.admin.SessionPeopleService
import net.eratiem.randogeddon.server.admin.SessionProblem
import net.eratiem.randogeddon.server.admin.SpoilerRuns
import net.eratiem.randogeddon.server.config.refuse
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AppSettings
import net.eratiem.randogeddon.shared.ApworldRemoval
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.CheatRequest
import net.eratiem.randogeddon.shared.CommandRequest
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.InviteRequest
import net.eratiem.randogeddon.shared.KeepRequest
import net.eratiem.randogeddon.shared.KeepRunningRequest
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.MinecraftDirectRequest
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.RandogeddonVersion
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.SessionIds
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SlotPlayersRequest
import net.eratiem.randogeddon.shared.Versions

private val logger = logger(MethodHandles.lookup().lookupClass())
private val LOG_POLL = 750.milliseconds

/** The largest apworld upload, as base64, all its files together (the gateway takes 64 MB files; the panel sends larger
 * selections in several). */
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
    get("/sessions") { apiCall { call.respond(admin.overview(call.loggedIn.user)) } }
    // What runs now: this tracker, the gateway (if it answers) and its releases
    get("/versions") {
      val gateway = runCatching { gameServer.gatewayVersion() }.getOrNull()
      val releases = runCatching { gameServer.versions() }.getOrDefault(emptyList())
      call.respond(Versions(RandogeddonVersion.VERSION, gateway, releases))
    }
    // Its game server keeps running without players, or sleeps when idle
    put("/sessions/{id}/keep-running") {
      val keep = call.receive<KeepRunningRequest>().keepRunning
      apiCall {
        admin.setKeepRunning(call.parameters["id"].orEmpty(), keep, call.loggedIn.user.name)
        call.respond(HttpStatusCode.NoContent)
      }
    }
    route("/settings") {
      get { call.respond(AppSettings(sessionsPerUser(settings), settings.gameServers(), settings.idleMinutes(), settings.watchStreams())) }
      put {
        val request = call.receive<AppSettings>()
        if (request.sessionsPerUser !in 0..MAX_SESSIONS_PER_USER) {
          return@put call.refuse(HttpStatusCode.BadRequest, Refusal.of(Problem.SESSIONS_PER_USER, "max" to MAX_SESSIONS_PER_USER))
        }
        if (request.gameServers !in 0..MAX_GAME_SERVERS) {
          return@put call.refuse(HttpStatusCode.BadRequest, Refusal.of(Problem.GAME_SERVERS, "max" to MAX_GAME_SERVERS))
        }
        if (request.idleMinutes !in 0..MAX_IDLE_MINUTES) {
          return@put call.refuse(HttpStatusCode.BadRequest, Refusal.of(Problem.IDLE_MINUTES, "max" to MAX_IDLE_MINUTES))
        }
        if (request.watchStreams !in 0..MAX_WATCH_STREAMS) {
          return@put call.refuse(HttpStatusCode.BadRequest, Refusal.of(Problem.WATCH_STREAMS, "max" to MAX_WATCH_STREAMS))
        }
        settings.set(SESSIONS_PER_USER, request.sessionsPerUser.toString())
        settings.set(GAME_SERVERS, request.gameServers.toString())
        settings.set(IDLE_MINUTES, request.idleMinutes.toString())
        settings.set(WATCH_STREAMS, request.watchStreams.toString())
        logger.info(
          "{} set the sessions per user to {}, the game servers at once to {}, their sleep after {}, the share-link " +
            "streams per address to {}",
          call.loggedIn.user.name,
          request.sessionsPerUser, request.gameServers.takeIf { it > 0 } ?: "no limit",
          request.idleMinutes.takeIf { it > 0 }?.let { "$it minutes without players" } ?: "never",
          request.watchStreams.takeIf { it > 0 } ?: "no limit",
        )
        call.respond(HttpStatusCode.NoContent)
      }
    }
    // The apworld library of each Archipelago release
    route("/apworlds") {
      get { apiCall { call.respond(gameServer.archipelago()) } }
      post {
        val upload = call.receive<ApworldUpload>()
        if (upload.files.sumOf { it.base64.length.toLong() } > MAX_APWORLD_BASE64) {
          return@post call.refuse(HttpStatusCode.PayloadTooLarge, Refusal(Problem.APWORLD_TOO_LARGE))
        }
        logger.info("{} uploads {} for Archipelago {}", call.loggedIn.user.name, upload.files.joinToString(", ") { it.fileName }, upload.release)
        apiCall { call.respond(gameServer.uploadApworlds(upload)) }
      }
      post("/try") {
        val request = call.receive<ApworldTry>()
        logger.info("{} tries {} of Archipelago {} with {}", call.loggedIn.user.name, request.file, request.from, request.release)
        apiCall { call.respond(gameServer.tryApworld(request)) }
      }
      post("/remove") {
        val removal = call.receive<ApworldRemoval>()
        logger.info("{} removes {} from Archipelago {}", call.loggedIn.user.name, removal.files.joinToString(", "), removal.release)
        apiCall {
          gameServer.removeApworlds(removal)
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
  }
}

/** The session ID of the path; a [SessionProblem] (404) if it isn't one. */
internal fun RoutingContext.id(): String =
  call.parameters["id"]?.takeIf(SessionIds::valid) ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to call.parameters["id"])

/** The query's `?since=` (the log lines after it); 0 without one. */
internal fun ApplicationCall.since(): Long = request.queryParameters["since"]?.toLongOrNull() ?: 0

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
  logins: LoginCheck,
  gameServers: GameServerLimit,
  spoilerRuns: SpoilerRuns,
  logicRuns: LogicRuns,
  minecraftRuns: MinecraftRuns,
  people: SessionPeopleService,
) {
  get { apiCall { call.respond(admin.view(id(), call.actor)) } }
  delete {
    apiCall {
      admin.delete(id(), call.loggedIn.user.name)
      call.respond(HttpStatusCode.NoContent)
    }
  }
  // Finishing it now, or stopping its countdown; reopening it; keeping it for good (or not)
  post("/finish") {
    apiCall {
      lifecycle.finishNow(id(), call.loggedIn.user.name)
      call.respond(HttpStatusCode.NoContent)
    }
  }
  post("/finish/cancel") {
    apiCall {
      lifecycle.cancel(id(), call.loggedIn.user.name)
      call.respond(HttpStatusCode.NoContent)
    }
  }
  post("/reopen") {
    apiCall {
      lifecycle.reopen(id(), call.actor)
      call.respond(HttpStatusCode.NoContent)
    }
  }
  // How its spoiler log is completed after the seed, and that run's log
  spoilerRunRoutes(spoilerRuns)
  // Its logic (the available checks): its state, built again; its log only for Administrators
  get("/logic") { apiCall { call.respond(logicRuns.view(id())) } }
  post("/logic/rebuild") {
    apiCall {
      val built = logicRuns.rebuild(id())
      if (built) logger.info("{}'s logic is built again by {}", id(), call.loggedIn.user.name)
      call.respond(if (built) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }
  }
  get("/logic/log") {
    apiCall {
      onlyAdministrators()
      call.respond(logicRuns.log(id(), call.since()))
    }
  }
  // Who plays a slot now (its hosted mode stays); someone taken off it loses its connection (a new password)
  post("/slots/{slot}") {
    val slot = call.parameters["slot"].orEmpty()
    val request = call.receive<SlotPlayersRequest>()
    apiCall {
      admin.setSlotPlayers(id(), slot, request, call.loggedIn.user.name)
      call.respond(HttpStatusCode.NoContent)
    }
  }
  // Someone without an account, invited to play in it (a slot of theirs follows): only an address no account has
  post("/invite") {
    apiCall {
      val request = call.receive<InviteRequest>()
      call.respond(people.inviteNew(request.email, request.suggestedName, call.loggedIn.user, call.language()))
    }
  }
  // Its Minecraft Dig slots' servers: their state, started and stopped by hand; their logs only for Administrators
  get("/minecraft") { apiCall { call.respond(minecraftRuns.view(id())) } }
  post("/minecraft/{slot}/start") {
    val slot = call.parameters["slot"].orEmpty()
    apiCall {
      val why = minecraftRuns.start(id(), slot)
      if (why == null) {
        logger.info("{}'s Minecraft server of {} started by {}", id(), slot, call.loggedIn.user.name)
        call.respond(HttpStatusCode.NoContent)
      } else {
        call.respond(HttpStatusCode.Conflict, ApiError(why))
      }
    }
  }
  post("/minecraft/{slot}/stop") {
    val slot = call.parameters["slot"].orEmpty()
    apiCall {
      val stopped = minecraftRuns.stop(id(), slot)
      if (stopped) logger.info("{}'s Minecraft server of {} stopped by {}", id(), slot, call.loggedIn.user.name)
      call.respond(if (stopped) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }
  }
  // Randogeddon runs a hosted slot's Minecraft server, or no longer: on only for Administrators, off for its
  // Session-Managers too
  post("/minecraft/{slot}/direct") {
    val slot = call.parameters["slot"].orEmpty()
    val direct = call.receive<MinecraftDirectRequest>().direct
    apiCall {
      admin.setDirect(id(), slot, direct, call.actor.administrator, call.loggedIn.user.name)
      call.respond(HttpStatusCode.NoContent)
    }
  }
  get("/minecraft/log") {
    apiCall {
      onlyAdministrators()
      call.respond(minecraftRuns.log(id(), call.since()))
    }
  }
  post("/keep") {
    val keep = call.receive<KeepRequest>().keep
    apiCall {
      admin.setKeep(id(), keep, call.loggedIn.user.name)
      call.respond(HttpStatusCode.NoContent)
    }
  }
  // Cheats: logged with the admin's name, shown in the live feed
  route("/cheat") {
    get { apiCall { call.respond(cheats.view(id())) } }
    get("/names") {
      val slot = call.request.queryParameters["slot"] ?: return@get call.respond(HttpStatusCode.BadRequest)
      apiCall { call.respond(cheats.names(id(), slot)) }
    }
    post {
      val request = call.receive<CheatRequest>()
      apiCall {
        cheats.run(id(), request, call.loggedIn.user.name, byRole(list))
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
  get("/server") { apiCall { call.respond(gameServer.state(id())) } }
  post("/server/{action}") {
    val action = call.parameters["action"]
    if (action !in setOf("start", "stop", "restart")) return@post call.respond(HttpStatusCode.NotFound)
    apiCall {
      val id = id()
      if (action != "stop" && sessions.get(id)?.finished == true) {
        throw SessionProblem(HttpStatusCode.Conflict, Problem.REOPEN_TO_START)
      }
      // Only so many run at once (its own may be one of them already, as at a restart)
      if (action != "stop") gameServers.check(id)
      logger.info("{} asked to {} the game server of {}", call.loggedIn.user.name, action, id)
      val state = gameServer.control(id, action!!)
      sessions.refresh()
      call.respond(state)
    }
  }
  post("/command") {
    val line = call.receive<CommandRequest>().line.trim()
    if (line.isEmpty()) return@post call.refuse(HttpStatusCode.BadRequest, Refusal(Problem.EMPTY_COMMAND))
    // One line: a line break would forge lines in the log and send more commands to the game server
    if (!consoleLine(line)) return@post call.refuse(HttpStatusCode.BadRequest, Refusal(Problem.COMMAND_CONTROL))
    apiCall {
      val id = id()
      logger.info("{} ran on the game server of {}: {}", call.loggedIn.user.name, id, line)
      if (gameServer.command(id, line)) call.respond(HttpStatusCode.NoContent)
      else call.refuse(HttpStatusCode.Conflict, Refusal(Problem.SERVER_NOT_RUNNING))
    }
  }
  // The game server's state ("state", whenever it changes) and its new log lines ("log"), polled from the gateway
  sse("/stream") {
    val id = call.parameters["id"]?.takeIf(SessionIds::valid) ?: return@sse
    val token = call.sessionToken
    // Ends once the login does, or the right to manage the session (as RequireSessionRole asks it for a call)
    val stillAllowed: suspend () -> Boolean = {
      token?.let { logins.userOf(it) }?.let { logins.roleIn(it, id) == SessionRole.MANAGER || logins.administratorProblem(it) == null } == true
    }
    OpenStreams.counted(call.loggedIn.user.id.toString()) { serverStream(gameServer, id, stillAllowed) }
  }
}

/**
 * A session's game server as the panel follows it: its state whenever it changes, and its new log lines; a keepalive
 * while neither does.
 */
private suspend fun ServerSSESession.serverStream(gameServer: GameServerClient, id: String, stillAllowed: suspend () -> Boolean = { true }) {
  val sender = StreamSender(this)
  var since = 0L
  var lastState: GameServerState? = null
  var reachable = true
  sender.loop(LOG_POLL, stillAllowed) {
    try {
      val state = gameServer.state(id)
      if (state != lastState || !reachable) {
        sender.send(ServerSentEvent(ApiJson.encodeToString(GameServerState.serializer(), state), event = "state"))
        lastState = state
        reachable = true
      }
      val lines = gameServer.log(id, since)
      // The gateway restarted and counts from 1 again
      if (lines.last < since) since = 0
      if (lines.lines.isNotEmpty()) {
        sender.send(ServerSentEvent(ApiJson.encodeToString(LogLines.serializer(), lines), event = "log"))
      }
      since = lines.last
    } catch (e: GameServerUnavailable) {
      if (reachable) sender.send(ServerSentEvent(e.message ?: "unreachable", event = "unreachable"))
      reachable = false
    }
  }
}

/** Whether [line] can go to a game server's console: one line, without control characters. */
internal fun consoleLine(line: String): Boolean = line.none { it.isISOControl() }

/** As what the caller acts in this session: a Session-Manager, or an Administrator without a part in it. */
private fun RoutingContext.byRole(list: SessionList): String =
  if (list.roleIn(call.loggedIn.user, call.parameters["id"].orEmpty()) == SessionRole.MANAGER) "manager" else "administrator"

/** The key of the setting, and its highest value. */
const val SESSIONS_PER_USER = "sessions_per_user"
const val MAX_SESSIONS_PER_USER = 20

/** How many running sessions one user (not an Administrator) may manage at once. */
suspend fun sessionsPerUser(settings: AppSettingsStore): Int = settings.get(SESSIONS_PER_USER)?.toIntOrNull() ?: 1

/**
 * `…/spoiler-run`: how session `{id}`'s spoiler log is completed after its seed; `…/log?since=`: that run's log;
 * with [retry] (for its Session-Managers and Administrators, not support) `POST …/retry`: a stopped one is tried again.
 */
internal fun Route.spoilerRunRoutes(spoilerRuns: SpoilerRuns, retry: Boolean = true) {
  get("/spoiler-run") { apiCall { call.respond(spoilerRuns.view(id())) } }
  if (retry) {
    post("/spoiler-run/retry") {
      apiCall {
        spoilerRuns.retry(id())
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
  get("/spoiler-run/log") { apiCall { call.respond(spoilerRuns.log(id(), call.since())) } }
}
