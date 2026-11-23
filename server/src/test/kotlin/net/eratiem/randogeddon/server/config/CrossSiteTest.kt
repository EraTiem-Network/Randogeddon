package net.eratiem.randogeddon.server.config

import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
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

class CrossSiteTest {
  private val public = "https://randogeddon.eratiem.net"

  private fun test(secure: Boolean = true, block: suspend ApplicationTestBuilder.(handled: () -> Int) -> Unit) {
    var handled = 0
    testApplication {
      install(ContentNegotiation) { json(ApiJson) }
      install(CrossSiteGuard) {
        publicUrl = public
        this.secure = secure
      }
      routing {
        get("/api/me") { call.respondText("token ${call.sessionToken}") }
        post("/api/sessions/brave-otter/manage/finish") {
          handled++
          call.respondText("finished")
        }
        delete("/api/sessions/brave-otter/people/x") {
          handled++
          call.respondText("removed")
        }
        post("/api/login") {
          handled++
          call.respondText("logged in")
        }
        // The provider's back-channel logout and its webhooks: server to server, with tokens of their own
        post("/api/login/oidc/backchannel") {
          handled++
          call.respondText("logged out there")
        }
        post("/bridge/sessions") {
          handled++
          call.respondText("bridge")
        }
      }
      block { handled }
    }
  }

  @Test
  fun `a change from another site's page is refused, before its handler runs`() = test { handled ->
    val finish = "/api/sessions/brave-otter/manage/finish"
    for (origin in listOf("https://evil.example", "https://room.randogeddon.eratiem.net", "http://randogeddon.eratiem.net", "null")) {
      val refused = client.post(finish) {
        cookie(SECURE_SESSION_COOKIE, "a")
        header(HttpHeaders.Origin, origin)
      }
      assertEquals(HttpStatusCode.Forbidden, refused.status, origin)
      assertTrue("OTHER_SITE" in refused.bodyAsText(), refused.bodyAsText())
    }
    // Without an Origin, by what the browser says of the page
    for (site in listOf("cross-site", "same-site")) {
      val refused = client.delete("/api/sessions/brave-otter/people/x") {
        cookie(SECURE_SESSION_COOKIE, "a")
        header("Sec-Fetch-Site", site)
      }
      assertEquals(HttpStatusCode.Forbidden, refused.status, site)
    }
    // A login slipped in from elsewhere, without a cookie yet
    assertEquals(HttpStatusCode.Forbidden, client.post("/api/login") { header(HttpHeaders.Origin, "https://evil.example") }.status)
    assertEquals(0, handled())
  }

  @Test
  fun `the app's own requests pass, and so does whatever isn't a browser's page`() = test { handled ->
    val finish = "/api/sessions/brave-otter/manage/finish"
    // The public address, with or without its default port
    for (origin in listOf(public, "https://RandoGeddon.eratiem.net:443")) {
      assertEquals("finished", client.post(finish) { cookie(SECURE_SESSION_COOKIE, "a"); header(HttpHeaders.Origin, origin) }.bodyAsText(), origin)
    }
    // The address it was reached at (the local demo's localhost:8000 and tracker:8000): `what counts as another page`
    for (site in listOf("same-origin", "none")) {
      assertEquals("finished", client.post(finish) { cookie(SECURE_SESSION_COOKIE, "a"); header("Sec-Fetch-Site", site) }.bodyAsText(), site)
    }
    // Neither header: the command line, the demo's admin script, curl
    assertEquals("finished", client.post(finish) { cookie(SECURE_SESSION_COOKIE, "a") }.bodyAsText())
    assertEquals(5, handled())
    // The provider's server and the bridge (a bearer key, no cookie) are no page of another site's
    assertEquals("logged out there", client.post("/api/login/oidc/backchannel") { header(HttpHeaders.Origin, "https://auth.example") }.bodyAsText())
    assertEquals("bridge", client.post("/bridge/sessions") { header(HttpHeaders.Origin, "https://evil.example") }.bodyAsText())
    // Reading isn't changing: a link from elsewhere opens the app
    assertEquals(HttpStatusCode.OK, client.get("/api/me") { header("Sec-Fetch-Site", "cross-site") }.status)
  }

  @Test
  fun `a login cookie of before (without the prefix) is no login any more, and the browser forgets it`() = test {
    val old = client.get("/api/me") { cookie(SESSION_COOKIE, "old") }
    assertEquals("token null", old.bodyAsText())
    val cleared = old.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$SESSION_COOKIE=") }
    assertTrue("Max-Age=0" in cleared && "Path=/" in cleared && "Secure" in cleared, cleared)
    val now = client.get("/api/me") { cookie(SECURE_SESSION_COOKIE, "new") }
    assertEquals("token new", now.bodyAsText())
    assertNull(now.headers[HttpHeaders.SetCookie])
  }

  @Test
  fun `over plain HTTP (a local demo) the login cookie keeps its plain name`() = test(secure = false) {
    val demo = client.get("/api/me") { cookie(SESSION_COOKIE, "demo") }
    assertEquals("token demo", demo.bodyAsText())
    assertNull(demo.headers[HttpHeaders.SetCookie])
  }

  @Test
  fun `what counts as another page`() {
    fun other(origin: String?, site: String? = null, host: String? = "randogeddon.eratiem.net") = fromAnotherPage(origin, site, public, host)
    assertFalse(other(public))
    assertFalse(other("$public/"))
    assertTrue(other("https://evil.example"))
    // Another host under the same domain is the same site, but another page
    assertTrue(other("https://room.randogeddon.eratiem.net"))
    assertTrue(other("null"))
    assertTrue(other("not a url"))
    // The host it was reached at; another port is another origin
    assertFalse(other("http://localhost:8000", host = "localhost:8000"))
    assertFalse(other("http://tracker:8000", host = "tracker:8000"))
    assertTrue(other("http://localhost:9000", host = "localhost:8000"))
    assertFalse(other("http://192.168.1.20:8000", host = "192.168.1.20:8000"))
    assertTrue(other("https://evil.example", host = null))
    // No Origin: by Sec-Fetch-Site; neither: not a page
    assertTrue(other(null, "cross-site"))
    assertTrue(other(null, "Same-Site"))
    assertFalse(other(null, "same-origin"))
    assertFalse(other(null, "none"))
    assertFalse(other(null, null))
    assertEquals("https://randogeddon.eratiem.net", originOf("https://RANDOGEDDON.eratiem.net:443/x"))
    assertEquals("http://localhost:8000", originOf("http://localhost:8000"))
    assertNull(originOf("null"))
  }
}
