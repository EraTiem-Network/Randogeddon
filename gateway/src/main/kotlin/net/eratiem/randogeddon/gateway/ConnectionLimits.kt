package net.eratiem.randogeddon.gateway

import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The largest frame a game client may send, on the wire and unpacked: its packets are small, the big ones come from the server. */
const val CLIENT_FRAME_LIMIT = 1024 * 1024

/**
 * What a game client may take of the gateway before and after it is in a slot. Anyone on the internet reaches the
 * gateway, and each connection opens one to MultiServer, so the ones that haven't shown a slot's password are kept
 * few and short; a household (or a carrier's shared address) still gets its games, trackers and text clients in.
 */
data class Limits(
  /** Connections of one address (an IPv6 /64 counts as one), in a slot or not. */
  val perAddress: Int = 48,
  /** Of them, the ones not in a slot yet. */
  val waitingPerAddress: Int = 8,
  /** Connections not in a slot yet, of everyone. */
  val waiting: Int = 256,
  /** How long a connection may wait for its `Connect` (a text client asks for the slot's name first). */
  val connectWithin: Duration = 5.minutes,
  /** Frames a connection may send before its `Connect` (the data package is asked for first). */
  val framesBeforeConnect: Int = 32,
)

/** Counts the game clients' connections against [limits]; one for the whole gateway. */
class ConnectionLimits(val limits: Limits = Limits()) {
  private class Count(var all: Int = 0, var waiting: Int = 0)

  private val byAddress = HashMap<String, Count>()
  private var waiting = 0

  /** A connection's place: waiting until [joined], given back with [leave]. */
  inner class Ticket internal constructor(private val key: String) {
    private var inSlot = false
    private var left = false

    /** Whether its `Connect` passed. */
    val joined: Boolean get() = synchronized(this@ConnectionLimits) { inSlot }

    /** Its `Connect` passed: it no longer counts as waiting. */
    fun joined() = synchronized(this@ConnectionLimits) {
      if (inSlot || left) return@synchronized
      inSlot = true
      waiting--
      byAddress[key]?.let { it.waiting-- }
    }

    fun leave() = synchronized(this@ConnectionLimits) {
      if (left) return@synchronized
      left = true
      val count = byAddress[key] ?: return@synchronized
      count.all--
      if (!inSlot) {
        count.waiting--
        waiting--
      }
      if (count.all <= 0) byAddress.remove(key)
    }
  }

  /** A place for a new connection from [address]; `null`: there is none (too many from there, or waiting in all). */
  fun enter(address: String): Ticket? = synchronized(this) {
    val key = addressKey(address)
    val count = byAddress[key]
    if (waiting >= limits.waiting) return null
    if (count != null && (count.all >= limits.perAddress || count.waiting >= limits.waitingPerAddress)) return null
    (count ?: Count().also { byAddress[key] = it }).apply {
      all++
      waiting++
    }
    waiting++
    Ticket(key)
  }
}

private val LITERAL = Regex("[0-9A-Fa-f:.]+")

/**
 * What counts as one address: an IPv4 address itself, an IPv6 address's /64 (a provider hands out at least that much
 * to one customer, who can pick any address in it). Anything else as it is.
 */
internal fun addressKey(address: String): String {
  val host = address.trim().removePrefix("[").substringBefore(']').substringBefore('%')
  if (':' !in host || !LITERAL.matches(host)) return host
  // A literal: parsed without a lookup
  val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return host
  if (bytes.size != 16) return bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
  return bytes.take(8).joinToString("") { "%02x".format(it) } + "::/64"
}

/** [text] for a log line: no control characters (a name a client sent could bring line breaks). */
internal fun printable(text: String?): String = text.orEmpty().map { if (it.isISOControl()) '?' else it }.joinToString("").take(80)
