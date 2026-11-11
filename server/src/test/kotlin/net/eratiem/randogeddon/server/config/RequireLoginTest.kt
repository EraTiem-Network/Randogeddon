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

  /** Alice is a person of the session (as "Alice"), Mallory has a login but isn't. */
  private val check = object : LoginCheck {
    override suspend fun userOf(token: String) = mapOf("a" to alice, "m" to mallory, "ada" to ada)[token]
    // Ada is an admin outside the session
    override fun personOf(user: User) = mapOf(alice to "Alice")[user]
  }

  private fun test(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(handled: () -> Boolean) -> Unit) {
    var handled = false
    testApplication {
      install(ContentNegotiation) { json(ApiJson) }
      routing {
        route("/api") {
          install(RequireLogin) { logins = check }
          get("/state") {
            handled = true
            call.respondText("state of ${call.loggedIn.person}")
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
