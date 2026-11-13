package net.eratiem.randogeddon.server.account

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OidcTest {
  private val issuer = "https://auth.example.org/application/o/randogeddon/"
  private val now = Instant.parse("2026-11-13T12:00:00Z")
  private val key: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
  private val other: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
  private val b64 = Base64.getUrlEncoder().withoutPadding()

  private fun unsigned(n: BigInteger) = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()

  private fun jwks(vararg keys: Pair<String, KeyPair>) = keys.joinToString(",", """{"keys":[""", "]}") { (kid, pair) ->
    val public = pair.public as RSAPublicKey
    """{"kty":"RSA","kid":"$kid","use":"sig","alg":"RS256","n":"${b64.encodeToString(unsigned(public.modulus))}","e":"${b64.encodeToString(unsigned(public.publicExponent))}"}"""
  }

  private fun token(
    claims: String = claims(),
    alg: String = "RS256",
    kid: String = "k1",
    signer: KeyPair = key,
  ): String {
    val head = b64.encodeToString("""{"alg":"$alg","kid":"$kid","typ":"JWT"}""".toByteArray())
    val body = b64.encodeToString(claims.toByteArray())
    val signature = Signature.getInstance("SHA256withRSA").apply {
      initSign(signer.private)
      update("$head.$body".toByteArray())
    }.sign()
    return "$head.$body.${b64.encodeToString(signature)}"
  }

  private fun claims(
    iss: String = issuer,
    aud: String = "\"randogeddon\"",
    exp: Long = now.epochSecond + 300,
    nonce: String = "n-1",
  ) = """{"iss":"$iss","aud":$aud,"exp":$exp,"nonce":"$nonce","sub":"abc123","email":"ada@example.org",
    |"email_verified":true,"preferred_username":"ada","name":"Ada","groups":["randogeddon-administrators","infra"]}""".trimMargin()

  private fun verify(token: String) = verifyIdToken(token, rsaKeys(jwks("k1" to key)), issuer, "randogeddon", "n-1", now)

  @Test
  fun `a signed token for this client and login is read`() {
    val identity = verify(token())
    assertEquals("abc123", identity.subject)
    assertEquals("ada@example.org", identity.email)
    assertTrue(identity.emailVerified)
    assertEquals("ada", identity.preferredUsername)
    assertEquals(listOf("randogeddon-administrators", "infra"), identity.groups)
    // The audience may be a list
    assertEquals("abc123", verify(token(claims(aud = """["other","randogeddon"]"""))).subject)
  }

  @Test
  fun `a forged, foreign, expired or replayed token is refused`() {
    assertFailsWith<OidcProblem> { verify(token(signer = other)) }
    assertFailsWith<OidcProblem> { verify(token(kid = "unknown")) }
    assertFailsWith<OidcProblem> { verify(token(alg = "none")) }
    assertFailsWith<OidcProblem> { verify(token(claims(iss = "https://evil.example.org/"))) }
    assertFailsWith<OidcProblem> { verify(token(claims(aud = "\"someone-else\""))) }
    assertFailsWith<OidcProblem> { verify(token(claims(exp = now.epochSecond - 120))) }
    assertFailsWith<OidcProblem> { verify(token(claims(nonce = "n-2"))) }
    assertFailsWith<OidcProblem> { verify("not.a-token") }
    // A minute of clock skew is fine
    assertEquals("abc123", verify(token(claims(exp = now.epochSecond - 30))).subject)
  }

  @Test
  fun `the keys come from the JWKS by their kid`() {
    val keys = rsaKeys(jwks("k1" to key, "k2" to other))
    assertEquals(setOf("k1", "k2"), keys.keys)
    assertEquals("abc123", verifyIdToken(token(kid = "k2", signer = other), keys, issuer, "randogeddon", "n-1", now).subject)
  }

  @Test
  fun `the PKCE challenge is RFC 7636's`() {
    assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
  }
}
