package net.eratiem.randogeddon.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.eratiem.randogeddon.database.SessionEventStore
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionEventsTest {
  private fun packet(text: String) = Json.parseToJsonElement(text) as JsonObject

  @Test
  fun `a chat line is kept cut`() {
    val long = "A".repeat(CLIENT_FRAME_LIMIT)
    val chat = packet("""{"cmd":"PrintJSON","data":[{"text":"Alice: $long"}],"type":"Chat","team":0,"slot":1,"message":"$long"}""")
    assertTrue(kept(chat))
    val stored = keptForm(chat)
    assertEquals(KEPT_TEXT, stored["message"]!!.jsonPrimitive.content.length)
    assertEquals("Alice: " + "A".repeat(KEPT_TEXT - 7), stored["data"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    assertEquals("1", stored["slot"]!!.jsonPrimitive.content)
    assertEquals("Chat", stored["type"]!!.jsonPrimitive.content)
    assertTrue(stored.toString().length < 3 * KEPT_TEXT)
    // A real one as it is
    val short = packet("""{"cmd":"PrintJSON","data":[{"text":"Alice: gg"}],"type":"Chat","team":0,"slot":1,"message":"gg"}""")
    assertEquals(short, keptForm(short))
  }

  @Test
  fun `a link's Bounced keeps what the tracker reads, cut`() {
    val death = packet(
      """{"cmd":"Bounced","tags":["DeathLink"],"data":{"time":1763577600.25,"source":"Alice","cause":"Alice fell"}}""",
    )
    assertEquals(death, keptForm(death))
    val junk = "x".repeat(CLIENT_FRAME_LIMIT)
    val flood = packet(
      """{"cmd":"Bounced","games":["$junk"],"tags":["DeathLink","$junk"],"slots":[1],""" +
        """"data":{"time":1,"source":"Alice","cause":"$junk","extra":"$junk","trap_name":7}}""",
    )
    assertTrue(kept(flood))
    val stored = keptForm(flood)
    assertEquals(
      packet("""{"cmd":"Bounced","tags":["DeathLink"],"data":{"time":1,"source":"Alice","cause":"${junk.take(KEPT_TEXT)}","trap_name":7}}"""),
      stored,
    )
  }

  @Test
  fun `what MultiServer makes itself is kept as it is`() {
    val send = packet(
      """{"cmd":"PrintJSON","type":"ItemSend","receiving":2,"item":{"item":1,"location":2,"player":1,"flags":1},""" +
        """"data":[{"text":"${"y".repeat(5000)}"}]}""",
    )
    assertEquals(send, keptForm(send))
  }

  @Test
  fun `a game server's stop is written before the shutdown goes on, and the start's stops directly`() = runBlocking {
    // A database that takes its time
    val rows = CopyOnWriteArrayList<String>()
    val scope = CoroutineScope(SupervisorJob())
    val http = HttpClient(CIO)
    try {
      val events = SessionEvents({ id, _, kind, _ -> delay(200); rows += "$id $kind" }, http, scope)
      // No room.json: the listener has no slot to connect as and only tries again, nothing is reached
      events.started("brave-otter", 1, RoomJson(Files.createTempDirectory("room").resolve("room.json")))
      events.ended("brave-otter")
      events.flush()
      assertEquals(listOf("brave-otter ${SessionEventStore.STOP}"), rows.toList())
      // Nothing left to wait for; a stop at the gateway's start is written when it returns
      events.flush()
      events.stopped("quiet-fox")
      assertEquals(listOf("brave-otter ${SessionEventStore.STOP}", "quiet-fox ${SessionEventStore.STOP}"), rows.toList())
    } finally {
      scope.cancel()
      http.close()
    }
  }
}
