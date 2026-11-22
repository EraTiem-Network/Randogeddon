package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.eratiem.randogeddon.shared.AddressRange
import net.eratiem.randogeddon.shared.clientAddress
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Relay")

/**
 * WebSockets as the game clients get them. What they send is small (the big frames come from MultiServer, over the
 * gateway's own client): a frame limit on the wire and unpacked, so nobody fills the memory before being in a slot.
 */
fun Application.gameClientWebSockets() {
  install(WebSockets) {
    maxFrameSize = CLIENT_FRAME_LIMIT.toLong()
    pingPeriod = 30.seconds
    extensions { install(WebSocketDeflateExtension) { maxInflatedFrameSize = CLIENT_FRAME_LIMIT } }
  }
}

/**
 * The address a game client counts as: the one the last proxy (Traefik) saw ([clientAddress]), believed only from a
 * [trusted] peer (`TRUSTED_PROXIES`; none set: from every peer), else the peer's own.
 */
fun ApplicationCall.gameClientAddress(trusted: List<AddressRange>): String =
  clientAddress(request.local.remoteAddress, request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty(), trusted)

/** A client's `Connect` that passed while the gateway answered it ([lobby]): MultiServer gets it first of all. */
private class Pending(val text: String, val verdict: Verdict.Join, val since: Long)

/**
 * One game client's connection from [address]: its place among the [connections] (only so many of everyone, and of
 * one address, aren't in a slot yet; none free: closed), then relayed to MultiServer at [upstreamUrl] ([relay]).
 * [upstreamUrl] is asked once the client has its place, so the wake of a sleeping game server counts against the
 * limits of a connection before its `Connect` (the places, the time); `null`: there's none, and it closed the
 * connection with its reason. Once the client is in a slot, [joined] hears of it; what that answers is called when it
 * leaves (`null`: nothing to call, as for a hosted slot).
 *
 * [kept]: what the gateway kept of a sleeping game server ([KeptRoom]); `null`: it doesn't sleep, or nothing is kept
 * for it. With it, the gateway answers the client itself until its `Connect` passes ([lobby]), and only that wakes the
 * game server; without it, the connection does, as a session's did before the gateway kept anything of it (each
 * session's first run after an update).
 *
 * [roomPassword]: the room password of the MultiServer that runs now ([GameServer.roomPassword]), put into every
 * `Connect` that passed in place of the slot's ([withRoomPassword]); `null`: the `Connect` passes as it came.
 */
suspend fun DefaultWebSocketServerSession.serve(
  http: HttpClient,
  upstreamUrl: suspend () -> String?,
  passwords: suspend () -> Map<String, String>,
  locked: suspend () -> Set<String>,
  connections: ConnectionLimits,
  joined: suspend (slot: String) -> (() -> Unit)? = { null },
  clients: SlotClients? = null,
  address: String = call.request.origin.remoteHost,
  kept: suspend () -> KeptRoom.Kept? = { null },
  roomPassword: () -> String? = { null },
) {
  val ticket = when (val entry = connections.enter(address)) {
    is ConnectionLimits.Ticket -> entry
    is ConnectionLimits.Full -> {
      if (entry == ConnectionLimits.Full.ADDRESS) {
        logger.info("Refused {}: too many of its connections wait for their Connect", address)
        val reason = "Too many connections from your address: try again in a moment"
        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, reason))
      } else {
        logger.info("Refused {}: too many connections wait for their Connect", address)
        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections: try again in a moment"))
      }
      return
    }
  }
  try {
    val arrived = TimeSource.Monotonic.markNow()
    val limits = connections.limits
    val before = BeforeConnect(limits.packetsBeforeConnect)
    // A sleeping game server's room, kept from its last run: the gateway answers until a Connect passes
    val room = kept()
    var late = true
    val first = room?.let {
      withTimeoutOrNull(limits.connectWithin - arrived.elapsedNow()) {
        lobby(it, passwords, locked, limits, before, clients, address).also { late = false }
      }
    }
    // Without a kept room, the game server is woken for the connection (and the client waits for its RoomInfo)
    if (room == null || first != null) {
      late = true
      val url = withTimeoutOrNull(limits.connectWithin - arrived.elapsedNow()) { upstreamUrl().also { late = false } }
      if (url != null) {
        relay(http, url, passwords, locked, ticket, limits, arrived, joined, clients, address, before, first, room, roomPassword)
      }
    }
    if (late) {
      logger.info("Closed {}: no Connect or no game server within {}", address, limits.connectWithin)
      close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "No Connect: connect again when you are ready"))
    }
  } finally {
    ticket.leave()
  }
}

/**
 * Answers a game client of a sleeping game server from what was [kept] of it, as MultiServer would before a `Connect`:
 * its `RoomInfo`, and each `GetDataPackage` from the kept data packages. Its frames are checked as the relay checks
 * them (the limits before a `Connect`, [checkClientFrame]); a `Connect` that doesn't pass is answered as there, and
 * the first that passes ends the wait: it is given back, for MultiServer once the game server is awake. `null`: the
 * client left, or was closed (a frame against the rules, a kept file that can't be read).
 */
private suspend fun DefaultWebSocketServerSession.lobby(
  kept: KeptRoom.Kept,
  passwords: suspend () -> Map<String, String>,
  locked: suspend () -> Set<String>,
  limits: Limits,
  before: BeforeConnect,
  clients: SlotClients?,
  address: String,
): Pending? {
  send(Frame.Text(kept.roomInfoFrame()))
  for (frame in incoming) {
    if (++before.frames > limits.framesBeforeConnect) {
      logger.info("Closed {}: {} frames without a Connect", address, before.frames)
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connect to a slot first"))
      return null
    }
    if (frame !is Frame.Text) {
      logger.info("Closed {}: binary frame", address)
      close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Archipelago protocol expected"))
      return null
    }
    val text = frame.readText()
    val since = clients?.mark() ?: 0L
    val asks = when (val verdict = checkClientFrame(text, passwords, locked, before)) {
      is Verdict.Join -> return Pending(text, verdict, since)
      is Verdict.Refuse -> {
        refuse(verdict, address)
        continue
      }
      is Verdict.Close -> {
        refuse(verdict, address)
        return null
      }
      Verdict.Forward -> text
      is Verdict.Only -> verdict.text ?: continue
    }
    // Only what MultiServer serves a client outside a slot is left: the data package requests (and Connects, which
    // made it a Join or a Refuse)
    for (packet in Json.parseToJsonElement(asks) as JsonArray) {
      if (((packet as JsonObject)["cmd"] as? JsonPrimitive)?.content != "GetDataPackage") continue
      val games = (packet["games"] as? JsonArray).orEmpty()
        .mapNotNull { game -> (game as? JsonPrimitive)?.takeIf { it.isString }?.content }
      val answer = kept.dataPackage(games)
      if (answer == null) {
        logger.warn("Closed {}: a kept data package can't be read", address)
        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Connection ended: connect again"))
        return null
      }
      send(Frame.Text(answer))
    }
  }
  return null
}

/** A `Connect` that didn't pass: the client is told why ([Verdict.Why.answer]). */
private suspend fun DefaultWebSocketServerSession.refuse(verdict: Verdict.Refuse, address: String) {
  logger.info("Refused {} as {}: {}", address, printable(verdict.slot), verdict.why.logged)
  send(Frame.Text(verdict.why.answer))
}

/** Not the Archipelago protocol: the connection is closed. */
private suspend fun DefaultWebSocketServerSession.refuse(verdict: Verdict.Close, address: String) {
  logger.info("Closed {}: {}", address, verdict.reason)
  close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Archipelago protocol expected"))
}

/**
 * Relays one game client's connection to MultiServer, both ways, until either side closes. Frames from MultiServer
 * pass unchanged (but its `RoomInfo`, [askingForPassword]); frames from the client are checked first
 * ([checkClientFrame]). Until its `Connect` passes, the client has [limits]' time since it [arrived], frames and
 * packets ([ticket]: its place among the connections, [before]: what it sent so far); then it is in a slot.
 *
 * [first]: the `Connect` that passed while the gateway answered the client from what it [kept] of the sleeping game
 * server: passed on first, and MultiServer's own `RoomInfo` isn't, the client has one ([replayedRoomInfo]). Every
 * `Connect` that passes reaches MultiServer with its [roomPassword] in place of the slot's.
 */
private suspend fun DefaultWebSocketServerSession.relay(
  http: HttpClient,
  upstreamUrl: String,
  passwords: suspend () -> Map<String, String>,
  locked: suspend () -> Set<String>,
  ticket: ConnectionLimits.Ticket,
  limits: Limits,
  arrived: TimeMark,
  joined: suspend (slot: String) -> (() -> Unit)?,
  clients: SlotClients?,
  address: String,
  before: BeforeConnect,
  first: Pending?,
  kept: KeptRoom.Kept?,
  roomPassword: () -> String?,
) {
  val client = this
  // Every slot it passed a Connect for: MultiServer keeps a client in its slot when a later Connect is refused there
  val slots = mutableSetOf<String>()
  // In a slot that counts as a player's: called when it leaves (or connects again)
  var left: (() -> Unit)? = null
  var opened = false

  /** What of a client's frame [text] goes to MultiServer, after its [verdict] ([since]: [SlotClients.mark] before). */
  suspend fun admit(text: String, verdict: Verdict, since: Long): Frame? = when (verdict) {
    Verdict.Forward -> Frame.Text(text)
    is Verdict.Only -> verdict.text?.let { Frame.Text(it) }
    is Verdict.Join -> {
      ticket.joined()
      left?.invoke()
      left = runCatching { joined(verdict.slot) }.getOrNull()
      slots += verdict.slot
      // Known as the slot's, so taking someone off it can close their connection; taken off since the check began:
      // closed now, with the password it had
      val closed = clients?.add(verdict.slot, client, since)
      if (closed == null) {
        // The one place a Connect is changed: MultiServer gets the room's password, never a slot's
        Frame.Text(roomPassword()?.let { withRoomPassword(text, it) } ?: text)
      } else {
        logger.info("Closed {} as {}: taken off the slot while connecting", address, printable(verdict.slot))
        client.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, closed))
        null
      }
    }
    is Verdict.Refuse -> {
      client.refuse(verdict, address)
      null
    }
    is Verdict.Close -> {
      client.refuse(verdict, address)
      null
    }
  }

  try {
    http.webSocket(upstreamUrl) {
      opened = true
      val upstream = this
      coroutineScope {
        // The room asks for a password: the slot's (see askingForPassword). MultiServer sends its RoomInfo once, first
        // of all: the frames after it aren't searched. A client the gateway answered has the kept one already
        var asked = false
        val down = launch {
          pump(from = upstream, to = client) { frame ->
            if (asked || frame !is Frame.Text) return@pump frame
            val text = frame.readText()
            if (kept != null) {
              asked = true
              return@pump replayedRoomInfo(text, kept.roomInfo)?.let { Frame.Text(it) }
            }
            val asking = askingForPassword(text)
            asked = asking !== text
            if (asked) Frame.Text(asking) else frame
          }
        }
        // A connection that never gets into a slot doesn't stay
        val waiting = launch {
          delay(limits.connectWithin - arrived.elapsedNow())
          if (!ticket.joined) {
            logger.info("Closed {}: no Connect within {}", address, limits.connectWithin)
            client.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "No Connect: connect again when you are ready"))
          }
        }
        first?.let { pending -> admit(pending.text, pending.verdict, pending.since)?.let { upstream.send(it) } }
        val up = launch {
          pump(from = client, to = upstream) { frame ->
            val inSlot = ticket.joined
            if (!inSlot && ++before.frames > limits.framesBeforeConnect) {
              logger.info("Closed {}: {} frames without a Connect", address, before.frames)
              client.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connect to a slot first"))
              return@pump null
            }
            when (frame) {
              is Frame.Text -> {
                // Decoded once; what was checked is what MultiServer gets (a Connect with the room's password)
                val text = frame.readText()
                val since = clients?.mark() ?: 0L
                admit(text, checkClientFrame(text, passwords, locked, before.takeIf { !inSlot }), since)
              }
              // Archipelago clients send text only; MultiServer would parse binary frames too, so they never get there
              is Frame.Binary -> {
                logger.info("Closed {}: binary frame", address)
                client.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Archipelago protocol expected"))
                null
              }
              else -> null
            }
          }
        }
        // Whichever side ends first ends the other
        select {
          down.onJoin {}
          up.onJoin {}
        }
        down.cancel()
        up.cancel()
        waiting.cancel()
      }
      upstream.close()
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    if (!opened) {
      logger.warn("MultiServer not reachable for {}: {}", address, e.message ?: e::class.simpleName)
      client.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Game server not reachable"))
    } else {
      // Mostly the client's own end: a frame over its limit, or gone while a frame was sent to it
      logger.info("Ended {}: {}", address, e.message ?: e::class.simpleName)
      runCatching { client.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Connection ended: connect again")) }
    }
  } finally {
    left?.invoke()
    slots.forEach { clients?.remove(it, client) }
  }
}

/** A session's game clients in a slot, by slot: closed together when someone is taken off the slot. */
class SlotClients {
  private val bySlot = HashMap<String, MutableSet<DefaultWebSocketSession>>()

  /** Each slot's last [close]: its number among them, and its reason. */
  private val closed = HashMap<String, Pair<Long, String>>()
  private var closes = 0L

  /** The [close]s so far: given to [add] by a client that is about to be checked. */
  fun mark(): Long = synchronized(this) { closes }

  /**
   * Files [client] under [slot]; `null` when it is, else the reason [slot] was closed with since [since] (its
   * [mark] before its `Connect` was checked, maybe with the password from before): then it is closed by the caller.
   */
  fun add(slot: String, client: DefaultWebSocketSession, since: Long = mark()): String? = synchronized(this) {
    val last = closed[slot]
    if (last != null && last.first > since) return last.second
    bySlot.getOrPut(slot) { mutableSetOf() }.add(client)
    null
  }

  fun remove(slot: String, client: DefaultWebSocketSession) {
    synchronized(this) { bySlot[slot]?.remove(client) }
  }

  /** Closes every connection in [slot] ([reason] to the client), also one still being let in; how many there were. */
  suspend fun close(slot: String, reason: String): Int {
    val now = synchronized(this) {
      closed[slot] = ++closes to reason
      bySlot.remove(slot).orEmpty().toList()
    }
    now.forEach { runCatching { it.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason)) } }
    return now.size
  }
}

/** Sends every data frame of [from] on to [to], after [map] (which may drop it by returning `null`). */
private suspend fun pump(from: DefaultWebSocketSession, to: DefaultWebSocketSession, map: suspend (Frame) -> Frame?) {
  for (frame in from.incoming) {
    // A fresh frame of the same bytes: the received one may still carry compression flags of its own connection
    val copy = when (frame) {
      is Frame.Text -> Frame.Text(true, frame.data)
      is Frame.Binary -> Frame.Binary(true, frame.data)
      else -> continue
    }
    map(copy)?.let { to.send(it) }
  }
}
