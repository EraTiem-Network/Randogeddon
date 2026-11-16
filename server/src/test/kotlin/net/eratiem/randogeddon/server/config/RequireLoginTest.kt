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
import net.eratiem.randogeddon.shared.SessionRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RequireLoginTest {
  private val alice = User(Uuid.random(), "alice", "hash")
  private val mallory = User(Uuid.random(), "Mallory", "hash")
  private val ada = User(Uuid.random(), "Ada", "hash", administrator = true, twoFactor = true)
  private val grace = User(Uuid.random(), "Grace", "hash", administrator = true)
  private val pete = User(Uuid.random(), "Pete", "hash")
  private val sam = User(Uuid.random(), "Sam", "hash")
  private val stu = User(Uuid.random(), "Stu", "hash")
  private val wanda = User(Uuid.random(), "Wanda", "hash")

  /**
   * Alice plays in brave-otter, Pete only in quiet-fox, Sam manages brave-otter, Stu supports it, Wanda watches it; Mallory has a login but is in none;
   * Ada is an Administrator, Grace one without two-factor login.
   */
  private val check = object : LoginCheck {
    override suspend fun userOf(token: String) =
      mapOf("a" to alice, "m" to mallory, "ada" to ada, "g" to grace, "p" to pete, "s" to sam, "stu" to stu, "w" to wanda)[token]
    private val roles = mapOf(
      "brave-otter" to mapOf(alice to SessionRole.PLAYER, sam to SessionRole.MANAGER, stu to SessionRole.SUPPORT, wanda to SessionRole.WATCHER),
      "quiet-fox" to mapOf(pete to SessionRole.PLAYER),
    )
    override fun maySee(user: User, id: String) = user.administrator || roleIn(user, id) != null
    override fun roleIn(user: User, id: String) = roles[id]?.get(user)
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
            route("/manage") {
              install(RequireSessionRole) {
                logins = check
                roles = setOf(SessionRole.MANAGER)
              }
              get {
                handled = true
                call.respondText("managing ${call.parameters["id"]}")
              }
            }
            route("/support") {
              install(RequireSessionRole) {
                logins = check
                roles = setOf(SessionRole.SUPPORT)
              }
              get { call.respondText("helping") }
            }
            route("/watchers") {
              install(RequireSessionRole) {
                logins = check
                roles = setOf(SessionRole.PLAYER, SessionRole.MANAGER)
              }
              get { call.respondText("watchers") }
            }
          }
          route("/admin") {
            install(RequireAdministrator) { logins = check }
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
    // An admin without two-factor login sets it up first
    val grace = client.get("/api/admin/sessions") { cookie(SESSION_COOKIE, "g") }
    assertEquals(HttpStatusCode.Forbidden, grace.status)
    assertTrue("two-factor" in grace.bodyAsText())
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

  @Test
  fun `a session is managed by its Session-Managers and Administrators`() = test { handled ->
    assertEquals("managing brave-otter", client.get("/api/sessions/brave-otter/manage") { cookie(SESSION_COOKIE, "s") }.bodyAsText())
    assertEquals("managing quiet-fox", client.get("/api/sessions/quiet-fox/manage") { cookie(SESSION_COOKIE, "ada") }.bodyAsText())
    // A player of it, a Session-Manager of another one, an Administrator without two-factor login
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/sessions/brave-otter/manage") { cookie(SESSION_COOKIE, "a") }.status)
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/sessions/quiet-fox/manage") { cookie(SESSION_COOKIE, "s") }.status)
    val grace = client.get("/api/sessions/quiet-fox/manage") { cookie(SESSION_COOKIE, "g") }
    assertEquals(HttpStatusCode.Forbidden, grace.status)
    assertTrue("two-factor" in grace.bodyAsText())
    // Managing doesn't make Sam an Administrator
    assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/sessions") { cookie(SESSION_COOKIE, "s") }.status)
  }

  @Test
  fun `an Administrator reads other sessions only with a login that may act as one`() = test { handled ->
    // Grace's login (no two-factor) reads no more than any user's, and she is told why
    val grace = client.get("/api/sessions/quiet-fox/state") { cookie(SESSION_COOKIE, "g") }
    assertEquals(HttpStatusCode.Forbidden, grace.status)
    assertTrue("two-factor" in grace.bodyAsText(), grace.bodyAsText())
    assertFalse(handled())
    // Ada (two-factor login) sees a session she has no part in
    assertEquals("state of quiet-fox for Ada", client.get("/api/sessions/quiet-fox/state") { cookie(SESSION_COOKIE, "ada") }.bodyAsText())
  }

  @Test
  fun `support helps, watchers only watch, players and Session-Managers add watchers`() = test {
    suspend fun status(path: String, who: String) = client.get(path) { cookie(SESSION_COOKIE, who) }.status
    // Watchers and support see the session
    assertEquals(HttpStatusCode.OK, status("/api/sessions/brave-otter/state", "w"))
    assertEquals(HttpStatusCode.OK, status("/api/sessions/brave-otter/state", "stu"))
    // Support: their own routes, not the management
    assertEquals("helping", client.get("/api/sessions/brave-otter/support") { cookie(SESSION_COOKIE, "stu") }.bodyAsText())
    assertEquals(HttpStatusCode.Forbidden, status("/api/sessions/brave-otter/manage", "stu"))
    for (who in listOf("a", "s", "w", "p")) assertEquals(HttpStatusCode.Forbidden, status("/api/sessions/brave-otter/support", who), who)
    assertEquals(HttpStatusCode.OK, status("/api/sessions/quiet-fox/support", "ada"))
    // Watchers: added by players and Session-Managers (and Administrators), not by support or watchers
    for (who in listOf("a", "s", "ada")) assertEquals(HttpStatusCode.OK, status("/api/sessions/brave-otter/watchers", who), who)
    for (who in listOf("stu", "w", "p", "m")) assertEquals(HttpStatusCode.Forbidden, status("/api/sessions/brave-otter/watchers", who), who)
  }
}
