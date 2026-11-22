package net.eratiem.randogeddon.gateway

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The largest frame a game client may send, on the wire and unpacked: its packets are small, the big ones come from the server. */
const val CLIENT_FRAME_LIMIT = 1024 * 1024

/**
 * What a game client may take of the gateway before it is in a slot. Anyone on the internet reaches the gateway, and
 * each connection opens one to MultiServer, so the ones that haven't shown a slot's password are kept short and few.
 * Nothing is counted per address: behind a VPN, a carrier's or a shared gateway's address are many households.
 */
data class Limits(
  /** Connections not in a slot yet, of everyone; the ones in a slot aren't limited. */
  val waiting: Int = 1024,
  /**
   * How long a connection may wait for its `Connect` (a text client asks for the slot's name first), counted from its
   * arrival: the wake of a sleeping game server is part of it.
   */
  val connectWithin: Duration = 5.minutes,
  /** Frames a connection may send before its `Connect` (the data package is asked for first). */
  val framesBeforeConnect: Int = 32,
  /**
   * Packets in those frames, in all: each `GetDataPackage` makes MultiServer encode the data package (several MB for
   * a large multiworld), so a frame full of them isn't let through. A real client asks once.
   */
  val packetsBeforeConnect: Int = 32,
)

/** Counts the game clients' connections that aren't in a slot yet against [limits]; one for the whole gateway. */
class ConnectionLimits(val limits: Limits = Limits()) {
  private var waiting = 0

  /** A connection's place: waiting until [joined], given back with [leave]. */
  inner class Ticket internal constructor() {
    private var inSlot = false
    private var left = false

    /** Whether its `Connect` passed. */
    val joined: Boolean get() = synchronized(this@ConnectionLimits) { inSlot }

    /** Its `Connect` passed: it no longer counts as waiting. */
    fun joined() = synchronized(this@ConnectionLimits) {
      if (inSlot || left) return@synchronized
      inSlot = true
      waiting--
    }

    fun leave() = synchronized(this@ConnectionLimits) {
      if (left) return@synchronized
      left = true
      if (!inSlot) waiting--
    }
  }

  /** A place for a new connection; `null`: too many wait for their `Connect` already. */
  fun enter(): Ticket? = synchronized(this) {
    if (waiting >= limits.waiting) return null
    waiting++
    Ticket()
  }
}

/** [text] for a log line: no control characters (a name a client sent could bring line breaks). */
internal fun printable(text: String?): String = withoutControls(text.orEmpty().take(80), '?')

/** [text] with each control character (a line break among them) made [replacement]. */
internal fun withoutControls(text: String, replacement: Char): String =
  buildString(text.length) { text.forEach { append(if (it.isISOControl()) replacement else it) } }
