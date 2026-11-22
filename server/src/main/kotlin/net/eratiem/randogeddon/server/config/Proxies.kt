package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import net.eratiem.randogeddon.shared.AddressRange
import net.eratiem.randogeddon.shared.addressKey
import net.eratiem.randogeddon.shared.clientAddress

// The address ranges, TRUSTED_PROXIES, the client's address and what counts as one are shared with the gateway
// (shared, jvmMain)

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
