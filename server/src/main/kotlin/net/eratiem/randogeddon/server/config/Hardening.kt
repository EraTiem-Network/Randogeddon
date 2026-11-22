package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal

class SecurityHeadersConfig {
  /** Served over HTTPS (the login cookie is Secure): browsers are told to keep to it. */
  var secure: Boolean = true
}

/**
 * What a browser may do with the page, for every answer. Everything comes from the page's own address: scripts only
 * as its files (none inline, no `eval`; the web app is WebAssembly, hence `wasm-unsafe-eval`), requests only to it.
 * Styles may be inline (index.html's own, and what the scripts set). Nothing frames it, nothing is posted elsewhere, no
 * plugins and no `<base>`.
 */
const val PAGE_POLICY = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
  "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self'; manifest-src 'self'; " +
  "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'"

/**
 * The headers every answer carries: [PAGE_POLICY], HTTPS only for a year (with [SecurityHeadersConfig.secure]), and
 * for the API's answers (the connection with its passwords among them) no storing.
 */
val SecurityHeaders = createApplicationPlugin("SecurityHeaders", ::SecurityHeadersConfig) {
  val secure = pluginConfig.secure
  onCall { call ->
    val headers = call.response.headers
    headers.append("X-Content-Type-Options", "nosniff")
    headers.append("Referrer-Policy", "no-referrer")
    headers.append("X-Frame-Options", "DENY")
    headers.append("Content-Security-Policy", PAGE_POLICY)
    if (secure) headers.append(HttpHeaders.StrictTransportSecurity, "max-age=31536000; includeSubDomains")
    val path = call.request.path()
    if (path.startsWith("/api/") || path.startsWith("/bridge/")) headers.append(HttpHeaders.CacheControl, "no-store")
  }
}

private const val KIB = 1024L

/** The largest body a request to [path] may have: small by default, more where files come in. */
internal fun bodyLimit(path: String): Long = when (path.trimEnd('/')) {
  // The apworlds as base64 (64 MB of files) in JSON
  "/api/admin/apworlds" -> 96 * KIB * KIB
  // A new session's YAMLs
  "/api/sessions", "/api/sessions/new/inspect" -> 8 * KIB * KIB
  // A seed generated elsewhere as base64 (64 MB of zip) in JSON
  "/api/sessions/new/seed" -> 96 * KIB * KIB
  else -> 256 * KIB
}

private fun size(bytes: Long): String = if (bytes >= KIB * KIB) "${bytes / KIB / KIB} MB" else "${bytes / KIB} KB"

private val WITH_BODY = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)

/**
 * No request brings more than its path may ([bodyLimit]): refused by its `Content-Length` before the body is read (413),
 * and one that names no length but sends a body (chunked) as well, so the limit can't be gone around.
 */
val RequestLimits = createApplicationPlugin("RequestLimits") {
  onCall { call ->
    val headers = call.request.headers
    val limit = bodyLimit(call.request.path())
    if (tooLarge(call.request.httpMethod, limit, headers[HttpHeaders.ContentLength], headers[HttpHeaders.TransferEncoding])) {
      call.refuse(HttpStatusCode.PayloadTooLarge, Refusal.of(Problem.REQUEST_TOO_LARGE, "max" to size(limit)))
    }
  }
}

/** Whether a request's body is more than [limit], by its headers: a length past it, or none while it sends one (chunked). */
internal fun tooLarge(method: HttpMethod, limit: Long, contentLength: String?, transferEncoding: String?): Boolean {
  if (method !in WITH_BODY) return false
  val length = contentLength?.trim()?.toLongOrNull()
  return when {
    length != null -> length > limit
    // A length that isn't a number, or a body sent in chunks: no telling how much comes
    else -> contentLength != null || transferEncoding != null
  }
}
