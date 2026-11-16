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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
 * none free: closed), then relayed to MultiServer at [upstreamUrl] ([relay]).
 */
suspend fun DefaultWebSocketServerSession.serve(
  http: HttpClient,
  upstreamUrl: String,
  passwords: suspend () -> Map<String, String>,
  locked: suspend () -> Set<String>,
  connections: ConnectionLimits,
) {
  val address = call.request.origin.remoteHost
  val ticket = connections.enter()
  if (ticket == null) {
    logger.info("Refused {}: too many connections wait for their Connect", address)
    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections: try again in a moment"))
    return
  }
  try {
    relay(http, upstreamUrl, passwords, locked, ticket, connections.limits)
  } finally {
    ticket.leave()
  }
}

/**
 * Relays one game client's connection to MultiServer, both ways, until either side closes. Frames from MultiServer
 * pass unchanged; frames from the client are checked first ([checkClientFrame]). Until its `Connect` passes, the
 * client has [limits]' time and frames ([ticket]: its place among the connections); then it is in a slot.
 */
private suspend fun DefaultWebSocketServerSession.relay(
  http: HttpClient,
  upstreamUrl: String,
  passwords: suspend () -> Map<String, String>,
  locked: suspend () -> Set<String>,
  ticket: ConnectionLimits.Ticket,
  limits: Limits,
) {
  val client = this
  val address = call.request.origin.remoteHost
  var framesBefore = 0
  try {
    http.webSocket(upstreamUrl) {
      val upstream = this
      coroutineScope {
        val down = launch { pump(from = upstream, to = client) { it } }
        // A connection that never gets into a slot doesn't stay
        val waiting = launch {
          delay(limits.connectWithin)
          if (!ticket.joined) {
            logger.info("Closed {}: no Connect within {}", address, limits.connectWithin)
            client.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "No Connect: connect again when you are ready"))
          }
        }
        val up = launch {
          pump(from = client, to = upstream) { frame ->
            if (!ticket.joined && ++framesBefore > limits.framesBeforeConnect) {
              logger.info("Closed {}: {} frames without a Connect", address, framesBefore)
              client.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connect to a slot first"))
              return@pump null
            }
            when (frame) {
              is Frame.Text -> when (val verdict = checkClientFrame(frame.readText(), passwords, locked)) {
                Verdict.Forward -> frame
                Verdict.Join -> {
                  ticket.joined()
                  frame
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
    logger.warn("MultiServer not reachable for {}: {}", address, e.message ?: e::class.simpleName)
    client.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Game server not reachable"))
  }
}

/** Sends every data frame of [from] on to [to], after [map] (which may drop it by returning `null`). */
private suspend fun pump(from: DefaultWebSocketSession, to: DefaultWebSocketSession, map: suspend (Frame) -> Frame?) {
  for (frame in from.incoming) {
    if (frame !is Frame.Text && frame !is Frame.Binary) continue
    // A fresh frame: the received one may still carry compression flags of its own connection
    val copy = when (frame) {
      is Frame.Text -> Frame.Text(frame.readText())
      else -> Frame.Binary(true, frame.data)
    }
    map(copy)?.let { to.send(it) }
  }
}
