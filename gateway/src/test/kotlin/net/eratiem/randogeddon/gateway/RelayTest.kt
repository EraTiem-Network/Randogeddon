package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.eratiem.randogeddon.shared.AddressRange
import net.eratiem.randogeddon.shared.trustedProxies
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** The game clients' way in, over real sockets: the gateway's own WebSocket settings in front of a stand-in MultiServer. */
class RelayTest {
  private val passwords = mapOf("Alice-HK" to "s3cr3t!", "Bob-Yacht" to "pw")

  /** What the stand-in MultiServer got. */
  private val seen = CopyOnWriteArrayList<String>()

  private fun connect(name: String, password: String) = """[{"cmd":"Connect","name":"$name","password":"$password","game":"","tags":["TextOnly"]}]"""

  /**
   * The gateway on a port of its own, relaying to a stand-in MultiServer that takes everyone in (as it does a group).
   * [wake] runs before MultiServer's address is known (a sleeping game server starts); [clients] and [joined] as the
   * gateway gives them.
   */
  private fun gateway(
    limits: Limits = Limits(),
    deflate: Boolean = false,
    clients: SlotClients? = null,
    joined: suspend (String) -> (() -> Unit)? = { null },
    wake: suspend () -> Unit = {},
    trusted: List<AddressRange> = emptyList(),
    roomPassword: () -> String? = { null },
    block: suspend (url: String, client: HttpClient) -> Unit,
  ) = runBlocking {
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
      routing {
        webSocket("/") {
          serve(
            client, { wake(); upstream }, { passwords }, { emptySet() }, connections, joined = joined, clients = clients,
            address = call.gameClientAddress(trusted), roomPassword = roomPassword,
          )
        }
      }
    }.start()
    try {
      block("ws://localhost:${server.engine.resolvedConnectors().first().port}/", client)
    } finally {
      client.close()
      server.stop(100, 500)
      multiServer.stop(100, 500)
    }
  }

  /**
   * The stand-in MultiServer has seen exactly [n] frames: waits for them to arrive (they come through two sockets,
   * a moment after the client sent them or was closed), then a little longer for one too many.
   */
  private suspend fun assertSeen(n: Int) {
    withTimeoutOrNull(5.seconds) { while (seen.size < n) delay(20) }
    delay(150)
    assertEquals(n, seen.size, "MultiServer saw $seen")
  }

  private suspend fun DefaultClientWebSocketSession.next(): String = withTimeout(5.seconds) { (incoming.receive() as Frame.Text).readText() }

  /** Why the gateway closed the connection (waits for it). */
  private suspend fun DefaultClientWebSocketSession.closed(): CloseReason? = withTimeout(10.seconds) { closeReason.await() }

  @Test
  fun `an item link's group gets no further than the gateway`() = gateway { url, client ->
    client.webSocket(url) {
      assertEquals("""[{"cmd":"RoomInfo","password":true}]""", next())
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
  fun `MultiServer gets the room's password in each Connect that passed, never a slot's`() =
    gateway(roomPassword = { "r00m-9f2c4e1a" }) { url, client ->
      client.webSocket(url) {
        next()
        // Refused here, with the wrong slot password: MultiServer sees nothing of it
        send(Frame.Text(connect("Alice-HK", "typo")))
        assertEquals(REFUSAL, next())
        send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
        assertEquals("""[{"cmd":"Connected"}]""", next())
        // Another slot's Connect later, in a frame with more: that one too, the rest as it came
        val batch = """[{"cmd":"Sync"},""" + connect("Bob-Yacht", "pw").removePrefix("[")
        send(Frame.Text(batch))
        assertEquals("""[{"cmd":"Connected"}]""", next())
        assertSeen(2)
        val room = "r00m-9f2c4e1a"
        assertEquals(
          listOf(connect("Alice-HK", room), """[{"cmd":"Sync"},""" + connect("Bob-Yacht", room).removePrefix("[")),
          seen.toList(),
        )
        assertTrue(seen.none { "s3cr3t!" in it || "\"pw\"" in it }, "MultiServer saw $seen")
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
  fun `a connection without a Connect doesn't stay`() = gateway(Limits(connectWithin = 1.seconds)) { url, client ->
    client.webSocket(url) {
      assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, closed()?.code)
    }
    // In a slot, it stays
    client.webSocket(url) {
      next()
      send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
      assertEquals("""[{"cmd":"Connected"}]""", next())
      // Well past the time a connection without a Connect gets
      delay(1500)
      send(Frame.Text("""[{"cmd":"Sync"}]"""))
      assertSeen(2)
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
    assertSeen(3)
    seen.clear()
    client.webSocket(url) {
      next()
      send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
      assertEquals("""[{"cmd":"Connected"}]""", next())
      repeat(10) { send(Frame.Text("""[{"cmd":"Sync"}]""")) }
      assertSeen(11)
      assertFalse(closeReason.isCompleted)
    }
  }

  @Test
  fun `before the Connect only what a client needs then reaches MultiServer`() = gateway { url, client ->
    client.webSocket(url) {
      next()
      // MultiServer ignores these from a client outside a slot: they don't get there at all
      send(Frame.Text("""[{"cmd":"Say","text":"hello"}]"""))
      send(Frame.Text("""[{"cmd":"GetDataPackage","games":["Hollow Knight"]},{"cmd":"LocationChecks","locations":[1]}]"""))
      send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
      assertEquals("""[{"cmd":"Connected"}]""", next())
      // In the slot, everything
      send(Frame.Text("""[{"cmd":"Say","text":"hello"}]"""))
      assertSeen(3)
      assertEquals(
        listOf("""[{"cmd":"GetDataPackage","games":["Hollow Knight"]}]""", connect("Alice-HK", "s3cr3t!"), """[{"cmd":"Say","text":"hello"}]"""),
        seen.toList(),
      )
    }
  }

  @Test
  fun `a packet without a command, or a frame full of data package requests, closes before MultiServer sees it`() = gateway { url, client ->
    client.webSocket(url) {
      next()
      // MultiServer would log it whole
      send(Frame.Text("""[{"a":"${"A".repeat(1000)}"}]"""))
      assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, closed()?.code)
    }
    client.webSocket(url) {
      next()
      // Each one the whole data package, encoded by MultiServer
      send(Frame.Text((1..1000).joinToString(",", "[", "]") { """{"cmd":"GetDataPackage"}""" }))
      assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, closed()?.code)
    }
    assertSeen(0)
  }

  @Test
  fun `a real client's opening passes as it is`() = gateway { url, client ->
    // Archipelago's CommonClient: the data package of the games it lacks, then its Connect with the version
    val dataPackage = """[{"cmd":"GetDataPackage","games":["Archipelago","Hollow Knight"]}]"""
    val connect = """[{"cmd":"Connect","password":"s3cr3t!","name":"Alice-HK","version":{"major":0,"minor":6,"build":8,"class":"Version"},""" +
      """"tags":["AP"],"items_handling":7,"uuid":"6c1f","game":"Hollow Knight","slot_data":true}]"""
    client.webSocket(url) {
      assertEquals("""[{"cmd":"RoomInfo","password":true}]""", next())
      send(Frame.Text(dataPackage))
      // A client that typed a wrong password first
      send(Frame.Text(connect.replace("s3cr3t!", "typo")))
      assertEquals(REFUSAL, next())
      send(Frame.Text(connect))
      assertEquals("""[{"cmd":"Connected"}]""", next())
      send(Frame.Text("""[{"cmd":"Sync"},{"cmd":"LocationChecks","locations":[1,2]}]"""))
      assertSeen(3)
      assertEquals(listOf(dataPackage, connect, """[{"cmd":"Sync"},{"cmd":"LocationChecks","locations":[1,2]}]"""), seen.toList())
    }
  }

  @Test
  fun `taking someone off a slot closes them, also under a slot they were in before`() {
    val clients = SlotClients()
    gateway(clients = clients) { url, client ->
      client.webSocket(url) {
        next()
        send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
        assertEquals("""[{"cmd":"Connected"}]""", next())
        // A Connect for another slot: MultiServer may refuse it and keep the client in the first
        send(Frame.Text(connect("Bob-Yacht", "pw")))
        assertEquals("""[{"cmd":"Connected"}]""", next())
        assertEquals(1, clients.close("Alice-HK", "Your access to this slot changed"))
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed()?.code)
      }
    }
  }

  @Test
  fun `taking someone off a slot while they connect closes them too`() {
    val clients = SlotClients()
    // Between the check and the filing: the gateway asks the database what the slot is
    gateway(clients = clients, joined = { slot -> clients.close(slot, "Your access to this slot changed"); null }) { url, client ->
      client.webSocket(url) {
        next()
        send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed()?.code)
      }
      assertSeen(0)
    }
  }

  @Test
  fun `a connection that waits for its game server to wake counts as waiting`() {
    val awake = CompletableDeferred<Unit>()
    gateway(Limits(waiting = 1), wake = { awake.await() }) { url, client ->
      client.webSocket(url) {
        // Its place is taken while the game server starts
        delay(300)
        client.webSocket(url) { assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, closed()?.code) }
        awake.complete(Unit)
        assertEquals("""[{"cmd":"RoomInfo","password":true}]""", next())
      }
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
      client.webSocket(url) { assertEquals("""[{"cmd":"RoomInfo","password":true}]""", next()) }
    }
  }

  /** A connection as if through Traefik, for a client at [address]. */
  private suspend fun HttpClient.from(url: String, address: String, block: suspend DefaultClientWebSocketSession.() -> Unit) =
    webSocket(url, request = { header(HttpHeaders.XForwardedFor, address) }) { block() }

  /** Refused for its address: closed before anything else. */
  private suspend fun DefaultClientWebSocketSession.refusedForItsAddress() {
    val reason = closed()
    assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
    assertTrue("your address" in reason?.message.orEmpty(), "closed with $reason")
  }

  @Test
  fun `one address holds only so many places before its Connect, an IPv6 network as one`() =
    // The test's own connections come from here: the proxy, whose forwarded address is believed
    gateway(Limits(perAddress = 2), trusted = trustedProxies("127.0.0.1, ::1")) { url, client ->
      client.from(url, "203.0.113.7") {
        next()
        client.from(url, "203.0.113.7") {
          next()
          client.from(url, "203.0.113.7") { refusedForItsAddress() }
          // Another address is still served
          client.from(url, "203.0.113.8") { assertEquals("""[{"cmd":"RoomInfo","password":true}]""", next()) }
          // In its slot, one makes room for the next one from there
          send(Frame.Text(connect("Alice-HK", "s3cr3t!")))
          assertEquals("""[{"cmd":"Connected"}]""", next())
          client.from(url, "203.0.113.7") { assertEquals("""[{"cmd":"RoomInfo","password":true}]""", next()) }
        }
      }
      client.from(url, "2001:db8:1:2::1") {
        next()
        client.from(url, "2001:db8:1:2::2") {
          next()
          client.from(url, "2001:db8:1:2:ffff::3") { refusedForItsAddress() }
          client.from(url, "2001:db8:1:3::1") { assertEquals("""[{"cmd":"RoomInfo","password":true}]""", next()) }
        }
      }
    }

  @Test
  fun `the forwarded address counts only from a trusted proxy`() =
    // Not the test's own address: what it forwards is made up, and every connection is the peer's
    gateway(Limits(perAddress = 2), trusted = trustedProxies("10.0.0.0/8")) { url, client ->
      client.from(url, "203.0.113.7") {
        next()
        client.from(url, "203.0.113.8") {
          next()
          client.from(url, "203.0.113.9") { refusedForItsAddress() }
        }
      }
    }
}
