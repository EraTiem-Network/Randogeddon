package net.eratiem.randogeddon.server.config

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.admin.GameServerUnavailable
import net.eratiem.randogeddon.server.admin.SlotDownloads
import net.eratiem.randogeddon.server.config.refuse
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.session.SpoilerLog
import net.eratiem.randogeddon.server.session.SpoilerSearches
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.server.session.managedBy
import net.eratiem.randogeddon.server.session.markChecked
import net.eratiem.randogeddon.server.session.readsLogicReasons
import net.eratiem.randogeddon.server.session.withoutLogicReason
import net.eratiem.randogeddon.server.session.withoutLogicReasons
import net.eratiem.randogeddon.shared.Connection
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SlotFiles
import net.eratiem.randogeddon.shared.SpoilerFindings
import net.eratiem.randogeddon.shared.SpoilerLines
import net.eratiem.randogeddon.shared.SpoilerPath
import net.eratiem.randogeddon.shared.SpoilerPaths
import net.eratiem.randogeddon.shared.SpoilerPlacement
import net.eratiem.randogeddon.shared.SpoilerPlacements

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
    // Why a slot's logic doesn't fit only for its Session-Managers, its support and Administrators (readsLogicReasons)
    get("/state") {
      session(sessions) { call.respondText(it.json(it.readsLogicReasons(call.actor)), ContentType.Application.Json) }
    }
    get("/player/{slot}") { session(sessions) { playerDetail(it, archives, it.readsLogicReasons(call.actor)) } }
    connectionRoute(config, sessions, slotPasswords)
    // A finished session as it was when it finished, with every slot's details
    get("/archive") {
      session(sessions) { live ->
        val archived = archives.read(live.id) ?: return@session call.refuse(HttpStatusCode.NotFound, Refusal.of(Problem.NOT_FINISHED, "id" to live.id))
        call.respond(if (live.readsLogicReasons(call.actor)) archived else archived.withoutLogicReasons())
      }
    }
    // The seed's files of the user's slots (patches, mods; every slot's for Session-Managers and Administrators)
    get("/files") { apiCall { call.respond(SlotFiles(downloads.of(id(), call.actor))) } }
    get("/files/{name}") {
      apiCall {
        val (file, bytes) = downloads.file(id(), call.parameters["name"].orEmpty(), call.actor)
        call.attachment(file.name)
        call.respondBytes(bytes, ContentType.Application.OctetStream)
      }
    }
    // A full spoiler: before a session is finished only Administrators, its Session-Managers and support see it.
    // The playthrough at once (once the log has one); the log itself by pages, and searched here (a big seed's is too
    // long for a browser)
    get("/spoiler") {
      spoiler(sessions, spoilers) { live, log ->
        // The steps already checked, as the tracker sees them now
        val checked = runCatching { live.tracker.read { checkedLocations() } }.getOrDefault(emptyMap())
        call.respond(markChecked(log.spoiler, checked).copy(slots = log.slots.values.map { it.name }, placements = log.allPlacements.size))
      }
    }
    // The log's parts as the page's views show them; its placements and paths filtered and paged here
    get("/spoiler/settings") { spoiler(sessions, spoilers) { _, log -> call.respond(log.settings) } }
    get("/spoiler/placements") {
      spoiler(sessions, spoilers) { live, log ->
        val (slot, query) = call.listed()
        val found = log.allPlacements.filter { placement ->
          (slot == null || placement.finder == slot || placement.receiver == slot) && (
            query.isEmpty() ||
              listOf(placement.location, placement.item, placement.finder, placement.receiver).any { it.contains(query, ignoreCase = true) }
            )
        }
        val checked = runCatching { live.tracker.read { checkedLocations() } }.getOrDefault(emptyMap())
        val (from, page) = call.page(found)
        call.respond(SpoilerPlacements(found.size, from, page.map {
          SpoilerPlacement(it.location, it.finder, it.item, it.receiver, checked[it.finder]?.contains(it.location) == true)
        }))
      }
    }
    get("/spoiler/paths") {
      spoiler(sessions, spoilers) { _, log ->
        val (slot, query) = call.listed()
        val found = log.paths.filter { path ->
          (slot == null || path.slot == slot) &&
            (query.isEmpty() || path.location.contains(query, ignoreCase = true) || path.regions.any { it.contains(query, ignoreCase = true) })
        }
        val (from, page) = call.page(found)
        call.respond(SpoilerPaths(found.size, from, page.map { SpoilerPath(it.location, it.slot, it.regions) }))
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
      val token = call.sessionToken
      // Ends once its login does (the page's next request then finds it logged out), or their part in the session (an
      // Administrator's too, as the account is now)
      val stillAllowed: suspend () -> Boolean = {
        token?.let { logins.userOf(it) }?.let { logins.maySee(logins.actorOf(it), live.id) } == true
      }
      // Whether it gets the logic's reasons as the viewer's part in it is at each update
      val actor = call.actor
      OpenStreams.counted(call.loggedIn.user.id.toString()) { follow(live, stillAllowed) { live.json(live.readsLogicReasons(actor)) } }
    }
  }
}

/** A spoiler list's `slot` (none: every slot) and `q` (what its rows contain; cut at the search's longest). */
private fun ApplicationCall.listed(): Pair<String?, String> =
  request.queryParameters["slot"]?.takeIf { it.isNotEmpty() } to request.queryParameters["q"].orEmpty().trim().take(SpoilerFindings.MAX_QUERY)

/** The page of [all] the request asks for (`from`, `count`): where it starts, and its rows. */
private fun <T> ApplicationCall.page(all: List<T>): Pair<Int, List<T>> {
  val from = (request.queryParameters["from"]?.toIntOrNull() ?: 0).coerceIn(0, all.size)
  val count = (request.queryParameters["count"]?.toIntOrNull() ?: SpoilerPlacements.MAX).coerceIn(1, SpoilerPlacements.MAX)
  return from to all.subList(from, minOf(all.size, from + count))
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
    call.refuse(e.status, call.gatewayRefusal(e))
  }
}

/** `GET …/connection`: where the person's game clients connect, with their slots' passwords (the bridge's too). */
internal fun Route.connectionRoute(config: TrackerConfig, sessions: LiveSessions, slotPasswords: SlotPasswords) {
  get("/connection") {
    session(sessions) { live ->
      val user = call.actor
      call.respond(Connection(config.addressOf(live.id), slotPasswords.of(live, live.personOf(user), live.managedBy(user))))
    }
  }
}

/**
 * The details of the path's `{slot}` of [live]; a finished session's from its archive, else maybe its kept snapshot.
 * [reasons]: with why its logic doesn't fit ([readsLogicReasons]); else the warning without it.
 */
internal suspend fun RoutingContext.playerDetail(live: LiveSession, archives: Archives, reasons: Boolean) {
  val slot = call.parameters["slot"]?.toIntOrNull() ?: return call.respond(HttpStatusCode.BadRequest)
  // A running session's kept one while its tracker isn't connected yet
  val archived = if (live.finished) archives.read(live.id) else live.shownKept
  val detail = (if (archived != null) archived.details.firstOrNull { it.slot == slot } else live.tracker.read { playerDetail(slot) })
    ?: return call.respond(HttpStatusCode.NotFound)
  call.respond(if (reasons) detail else detail.withoutLogicReason())
}

/** The session of the path's `{id}`, or 404. */
internal suspend fun RoutingContext.session(sessions: LiveSessions, block: suspend (LiveSession) -> Unit) {
  val id = call.parameters["id"].orEmpty()
  val live = sessions.get(id) ?: return call.refuse(HttpStatusCode.NotFound, Refusal.of(Problem.NO_SESSION, "id" to id))
  block(live)
}
