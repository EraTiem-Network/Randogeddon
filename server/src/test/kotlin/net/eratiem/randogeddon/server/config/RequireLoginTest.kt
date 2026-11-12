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

  /** Alice is a player of the session (as "Alice"), Pete only of a past one, Mallory has a login but is in none. */
  private val check = object : LoginCheck {
    override suspend fun userOf(token: String) = mapOf("a" to alice, "m" to mallory, "ada" to ada, "p" to pete)[token]
    // Ada is an admin outside the session
    override fun personOf(user: User) = mapOf(alice to "Alice")[user]
    override suspend fun mayEnter(user: User) = user.admin || user == pete
  }

  private fun test(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(handled: () -> Boolean) -> Unit) {
    var handled = false
    testApplication {
      install(ContentNegotiation) { json(ApiJson) }
      routing {
        route("/api") {
          install(RequireLogin) { logins = check }
          route("/state") {
            install(RequireSessionMember)
            get {
              handled = true
              call.respondText("state of ${call.loggedIn.person}")
            }
          }
          get("/sessions") {
            handled = true
            call.respondText("past sessions of ${call.loggedIn.user.name}")
          }
          route("/admin") {
            install(RequireAdmin)
            get("/server") {
              handled = true
              call.respondText("server")
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
    val response = client.get("/api/state")
    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertTrue("state" !in response.bodyAsText())
    assertFalse(handled())

    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/state") { cookie(SESSION_COOKIE, "unknown") }.status)
    assertFalse(handled())
  }

  @Test
  fun `a login of someone outside the session is forbidden`() = test { handled ->
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/state") { cookie(SESSION_COOKIE, "m") }.status)
    assertFalse(handled())
  }

  @Test
  fun `a player of a past session sees past sessions, not the current one`() = test { handled ->
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/state") { cookie(SESSION_COOKIE, "p") }.status)
    assertFalse(handled())
    assertEquals("past sessions of Pete", client.get("/api/sessions") { cookie(SESSION_COOKIE, "p") }.bodyAsText())
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/sessions") { cookie(SESSION_COOKIE, "m") }.status)
  }

  @Test
  fun `an admin outside the session sees it`() = test {
    assertEquals("state of null", client.get("/api/state") { cookie(SESSION_COOKIE, "ada") }.bodyAsText())
  }

  @Test
  fun `a person of the session gets through`() = test { handled ->
    val response = client.get("/api/state") { cookie(SESSION_COOKIE, "a") }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("state of Alice", response.bodyAsText())
    assertTrue(handled())
  }

  @Test
  fun `the admin routes are for admins only`() = test { handled ->
    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/server").status)
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/server") { cookie(SESSION_COOKIE, "a") }.status)
    assertFalse(handled())
    assertEquals("server", client.get("/api/admin/server") { cookie(SESSION_COOKIE, "ada") }.bodyAsText())
    assertTrue(handled())
  }

  @Test
  fun `routes outside the branch stay open`() = test {
    assertEquals("ok", client.get("/healthz").bodyAsText())
  }
}
