package net.eratiem.randogeddon.server.config

import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.account.AccountProblem
import net.eratiem.randogeddon.server.account.LoginAttempts
import net.eratiem.randogeddon.server.account.LoginCheck
import net.eratiem.randogeddon.server.admin.GameServerUnavailable
import net.eratiem.randogeddon.server.admin.SessionProblem
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.SessionRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ApiCallTest {
  private val alice = User(Uuid.random(), "alice", "hash")
  private val sam = User(Uuid.random(), "Sam", "hash")
  private val stu = User(Uuid.random(), "Stu", "hash")
  private val ada = User(Uuid.random(), "Ada", "hash", administrator = true, twoFactor = true)
  private val grace = User(Uuid.random(), "Grace", "hash", administrator = true)

  /** Alice plays in brave-otter, Sam manages it, Stu supports it, Grace (no two-factor login) plays in it too; Ada is an Administrator. */
  private val check = object : LoginCheck {
    override suspend fun userOf(token: String) = mapOf("a" to alice, "s" to sam, "stu" to stu, "ada" to ada, "g" to grace)[token]
    private val roles = mapOf(
      "brave-otter" to mapOf(alice to SessionRole.PLAYER, sam to SessionRole.MANAGER, stu to SessionRole.SUPPORT, grace to SessionRole.PLAYER),
    )
    override fun maySee(user: User, id: String) = user.administrator || roleIn(user, id) != null
    // By the account, as the sessions know it (an Administrator's login that may not act as one is the same person)
    override fun roleIn(user: User, id: String) = roles[id]?.entries?.firstOrNull { it.key.id == user.id }?.value
  }

  private val failures = mutableListOf<Throwable>()

  /** What the gateway's client says when it can't reach it: the internal host and port. */
  private val unreachable = "The gateway can't be reached: Connection refused: gateway:8001"

  private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    install(ContentNegotiation) { json(ApiJson) }
    install(createApplicationPlugin("failures") { on(CallFailed) { _, cause -> failures += cause } })
    routing {
      route("/api") {
        install(RequireLogin) { logins = check }
        route("/sessions/{id}") {
          install(RequireSessionMember) { logins = check }
          // As the people and watchers routes: an ID that can't be a session's
          get("/people") { apiCall { call.respondText("people of ${id()}") } }
          get("/account") { apiCall { throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT) } }
          get("/session") { apiCall { throw SessionProblem(HttpStatusCode.Conflict, Problem.REOPEN_TO_START) } }
          // As the files and the spoiler: the gateway is away
          get("/files") { apiCall { throw GameServerUnavailable(HttpStatusCode.BadGateway, unreachable) } }
          route("/manage") {
            install(RequireSessionRole) {
              logins = check
              roles = setOf(SessionRole.MANAGER)
            }
            get("/server") { apiCall { throw GameServerUnavailable(HttpStatusCode.BadGateway, unreachable) } }
          }
        }
        // Outside a session (a new one's routes): only an Administrator reads it
        get("/sessions/new") { apiCall { throw GameServerUnavailable(HttpStatusCode.BadGateway, unreachable) } }
      }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.getAs(path: String, who: String): HttpResponse = client.get(path) { cookie(SESSION_COOKIE, who) }

  private suspend fun HttpResponse.error(): ApiError = ApiJson.decodeFromString(ApiError.serializer(), bodyAsText())

  @Test
  fun `an account's, a session's and the gateway's problems are answered, not failed`() = test {
    // Ada passes the session's guard for any ID; one that can't be a session's is 404, not 500
    val nosuch = getAs("/api/sessions/No_Such!/people", "ada")
    assertEquals(HttpStatusCode.NotFound, nosuch.status)
    assertEquals(Problem.NO_SESSION, nosuch.error().code)
    assertEquals("people of brave-otter", getAs("/api/sessions/brave-otter/people", "ada").bodyAsText())
    assertEquals(HttpStatusCode.NotFound, getAs("/api/sessions/brave-otter/account", "ada").status)
    assertEquals(Problem.NO_ACCOUNT, getAs("/api/sessions/brave-otter/account", "ada").error().code)
    assertEquals(HttpStatusCode.Conflict, getAs("/api/sessions/brave-otter/session", "a").status)
    val away = getAs("/api/sessions/brave-otter/files", "a")
    assertEquals(HttpStatusCode.BadGateway, away.status)
    assertEquals(Problem.GATEWAY_UNREACHABLE, away.error().code)
    assertEquals(emptyList(), failures.map { it.toString() })
  }

  @Test
  fun `what the gateway said goes only to those who run the session`() = test {
    // Its Session-Manager and an Administrator read it, on the session's routes and on its management's
    for ((path, who) in listOf("files" to "s", "files" to "ada", "manage/server" to "s", "manage/server" to "ada")) {
      assertEquals(unreachable, getAs("/api/sessions/brave-otter/$path", who).error().detail, "$path $who")
    }
    // A player, support, an Administrator whose login may not act as one: the problem without the internal address
    for (who in listOf("a", "stu", "g")) {
      val away = getAs("/api/sessions/brave-otter/files", who)
      assertEquals(HttpStatusCode.BadGateway, away.status, who)
      assertNull(away.error().detail, who)
      assertFalse("gateway:8001" in away.bodyAsText(), who)
    }
    // Outside a session only an Administrator
    assertEquals(unreachable, getAs("/api/sessions/new", "ada").error().detail)
    assertNull(getAs("/api/sessions/new", "s").error().detail)
  }

  @Test
  fun `a console line is one line, without control characters`() {
    assertTrue(consoleLine("/send Ada Hookshot"))
    assertTrue(consoleLine("/hint \"Ada\" \"Ein Schlüssel\""))
    for (line in listOf("a\n2026-11-22 INFO Ada deleted session x", "a\rb", "a\tb", "a\u0000b", "a\u001bb", "a\u0085b")) {
      assertFalse(consoleLine(line), line)
    }
  }

  @Test
  fun `wrong codes and wrong passwords count per account, and a blocked account is refused before it is checked`() = runBlocking {
    val attempts = LoginAttempts()
    val id = Uuid.random()
    val keys = listOf(LoginAttempts.code(id.toString()))
    var checked = 0
    suspend fun wrongCode() = assertFailsWith<AccountProblem> {
      countedTry(attempts, keys, Problem.WRONG_CODE) {
        checked++
        throw AccountProblem(HttpStatusCode.Forbidden, Problem.WRONG_CODE)
      }
    }
    // Another refusal (an Administrator can't turn it off) isn't a wrong code
    assertFailsWith<AccountProblem> {
      countedTry(attempts, keys, Problem.WRONG_CODE) { throw AccountProblem(HttpStatusCode.Forbidden, Problem.ADMINISTRATORS_NEED_TWO_FACTOR) }
    }
    repeat(LoginAttempts.MAX) { assertEquals(Problem.WRONG_CODE, wrongCode().refusal.problem) }
    val blocked = wrongCode()
    assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
    assertEquals(Problem.TOO_MANY_ATTEMPTS, blocked.refusal.problem)
    assertEquals(LoginAttempts.MAX, checked)
    // The login's code step counts under the same key
    assertTrue(attempts.blocked(LoginAttempts.code(id.toString())))
    // A right one goes through while not blocked
    assertEquals("codes", countedTry(LoginAttempts(), keys, Problem.WRONG_CODE) { "codes" })

    // Wrong passwords at a password change count under the account, as the login's do by name and by address
    val passwords = LoginAttempts()
    repeat(LoginAttempts.MAX / 2) {
      passwords.failed(LoginAttempts.password("bob"))
      passwords.failed(LoginAttempts.accountPassword(id))
      passwords.failed(LoginAttempts.password("bob@example.org"))
      passwords.failed(LoginAttempts.accountPassword(id))
    }
    assertFalse(passwords.blocked(LoginAttempts.password("bob")))
    assertTrue(passwords.blocked(LoginAttempts.accountPassword(id)))
    val change = assertFailsWith<AccountProblem> {
      countedTry(passwords, listOf(LoginAttempts.accountPassword(id)), Problem.WRONG_PASSWORD) { "a new login" }
    }
    assertEquals(HttpStatusCode.TooManyRequests, change.status)
  }

  @Test
  fun `the new session form's own pace never meets its limit, a loop does`() {
    val pace = newSessionPace()
    // The list every 15 s for an hour, an ID typed with a check after every key (each after a pause), a batch of YAMLs
    // now and then
    var now = 0L
    repeat(240) {
      assertTrue(pace.take("new:alice", now))
      now += 15_000
    }
    now = 0L
    repeat(3) {
      repeat(40) {
        assertTrue(pace.take("id:alice", now), "check $it")
        now += 450
      }
      now += 20_000
    }
    repeat(10) { assertTrue(pace.take("inspect:alice", it * 5_000L)) }
    // A loop: through at first, then refused, and only one a second gets through
    val taken = (0 until 1000).count { pace.take("id:mallory", 1_000_000L + it * 10L) }
    assertTrue(taken in 30..45, "$taken")
    // Someone else isn't held up by it
    assertTrue(pace.take("id:bob", 1_010_000L))
  }
}
