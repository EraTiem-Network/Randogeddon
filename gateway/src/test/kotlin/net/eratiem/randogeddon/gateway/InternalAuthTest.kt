package net.eratiem.randogeddon.gateway

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class InternalAuthTest {
  /** How often a handler that changes something ran (deleting a session, a console command). */
  private val done = AtomicInteger()

  private fun test(token: String, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
    routing {
      route("/internal") {
        install(InternalAuth) { this.token = token }
        get("/server") { call.respondText("state") }
        route("/sessions/{id}") {
          delete {
            done.incrementAndGet()
            call.respond(HttpStatusCode.NoContent)
          }
          post("/server/command") {
            done.incrementAndGet()
            call.respondText(call.receiveText())
          }
        }
      }
    }
    block()
  }

  @Test
  fun `no handler runs after a refused token`() = test("s3cret") {
    for (token in listOf(null, "Bearer nope", "Bearer s3cre", "Bearer s3cret2", "Basic s3cret")) {
      val deleted = client.delete("/internal/sessions/brave-otter") { token?.let { header("Authorization", it) } }
      val command = client.post("/internal/sessions/brave-otter/server/command") {
        token?.let { header("Authorization", it) }
        setBody("/exit")
      }
      assertEquals(HttpStatusCode.Unauthorized, deleted.status, token)
      assertEquals(HttpStatusCode.Unauthorized, command.status, token)
      assertEquals("", command.bodyAsText(), token)
    }
    assertEquals(0, done.get())
    // With the token both run
    val deleted = client.delete("/internal/sessions/brave-otter") { header("Authorization", "Bearer s3cret") }
    val command = client.post("/internal/sessions/brave-otter/server/command") {
      header("Authorization", "Bearer s3cret")
      setBody("/exit")
    }
    assertEquals(HttpStatusCode.NoContent, deleted.status)
    assertEquals("/exit", command.bodyAsText())
    assertEquals(2, done.get())
  }

  @Test
  fun `without a configured token no handler runs`() = test("") {
    client.delete("/internal/sessions/brave-otter")
    client.delete("/internal/sessions/brave-otter") { header("Authorization", "Bearer ") }
    assertEquals(0, done.get())
  }

  @Test
  fun `only the right token gets through`() = test("s3cret") {
    assertEquals(HttpStatusCode.Unauthorized, client.get("/internal/server").status)
    assertEquals(HttpStatusCode.Unauthorized, client.get("/internal/server") { header("Authorization", "Bearer nope") }.status)
    assertEquals("state", client.get("/internal/server") { header("Authorization", "Bearer s3cret") }.bodyAsText())
  }

  @Test
  fun `without a configured token nothing gets through`() = test("") {
    assertEquals(HttpStatusCode.Unauthorized, client.get("/internal/server") { header("Authorization", "Bearer ") }.status)
  }
}
