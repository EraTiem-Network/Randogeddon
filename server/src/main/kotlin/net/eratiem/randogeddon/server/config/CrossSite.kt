package net.eratiem.randogeddon.server.config

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import java.net.URI
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal

class CrossSiteGuardConfig {
  /** Where the tracker is reached ([TrackerConfig.publicUrl]): the page whose requests are its own. */
  var publicUrl: String = "http://localhost:8000"

  /** The login cookie is Secure ([TrackerConfig.cookieSecure]), so its name has the `__Host-` prefix. */
  var secure: Boolean = true
}

private val SESSION_COOKIE_NAME = AttributeKey<String>("SessionCookieName")

/**
 * The login cookie a call carries: by the name [CrossSiteGuard] gives it ([sessionCookieName]); without that plugin (a
 * test), by [SESSION_COOKIE].
 */
val ApplicationCall.sessionToken: String?
  get() = request.cookies[attributes.getOrNull(SESSION_COOKIE_NAME) ?: SESSION_COOKIE]

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/** The provider's own calls (its back-channel logout, its webhooks): server to server, each with a token of its own. */
private const val PROVIDER_CALLS = "/api/login/oidc/"

/**
 * No request that changes something comes from another site's page: one with the login cookie (anywhere), or to the
 * API (but the provider's own calls, [PROVIDER_CALLS]), is refused (403) when the browser says it comes from another
 * page ([fromAnotherPage]). SameSite=Strict keeps the cookie from other sites, not from the other hosts under the same
 * domain; this does. The command line, the bridge, the provider and curl send neither header and pass.
 *
 * And the login cookie's name ([sessionCookieName], for [sessionToken]): where it is Secure, the one of before (without
 * the `__Host-` prefix) is no login any more, and a browser that still sends it is told to forget it.
 */
val CrossSiteGuard = createApplicationPlugin("CrossSiteGuard", ::CrossSiteGuardConfig) {
  val publicUrl = pluginConfig.publicUrl
  val secure = pluginConfig.secure
  val name = sessionCookieName(secure)
  onCall { call ->
    call.attributes.put(SESSION_COOKIE_NAME, name)
    // Refused already (too large): a second answer would fail
    if (call.response.isCommitted) return@onCall
    val cookies = call.request.cookies
    if (secure && cookies[SESSION_COOKIE] != null) {
      call.response.cookies.append(
        Cookie(SESSION_COOKIE, "", maxAge = 0, path = "/", secure = true, httpOnly = true, extensions = mapOf("SameSite" to "Strict"))
      )
    }
    if (call.request.httpMethod in SAFE_METHODS) return@onCall
    val path = call.request.path()
    val guarded = cookies[SESSION_COOKIE] != null || cookies[SECURE_SESSION_COOKIE] != null ||
      (path.startsWith("/api/") && !path.startsWith(PROVIDER_CALLS))
    val headers = call.request.headers
    if (guarded && fromAnotherPage(headers[HttpHeaders.Origin], headers[SEC_FETCH_SITE], publicUrl, headers[HttpHeaders.Host])) {
      call.refuse(HttpStatusCode.Forbidden, Refusal(Problem.OTHER_SITE))
    }
  }
}

private const val SEC_FETCH_SITE = "Sec-Fetch-Site"

/**
 * Whether a browser says a request comes from another page than the tracker's own. Its `Origin` names another origin
 * than [publicUrl]'s and than the [host] it was sent to (the address it was reached at: `localhost:8000`, the
 * compose network's `tracker:8000`, a LAN address), or is `null` (a sandboxed or privacy-redirected page). Without an
 * `Origin`, `Sec-Fetch-Site` says `cross-site` or `same-site` (another host under the same domain). Neither header: not
 * a page in a browser (the command line, the bridge, the provider's server, curl).
 */
internal fun fromAnotherPage(origin: String?, fetchSite: String?, publicUrl: String, host: String?): Boolean {
  if (origin != null) {
    val seen = originOf(origin) ?: return true
    if (seen == originOf(publicUrl)) return false
    val authority = seen.substringAfter("://")
    val reached = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
    return authority != reached && authority != reached.removeSuffix(":443").removeSuffix(":80")
  }
  return fetchSite?.trim()?.lowercase() in setOf("cross-site", "same-site")
}

/** `scheme://host[:port]` of [url], lower-case and without the scheme's default port; `null` if it names no host. */
internal fun originOf(url: String): String? {
  val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
  val scheme = uri.scheme?.lowercase() ?: return null
  val host = uri.host?.lowercase() ?: return null
  val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
  return "$scheme://$host" + (port?.let { ":$it" } ?: "")
}
