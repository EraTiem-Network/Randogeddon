package net.eratiem.randogeddon.gateway

import net.eratiem.randogeddon.shared.addressKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The largest frame a game client may send, on the wire and unpacked: its packets are small, the big ones come from the server. */
const val CLIENT_FRAME_LIMIT = 1024 * 1024

/**
 * What a game client may take of the gateway before it is in a slot. Anyone on the internet reaches the gateway, and
 * each connection opens one to MultiServer, so the ones that haven't shown a slot's password are kept short and few.
 */
data class Limits(
  /** Connections not in a slot yet, of everyone; the ones in a slot aren't limited. */
  val waiting: Int = 1024,
  /**
   * Of those, from one address (an IPv6 /64 as one): so a few addresses can't take every place. Generous, since behind
   * a VPN, a carrier's or a shared gateway's address are many households; a real client is in its slot within a second.
   */
  val perAddress: Int = 32,
  /**
   * How long a connection may wait for its `Connect` (a text client asks for the slot's name first), counted from its
   * arrival: the wake of a sleeping game server is part of it.
   */
  val connectWithin: Duration = 5.minutes,
  /** Frames a connection may send before its `Connect` (the data package is asked for first). */
  val framesBeforeConnect: Int = 32,
  /**
   * Packets in those frames, in all: each `GetDataPackage` makes MultiServer encode the data package (several MB for
   * a large multiworld), or the gateway read it ([KeptRoom]), so a frame full of them isn't let through. A real client
   * asks once.
   */
  val packetsBeforeConnect: Int = 32,
)

/**
 * Counts the game clients' connections that aren't in a slot yet against [limits]: one count for the whole gateway,
 * and one per address ([addressKey]: an IPv6 /64 as one).
 */
class ConnectionLimits(val limits: Limits = Limits()) {
  private var waiting = 0
  private val byAddress = HashMap<String, Int>()

  /** What [enter] gives: a place, or why there's none. */
  sealed interface Entry

  /** Too many wait for their `Connect`: of everyone ([ALL]), or from this address ([ADDRESS]). */
  enum class Full : Entry { ALL, ADDRESS }

  /** A connection's place: waiting until [joined], given back with [leave]. */
  inner class Ticket internal constructor(private val key: String) : Entry {
    private var inSlot = false
    private var left = false

    /** Whether its `Connect` passed. */
    val joined: Boolean get() = synchronized(this@ConnectionLimits) { inSlot }

    /** Its `Connect` passed: it no longer counts as waiting. */
    fun joined() = synchronized(this@ConnectionLimits) {
      if (inSlot || left) return@synchronized
      inSlot = true
      free(key)
    }

    fun leave() = synchronized(this@ConnectionLimits) {
      if (left) return@synchronized
      left = true
      if (!inSlot) free(key)
    }
  }

  /** A place for a new connection from [address]; or why there's none. */
  fun enter(address: String): Entry = synchronized(this) {
    val key = addressKey(address)
    if (waiting >= limits.waiting) return Full.ALL
    if ((byAddress[key] ?: 0) >= limits.perAddress) return Full.ADDRESS
    waiting++
    byAddress.merge(key, 1, Int::plus)
    Ticket(key)
  }

  /** How many from [address] wait now. */
  internal fun waitingFrom(address: String): Int = synchronized(this) { byAddress[addressKey(address)] ?: 0 }

  private fun free(key: String) {
    waiting--
    // Gone at 0, so the map holds only the addresses that wait
    byAddress.computeIfPresent(key) { _, n -> (n - 1).takeIf { it > 0 } }
  }
}

/** [text] for a log line: no control characters (a name a client sent could bring line breaks). */
internal fun printable(text: String?): String = withoutControls(text.orEmpty().take(80), '?')

/** [text] with each control character (a line break among them) made [replacement]. */
internal fun withoutControls(text: String, replacement: Char): String =
  buildString(text.length) { text.forEach { append(if (it.isISOControl()) replacement else it) } }
