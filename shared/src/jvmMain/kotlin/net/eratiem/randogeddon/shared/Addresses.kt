package net.eratiem.randogeddon.shared

import java.net.InetAddress
import java.util.HexFormat

// A client's address behind a proxy, as the tracker and the gateway both decide it: whose X-Forwarded-For is believed
// (TRUSTED_PROXIES), and what counts as one address for their limits. JVM only (java.net), so not in commonMain

/**
 * An address, or a range of them in CIDR notation (`10.0.0.0/8`, `fd00::/8`, `203.0.113.7`). Only literals: nothing
 * is ever looked up.
 */
class AddressRange private constructor(private val network: ByteArray, private val bits: Int) {
  /** Whether [address] (a literal; anything else isn't in it) is in the range. */
  operator fun contains(address: String): Boolean {
    val bytes = addressBytes(address) ?: return false
    if (bytes.size != network.size) return false
    return (0 until bits).all { bit ->
      val mask = 0x80 ushr (bit % 8)
      (bytes[bit / 8].toInt() and mask) == (network[bit / 8].toInt() and mask)
    }
  }

  companion object {
    /** [text] as a range: an address, with `/<bits>` or without (just that address); `null` if it is neither. */
    fun parse(text: String): AddressRange? {
      val parts = text.trim().split('/', limit = 2)
      val bytes = addressBytes(parts[0]) ?: return null
      val bits = if (parts.size == 2) parts[1].trim().toIntOrNull() ?: return null else bytes.size * 8
      return AddressRange(bytes, bits).takeIf { bits in 0..bytes.size * 8 }
    }
  }
}

/**
 * The comma-separated addresses and ranges of [value] (`TRUSTED_PROXIES`); one that is neither stops the start, since
 * it would trust others than meant, or nobody.
 */
fun trustedProxies(value: String): List<AddressRange> =
  value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    .map { AddressRange.parse(it) ?: throw IllegalArgumentException("TRUSTED_PROXIES: $it is neither an address nor a CIDR range") }

private val IPV6 = Regex("[0-9A-Fa-f:.]+")

/** The bytes of an address literal (an IPv4 address written as IPv6 is that IPv4 address); `null` for anything else. */
internal fun addressBytes(address: String): ByteArray? {
  val host = address.trim().removePrefix("[").substringBefore(']').substringBefore('%')
  if (':' !in host) {
    val parts = host.split('.').map { part -> part.toIntOrNull()?.takeIf { it in 0..255 && part.length <= 3 } }
    return if (parts.size == 4 && parts.all { it != null }) ByteArray(4) { parts[it]!!.toByte() } else null
  }
  if (!IPV6.matches(host)) return null
  // In brackets: an IPv6 literal for certain, never a name to look up
  return runCatching { InetAddress.getByName("[$host]").address }.getOrNull()
}

/**
 * The caller's address: the one the last proxy saw (the last of [forwardedFor], the `X-Forwarded-For` headers) when
 * [peer], who connected, is one of the [trusted] proxies (or none are set: then every peer is believed), else [peer]
 * itself (someone who reached the service past the proxy and could write any header).
 */
fun clientAddress(peer: String, forwardedFor: List<String>, trusted: List<AddressRange>): String {
  val believed = trusted.isEmpty() || trusted.any { peer in it }
  val forwarded = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.lastOrNull { it.isNotEmpty() }
  return forwarded?.takeIf { believed } ?: peer
}

/**
 * What counts as one address for a limit: an IPv4 address itself, an IPv6 address's /64 (a provider hands out at
 * least that much to one customer, who can pick any address in it). Anything else as it is.
 */
fun addressKey(address: String): String {
  val host = address.trim().removePrefix("[").substringBefore(']').substringBefore('%')
  if (':' !in host || !IPV6.matches(host)) return host
  // A literal: parsed without a lookup
  val bytes = runCatching { InetAddress.getByName("[$host]").address }.getOrNull() ?: return host
  if (bytes.size != 16) return bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
  return HexFormat.of().formatHex(bytes.copyOf(8)) + "::/64"
}
