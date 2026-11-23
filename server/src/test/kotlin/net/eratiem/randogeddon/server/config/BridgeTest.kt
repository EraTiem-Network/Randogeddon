package net.eratiem.randogeddon.server.config

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.LoginMethods
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.BridgeDownload
import net.eratiem.randogeddon.shared.BridgeDownloads
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.palettes
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BridgeTest {
  private val alice = User(Uuid.random(), "alice", "hash")
  private val aliceKey = alice.copy(loginMethod = LoginMethods.BRIDGE)

  /** Alice's cookie `a`, her bridge key `rgb_alice`; the store would answer the key for the cookie too. */
  private val check = object : LoginCheck {
    override suspend fun userOf(token: String) = mapOf("a" to alice, "rgb_alice" to aliceKey)[token]
    override fun maySee(user: User, id: String) = id == "brave-otter"
    override fun roleIn(user: User, id: String) = SessionRole.PLAYER.takeIf { id == "brave-otter" }
  }
  private val keyUser: suspend (String) -> User? = { key -> aliceKey.takeIf { key == "rgb_alice" } }

  private fun test(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
    install(ContentNegotiation) { json(ApiJson) }
    routing {
      route("/api") {
        install(RequireLogin) { logins = check }
        get("/account") { call.respondText("settings of ${call.loggedIn.user.name}") }
      }
      route("/bridge") {
        install(RequireBridgeKey) { userOf = keyUser }
        get("/sessions") { call.respondText("sessions of ${call.loggedIn.user.name}") }
        route("/sessions/{id}") {
          install(RequireSessionMember) { logins = check }
          get("/connection") { call.respondText("connection to ${call.parameters["id"]}") }
        }
      }
    }
    block()
  }

  @Test
  fun `a bridge key reaches the bridge's routes`() = test {
    assertEquals("sessions of alice", client.get("/bridge/sessions") { bearerAuth("rgb_alice") }.bodyAsText())
    assertEquals("connection to brave-otter", client.get("/bridge/sessions/brave-otter/connection") { bearerAuth("rgb_alice") }.bodyAsText())
    // Still only the sessions the user is part of
    assertEquals(HttpStatusCode.Forbidden, client.get("/bridge/sessions/quiet-fox/connection") { bearerAuth("rgb_alice") }.status)
  }

  @Test
  fun `without a valid key the bridge's routes answer 401, and a cookie doesn't count`() = test {
    assertEquals(HttpStatusCode.Unauthorized, client.get("/bridge/sessions").status)
    assertEquals(HttpStatusCode.Unauthorized, client.get("/bridge/sessions") { bearerAuth("rgb_ended") }.status)
    val withCookie = client.get("/bridge/sessions") { cookie(SESSION_COOKIE, "a") }
    assertEquals(HttpStatusCode.Unauthorized, withCookie.status)
    assertTrue("BRIDGE_KEY_GONE" in withCookie.bodyAsText())
  }

  @Test
  fun `a bridge key reaches nothing else, not as a bearer and not as the cookie`() = test {
    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/account") { bearerAuth("rgb_alice") }.status)
    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/account") { cookie(SESSION_COOKIE, "rgb_alice") }.status)
    assertEquals("settings of alice", client.get("/api/account") { cookie(SESSION_COOKIE, "a") }.bodyAsText())
  }

  @Test
  fun `the downloads are the builds in the directory, with their version`() {
    val dir = Files.createTempDirectory("bridge")
    assertNull(bridgeDownloads(dir).version)
    dir.resolve("VERSION").writeText("0.12.2\n")
    dir.resolve("randogeddon-bridge-0.12.2-windows-amd64.exe").writeText("exe")
    // One from before 0.19.7, without its version in the name
    dir.resolve("randogeddon-bridge-linux-arm64").writeText("elf")
    dir.resolve("notes.txt").writeText("not a build")
    dir.resolve("randogeddon-bridge-notaversion-linux-amd64").writeText("not a build either")
    val offered = bridgeDownloads(dir)
    assertEquals("0.12.2", offered.version)
    assertEquals(listOf("linux" to "arm64", "windows" to "amd64"), offered.downloads.map { it.os to it.arch })
    assertEquals(3L, offered.downloads.last().bytes)
    // Their checksums as sha256sum writes them (of "elf" and "exe"); nothing else of the directory
    assertEquals(64, bridgeChecksums(dir).lines().first().substringBefore("  ").length)
    assertEquals(
      listOf("randogeddon-bridge-linux-arm64", "randogeddon-bridge-0.12.2-windows-amd64.exe"),
      bridgeChecksums(dir).trim().lines().map { it.substringAfter("  ") },
    )
    val exe = java.security.MessageDigest.getInstance("SHA-256").digest("exe".toByteArray()).joinToString("") { "%02x".format(it) }
    assertEquals("$exe  randogeddon-bridge-0.12.2-windows-amd64.exe", bridgeChecksums(dir).trim().lines().last())
    assertEquals("randogeddon-bridge-0.12.2-windows-amd64.exe", offered.downloads.last().file)
  }

  @Test
  fun `a build is served by its name, and for another version's name or none the build for that system`() {
    val offered = BridgeDownloads(
      "0.12.2",
      listOf(
        BridgeDownload("linux", "arm64", "randogeddon-bridge-0.12.2-linux-arm64", 3),
        BridgeDownload("windows", "amd64", "randogeddon-bridge-0.12.2-windows-amd64.exe", 3),
      ),
    )
    val exe = "randogeddon-bridge-0.12.2-windows-amd64.exe"
    assertEquals(exe, offered.buildFor(exe)?.file)
    // A bridge from before asks by the name without the version, or by its own version's
    assertEquals(exe, offered.buildFor("randogeddon-bridge-windows-amd64.exe")?.file)
    assertEquals(exe, offered.buildFor("randogeddon-bridge-0.11.0-windows-amd64.exe")?.file)
    assertEquals("randogeddon-bridge-0.12.2-linux-arm64", offered.buildFor("randogeddon-bridge-dev-linux-arm64")?.file)
    // No build for that system, or not a build's name at all
    assertNull(offered.buildFor("randogeddon-bridge-macos-arm64"))
    assertNull(offered.buildFor("SHA256SUMS"))
    assertNull(offered.buildFor("../randogeddon-bridge-0.12.2-linux-arm64"))
  }

  @Test
  fun `a palette becomes CSS colours`() {
    val (light, dark) = palettes(Theme.RANDOGEDDON)
    val css = light.css()
    assertTrue(css.values.all { Regex("#[0-9a-f]{6}([0-9a-f]{2})?").matches(it) }, css.toString())
    assertEquals(7, css.getValue("text").length)
    assertTrue(dark.css().getValue("background") != css.getValue("background"))
  }
}
