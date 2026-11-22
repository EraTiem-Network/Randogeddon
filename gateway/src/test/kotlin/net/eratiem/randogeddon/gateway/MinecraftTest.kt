package net.eratiem.randogeddon.gateway

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MinecraftTest {
  private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { z -> files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
  }.toByteArray()

  @Test
  fun `a seed's Minecraft Dig slots come from its patches, each with its data json`() {
    val patch = zip("data.json" to """{"player_name":"Erin-Dig","client_version":16}""".toByteArray(), "archipelago.json" to "{}".toByteArray())
    val seed = Files.createTempFile("AP_123", ".zip")
    seed.writeBytes(
      zip(
        "AP_123.archipelago" to byteArrayOf(1),
        "AP_123_P7_Erin-Dig.apmcdig" to patch,
        "AP_123_P2_Alice_HK.apmcdig" to zip("data.json" to "{}".toByteArray()),
        "AP_123_P3_Bob.apz5" to byteArrayOf(2),
        // Without its data.json: not a slot to host
        "AP_123_P4_Broken.apmcdig" to zip("other.txt" to byteArrayOf(3)),
      ),
    )
    val slots = digSlots(seed)
    assertEquals(listOf(2 to "Alice_HK", 7 to "Erin-Dig"), slots.map { it.number to it.name })
    assertEquals("""{"player_name":"Erin-Dig","client_version":16}""", slots.last().data)
    Files.delete(seed)
  }

  @Test
  fun `a patch whose data json is too long is no slot, and isn't read whole`() {
    val seed = Files.createTempFile("AP_123", ".zip")
    seed.writeBytes(
      zip(
        "AP_123_P1_Huge.apmcdig" to zip("data.json" to ByteArray(MAX_DIG_DATA + 1) { 'x'.code.toByte() }),
        "AP_123_P2_Fits.apmcdig" to zip("data.json" to ByteArray(MAX_DIG_DATA) { 'y'.code.toByte() }),
      ),
    )
    assertEquals(listOf("Fits"), digSlots(seed).map { it.name })
    Files.delete(seed)
  }

  @Test
  fun `a deleted session's servers are its own, not those of a session whose ID starts the same`() {
    val keys = listOf("abc-p1", "abc-p12", "abc-party-p2", "abc-p1-p1", "abcd-p1", "abc-p", "xabc-p1")
    assertEquals(listOf("abc-p1", "abc-p12"), serverKeysOf("abc", keys))
    assertEquals(listOf("abc-p1-p1"), serverKeysOf("abc-p1", keys))
    assertEquals(listOf("abc-party-p2"), serverKeysOf("abc-party", keys))
    assertEquals(emptyList(), serverKeysOf("ab", keys))
  }

  @Test
  fun `a server's session comes from its key`() {
    assertEquals("abc", sessionOfKey("abc-p1"))
    assertEquals("abc-p1", sessionOfKey("abc-p1-p12"))
    assertEquals("abc-party", sessionOfKey("abc-party-p2"))
    assertNull(sessionOfKey("abc-p"))
    assertNull(sessionOfKey("abc"))
    assertNull(sessionOfKey("-p1"))
  }

  /** Data directory with session [id]'s seed, whose only Minecraft Dig slot is [slot] (P[number]). */
  private fun session(data: Path, id: String, number: Int, slot: String) {
    val dir = data.resolve("sessions").resolve(id).createDirectories()
    dir.resolve("AP_seed$number.zip").writeBytes(zip("AP_seed${number}_P${number}_$slot.apmcdig" to zip("data.json" to "{}".toByteArray())))
  }

  /** What the made-up minecraft service was asked: each call's method, path and `Authorization`. */
  private class Asked {
    val calls = ConcurrentLinkedQueue<String>()
    val commands = ConcurrentLinkedQueue<String>()

    fun note(call: ApplicationCall, method: String) {
      calls += "$method ${call.request.uri} ${call.request.headers["Authorization"]}"
    }
  }

  @Test
  fun `without its own token the minecraft service is asked nothing, and the panel says why`() = testApplication {
    val asked = Asked()
    routing { get("/servers") { asked.note(call, "GET"); call.respondText("""{"servers":[]}""", ContentType.Application.Json) } }
    val data = Files.createTempDirectory("minecraft")
    session(data, "brave-otter", 7, "Erin-Dig")
    val scope = CoroutineScope(SupervisorJob())
    val minecraft = Minecraft(
      client, "http://localhost", "", SessionDirs(data), eula = { true }, passwords = { mapOf("Erin-Dig" to "pw") },
      idleMinutes = { 30 }, rules = { SleepRules(hosted = setOf("Erin-Dig"), direct = setOf("Erin-Dig")) }, scope = scope,
    )
    assertFalse(minecraft.enabled)
    val view = minecraft.servers("brave-otter")
    assertFalse(view.hosting)
    assertEquals(listOf(NO_MINECRAFT_TOKEN), view.servers.map { it.problem })
    assertEquals(NO_MINECRAFT_TOKEN, minecraft.start("brave-otter", "Erin-Dig"))
    assertTrue(minecraft.log("brave-otter", 0).logs.values.all { it.lines.isEmpty() })
    // Nothing runs in the background either
    assertEquals(emptyList(), asked.calls.toList())
    scope.cancel()
  }

  @Test
  fun `the minecraft service gets its own token, and a slot's password only for a server the gateway hosts`() = testApplication {
    val asked = Asked()
    routing {
      // Another session's server first, and named as this session's: code in a hosted server could answer anything
      get("/servers") {
        asked.note(call, "GET")
        call.respondText(
          """{"servers":[
            {"key":"other-fox-p2","status":"running","players":["Mallory"],"relay":39001,"session":"brave-otter"},
            {"key":"brave-otter-p7","status":"running","players":["Erin"],"relay":39000,"session":"other-fox"}
          ]}""",
          ContentType.Application.Json,
        )
      }
      put("/servers/{key}") { asked.note(call, "PUT"); call.respondText("{}", ContentType.Application.Json) }
      post("/servers/{key}/start") { asked.note(call, "POST"); call.respondText("{}", ContentType.Application.Json) }
      post("/servers/{key}/command") {
        asked.note(call, "POST")
        asked.commands += "${call.parameters["key"]} ${call.receiveText()}"
        call.respond(HttpStatusCode.NoContent)
      }
    }
    val data = Files.createTempDirectory("minecraft")
    session(data, "brave-otter", 7, "Erin-Dig")
    // Its Minecraft Dig slot is hosted, but not here: its Session-Managers run its server
    session(data, "other-fox", 2, "Mal-Dig")
    val passwordsOf = ConcurrentLinkedQueue<String>()
    val scope = CoroutineScope(SupervisorJob())
    val minecraft = Minecraft(
      client, "http://localhost", "mc-token", SessionDirs(data), eula = { true },
      passwords = { id -> passwordsOf += id; mapOf("Erin-Dig" to "erin-pw", "Mal-Dig" to "mal-pw") },
      idleMinutes = { 30 },
      rules = { id ->
        if (id == "brave-otter") SleepRules(hosted = setOf("Erin-Dig"), direct = setOf("Erin-Dig")) else SleepRules(hosted = setOf("Mal-Dig"))
      },
      scope = scope,
    )
    assertTrue(minecraft.enabled)
    assertNull(minecraft.start("brave-otter", "Erin-Dig"))
    // In real time (the test's own clock may skip ahead)
    withContext(Dispatchers.Default) { withTimeout(5.seconds) { while (asked.commands.isEmpty()) delay(20) } }
    assertEquals(listOf("brave-otter-p7 {\"line\":\"connect 127.0.0.1 39000 \\\"erin-pw\\\"\"}"), asked.commands.toList())
    assertEquals(listOf("brave-otter"), passwordsOf.toList().distinct())
    // Every call with the minecraft service's own token
    assertTrue(asked.calls.isNotEmpty())
    assertTrue(asked.calls.all { it.endsWith(" Bearer mc-token") }, asked.calls.toString())
    scope.cancel()
  }
}
