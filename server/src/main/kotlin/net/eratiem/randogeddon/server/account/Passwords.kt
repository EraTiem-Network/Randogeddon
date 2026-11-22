package net.eratiem.randogeddon.server.account

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.util.Base64
import net.eratiem.randogeddon.server.util.hex
import net.eratiem.randogeddon.server.util.secureRandom

/**
 * Generated passwords (logins and game slots), see `.docs/TODO.md`, Conventions: 32 characters with lower- and
 * upper-case letters, digits and symbols. Players type the slot passwords into game clients, some of them only into an
 * in-game text field, so the symbols are printable ASCII without spaces, quotes or backslashes, which every client
 * accepts and which don't break config files.
 */
object PasswordGenerator {
  private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
  private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
  private const val DIGITS = "0123456789"
  const val SYMBOLS = "!#$%&()*+,-./:;<=>?@[]^_{|}~"
  private val CLASSES = listOf(LOWER, UPPER, DIGITS, SYMBOLS)
  private val ALL = CLASSES.joinToString("")

  fun generate(length: Int = 32): String {
    require(length >= CLASSES.size) { "A password needs at least ${CLASSES.size} characters" }
    // One of each class, the rest from all of them, then shuffled so the classes aren't at fixed places
    val chars = CLASSES.map { it.pick() } + List(length - CLASSES.size) { ALL.pick() }
    return chars.shuffled(secureRandom).joinToString("")
  }

  private fun String.pick(): Char = this[secureRandom.nextInt(length)]
}

/**
 * Argon2id for the login passwords, stored in the PHC string format (`$argon2id$v=19$m=…,t=…,p=…$salt$hash`), so the
 * parameters can be raised later without breaking existing hashes. Parameters: OWASP's recommendation (19 MiB, 2
 * iterations, 1 lane).
 */
object PasswordHasher {
  private const val MEMORY_KIB = 19 * 1024
  private const val ITERATIONS = 2
  private const val PARALLELISM = 1
  private const val SALT_BYTES = 16
  private const val HASH_BYTES = 32
  private val encoder = Base64.getEncoder().withoutPadding()
  private val decoder = Base64.getDecoder()

  /** Checked when the name is unknown, so a failed login takes as long either way. */
  val dummyHash: String by lazy { hash(PasswordGenerator.generate()) }

  /** Other parameters than the defaults only for tests (hashes made before the parameters were raised). */
  fun hash(password: String, memoryKib: Int = MEMORY_KIB, iterations: Int = ITERATIONS): String {
    val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
    val hash = argon2(password, salt, memoryKib, iterations, PARALLELISM, HASH_BYTES)
    return "\$argon2id\$v=19\$m=$memoryKib,t=$iterations,p=$PARALLELISM\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
  }

  fun verify(password: String, phc: String): Boolean {
    // "", "argon2id", "v=19", "m=…,t=…,p=…", salt, hash
    val parts = phc.split('$')
    if (parts.size != 6 || parts[1] != "argon2id" || parts[2] != "v=19") return false
    val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
    val memory = params["m"] ?: return false
    val iterations = params["t"] ?: return false
    val parallelism = params["p"] ?: return false
    val salt = runCatching { decoder.decode(parts[4]) }.getOrNull() ?: return false
    val expected = runCatching { decoder.decode(parts[5]) }.getOrNull() ?: return false
    val actual = argon2(password, salt, memory, iterations, parallelism, expected.size)
    return MessageDigest.isEqual(expected, actual)
  }

  private fun argon2(password: String, salt: ByteArray, memoryKib: Int, iterations: Int, parallelism: Int, length: Int):
    ByteArray {
    val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withMemoryAsKB(memoryKib)
      .withIterations(iterations)
      .withParallelism(parallelism)
      .withSalt(salt)
      .build()
    return ByteArray(length).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toCharArray(), it) }
  }
}

/** A random token of [bytes] bytes, URL-safe: a login token for the session cookie (256 bits), or a bridge key. */
fun newToken(bytes: Int = 32): String =
  Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(secureRandom::nextBytes))

/** What is stored of a token: its SHA-256, hex. A database leak then doesn't hand out logins. */
fun tokenHash(token: String): String =
  MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).hex()
