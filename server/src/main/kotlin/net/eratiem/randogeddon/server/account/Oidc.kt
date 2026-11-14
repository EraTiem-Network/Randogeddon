package net.eratiem.randogeddon.server.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.eratiem.randogeddon.server.config.OidcConfig
import net.eratiem.randogeddon.shared.ApiJson
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration.Companion.hours

/** Clocks of the provider and of this server may differ a little. */
private const val CLOCK_SKEW_SECONDS = 60L

/** What a login through the provider tells about the person: who they are there, and their groups. */
data class OidcIdentity(
  val subject: String,
  val email: String?,
  val emailVerified: Boolean,
  val preferredUsername: String?,
  val name: String?,
  val groups: List<String>,
)

/** The ID token was refused: why (for the log; the person sees a plain "the login didn't work"). */
class OidcProblem(message: String) : Exception(message)

private val b64url: Base64.Decoder = Base64.getUrlDecoder()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** PKCE (RFC 7636): the challenge of [verifier], `S256`. */
internal fun pkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
  .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

/** The RSA keys of a JWKS document by their `kid` (other key types are left out). */
internal fun rsaKeys(jwks: String): Map<String, PublicKey> {
  val factory = KeyFactory.getInstance("RSA")
  return ApiJson.parseToJsonElement(jwks).jsonObject["keys"]?.jsonArray.orEmpty().mapNotNull { element ->
    val key = element.jsonObject
    if (key.string("kty") != "RSA") return@mapNotNull null
    val n = key.string("n") ?: return@mapNotNull null
    val e = key.string("e") ?: return@mapNotNull null
    (key.string("kid") ?: "") to factory.generatePublic(RSAPublicKeySpec(BigInteger(1, b64url.decode(n)), BigInteger(1, b64url.decode(e))))
  }.toMap()
}

/**
 * Checks an ID token (a JWT signed with RS256) and reads it: the signature with one of [keys] (by its `kid`), the
 * issuer, this client in its audience, not expired, and the [nonce] of this login. [OidcProblem] if anything fails.
 */
internal fun verifyIdToken(token: String, keys: Map<String, PublicKey>, issuer: String, clientId: String, nonce: String, now: Instant): OidcIdentity {
  val parts = token.split('.')
  if (parts.size != 3) throw OidcProblem("Not a JWT")
  val header = runCatching { ApiJson.parseToJsonElement(String(b64url.decode(parts[0]))).jsonObject }.getOrNull()
    ?: throw OidcProblem("Unreadable header")
  if (header.string("alg") != "RS256") throw OidcProblem("Unsupported algorithm ${header.string("alg")}")
  val key = keys[header.string("kid") ?: ""] ?: keys.values.singleOrNull().takeIf { header.string("kid") == null }
    ?: throw OidcProblem("Unknown key ${header.string("kid")}")
  val signed = Signature.getInstance("SHA256withRSA").apply {
    initVerify(key)
    update("${parts[0]}.${parts[1]}".toByteArray())
  }.verify(runCatching { b64url.decode(parts[2]) }.getOrElse { throw OidcProblem("Unreadable signature") })
  if (!signed) throw OidcProblem("Wrong signature")
  val claims = runCatching { ApiJson.parseToJsonElement(String(b64url.decode(parts[1]))).jsonObject }.getOrNull()
    ?: throw OidcProblem("Unreadable claims")
  if (claims.string("iss") != issuer) throw OidcProblem("Wrong issuer ${claims.string("iss")}")
  val audience = when (val aud = claims["aud"]) {
    is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
    else -> emptyList()
  }
  if (clientId !in audience) throw OidcProblem("Not for this client")
  val expires = claims["exp"]?.jsonPrimitive?.longOrNull ?: throw OidcProblem("No expiry")
  if (now.epochSecond > expires + CLOCK_SKEW_SECONDS) throw OidcProblem("Expired")
  if (claims.string("nonce") != nonce) throw OidcProblem("Wrong nonce")
  val subject = claims.string("sub")?.takeIf { it.isNotEmpty() } ?: throw OidcProblem("No subject")
  return OidcIdentity(
    subject = subject,
    email = claims.string("email"),
    emailVerified = (claims["email_verified"] as? JsonPrimitive)?.booleanOrNull == true,
    preferredUsername = claims.string("preferred_username"),
    name = claims.string("name"),
    groups = (claims["groups"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
  )
}

/**
 * The OpenID Connect provider (Authentik), by its discovery document: the link a login starts with (authorization
 * code with PKCE), and the code exchanged for a checked ID token. The discovery document and the keys are kept for an
 * hour; an unknown key fetches them again (the provider rotated it).
 */
class OidcClient(private val config: OidcConfig, private val redirectUri: String) {
  private val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 15_000 } }
  private val mutex = Mutex()
  private var discovery: Discovery? = null
  private var keys: Map<String, PublicKey> = emptyMap()
  private var loadedAt = Instant.EPOCH

  private data class Discovery(val issuer: String, val authorization: String, val token: String, val jwks: String)

  /** The provider's login page for this login ([state], [nonce], the PKCE [verifier]'s challenge). */
  suspend fun authorizationUrl(state: String, nonce: String, verifier: String): String {
    val d = discovery()
    return URLBuilder(d.authorization).apply {
      parameters.append("response_type", "code")
      parameters.append("client_id", config.clientId)
      parameters.append("redirect_uri", redirectUri)
      parameters.append("scope", "openid email profile")
      parameters.append("state", state)
      parameters.append("nonce", nonce)
      parameters.append("code_challenge", pkceChallenge(verifier))
      parameters.append("code_challenge_method", "S256")
    }.buildString()
  }

  /** Exchanges the [code] of the callback for the ID token, checked; [OidcProblem] if refused. */
  suspend fun identity(code: String, nonce: String, verifier: String): OidcIdentity {
    val d = discovery()
    val response = http.submitForm(d.token, parameters {
      append("grant_type", "authorization_code")
      append("code", code)
      append("redirect_uri", redirectUri)
      append("client_id", config.clientId)
      append("client_secret", config.clientSecret)
      append("code_verifier", verifier)
    })
    val body = response.bodyAsText()
    if (!response.status.isSuccess()) throw OidcProblem("The token endpoint answered ${response.status}")
    val idToken = (ApiJson.parseToJsonElement(body).jsonObject["id_token"] as? JsonPrimitive)?.contentOrNull
      ?: throw OidcProblem("No ID token")
    val now = Instant.now()
    return try {
      verifyIdToken(idToken, keys, d.issuer, config.clientId, nonce, now)
    } catch (e: OidcProblem) {
      // A rotated key: once more with the current ones
      if (e.message?.startsWith("Unknown key") != true) throw e
      verifyIdToken(idToken, discovery(force = true).let { keys }, d.issuer, config.clientId, nonce, now)
    }
  }

  private suspend fun discovery(force: Boolean = false): Discovery = mutex.withLock {
    val current = discovery
    if (!force && current != null && loadedAt.isAfter(Instant.now().minusSeconds(1.hours.inWholeSeconds))) return current
    val document = json(http.get(config.issuer.trimEnd('/') + "/.well-known/openid-configuration").bodyAsText()).jsonObject
    fun endpoint(key: String) = document.string(key) ?: throw OidcProblem("The provider names no $key")
    val fresh = Discovery(endpoint("issuer"), endpoint("authorization_endpoint"), endpoint("token_endpoint"), endpoint("jwks_uri"))
    keys = rsaKeys(http.get(fresh.jwks).bodyAsText())
    discovery = fresh
    loadedAt = Instant.now()
    fresh
  }

  private fun json(text: String): JsonElement = runCatching { ApiJson.parseToJsonElement(text) }
    .getOrElse { throw OidcProblem("The provider's answer isn't JSON") }
}
