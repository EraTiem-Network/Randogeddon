package net.eratiem.randogeddon.server.account

import io.nayuki.qrcodegen.QrCode
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import net.eratiem.randogeddon.server.util.hex
import net.eratiem.randogeddon.server.util.secureRandom

/**
 * Time-based one-time passwords (RFC 6238, the way every authenticator app does them): HMAC-SHA1 over the number of
 * 30-second steps since 1970, 6 digits. A code of the step before or after counts too (clocks drift a little).
 */
object Totp {
  const val DIGITS = 6
  const val STEP_SECONDS = 30L

  /** A new secret: 20 random bytes (160 bits, as RFC 4226 recommends). */
  fun newSecret(): ByteArray = ByteArray(20).also(secureRandom::nextBytes)

  fun step(epochMillis: Long): Long = epochMillis / 1000 / STEP_SECONDS

  /** The code of [step]. */
  fun code(secret: ByteArray, step: Long, digits: Int = DIGITS): String {
    val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
    val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
    val offset = hash.last().toInt() and 0x0f
    val binary = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or
      ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
    var modulo = 1
    repeat(digits) { modulo *= 10 }
    return (binary % modulo).toString().padStart(digits, '0')
  }

  /** The step [code] belongs to (now, or one before or after), or `null` if it isn't a valid code. */
  fun match(secret: ByteArray, code: String, epochMillis: Long): Long? {
    val clean = code.filter { !it.isWhitespace() }
    if (clean.length != DIGITS || !clean.all(Char::isDigit)) return null
    val now = step(epochMillis)
    return listOf(now, now - 1, now + 1).firstOrNull { java.security.MessageDigest.isEqual(code(secret, it).toByteArray(), clean.toByteArray()) }
  }

  /** What authenticator apps scan: `otpauth://totp/Randogeddon:<name>?secret=…&issuer=Randogeddon`. */
  fun uri(secret: ByteArray, account: String, issuer: String = "Randogeddon"): String {
    fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=${Base32.encode(secret)}&issuer=${enc(issuer)}" +
      "&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
  }
}

/** RFC 4648 base32 without padding: how authenticator apps take a secret typed in by hand. */
object Base32 {
  private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

  fun encode(bytes: ByteArray): String = buildString {
    var buffer = 0
    var bits = 0
    for (b in bytes) {
      buffer = (buffer shl 8) or (b.toInt() and 0xff)
      bits += 8
      while (bits >= 5) {
        append(ALPHABET[(buffer shr (bits - 5)) and 31])
        bits -= 5
      }
    }
    if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
  }

  fun decode(text: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (c in text.uppercase().filter { it != '=' && !it.isWhitespace() }) {
      val value = ALPHABET.indexOf(c)
      require(value >= 0) { "Not base32: $c" }
      buffer = (buffer shl 5) or value
      bits += 5
      if (bits >= 8) {
        out.write((buffer shr (bits - 8)) and 0xff)
        bits -= 8
      }
    }
    return out.toByteArray()
  }
}

/**
 * One-time codes for a lost authenticator: 10 of `xxxx-xxxx` (about 41 bits each), shown once, stored as a keyed hash
 * ([SecretBox.mac]; [hash] is how codes from before were stored, still taken until they are used or replaced).
 */
object RecoveryCodes {
  const val COUNT = 10
  // No 0/o, 1/l/i: easy to read off a printout
  private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

  fun generate(): List<String> = List(COUNT) {
    val chars = List(8) { ALPHABET[secureRandom.nextInt(ALPHABET.length)] }.joinToString("")
    "${chars.take(4)}-${chars.drop(4)}"
  }

  /** How a code typed in is compared: lower case, without spaces or dashes. */
  fun normalize(code: String): String = code.lowercase().filter { it in ALPHABET }

  fun hash(code: String): String = tokenHash(normalize(code))

  /** Looks like a recovery code (not a TOTP code). */
  fun looksLike(code: String): Boolean = normalize(code).length == 8
}

/**
 * Encrypts the TOTP secrets (AES-256-GCM): a database dump alone doesn't give anyone's second factor. The key comes
 * from `TOTP_KEY` (base64 of 32 bytes), or else from a key file the tracker makes once in its state directory.
 */
class SecretBox(private val key: ByteArray) {
  init {
    require(key.size == 32) { "The TOTP key must be 32 bytes (base64 in TOTP_KEY)" }
  }

  fun seal(plain: ByteArray): String {
    val iv = ByteArray(12).also(secureRandom::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
    return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain))
  }

  /**
   * A keyed hash of [text] (HMAC-SHA256, hex) under a key of its own made from this one for [purpose]: what is stored
   * of a short secret, which a plain hash would give away to anyone with the database.
   */
  fun mac(purpose: String, text: String): String {
    fun hmac(key: ByteArray, data: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
      .doFinal(data.toByteArray())
    return hmac(hmac(key, purpose), text).hex()
  }

  fun open(sealed: String): ByteArray {
    val bytes = Base64.getDecoder().decode(sealed)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12)) }
    return cipher.doFinal(bytes, 12, bytes.size - 12)
  }

  companion object {
    /** From [configured] (base64), or the key file [file], made with a new key if there is none. */
    fun from(configured: String, file: Path): SecretBox {
      if (configured.isNotBlank()) return SecretBox(Base64.getDecoder().decode(configured.trim()))
      if (!file.exists()) {
        Files.createDirectories(file.parent)
        file.writeText(Base64.getEncoder().encodeToString(ByteArray(32).also(secureRandom::nextBytes)))
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
      }
      return SecretBox(Base64.getDecoder().decode(file.readText().trim()))
    }
  }
}

/** A QR code of [text]: as SVG (for index.html's setup form) and as rows of `0`/`1` (for the web app to draw). */
data class Qr(val svg: String, val rows: List<String>)

fun qr(text: String): Qr {
  val code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
  val size = code.size
  val rows = (0 until size).map { y -> (0 until size).joinToString("") { x -> if (code.getModule(x, y)) "1" else "0" } }
  // A quiet zone of 4 modules around it, as scanners expect
  val path = buildString {
    for (y in 0 until size) for (x in 0 until size) if (code.getModule(x, y)) append("M${x + 4},${y + 4}h1v1h-1z")
  }
  val full = size + 8
  val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $full $full" shape-rendering="crispEdges">""" +
    """<rect width="100%" height="100%" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
  return Qr(svg, rows)
}
