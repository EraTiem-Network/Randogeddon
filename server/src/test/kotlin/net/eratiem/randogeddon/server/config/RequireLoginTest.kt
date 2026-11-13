package net.eratiem.randogeddon.server.config

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
import net.eratiem.randogeddon.shared.ApiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RequireLoginTest {
  private val alice = User(Uuid.random(), "alice", "hash")
  private val mallory = User(Uuid.random(), "Mallory", "hash")
  private val ada = User(Uuid.random(), "Ada", "hash", admin = true)
  private val pete = User(Uuid.random(), "Pete", "hash")

  /** Alice plays in brave-otter, Pete only in quiet-fox, Mallory has a login but is in none; Ada is an admin. */
  private val check = object : LoginCheck {
    override suspend fun userOf(token: String) = mapOf("a" to alice, "m" to mallory, "ada" to ada, "p" to pete)[token]
    private val members = mapOf("brave-otter" to setOf(alice), "quiet-fox" to setOf(pete))
    override fun maySee(user: User, id: String) = user.admin || user in members[id].orEmpty()
  }

  private fun test(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(handled: () -> Boolean) -> Unit) {
    var handled = false
    testApplication {
      install(ContentNegotiation) { json(ApiJson) }
      routing {
        route("/api") {
          install(RequireLogin) { logins = check }
          get("/sessions") {
            handled = true
            call.respondText("sessions of ${call.loggedIn.user.name}")
          }
          route("/sessions/{id}") {
            install(RequireSessionMember) { logins = check }
            get("/state") {
              handled = true
              call.respondText("state of ${call.parameters["id"]} for ${call.loggedIn.user.name}")
            }
          }
          route("/admin") {
            install(RequireAdmin)
            get("/sessions") {
              handled = true
              call.respondText("all sessions")
            }
          }
        }
        get("/healthz") { call.respondText("ok") }
      }
      block { handled }
    }
  }

  @Test
  fun `without a login the session data is refused and the handler never runs`() = test { handled ->
    val response = client.get("/api/sessions/brave-otter/state")
    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertTrue("state" !in response.bodyAsText())
    assertFalse(handled())

    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sessions/brave-otter/state") { cookie(SESSION_COOKIE, "unknown") }.status)
    assertFalse(handled())
  }

  @Test
  fun `someone in no session gets in, but sees no session`() = test { handled ->
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/sessions/brave-otter/state") { cookie(SESSION_COOKIE, "m") }.status)
    assertFalse(handled())
    // Their (empty) list of sessions, their settings: an account belongs to a person, not to a session
    assertEquals("sessions of Mallory", client.get("/api/sessions") { cookie(SESSION_COOKIE, "m") }.bodyAsText())
  }

  @Test
  fun `a player sees their own sessions, not the others`() = test { handled ->
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/sessions/brave-otter/state") { cookie(SESSION_COOKIE, "p") }.status)
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/sessions/no-such-one/state") { cookie(SESSION_COOKIE, "p") }.status)
    assertFalse(handled())
    assertEquals("state of quiet-fox for Pete", client.get("/api/sessions/quiet-fox/state") { cookie(SESSION_COOKIE, "p") }.bodyAsText())
    assertEquals("sessions of Pete", client.get("/api/sessions") { cookie(SESSION_COOKIE, "p") }.bodyAsText())
  }

  @Test
  fun `an admin sees every session`() = test {
    assertEquals("state of brave-otter for Ada", client.get("/api/sessions/brave-otter/state") { cookie(SESSION_COOKIE, "ada") }.bodyAsText())
    assertEquals("state of quiet-fox for Ada", client.get("/api/sessions/quiet-fox/state") { cookie(SESSION_COOKIE, "ada") }.bodyAsText())
  }

  @Test
  fun `a player of the session gets through`() = test { handled ->
    val response = client.get("/api/sessions/brave-otter/state") { cookie(SESSION_COOKIE, "a") }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("state of brave-otter for alice", response.bodyAsText())
    assertTrue(handled())
  }

  @Test
  fun `the admin routes are for admins only`() = test { handled ->
    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/sessions").status)
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/sessions") { cookie(SESSION_COOKIE, "a") }.status)
    assertFalse(handled())
    assertEquals("all sessions", client.get("/api/admin/sessions") { cookie(SESSION_COOKIE, "ada") }.bodyAsText())
    assertTrue(handled())
  }

  @Test
  fun `routes outside the branch stay open`() = test {
    assertEquals("ok", client.get("/healthz").bodyAsText())
  }

  @Test
  fun `the login cookie is secure unless switched off for a local demo`() {
    val cookie = sessionCookie("t", 60, secure = true)
    assertTrue(cookie.secure && cookie.httpOnly)
    assertEquals("Strict", cookie.extensions["SameSite"])
    val demo = sessionCookie("t", 60, secure = false)
    assertFalse(demo.secure)
    assertTrue(demo.httpOnly)
  }
}
