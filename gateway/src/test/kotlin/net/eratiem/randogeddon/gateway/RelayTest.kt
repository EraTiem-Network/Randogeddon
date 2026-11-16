package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** The game clients' way in, over real sockets: the gateway's own WebSocket settings in front of a stand-in MultiServer. */
class RelayTest {
  private val passwords = mapOf("Alice-HK" to "s3cr3t!")

  /** What the stand-in MultiServer got. */
  private val seen = CopyOnWriteArrayList<String>()

  private fun connect(name: String, password: String) = """[{"cmd":"Connect","name":"$name","password":"$password","game":"","tags":["TextOnly"]}]"""

  /** The gateway on a port of its own, relaying to a stand-in MultiServer that takes everyone in (as it does a group). */
  private fun gateway(limits: Limits = Limits(), deflate: Boolean = false, block: suspend (url: String, client: HttpClient) -> Unit) = runBlocking {
    val multiServer = embeddedServer(Netty, port = 0) {
      install(WebSockets) { maxFrameSize = Long.MAX_VALUE }
      routing {
        webSocket("/") {
          send(Frame.Text("""[{"cmd":"RoomInfo"}]"""))
          for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            seen += text
            if ("\"Connect\"" in text) send(Frame.Text("""[{"cmd":"Connected"}]"""))
          }
        }
      }
    }.start()
    val client = HttpClient(CIO) {
      install(ClientWebSockets) {
        maxFrameSize = Long.MAX_VALUE
        if (deflate) extensions { install(WebSocketDeflateExtension) }
      }
    }
    val upstream = "ws://localhost:${multiServer.engine.resolvedConnectors().first().port}/"
    val connections = ConnectionLimits(limits)
    val server = embeddedServer(Netty, port = 0) {
      gameClientWebSockets()
      routing { webSocket("/") { serve(client, upstream, { passwords }, { emptySet() }, connections) } }
    }.start()
    try {
      block("ws://localhost:${server.engine.resolvedConnectors().first().port}/", client)
    } finally {
      client.close()
      server.stop(100, 500)
      multiServer.stop(100, 500)
    }
  }

  private suspend fun DefaultClientWebSocketSession.next(): String = withTimeout(5.seconds) { (incoming.receive() as Frame.Text).readText() }

  /** Why the gateway closed the connection (waits for it). */
  private suspend fun DefaultClientWebSocketSession.closed(): CloseReason? = withTimeout(10.seconds) { closeReason.await() }

  @Test
  fun `an item link's group gets no further than the gateway`() = gateway { url, client ->
    client.webSocket(url) {
      assertEquals("""[{"cmd":"RoomInfo"}]""", next())
      // MultiServer would take this in: a group's name, no password, no game
      send(Frame.Text(connect("Everyone", "")))
      assertEquals(REFUSAL_UNKNOWN, next())
      assertTrue(seen.isEmpty(), "MultiServer saw $seen")
      // A slot with its password still gets in
      send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
      assertEquals("""[{"cmd":"Connected"}]""", next())
      assertEquals(listOf(connect("Alice-HK", "s3cr3t!")), seen.toList())
    }
  }

  @Test
  fun `a frame larger than a client's may be closes the connection`() = gateway { url, client ->
    client.webSocket(url) {
      send(Frame.Text("[" + " ".repeat(2 * CLIENT_FRAME_LIMIT) + "]"))
      closed()
    }
    assertTrue(seen.isEmpty(), "MultiServer saw a frame of ${seen.firstOrNull()?.length}")
  }

  @Test
  fun `a small frame that unpacks to a large one closes the connection too`() = gateway(deflate = true) { url, client ->
    client.webSocket(url) {
      // 16 MiB of spaces: a few KB on the wire
      send(Frame.Text("[" + " ".repeat(16 * CLIENT_FRAME_LIMIT) + "]"))
      closed()
    }
    assertTrue(seen.isEmpty(), "MultiServer saw a frame of ${seen.firstOrNull()?.length}")
  }

  @Test
  fun `a connection without a Connect doesn't stay`() = gateway(Limits(connectWithin = 300.milliseconds)) { url, client ->
    client.webSocket(url) {
      assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, closed()?.code)
    }
    // In a slot, it stays
    client.webSocket(url) {
      next()
      send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
      assertEquals("""[{"cmd":"Connected"}]""", next())
      delay(800)
      send(Frame.Text("""[{"cmd":"Sync"}]"""))
      delay(200)
      assertFalse(closeReason.isCompleted)
      assertEquals("""[{"cmd":"Sync"}]""", seen.last())
    }
  }

  @Test
  fun `only a few frames come before the Connect, any number after it`() = gateway(Limits(framesBeforeConnect = 3)) { url, client ->
    client.webSocket(url) {
      repeat(4) { send(Frame.Text("""[{"cmd":"GetDataPackage"}]""")) }
      assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed()?.code)
    }
    assertEquals(3, seen.size)
    seen.clear()
    client.webSocket(url) {
      next()
      send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
      assertEquals("""[{"cmd":"Connected"}]""", next())
      repeat(10) { send(Frame.Text("""[{"cmd":"Sync"}]""")) }
      delay(300)
      assertFalse(closeReason.isCompleted)
      assertEquals(11, seen.size)
    }
  }

  @Test
  fun `only so many connections wait for their Connect at once`() = gateway(Limits(waiting = 1)) { url, client ->
    client.webSocket(url) {
      next()
      // A second one, while this one isn't in a slot
      client.webSocket(url) { assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, closed()?.code) }
      send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
      assertEquals("""[{"cmd":"Connected"}]""", next())
      // In its slot, it makes room
      client.webSocket(url) { assertEquals("""[{"cmd":"RoomInfo"}]""", next()) }
    }
  }
}
