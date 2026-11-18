package net.eratiem.randogeddon.server.config

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import net.eratiem.randogeddon.server.session.NO_VERSION
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.coroutineScope
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.admin.GameServerUnavailable
import net.eratiem.randogeddon.server.admin.SlotDownloads
import net.eratiem.randogeddon.server.config.refuse
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.shared.SpoilerFindings
import net.eratiem.randogeddon.shared.SpoilerLines
import net.eratiem.randogeddon.server.session.SpoilerSearches
import net.eratiem.randogeddon.server.session.SpoilerLog
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.server.session.markChecked
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.Connection
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SlotFiles

private val KEEPALIVE = 15.seconds
/** How often a stream looks whether its keepalive is due (and its login, every [LOGIN_CHECK]). */
private val KEEPALIVE_CHECK = 5.seconds
/** How often a stream checks that its login still holds (ended: e.g. logged out, or at the provider). */
internal val LOGIN_CHECK = 30.seconds

/** The live streams open per account (each holds a connection for as long as it is open): only so many at once. */
object OpenStreams {
  const val MAX = 20
  private val open = ConcurrentHashMap<String, Int>()

  /** Runs [block] as one of [account]'s streams; not at all if they have [MAX] open already. */
  suspend fun <T> counted(account: String, block: suspend () -> T): T? {
    val mine = open.merge(account, 1, Int::plus) ?: 1
    try {
      return if (mine > MAX) null else block()
    } finally {
      open.compute(account) { _, n -> (n ?: 1).minus(1).takeIf { it > 0 } }
    }
  }
}

/**
 * Under the protected `/api` branch: the sessions a user may see (theirs, all for admins), and each one's summary
 * (once and as a live stream), the details of one slot, where and with which passwords to connect, the seed's files of
 * the user's slots, and its spoiler log (for its players once it is finished, any time for admins). A finished session
 * answers as it was when it finished (its archive).
 */
fun Route.sessionRoutes(
  config: TrackerConfig,
  logins: LoginCheck,
  sessions: LiveSessions,
  list: SessionList,
  archives: Archives,
  slotPasswords: SlotPasswords,
  spoilers: Spoilers,
  downloads: SlotDownloads,
  searches: SpoilerSearches = SpoilerSearches(),
) {
  get("/sessions") { call.respond(list.visibleTo(call.actor)) }
  route("/sessions/{id}") {
    install(RequireSessionMember) { this.logins = logins }
    get("/state") { session(sessions) { call.respondText(it.json(), ContentType.Application.Json) } }
    get("/player/{slot}") { session(sessions) { playerDetail(it, archives) } }
    connectionRoute(config, sessions, slotPasswords)
    // A finished session as it was when it finished, with every slot's details
    get("/archive") {
      session(sessions) { live ->
        val archived = archives.read(live.id) ?: return@session call.refuse(HttpStatusCode.NotFound, Refusal.of(Problem.NOT_FINISHED, "id" to live.id))
        call.respond(archived)
      }
    }
    // The seed's files of the user's slots (patches, mods; every slot's for Session-Managers and Administrators)
    get("/files") { gatewayCall { call.respond(SlotFiles(downloads.of(id(), call.actor))) } }
    get("/files/{name}") {
      gatewayCall {
        val (file, bytes) = downloads.file(id(), call.parameters["name"].orEmpty(), call.actor)
        call.response.header(
          HttpHeaders.ContentDisposition,
          ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString(),
        )
        call.respondBytes(bytes, ContentType.Application.OctetStream)
      }
    }
    // A full spoiler: before a session is finished only Administrators, its Session-Managers and support see it.
    // The playthrough at once; the log itself by pages, and searched here (a big seed's is too long for a browser)
    get("/spoiler") {
      spoiler(sessions, spoilers) { live, log ->
        // The steps already checked, as the tracker sees them now
        val checked = runCatching { live.tracker.read { checkedLocations() } }.getOrDefault(emptyMap())
        call.respond(markChecked(log.spoiler, checked))
      }
    }
    get("/spoiler/log") {
      spoiler(sessions, spoilers) { _, log ->
        val from = call.request.queryParameters["from"]?.toIntOrNull() ?: 0
        call.respond(log.page(from, call.request.queryParameters["count"]?.toIntOrNull() ?: SpoilerLines.MAX))
      }
    }
    get("/spoiler/search") {
      spoiler(sessions, spoilers) { _, log ->
        val query = call.request.queryParameters["q"].orEmpty().trim()
        if (query.length !in SpoilerFindings.MIN_QUERY..SpoilerFindings.MAX_QUERY) {
          return@spoiler call.refuse(
            HttpStatusCode.BadRequest,
            Refusal.of(Problem.QUERY_LENGTH, "min" to SpoilerFindings.MIN_QUERY, "max" to SpoilerFindings.MAX_QUERY),
          )
        }
        val found = searches.paced(call.loggedIn.user.id.toString()) { log.search(query) }
          ?: return@spoiler call.refuse(HttpStatusCode.TooManyRequests, Refusal(Problem.TOO_MANY_SEARCHES))
        call.respond(found)
      }
    }
    // Pushes the whole summary whenever the state changes. Polled and therefore debounced: a burst of item sends (e.g.
    // a release) becomes one update.
    sse("/stream") {
      val live = sessions.get(call.parameters["id"].orEmpty()) ?: return@sse
      val token = call.request.cookies[SESSION_COOKIE]
      val me = call.actor
      // Ends once its login does (the page's next request then finds it logged out), or their part in the session
      OpenStreams.counted(call.loggedIn.user.id.toString()) {
        follow(live, stillAllowed = { token != null && logins.userOf(token) != null && logins.maySee(me, live.id) }) { live.json() }
      }
    }
  }
}

/**
 * [block] with the path's session and its spoiler log, for who may read it: before the session is finished only
 * Administrators, its Session-Managers and support.
 */
private suspend fun RoutingContext.spoiler(
  sessions: LiveSessions,
  spoilers: Spoilers,
  block: suspend RoutingContext.(LiveSession, SpoilerLog) -> Unit,
) = session(sessions) { live ->
  val user = call.actor
  val helps = user.administrator || live.roleOf(user) in setOf(SessionRole.MANAGER, SessionRole.SUPPORT)
  if (!helps && !live.finished) return@session call.refuse(HttpStatusCode.Forbidden, Refusal(Problem.SPOILER_LATER))
  try {
    val log = spoilers.of(live.id, live.seedName)
      ?: return@session call.refuse(HttpStatusCode.NotFound, Refusal.of(Problem.NO_SPOILER, "id" to live.id))
    this.block(live, log)
  } catch (e: GameServerUnavailable) {
    call.refuse(e.status, Refusal(Problem.GATEWAY_UNREACHABLE, detail = e.message))
  }
}

/** The details of the path's `{slot}` of [live]; a finished session's from its archive. */
/** `GET …/connection`: where the person's game clients connect, with their slots' passwords (the bridge's too). */
internal fun Route.connectionRoute(config: TrackerConfig, sessions: LiveSessions, slotPasswords: SlotPasswords) {
  get("/connection") {
    session(sessions) { live ->
      val user = call.actor
      val manages = user.administrator || live.roleOf(user) == SessionRole.MANAGER
      call.respond(Connection(config.addressOf(live.id), slotPasswords.of(live, live.personOf(user), manages)))
    }
  }
}

internal suspend fun RoutingContext.playerDetail(live: LiveSession, archives: Archives) {
  val slot = call.parameters["slot"]?.toIntOrNull() ?: return call.respond(HttpStatusCode.BadRequest)
  val archived = if (live.finished) archives.read(live.id) else null
  val detail = (if (archived != null) archived.details.firstOrNull { it.slot == slot } else live.tracker.read { playerDetail(slot) })
    ?: return call.respond(HttpStatusCode.NotFound)
  call.respond(detail)
}

/**
 * Sends [live]'s summary ([json]) whenever it changes ([LiveSession.changes], one poller for all its streams), with a
 * keepalive while nothing does, until the client goes or [stillAllowed] says no more.
 */
internal suspend fun ServerSSESession.follow(
  live: LiveSession,
  stillAllowed: suspend () -> Boolean = { true },
  json: suspend () -> String,
) = coroutineScope {
  // The summaries and the keepalive come from two coroutines: one at a time on the stream
  val sending = Mutex()
  var lastSent = TimeSource.Monotonic.markNow()
  suspend fun emit(event: ServerSentEvent) = sending.withLock {
    send(event)
    lastSent = TimeSource.Monotonic.markNow()
  }
  // A finished session's summary no longer changes, but finishing (and reopening) changes the version
  val updates = launch { live.changes.filter { it != NO_VERSION }.collect { emit(ServerSentEvent(data = json(), event = "state")) } }
  var unchecked = 0.milliseconds
  while (updates.isActive) {
    delay(KEEPALIVE_CHECK)
    unchecked += KEEPALIVE_CHECK
    if (unchecked >= LOGIN_CHECK) {
      unchecked = 0.milliseconds
      if (!stillAllowed()) break
    }
    if (lastSent.elapsedNow() >= KEEPALIVE) emit(ServerSentEvent(comments = "keepalive"))
  }
  updates.cancel()
}

/** The session of the path's `{id}`, or 404. */
internal suspend fun RoutingContext.session(sessions: LiveSessions, block: suspend (LiveSession) -> Unit) {
  val id = call.parameters["id"].orEmpty()
  val live = sessions.get(id) ?: return call.refuse(HttpStatusCode.NotFound, Refusal.of(Problem.NO_SESSION, "id" to id))
  block(live)
}
