package net.eratiem.randogeddon.server.util

import java.security.SecureRandom
import java.util.HexFormat

/** The one source of randomness for what has to be secret: salts, tokens, keys, secrets, locks and hints by chance. */
internal val secureRandom = SecureRandom()

private val HEX = HexFormat.of()

/** The bytes as lowercase hex, two digits each (a hash as it is stored and shown). */
internal fun ByteArray.hex(): String = HEX.formatHex(this)
