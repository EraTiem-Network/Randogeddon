package net.eratiem.randogeddon.server.account

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TotpTest {
  /** RFC 6238, appendix B: the SHA-1 test vectors (8 digits). */
  @Test
  fun `codes match the RFC's test vectors`() {
    val secret = "12345678901234567890".toByteArray()
    mapOf(59L to "94287082", 1111111109L to "07081804", 1234567890L to "89005924", 20000000000L to "65353130").forEach { (time, code) ->
      assertEquals(code, Totp.code(secret, time / 30, digits = 8), "at $time")
    }
    assertEquals("287082", Totp.code(secret, 59L / 30))
  }

  @Test
  fun `a code counts for its step and the ones next to it, nothing else`() {
    val secret = Totp.newSecret()
    val now = 1_800_000_000_000L
    val step = Totp.step(now)
    assertEquals(step, Totp.match(secret, Totp.code(secret, step), now))
    assertEquals(step - 1, Totp.match(secret, Totp.code(secret, step - 1), now))
    assertEquals(step + 1, Totp.match(secret, " ${Totp.code(secret, step + 1).chunked(3).joinToString(" ")} ", now))
    assertNull(Totp.match(secret, Totp.code(secret, step - 2), now))
    assertNull(Totp.match(secret, "12345", now))
    assertNull(Totp.match(secret, "abcdef", now))
  }

  @Test
  fun `base32 as RFC 4648 has it, without padding`() {
    assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
    assertEquals("foobar", String(Base32.decode("mzxw6ytboi======")))
    val secret = Totp.newSecret()
    assertContentEquals(secret, Base32.decode(Base32.encode(secret)))
  }

  @Test
  fun `the link authenticator apps scan`() {
    val uri = Totp.uri("foobar".toByteArray(), "Dr. Ünicode")
    assertEquals(
      "otpauth://totp/Randogeddon:Dr.%20%C3%9Cnicode?secret=MZXW6YTBOI&issuer=Randogeddon&algorithm=SHA1&digits=6&period=30",
      uri,
    )
    val code = qr(uri)
    assertTrue(code.svg.startsWith("<svg") && "<path d=\"M" in code.svg)
    assertTrue(code.rows.size >= 21 && code.rows.all { it.length == code.rows.size && it.all { c -> c == '0' || c == '1' } })
  }

  @Test
  fun `secrets are sealed with the key, and a changed one doesn't open`() {
    val box = SecretBox(ByteArray(32) { it.toByte() })
    val secret = Totp.newSecret()
    val sealed = box.seal(secret)
    assertNotEquals(sealed, box.seal(secret))
    assertContentEquals(secret, box.open(sealed))
    val tampered = sealed.dropLast(4) + if (sealed.takeLast(4) == "AAAA") "BBBB" else "AAAA"
    assertFailsWith<Exception> { box.open(tampered) }
    assertFailsWith<Exception> { SecretBox(ByteArray(32)).open(sealed) }
  }

  @Test
  fun `without TOTP_KEY the key comes from a file made once`() {
    val file = Files.createTempDirectory("state").resolve("totp.key")
    val first = SecretBox.from("", file)
    assertTrue(file.exists())
    val sealed = first.seal("secret".toByteArray())
    assertEquals("secret", String(SecretBox.from("", file).open(sealed)))
  }

  @Test
  fun `recovery codes are 10 of xxxx-xxxx, compared without case, spaces or dashes`() {
    val codes = RecoveryCodes.generate()
    assertEquals(10, codes.distinct().size)
    assertTrue(codes.all { Regex("[a-z2-9]{4}-[a-z2-9]{4}").matches(it) })
    val code = codes.first()
    assertEquals(RecoveryCodes.normalize(code), RecoveryCodes.normalize(" ${code.uppercase().replace("-", " ")} "))
    assertTrue(RecoveryCodes.looksLike(code))
    assertTrue(!RecoveryCodes.looksLike("123456"))
  }

  @Test
  fun `a pending login lasts 5 minutes and 5 wrong codes`() {
    val pending = PendingLogins()
    val user = Uuid.random()
    val token = pending.create(user, now = 0)
    assertEquals(user, pending.userOf(token, now = 60_000))
    assertNull(pending.userOf(token, now = 5 * 60_000))
    val other = pending.create(user, now = 0)
    repeat(4) { pending.wrongCode(other) }
    assertEquals(user, pending.userOf(other, now = 1))
    pending.wrongCode(other)
    assertNull(pending.userOf(other, now = 1))
    val done = pending.create(user, now = 0)
    pending.finish(done)
    assertNull(pending.userOf(done, now = 1))
  }

  @Test
  fun `a keyed hash needs the key, and differs by purpose`() {
    val box = SecretBox(ByteArray(32) { it.toByte() })
    val hash = box.mac("recovery codes", "abcdefgh")
    assertEquals(64, hash.length)
    assertEquals(hash, box.mac("recovery codes", "abcdefgh"))
    // Not the plain hash a database dump could be searched with, another under another key, purpose or text
    assertTrue(hash != tokenHash("abcdefgh"))
    assertTrue(hash != SecretBox(ByteArray(32)).mac("recovery codes", "abcdefgh"))
    assertTrue(hash != box.mac("something else", "abcdefgh"))
    assertTrue(hash != box.mac("recovery codes", "abcdefgj"))
  }
}
