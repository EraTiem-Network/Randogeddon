package net.eratiem.randogeddon.server.config

import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import kotlin.test.Test
import kotlin.test.assertEquals

class RefusalsTest {
  @Test
  fun `a refusal is worded in the request's language and carries its code and values`() = testApplication {
    install(ContentNegotiation) { json(ApiJson) }
    routing {
      get("/taken") { call.refuse(HttpStatusCode.Conflict, Refusal.of(Problem.NAME_TAKEN, "name" to "Ada")) }
    }
    suspend fun answer(build: io.ktor.client.request.HttpRequestBuilder.() -> Unit): ApiError =
      ApiJson.decodeFromString(ApiError.serializer(), client.get("/taken", build).bodyAsText())

    val english = answer {}
    assertEquals("The name Ada is taken", english.message)
    assertEquals(Problem.NAME_TAKEN, english.code)
    assertEquals(mapOf("name" to "Ada"), english.args)
    // The app's cookie first, then the browser's languages
    assertEquals("Der Name Ada ist vergeben", answer { cookie(LANGUAGE_COOKIE, "de") }.message)
    assertEquals("Der Name Ada ist vergeben", answer { header(HttpHeaders.AcceptLanguage, "de-AT,de;q=0.9,en;q=0.8") }.message)
    assertEquals("The name Ada is taken", answer { cookie(LANGUAGE_COOKIE, "en"); header(HttpHeaders.AcceptLanguage, "de") }.message)
    assertEquals("The name Ada is taken", answer { header(HttpHeaders.AcceptLanguage, "fr") }.message)
  }
}
