package net.eratiem.randogeddon.gateway

import org.slf4j.LoggerFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApworldRemoval
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.AvailableRequest
import net.eratiem.randogeddon.shared.RandogeddonVersion
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.CommandRequest
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.SeedInstall
import net.eratiem.randogeddon.shared.SeedUpload
import net.eratiem.randogeddon.shared.SessionIds
import net.eratiem.randogeddon.shared.SlotFiles
import java.security.MessageDigest

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.InternalApi")

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
 * The tracker's control of the sessions' game servers (each: state, start/stop/restart, log, console), generating a
 * session's seed, deleting a session's files, its spoiler log and its slots' files; the Archipelago releases and the
 * apworld library of each.
 */
fun Route.internalApi(
  token: String,
  sessions: Sessions,
  archipelago: Archipelago,
  generator: Generator,
  apworlds: Apworlds,
  dirs: SessionDirs,
  seeds: UploadedSeeds,
  logic: Logic,
  minecraft: Minecraft,
) {
  route("/internal") {
    install(InternalAuth) { this.token = token }
    get("/versions") { call.respond(archipelago.versions()) }
    // This gateway's own version (the panel shows it)
    get("/version") { call.respondText(RandogeddonVersion.VERSION) }
    // Every release with its games (the first listing of a release takes seconds) and the libraries
    get("/archipelago") { call.respond(apworlds.info()) }
    route("/apworlds") {
      post { library { call.respond(apworlds.upload(call.receive<ApworldUpload>())) } }
      post("/try") { library { call.respond(apworlds.tryWith(call.receive<ApworldTry>())) } }
      post("/remove") {
        library {
          apworlds.remove(call.receive<ApworldRemoval>())
          call.respond(HttpStatusCode.NoContent)
        }
      }
    }
    // A seed generated elsewhere, uploaded for a new session: checked, then kept under a token until its session is
    // made (POST /sessions/{id}/seed) or an hour has passed
    post("/seeds") {
      val upload = call.receive<SeedUpload>()
      try {
        call.respond(withContext(Dispatchers.IO) { seeds.take(upload) })
      } catch (e: SeedProblem) {
        call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Not taken"))
      }
    }
    delete("/seeds/{token}") {
      call.respond(if (seeds.drop(call.parameters["token"].orEmpty())) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }
    route("/sessions") {
      get {
        call.respond(sessions.list().let {
          it.copy(sessions = it.sessions.map { s -> s.copy(spoiler = generator.spoiler(s.id), logic = logic.progress(s.id)) })
        })
      }
      route("/{id}") {
        // Takes as long as Generate.py does (up to minutes); the new seed's game server waits for a start
        post("/generate") {
          val id = sessionId() ?: return@post
          val request = call.receive<GenerateRequest>()
          val result = try {
            generator.generate(id, request)
          } catch (e: IllegalStateException) {
            return@post call.respond(HttpStatusCode.Conflict, ApiError(e.message ?: "It has a seed already"))
          }
          if (result.seedName != null) sessions.added(id)
          call.respond(result)
        }
        // An uploaded seed becomes its seed; its game server waits for a start, as after a generation
        post("/seed") {
          val id = sessionId() ?: return@post
          val install = call.receive<SeedInstall>()
          val info = try {
            withContext(Dispatchers.IO) { seeds.install(id, install.token) }
          } catch (e: IllegalStateException) {
            return@post call.respond(HttpStatusCode.Conflict, ApiError(e.message ?: "It has a seed already"))
          } ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("No such uploaded seed (any more)"))
          sessions.added(id)
          call.respond(info)
        }
        // How far its generation is, while it runs (no content: none runs)
        get("/progress") {
          val id = sessionId() ?: return@get
          generator.progress(id)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NoContent)
        }
        // Its last generation's log: lines after ?since= (at most ?max=), or all of it as text
        get("/generate/log") {
          val id = sessionId() ?: return@get
          val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
          val max = call.request.queryParameters["max"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 1000
          generator.log(id, since, max)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NotFound)
        }
        // Its spoiler log's background run: its lines after ?since=
        get("/spoiler-run/log") {
          val id = sessionId() ?: return@get
          val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
          generator.spoilerLog(id, since, 1000)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NotFound)
        }
        // Its spoiler log stopped: tried again from there
        post("/spoiler-run/retry") {
          val id = sessionId() ?: return@post
          call.respond(if (generator.retrySpoiler(id)) HttpStatusCode.NoContent else HttpStatusCode.Conflict)
        }
        get("/generate/log.txt") {
          val id = sessionId() ?: return@get
          generator.logText(id)?.let { call.respondText(it) } ?: call.respond(HttpStatusCode.NotFound)
        }
        // Ends its generation, also one waiting for its turn (the request that started it answers then)
        delete("/generate") {
          val id = sessionId() ?: return@delete
          call.respond(if (generator.cancel(id)) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
        // Someone was taken off a slot (its password is new): the new one read at once, the slot's connections
        // closed (they connect again with the new one), its hosted Minecraft server connected again
        post("/slots/{slot}/disconnect") {
          val id = sessionId() ?: return@post
          val slot = call.parameters["slot"].orEmpty()
          val session = sessions.get(id) ?: return@post call.respond(HttpStatusCode.NotFound)
          session.passwords.invalidate()
          val closed = session.clients.close(slot, "Your access to this slot changed")
          minecraft.reconnect(id, slot)
          logger.info("{}'s slot {}: its password is new, {} connection(s) closed", id, slot, closed)
          call.respond(HttpStatusCode.NoContent)
        }
        // Its Minecraft Dig slots' servers: their state; started and stopped by hand; their logs (by slot)
        get("/minecraft") {
          val id = sessionId() ?: return@get
          call.respond(minecraft.servers(id))
        }
        post("/minecraft/{slot}/start") {
          val id = sessionId() ?: return@post
          val why = minecraft.start(id, call.parameters["slot"].orEmpty())
          if (why == null) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.Conflict, ApiError(why))
        }
        post("/minecraft/{slot}/stop") {
          val id = sessionId() ?: return@post
          call.respond(if (minecraft.stop(id, call.parameters["slot"].orEmpty())) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
        get("/minecraft/log") {
          val id = sessionId() ?: return@get
          val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
          call.respond(minecraft.log(id, since))
        }
        // Its logic: how many of each slot's locations it can check now (no content: it isn't running)
        post("/logic/available") {
          val id = sessionId() ?: return@post
          logic.available(id, call.receive<AvailableRequest>())?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NoContent)
        }
        // Built again from the start (also after a failed build)
        post("/logic/rebuild") {
          val id = sessionId() ?: return@post
          call.respond(if (logic.rebuild(id)) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
        // Its logic's log: lines after ?since=
        get("/logic/log") {
          val id = sessionId() ?: return@get
          val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
          logic.log(id, since)?.let { call.respond(it) } ?: call.respond(HttpStatusCode.NotFound)
        }
        // Stops its game server; its files go to data/old, or for good with ?purge=true
        delete {
          val id = sessionId() ?: return@delete
          generator.dropSpoiler(id)
          logic.stop(id)
          minecraft.remove(id)
          val purge = call.request.queryParameters["purge"] == "true"
          if (sessions.remove(id, purge)) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
        get("/spoiler") {
          val id = sessionId() ?: return@get
          val text = dirs.spoiler(id)
          if (text == null) call.respond(HttpStatusCode.NotFound, ApiError("No spoiler log for this session"))
          else call.respondText(text, ContentType.Text.Plain)
        }
        // The seed's files of the slots (patches, mods), for the players to download through the tracker
        route("/files") {
          get {
            val id = sessionId() ?: return@get
            call.respond(SlotFiles(dirs.slotFiles(id)))
          }
          get("/{name}") {
            val id = sessionId() ?: return@get
            val bytes = dirs.slotFile(id, call.parameters["name"].orEmpty())
              ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("No such file"))
            call.respondBytes(bytes, ContentType.Application.OctetStream)
          }
        }
        route("/server") {
          get { server(sessions) { call.respond(it.server.state) } }
          post("/start") { control { sessions.start(it) } }
          post("/stop") { control { sessions.stop(it) } }
          post("/restart") { control { sessions.restart(it) } }
          get("/log") {
            server(sessions) {
              val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
              call.respond(it.server.log(since))
            }
          }
          post("/command") {
            server(sessions) {
              val line = call.receive<CommandRequest>().line.trim()
              when {
                line.isEmpty() -> call.respond(HttpStatusCode.BadRequest)
                it.server.command(line) -> call.respond(HttpStatusCode.NoContent)
                // No server running to take it
                else -> call.respond(HttpStatusCode.Conflict)
              }
            }
          }
        }
      }
    }
  }
}

/** The `{id}` of the path, if it is a session ID; otherwise answers 400 and gives `null`. */
private suspend fun RoutingContext.sessionId(): String? {
  val id = call.parameters["id"]
  if (id == null || !SessionIds.valid(id)) {
    call.respond(HttpStatusCode.BadRequest, ApiError("Not a session ID: $id"))
    return null
  }
  return id
}

/** The session's game server, or 404. */
private suspend fun RoutingContext.server(sessions: Sessions, block: suspend (SessionGameServer) -> Unit) {
  val id = sessionId() ?: return
  val session = sessions.get(id) ?: return call.respond(HttpStatusCode.NotFound, ApiError("No session $id"))
  block(session)
}

/** Start, stop or restart: the new state; 404 for an unknown session, 409 when as many run as are allowed. */
private suspend fun RoutingContext.control(action: suspend (String) -> GameServerState) {
  val id = sessionId() ?: return
  try {
    call.respond(action(id))
  } catch (e: NoSuchElementException) {
    call.respond(HttpStatusCode.NotFound, ApiError(e.message ?: "No session $id"))
  } catch (e: ServersBusy) {
    call.respond(HttpStatusCode.Conflict, ApiError(e.message ?: "No game server free"))
  }
}

/** An apworld that can't be taken (or removed) is a 400 with the reason, for the admin panel. */
private suspend fun RoutingContext.library(block: suspend () -> Unit) {
  try {
    block()
  } catch (e: ApworldProblem) {
    call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Not possible"))
  }
}
