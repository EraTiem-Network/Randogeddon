package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.plugins.origin
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.Relay")

/**
 * Relays one game client's connection to MultiServer, both ways, until either side closes. Frames from MultiServer
 * pass unchanged; frames from the client are checked first ([checkClientFrame]).
 */
suspend fun DefaultWebSocketServerSession.relay(http: HttpClient, upstreamUrl: String, passwords: SlotPasswordCache) {
  val client = this
  val address = call.request.origin.remoteHost
  try {
    http.webSocket(upstreamUrl) {
      val upstream = this
      coroutineScope {
        val down = launch { pump(from = upstream, to = client) { it } }
        val up = launch {
          pump(from = client, to = upstream) { frame ->
            when (frame) {
              is Frame.Text -> when (val verdict = checkClientFrame(frame.readText(), passwords::current)) {
                Verdict.Forward -> frame
                is Verdict.Refuse -> {
                  logger.info("Refused {} as {}: wrong password", address, verdict.slot)
                  client.send(Frame.Text(REFUSAL))
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
