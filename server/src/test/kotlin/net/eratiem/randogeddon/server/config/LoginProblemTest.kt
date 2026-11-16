package net.eratiem.randogeddon.server.config

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.renderSetCookieHeader
import net.eratiem.randogeddon.server.admin.atLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginProblemTest {
  @Test
  fun `a login problem reaches the page in a short cookie it can read, as one encoded value`() {
    val text = "Authentik can't be reached: 1+1 ä; ok"
    val header = renderSetCookieHeader(problemCookie(text, secure = true))
    val value = header.substringAfter("$LOGIN_PROBLEM_COOKIE=").substringBefore(";")
    // Nothing in it ends the value or starts another attribute; the page decodes it the same way (+ is a space)
    assertTrue(Regex("[A-Za-z0-9%+._~*'()!-]+").matches(value), value)
    assertEquals(text, value.decodeURLQueryComponent(plusIsSpace = true))
    val attributes = header.substringAfter(";").split(";").map { it.trim() }
    assertTrue("Max-Age=60" in attributes && "Path=/" in attributes && "Secure" in attributes && "SameSite=Lax" in attributes, header)
    // The page's script reads it
    assertFalse(attributes.any { it.equals("HttpOnly", ignoreCase = true) }, header)
  }

  @Test
  fun `the limit of running sessions is reached at it, not only above`() {
    assertFalse(atLimit(running = 0, limit = 1))
    assertTrue(atLimit(running = 1, limit = 1))
    assertTrue(atLimit(running = 3, limit = 2))
    // 0: only Administrators run sessions
    assertTrue(atLimit(running = 0, limit = 0))
  }
}
