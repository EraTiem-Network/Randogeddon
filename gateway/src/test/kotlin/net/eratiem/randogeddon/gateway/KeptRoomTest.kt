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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * A sleeping game server's room, kept by the gateway: asked of MultiServer while it runs, answered from while it
 * sleeps, so only a Connect with a slot's password wakes it. Over real sockets, with a stand-in MultiServer.
 */
class KeptRoomTest {
  private val passwords = mapOf("Alice-HK" to "s3cr3t!")
  private val seed = "AP_4711.zip"

  private val roomInfo = """{"cmd":"RoomInfo","password":false,"games":["Archipelago","Hollow Knight"],"tags":["AP"],""" +
    """"version":{"major":0,"minor":6,"build":8,"class":"Version"},"generator_version":{"major":0,"minor":6,"build":8,"class":"Version"},""" +
    """"permissions":{"release":2,"remaining":2,"collect":2},"hint_cost":10,"location_check_points":1,""" +
    """"datapackage_checksums":{"Archipelago":"a1","Hollow Knight":"h1"},"seed_name":"4711","time":1763577600.5}"""
  private val packages = mapOf(
    "Archipelago" to """{"item_name_to_id":{"Nothing":-1},"location_name_to_id":{"Cheat Console":-1},"checksum":"a1"}""",
    "Hollow Knight" to """{"item_name_to_id":{"Grub":17},"location_name_to_id":{"Grub-Crossroads":42},"checksum":"h1"}""",
  )

  /** The RoomInfo the stand-in MultiServer sends each connection (its options may differ from the kept one's). */
  private var serverRoomInfo = roomInfo

  /** The connections MultiServer got, the frames they sent, and the wakes the gateway asked for. */
  private val opened = AtomicInteger()
  private val seen = CopyOnWriteArrayList<String>()
  private val woke = AtomicInteger()

  private fun json(text: String) = Json.parseToJsonElement(text)

  private fun connect(password: String) =
    """[{"cmd":"Connect","name":"Alice-HK","password":"$password","game":"Hollow Knight","tags":["AP"],"items_handling":7,""" +
      """"version":{"major":0,"minor":6,"build":8,"class":"Version"},"uuid":"6c1f"}]"""

  /** MultiServer's answer to a GetDataPackage for [games]: the ones of the room, the others left out. */
  private fun dataPackage(vararg games: String) = buildJsonObject {
    put("cmd", "DataPackage")
    put("data", buildJsonObject { put("games", JsonObject(games.filter { it in packages }.associateWith { json(packages.getValue(it)) })) })
  }.let { JsonArray(listOf(it)) }

  /**
   * The gateway on a port of its own in front of a stand-in MultiServer; [kept] is what it kept of the session's game
   * server (`null`: nothing), read for [seed] as long as the server sleeps.
   */
  private fun gateway(kept: KeptRoom?, block: suspend (url: String, multiServer: String, client: HttpClient) -> Unit) = runBlocking {
    val multiServer = embeddedServer(Netty, port = 0) {
      install(WebSockets) { maxFrameSize = Long.MAX_VALUE }
      routing {
        webSocket("/") {
          opened.incrementAndGet()
          send(Frame.Text("[$serverRoomInfo]"))
          for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            seen += text
            for (packet in json(text).jsonArray.map { it.jsonObject }) {
              when (packet["cmd"]?.jsonPrimitive?.content) {
                "GetDataPackage" -> {
                  val games = packet["games"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                  send(Frame.Text(dataPackage(*games.toTypedArray()).toString()))
                }
                "Connect" -> send(Frame.Text("""[{"cmd":"Connected","team":0,"slot":1}]"""))
              }
            }
          }
        }
      }
    }.start()
    val client = HttpClient(CIO) { install(ClientWebSockets) { maxFrameSize = Long.MAX_VALUE } }
    val upstream = "ws://localhost:${multiServer.engine.resolvedConnectors().first().port}/"
    val connections = ConnectionLimits()
    val server = embeddedServer(Netty, port = 0) {
      gameClientWebSockets()
      routing {
        webSocket("/") {
          serve(
            client, { woke.incrementAndGet(); upstream }, { passwords }, { emptySet() }, connections,
            kept = { kept?.read(seed) },
          )
        }
      }
    }.start()
    try {
      block("ws://localhost:${server.engine.resolvedConnectors().first().port}/", upstream, client)
    } finally {
      client.close()
      server.stop(100, 500)
      multiServer.stop(100, 500)
    }
  }

  /** A session's kept room, as the gateway keeps it while its game server runs. */
  private fun keptRoom(): KeptRoom = runBlocking {
    KeptRoom.of(Files.createTempDirectory("session")).also { room ->
      room.keep(seed, json(roomInfo).jsonObject, JsonObject(packages.mapValues { json(it.value) }))
    }
  }

  private suspend fun DefaultClientWebSocketSession.next(): String = withTimeout(5.seconds) { (incoming.receive() as Frame.Text).readText() }

  private fun JsonObject.withoutTime() = JsonObject(this - "time")

  @Test
  fun `a running game server is asked for its room without a Connect, and it is kept`() = gateway(null) { _, multiServer, client ->
    val room = KeptRoom.of(Files.createTempDirectory("session"))
    room.capture(client, multiServer, seed)
    // One GetDataPackage for every game of the room, nothing else
    assertEquals(listOf(json("""[{"cmd":"GetDataPackage","games":["Archipelago","Hollow Knight"]}]""")), seen.map(::json))
    val kept = assertNotNull(room.read(seed))
    assertEquals(json(roomInfo), kept.roomInfo)
    assertEquals(dataPackage("Hollow Knight"), json(assertNotNull(kept.dataPackage(listOf("Hollow Knight", "Super Metroid")))))
    assertEquals(dataPackage("Archipelago", "Hollow Knight"), json(assertNotNull(kept.dataPackage(listOf("Hollow Knight", "Archipelago")))))
  }

  @Test
  fun `a sleeping game server's client gets its RoomInfo and data package without waking it`() = gateway(keptRoom()) { url, _, client ->
    client.webSocket(url) {
      // As MultiServer sent it, but asking for the slot's password, at the time now
      val info = (json(next()) as JsonArray).single().jsonObject
      assertEquals(JsonObject(json(roomInfo).jsonObject + ("password" to JsonPrimitive(true))).withoutTime(), info.withoutTime())
      assertNotEquals(1763577600.5, info["time"]!!.jsonPrimitive.content.toDouble())
      // Only the games of the room that were asked for, as MultiServer answers
      send(Frame.Text("""[{"cmd":"GetDataPackage","games":["Hollow Knight","Super Metroid"]}]"""))
      assertEquals(dataPackage("Hollow Knight"), json(next()))
      // What MultiServer ignores outside a slot is ignored
      send(Frame.Text("""[{"cmd":"Say","text":"hello"},{"cmd":"GetDataPackage"}]"""))
      assertEquals(dataPackage(), json(next()))
      // A wrong password or slot wakes nothing
      send(Frame.Text(connect("typo")))
      assertEquals(REFUSAL, next())
      send(Frame.Text(connect("s3cr3t!").replace("Alice-HK", "Nobody")))
      assertEquals(REFUSAL_UNKNOWN, next())
    }
    delay(200)
    assertEquals(0, woke.get())
    assertEquals(0, opened.get())
  }

  @Test
  fun `a slot's password wakes it, and the client sees one RoomInfo`() = gateway(keptRoom()) { url, _, client ->
    client.webSocket(url) {
      assertEquals("RoomInfo", (json(next()) as JsonArray).single().jsonObject["cmd"]!!.jsonPrimitive.content)
      assertEquals(0, woke.get())
      send(Frame.Text(connect("s3cr3t!")))
      // MultiServer's own RoomInfo isn't passed on: the client has one
      assertEquals("""[{"cmd":"Connected","team":0,"slot":1}]""", next())
      assertNull(withTimeoutOrNull(500) { incoming.receive() })
      // In its slot, everything goes through
      send(Frame.Text("""[{"cmd":"Sync"}]"""))
      withTimeoutOrNull(5.seconds) { while (seen.size < 2) delay(20) }
    }
    assertEquals(1, woke.get())
    assertEquals(1, opened.get())
    assertEquals(listOf(connect("s3cr3t!"), """[{"cmd":"Sync"}]"""), seen.toList())
  }

  @Test
  fun `options changed since the room was kept come as a RoomUpdate`() {
    serverRoomInfo = roomInfo.replace(""""hint_cost":10""", """"hint_cost":25""").replace(""""time":1763577600.5""", """"time":1763580000.0""")
    gateway(keptRoom()) { url, _, client ->
      client.webSocket(url) {
        next()
        send(Frame.Text(connect("s3cr3t!")))
        assertEquals(json("""[{"cmd":"RoomUpdate","hint_cost":25}]"""), json(next()))
        assertEquals("""[{"cmd":"Connected","team":0,"slot":1}]""", next())
      }
    }
  }

  @Test
  fun `without a kept room the connection wakes it, as before`() = gateway(null) { url, _, client ->
    client.webSocket(url) {
      // MultiServer's own, before the client sent anything
      val info = (json(next()) as JsonArray).single().jsonObject
      assertEquals(JsonPrimitive(true), info["password"])
      assertEquals(1, woke.get())
      send(Frame.Text(connect("s3cr3t!")))
      assertEquals("""[{"cmd":"Connected","team":0,"slot":1}]""", next())
    }
  }

  @Test
  fun `what was kept for another seed is dropped, and a damaged room counts as none`() = runBlocking {
    val dir = Files.createTempDirectory("session")
    val room = KeptRoom.of(dir)
    room.keep(seed, json(roomInfo).jsonObject, JsonObject(packages.mapValues { json(it.value) }))
    assertNotNull(room.read(seed))
    assertNull(room.read(null))
    // The session has a new seed: the old one's room is gone
    assertNull(room.read("AP_4712.zip"))
    assertTrue(!dir.resolve("kept").exists())
    assertNull(room.read(seed))
    // A damaged index, a room whose game has no data package, a game's file cut short
    room.keep(seed, json(roomInfo).jsonObject, JsonObject(packages.mapValues { json(it.value) }))
    dir.resolve("kept/room-info.json").writeText("""{"seed":"$seed","roomInfo":{"cmd":"Room""")
    assertNull(room.read(seed))
    room.keep(seed, json(roomInfo).jsonObject, JsonObject(mapOf("Archipelago" to json(packages.getValue("Archipelago")))))
    assertNull(room.read(seed))
    room.keep(seed, json(roomInfo).jsonObject, JsonObject(packages.mapValues { json(it.value) }))
    val kept = assertNotNull(room.read(seed))
    dir.resolve("kept").listDirectoryEntries("game-*.json").forEach { it.writeText("""{"item_name_to_id":{"Gr""") }
    assertNull(kept.dataPackage(listOf("Hollow Knight")))
  }

  @Test
  fun `MultiServer's RoomInfo after the kept one leaves only what changed`() {
    val sent = json(roomInfo).jsonObject
    assertNull(replayedRoomInfo("[$roomInfo]", sent))
    val later = roomInfo.replace(""""time":1763577600.5""", """"time":1763580000.0""")
    assertNull(replayedRoomInfo("[$later]", sent))
    val released = later.replace(""""release":2""", """"release":1""")
    assertEquals(
      json("""[{"cmd":"RoomUpdate","permissions":{"release":1,"remaining":2,"collect":2}}]"""),
      json(assertNotNull(replayedRoomInfo("[$released]", sent))),
    )
  }
}
