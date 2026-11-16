package net.eratiem.randogeddon.server.config

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import net.eratiem.randogeddon.shared.ApiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardeningTest {
  private fun test(secure: Boolean = true, block: suspend ApplicationTestBuilder.(handled: () -> Int) -> Unit) {
    var handled = 0
    testApplication {
      install(ContentNegotiation) { json(ApiJson) }
      install(SecurityHeaders) { this.secure = secure }
      install(RequestLimits)
      routing {
        get("/") { call.respondText("page") }
        get("/api/me") { call.respondText("me") }
        post("/api/login") {
          handled++
          call.respondText("got ${call.receiveText().length}")
        }
        post("/api/sessions") {
          handled++
          call.respondText("got ${call.receiveText().length}")
        }
      }
      block { handled }
    }
  }

  @Test
  fun `every answer says what a browser may do with it`() = test {
    val page = client.get("/")
    assertEquals("nosniff", page.headers["X-Content-Type-Options"])
    assertEquals("no-referrer", page.headers["Referrer-Policy"])
    assertEquals("DENY", page.headers["X-Frame-Options"])
    assertEquals(PAGE_POLICY, page.headers["Content-Security-Policy"])
    assertTrue("frame-ancestors 'none'" in PAGE_POLICY)
    // Scripts only from the page's own files: nothing inline, no eval but WebAssembly's
    assertTrue("script-src 'self' 'wasm-unsafe-eval';" in PAGE_POLICY)
    assertFalse("script-src 'self' 'unsafe" in PAGE_POLICY)
    assertEquals("max-age=31536000; includeSubDomains", page.headers[HttpHeaders.StrictTransportSecurity])
    // The page itself may be kept (it is revalidated); the API's answers not
    assertNull(page.headers[HttpHeaders.CacheControl])
    assertEquals("no-store", client.get("/api/me").headers[HttpHeaders.CacheControl])
    // A refusal carries them too
    assertEquals("nosniff", client.get("/nothing-here").headers["X-Content-Type-Options"])
  }

  @Test
  fun `over plain HTTP (a local demo) nothing promises HTTPS`() = test(secure = false) {
    assertNull(client.get("/").headers[HttpHeaders.StrictTransportSecurity])
  }

  @Test
  fun `a request larger than its path may be is refused before it is read`() = test { handled ->
    val small = client.post("/api/login") { setBody("x".repeat(1000)) }
    assertEquals("got 1000", small.bodyAsText())
    val large = client.post("/api/login") { setBody("x".repeat(300 * 1024)) }
    assertEquals(HttpStatusCode.PayloadTooLarge, large.status)
    assertTrue("256 KB" in large.bodyAsText(), large.bodyAsText())
    assertEquals(1, handled())
    // The YAMLs of a new session may be larger, up to their own limit
    assertEquals("got ${300 * 1024}", client.post("/api/sessions") { setBody("x".repeat(300 * 1024)) }.bodyAsText())
    assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/api/sessions") { setBody("x".repeat(9 * 1024 * 1024)) }.status)
    assertEquals(2, handled())
  }

  @Test
  fun `a body without a length doesn't get around the limit`() {
    val limit = 1000L
    assertFalse(tooLarge(HttpMethod.Post, limit, "1000", null))
    assertTrue(tooLarge(HttpMethod.Post, limit, "1001", null))
    // Sent in chunks, or with a length that isn't one
    assertTrue(tooLarge(HttpMethod.Post, limit, null, "chunked"))
    assertTrue(tooLarge(HttpMethod.Put, limit, "lots", null))
    // No body at all (a plain POST), and requests that bring none
    assertFalse(tooLarge(HttpMethod.Post, limit, null, null))
    assertFalse(tooLarge(HttpMethod.Get, limit, "5000", null))
  }

  @Test
  fun `paths have their limits`() {
    assertEquals(256L * 1024, bodyLimit("/api/login"))
    assertEquals(256L * 1024, bodyLimit("/api/sessions/brave-otter/manage/command"))
    assertEquals(8L * 1024 * 1024, bodyLimit("/api/sessions"))
    assertEquals(8L * 1024 * 1024, bodyLimit("/api/sessions/new/inspect"))
    assertEquals(96L * 1024 * 1024, bodyLimit("/api/admin/apworlds"))
    // Not its siblings
    assertEquals(256L * 1024, bodyLimit("/api/admin/apworlds/remove"))
  }

  @Test
  fun `an IPv6 network counts as one address for the rate limits`() {
    assertEquals(addressKey("2001:db8:1:2::1"), addressKey("2001:db8:1:2:ffff:ffff:ffff:ffff"))
    assertEquals(addressKey("2001:db8:1:2::1"), addressKey("[2001:DB8:1:2:0:0:0:9]"))
    assertFalse(addressKey("2001:db8:1:2::1") == addressKey("2001:db8:1:3::1"))
    assertEquals("203.0.113.7", addressKey("203.0.113.7"))
    // An IPv4 address written as IPv6 is that IPv4 address
    assertEquals("203.0.113.7", addressKey("::ffff:203.0.113.7"))
    // Not an address: left as it is, without a lookup
    assertEquals("some-proxy", addressKey("some-proxy"))
  }
}
