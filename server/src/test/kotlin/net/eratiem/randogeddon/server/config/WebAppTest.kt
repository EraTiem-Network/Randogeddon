package net.eratiem.randogeddon.server.config

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebAppTest {
  private val wasm = "abcdef0123456789abcd.wasm"
  private val plain = ByteArray(4096) { (it % 7).toByte() }
  // Stand-ins: what the build's brotli would write is just bytes to the server
  private val brotli = "brotli of the wasm".toByteArray()

  private fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

  private fun web(compressed: Boolean): File = Files.createTempDirectory("web").toFile().apply {
    resolve("index.html").writeText("<html></html>")
    resolve(wasm).writeBytes(plain)
    if (compressed) {
      resolve("$wasm.br").writeBytes(brotli)
      resolve("$wasm.gz").writeBytes(gzip(plain))
    }
    deleteOnExit()
  }

  // As in the application: the static files with the per-request gzip and the conditional headers around them
  private fun test(dir: File, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    install(ConditionalHeaders)
    install(Compression) { gzip() }
    routing { webApp(dir) }
    // The test client would decode gzip itself; the bytes as sent are what is checked here
    client = createClient { }
    block()
  }

  @Test
  fun `a browser that takes Brotli gets the build's br file as it is`() = test(web(compressed = true)) {
    val response = client.get("/$wasm") { header(HttpHeaders.AcceptEncoding, "gzip, deflate, br") }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("br", response.headers[HttpHeaders.ContentEncoding])
    assertEquals(ContentType("application", "wasm"), response.headers[HttpHeaders.ContentType]?.let(ContentType::parse))
    assertContentEquals(brotli, response.bodyAsBytes())
    assertTrue("max-age=31536000" in response.headers[HttpHeaders.CacheControl].orEmpty())
    // A cache between keeps the encodings apart
    assertEquals("Accept-Encoding", response.headers[HttpHeaders.Vary])
  }

  @Test
  fun `one that takes only gzip gets the build's gz file, not one compressed again`() = test(web(compressed = true)) {
    val response = client.get("/$wasm") { header(HttpHeaders.AcceptEncoding, "gzip") }
    assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
    assertContentEquals(gzip(plain), response.bodyAsBytes())
  }

  @Test
  fun `one that takes neither gets the plain file`() = test(web(compressed = true)) {
    val response = client.get("/$wasm") { header(HttpHeaders.AcceptEncoding, "identity") }
    assertNull(response.headers[HttpHeaders.ContentEncoding])
    assertContentEquals(plain, response.bodyAsBytes())
  }

  @Test
  fun `a local build without compressed files is gzipped per request as before`() = test(web(compressed = false)) {
    val response = client.get("/$wasm") { header(HttpHeaders.AcceptEncoding, "gzip, br") }
    assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `the page itself is revalidated, not cached`() = test(web(compressed = true)) {
    val response = client.get("/")
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue("no-cache" in response.headers[HttpHeaders.CacheControl].orEmpty())
  }
}
