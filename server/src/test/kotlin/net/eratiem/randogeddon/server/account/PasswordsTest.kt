package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.server.util.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordsTest {
  @Test
  fun `generated passwords have every class and only characters game clients accept`() {
    repeat(200) {
      val password = PasswordGenerator.generate()
      assertEquals(32, password.length)
      assertTrue(password.any { it.isLowerCase() }, password)
      assertTrue(password.any { it.isUpperCase() }, password)
      assertTrue(password.any { it.isDigit() }, password)
      assertTrue(password.any { it in PasswordGenerator.SYMBOLS }, password)
      // Printable ASCII without spaces, quotes or backslashes
      assertTrue(password.all { it.code in 0x21..0x7e && it !in "\"'`\\" }, password)
    }
    assertNotEquals(PasswordGenerator.generate(), PasswordGenerator.generate())
  }

  @Test
  fun `argon2id hashes verify only the right password`() {
    val hash = PasswordHasher.hash("correct horse")
    assertTrue(hash.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"), hash)
    assertTrue(PasswordHasher.verify("correct horse", hash))
    assertFalse(PasswordHasher.verify("correct horse!", hash))
    // Same password, new salt
    assertNotEquals(hash, PasswordHasher.hash("correct horse"))
  }

  @Test
  fun `the parameters are read from the hash, other algorithms and broken hashes fail`() {
    // Made with other parameters than today's (as before raising them): verify reads them from the string
    val older = PasswordHasher.hash("pw", memoryKib = 8192, iterations = 1)
    assertTrue(older.startsWith("\$argon2id\$v=19\$m=8192,t=1,p=1\$"), older)
    assertTrue(PasswordHasher.verify("pw", older))
    // The same salt and hash with other numbers don't match
    val parts = older.split('$')
    val salt = parts[4]
    assertFalse(PasswordHasher.verify("pw", "\$argon2id\$v=19\$m=19456,t=2,p=1\$$salt\$${parts[5]}"))
    assertFalse(PasswordHasher.verify("pw", "\$argon2i\$v=19\$m=19456,t=2,p=1\$$salt\$${parts[5]}"))
    assertFalse(PasswordHasher.verify("pw", "not a hash"))
    assertFalse(PasswordHasher.verify("pw", "\$argon2id\$v=19\$m=x,t=2,p=1\$AAAA\$AAAA"))
  }

  @Test
  fun `tokens are random and stored only as their hash`() {
    val token = newToken()
    assertEquals(43, token.length) // 32 bytes, base64url without padding
    assertNotEquals(token, newToken())
    assertEquals(64, tokenHash(token).length)
    assertEquals(tokenHash(token), tokenHash(token))
    // SHA-256 in lowercase hex, two digits a byte (as the stored hashes of before)
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", tokenHash("abc"))
    assertEquals("00ff7f80", byteArrayOf(0, -1, 127, -128).hex())
    // A bridge key's 48 bytes
    val key = newToken(48)
    assertEquals(64, key.length)
    assertTrue(key.all { it.isLetterOrDigit() || it == '-' || it == '_' }, key)
  }
}
