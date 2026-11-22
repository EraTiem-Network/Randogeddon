package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.Application
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
 * One game client's connection: its place among the [connections] (only so many of everyone aren't in a slot yet;
 * none free: closed), then relayed to MultiServer at [upstreamUrl] ([relay]). [upstreamUrl] is asked once the client
 * has its place, so the wake of a sleeping game server counts against the limits of a connection before its
 * `Connect` (the places, the time); `null`: there's none, and it closed the connection with its reason. Once the
 * client is in a slot, [joined] hears of it; what that answers is called when it leaves (`null`: nothing to call, as
 * for a hosted slot).
 */
suspend fun DefaultWebSocketServerSession.serve(
  http: HttpClient,
  upstreamUrl: suspend () -> String?,
  passwords: suspend () -> Map<String, String>,
  locked: suspend () -> Set<String>,
  connections: ConnectionLimits,
  joined: suspend (slot: String) -> (() -> Unit)? = { null },
  clients: SlotClients? = null,
) {
  val address = call.request.origin.remoteHost
  val ticket = connections.enter()
  if (ticket == null) {
    logger.info("Refused {}: too many connections wait for their Connect", address)
    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections: try again in a moment"))
    return
  }
  try {
    val arrived = TimeSource.Monotonic.markNow()
    val limits = connections.limits
    var late = true
    val url = withTimeoutOrNull(limits.connectWithin) { upstreamUrl().also { late = false } }
    if (late) {
      logger.info("Closed {}: no game server within {}", address, limits.connectWithin)
      close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "No Connect: connect again when you are ready"))
    }
    if (url == null) return
    relay(http, url, passwords, locked, ticket, limits, arrived, joined, clients)
  } finally {
    ticket.leave()
  }
}

/**
 * Relays one game client's connection to MultiServer, both ways, until either side closes. Frames from MultiServer
 * pass unchanged (but its `RoomInfo`, [askingForPassword]); frames from the client are checked first
 * ([checkClientFrame]). Until its `Connect` passes, the client has [limits]' time since it [arrived], frames and
 * packets ([ticket]: its place among the connections); then it is in a slot.
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
) {
  val client = this
  val address = call.request.origin.remoteHost
  var framesBefore = 0
  val before = BeforeConnect(limits.packetsBeforeConnect)
  // Every slot it passed a Connect for: MultiServer keeps a client in its slot when a later Connect is refused there
  val slots = mutableSetOf<String>()
  // In a slot that counts as a player's: called when it leaves (or connects again)
  var left: (() -> Unit)? = null
  var opened = false
  try {
    http.webSocket(upstreamUrl) {
      opened = true
      val upstream = this
      coroutineScope {
        // The room asks for a password: the slot's (see askingForPassword). MultiServer sends its RoomInfo once, first
        // of all: the frames after it aren't searched
        var asked = false
        val down = launch {
          pump(from = upstream, to = client) { frame ->
            if (asked || frame !is Frame.Text) return@pump frame
            val text = frame.readText()
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
        val up = launch {
          pump(from = client, to = upstream) { frame ->
            val inSlot = ticket.joined
            if (!inSlot && ++framesBefore > limits.framesBeforeConnect) {
              logger.info("Closed {}: {} frames without a Connect", address, framesBefore)
              client.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connect to a slot first"))
              return@pump null
            }
            when (frame) {
              is Frame.Text -> {
                // Decoded once; what was checked is what MultiServer gets
                val text = frame.readText()
                val since = clients?.mark() ?: 0L
                when (val verdict = checkClientFrame(text, passwords, locked, before.takeIf { !inSlot })) {
                  Verdict.Forward -> Frame.Text(text)
                  is Verdict.Only -> verdict.text?.let { Frame.Text(it) }
                  is Verdict.Join -> {
                    ticket.joined()
                    left?.invoke()
                    left = runCatching { joined(verdict.slot) }.getOrNull()
                    slots += verdict.slot
                    // Known as the slot's, so taking someone off it can close their connection; taken off since the
                    // check began: closed now, with the password it had
                    val closed = clients?.add(verdict.slot, client, since)
                    if (closed == null) {
                      Frame.Text(text)
                    } else {
                      logger.info("Closed {} as {}: taken off the slot while connecting", address, printable(verdict.slot))
                      client.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, closed))
                      null
                    }
                  }
                  is Verdict.Refuse -> {
                    logger.info("Refused {} as {}: {}", address, printable(verdict.slot), verdict.why.logged)
                    client.send(Frame.Text(verdict.why.answer))
                    null
                  }
                  is Verdict.Close -> {
                    logger.info("Closed {}: {}", address, verdict.reason)
                    client.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Archipelago protocol expected"))
                    null
                  }
                }
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
