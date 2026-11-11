package net.eratiem.randogeddon.gateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class InternalAuthTest {
  private fun test(token: String, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
    routing {
      route("/internal") {
        install(InternalAuth) { this.token = token }
        get("/server") { call.respondText("state") }
      }
    }
    block()
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
