package net.eratiem.randogeddon.shared

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class SigningKeyTest {
  /** The bridge's signing key, from this module's directory: an `allowed_signers` line (`<principal> <options> <type> <key>`). */
  private val key = File("../bridge/signing-key.pub")

  @Test
  fun `the fingerprint the web app shows is the one of the bridge's signing key`() {
    val parts = key.readText().trim().split(Regex("\\s+"))
    val type = parts.indexOfFirst { it.startsWith("ssh-") }
    assertEquals("ssh-ed25519", parts[type])
    // As ssh-keygen -l shows it: the SHA-256 of the key's blob, in base64 without its padding
    val blob = Base64.getDecoder().decode(parts[type + 1])
    val hash = MessageDigest.getInstance("SHA-256").digest(blob)
    assertEquals("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash), BRIDGE_SIGNING_KEY_FINGERPRINT)
  }
}
