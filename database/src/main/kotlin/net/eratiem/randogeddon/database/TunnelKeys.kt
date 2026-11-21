package net.eratiem.randogeddon.database

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The key that lets Randogeddon Bridge's tunnel reach a directly hosted Minecraft Dig slot's server: made from the
 * slot's password, so the tracker gives it to the session's players and the gateway checks it without anything stored,
 * and nobody gets the password from it (that would let them connect to Archipelago as the slot).
 */
fun minecraftTunnelKey(password: String, slot: String): String {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(password.encodeToByteArray(), "HmacSHA256"))
  return mac.doFinal("randogeddon-minecraft-tunnel\n$slot".encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
