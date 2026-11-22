package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import java.net.InetAddress

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
internal fun trustedProxies(value: String): List<AddressRange> =
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
 * itself (someone who reached the tracker past the proxy and could write any header).
 */
internal fun clientAddress(peer: String, forwardedFor: List<String>, trusted: List<AddressRange>): String {
  val believed = trusted.isEmpty() || trusted.any { peer in it }
  val forwarded = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.lastOrNull { it.isNotEmpty() }
  return forwarded?.takeIf { believed } ?: peer
}

class ClientAddressesConfig {
  /** The proxies whose `X-Forwarded-For` is believed; empty: every peer's. */
  var trusted: List<AddressRange> = emptyList()
}

private val CLIENT_KEY = AttributeKey<String>("ClientKey")

/** Works out each call's [clientKey] once, from [clientAddress]. */
val ClientAddresses = createApplicationPlugin("ClientAddresses", ::ClientAddressesConfig) {
  val trusted = pluginConfig.trusted
  onCall { call ->
    val forwardedFor = call.request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty()
    call.attributes.put(CLIENT_KEY, addressKey(clientAddress(call.request.local.remoteAddress, forwardedFor, trusted)))
  }
}

/**
 * Who the caller counts as for the rate limits and the share links' streams: their address ([clientAddress]), an
 * IPv6 address's /64 ([addressKey]).
 */
val ApplicationCall.clientKey: String
  get() = attributes.getOrNull(CLIENT_KEY) ?: addressKey(request.local.remoteAddress)
