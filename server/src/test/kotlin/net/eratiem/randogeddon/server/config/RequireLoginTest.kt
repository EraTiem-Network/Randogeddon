package net.eratiem.randogeddon.server.config

import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.account.adminLoginProblem
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.Problem
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
  private val gail = User(Uuid.random(), "Gail", "hash", administrator = true)

  /**
   * Alice plays in brave-otter, Pete only in quiet-fox, Sam manages brave-otter, Stu supports it, Wanda watches it; Mallory has a login but is in none;
   * Ada is an Administrator, Grace one without two-factor login, Gail too, who also manages brave-otter. Administrators
   * by the rule without single sign-on and with ADMIN_PASSWORD_LOGIN (their password and two-factor login).
   */
  private val check = object : LoginCheck {
    override suspend fun userOf(token: String) =
      mapOf("a" to alice, "m" to mallory, "ada" to ada, "g" to grace, "p" to pete, "s" to sam, "stu" to stu, "w" to wanda, "gail" to gail)[token]
    private val roles = mapOf(
      "brave-otter" to mapOf(
        alice to SessionRole.PLAYER, sam to SessionRole.MANAGER, stu to SessionRole.SUPPORT, wanda to SessionRole.WATCHER,
        gail to SessionRole.MANAGER,
      ),
      "quiet-fox" to mapOf(pete to SessionRole.PLAYER),
    )
    override fun maySee(user: User, id: String) = user.administrator || roleIn(user, id) != null
    // By the account, as the sessions know it (an Administrator's login that may not act as one is the same person)
    override fun roleIn(user: User, id: String) = roles[id]?.entries?.firstOrNull { it.key.id == user.id }?.value
  }

  /** What a call threw on the server (the client still gets its answer, so only this shows it). */
  private val failures = mutableListOf<Throwable>()

  /** How many bodies of a seed upload the handler took in. */
  private var seedsRead = 0

  private fun test(
    check: LoginCheck = this.check,
    block: suspend io.ktor.server.testing.ApplicationTestBuilder.(handled: () -> Boolean) -> Unit,
  ) {
    var handled = false
    testApplication {
      install(ContentNegotiation) { json(ApiJson) }
      install(createApplicationPlugin("failures") { on(CallFailed) { _, cause -> failures += cause } })
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
              // As manageRoutes' logs: for Administrators only, by the login, not the account
              get("/logic/log") {
                apiCall {
                  onlyAdministrators()
                  call.respondText("logic log")
                }
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
                roles = setOf(SessionRole.PLAYER, SessionRole.SUPPORT, SessionRole.MANAGER)
              }
              get { call.respondText("watchers") }
            }
          }
          // As newSessionRoutes' seed upload: refused before its body is read
          post("/sessions/new/seed") {
            apiCall {
              onlyAdministrators(Problem.ONLY_ADMINISTRATORS_SEED)
              seedsRead++
              call.respondText("seed of ${call.receiveText().length}")
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
  fun `without a login the guards under the login's say nothing more and throw nothing`() = test { handled ->
    for (path in listOf("/api/sessions/brave-otter/state", "/api/sessions/brave-otter/manage", "/api/sessions/brave-otter/support", "/api/admin/sessions")) {
      val response = client.get(path)
      assertEquals(HttpStatusCode.Unauthorized, response.status, path)
      assertTrue("NOT_LOGGED_IN" in response.bodyAsText(), path)
    }
    assertFalse(handled())
    // Each threw once (a missing login in the call's attributes), which filled the log with a stack trace per call
    assertEquals(emptyList(), failures.map { it.toString() })
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

  /** The same logins without single sign-on and without ADMIN_PASSWORD_LOGIN: no login acts as an Administrator. */
  private val noAdministrators = object : LoginCheck by check {
    override fun administratorProblem(user: User) = adminLoginProblem(user, provider = null, passwordLogin = false)
  }

  @Test
  fun `without single sign-on and ADMIN_PASSWORD_LOGIN no login acts as an Administrator, two-factor or not`() =
    test(noAdministrators) { handled ->
      suspend fun get(path: String, who: String) = client.get(path) { cookie(SESSION_COOKIE, who) }
      for (who in listOf("ada", "g")) {
        val panel = get("/api/admin/sessions", who)
        assertEquals(HttpStatusCode.Forbidden, panel.status, who)
        assertTrue("ONLY_ADMINISTRATORS" in panel.bodyAsText(), panel.bodyAsText())
        // Another session, its management, an Administrator's log: as for any user
        assertEquals(HttpStatusCode.Forbidden, get("/api/sessions/quiet-fox/state", who).status, who)
        assertEquals(HttpStatusCode.Forbidden, get("/api/sessions/quiet-fox/manage", who).status, who)
      }
      assertFalse(handled())
      assertEquals(HttpStatusCode.Forbidden, get("/api/sessions/brave-otter/manage/logic/log", "gail").status)
      // What they have a part in stays theirs: Gail manages brave-otter
      assertEquals("managing brave-otter", get("/api/sessions/brave-otter/manage", "gail").bodyAsText())
      assertEquals("sessions of Ada", get("/api/sessions", "ada").bodyAsText())
      assertEquals(emptyList(), failures.map { it.toString() })
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
    // Secure: with the __Host- prefix (only this host sets it, for every path, with no Domain)
    assertEquals("__Host-randogeddon_session", cookie.name)
    assertEquals("/", cookie.path)
    assertEquals(null, cookie.domain)
    val demo = sessionCookie("t", 60, secure = false)
    assertFalse(demo.secure)
    assertTrue(demo.httpOnly)
    assertEquals(SESSION_COOKIE, demo.name)
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
    // Each refused once: the guard after the one that answered says nothing more (a second answer would fail)
    assertEquals(emptyList(), failures.map { it.toString() })
  }

  @Test
  fun `a session's logs are for Administrators whose login may act as one, not for every manager`() = test {
    suspend fun log(who: String) = client.get("/api/sessions/brave-otter/manage/logic/log") { cookie(SESSION_COOKIE, who) }
    assertEquals("logic log", log("ada").bodyAsText())
    // Sam manages it; Gail too, and her account is an Administrator's, but not her login (no two-factor login)
    for (who in listOf("s", "gail")) {
      val refused = log(who)
      assertEquals(HttpStatusCode.Forbidden, refused.status, who)
      assertTrue("ONLY_ADMINISTRATORS" in refused.bodyAsText(), refused.bodyAsText())
    }
    assertEquals("managing brave-otter", client.get("/api/sessions/brave-otter/manage") { cookie(SESSION_COOKIE, "gail") }.bodyAsText())
    assertEquals(emptyList(), failures.map { it.toString() })
  }

  @Test
  fun `a seed from elsewhere is refused before its body is read, unless from an Administrator`() = test {
    val body = "x".repeat(100_000)
    for (who in listOf("w", "m", "s", "g")) {
      val refused = client.post("/api/sessions/new/seed") {
        cookie(SESSION_COOKIE, who)
        setBody(body)
      }
      assertEquals(HttpStatusCode.Forbidden, refused.status, who)
      assertTrue("ONLY_ADMINISTRATORS_SEED" in refused.bodyAsText(), refused.bodyAsText())
    }
    assertEquals(0, seedsRead)
    assertEquals("seed of 100000", client.post("/api/sessions/new/seed") { cookie(SESSION_COOKIE, "ada"); setBody(body) }.bodyAsText())
    assertEquals(1, seedsRead)
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
  fun `support helps, watchers only watch, players, support and Session-Managers add watchers`() = test {
    suspend fun status(path: String, who: String) = client.get(path) { cookie(SESSION_COOKIE, who) }.status
    // Watchers and support see the session
    assertEquals(HttpStatusCode.OK, status("/api/sessions/brave-otter/state", "w"))
    assertEquals(HttpStatusCode.OK, status("/api/sessions/brave-otter/state", "stu"))
    // Support: their own routes, not the management
    assertEquals("helping", client.get("/api/sessions/brave-otter/support") { cookie(SESSION_COOKIE, "stu") }.bodyAsText())
    assertEquals(HttpStatusCode.Forbidden, status("/api/sessions/brave-otter/manage", "stu"))
    for (who in listOf("a", "s", "w", "p")) assertEquals(HttpStatusCode.Forbidden, status("/api/sessions/brave-otter/support", who), who)
    assertEquals(HttpStatusCode.OK, status("/api/sessions/quiet-fox/support", "ada"))
    // Watchers: added by players, support and Session-Managers (and Administrators), not by watchers
    for (who in listOf("a", "s", "ada", "stu")) assertEquals(HttpStatusCode.OK, status("/api/sessions/brave-otter/watchers", who), who)
    for (who in listOf("w", "p", "m")) assertEquals(HttpStatusCode.Forbidden, status("/api/sessions/brave-otter/watchers", who), who)
  }
}
