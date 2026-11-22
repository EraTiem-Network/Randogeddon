package net.eratiem.randogeddon.server.config

import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.NO_VERSION

private val KEEPALIVE = 15.seconds
/** How often a stream looks whether its keepalive is due (and its login, every [LOGIN_CHECK]). */
private val KEEPALIVE_CHECK = 5.seconds
/** How often a stream checks that its login still holds (ended: e.g. logged out, or at the provider). */
internal val LOGIN_CHECK = 30.seconds

/**
 * The live streams open per account, and per address for those of a share link (each holds a connection for as long
 * as it is open): only so many at once.
 */
object OpenStreams {
  const val MAX = 20
  private val open = ConcurrentHashMap<String, Int>()

  /**
   * Runs [block] as one of [account]'s streams (or an address's); not at all if they have [max] open already (0: as
   * many as they like).
   */
  suspend fun <T> counted(account: String, max: Int = MAX, block: suspend () -> T): T? {
    val mine = open.merge(account, 1, Int::plus) ?: 1
    try {
      return if (max > 0 && mine > max) null else block()
    } finally {
      open.compute(account) { _, n -> (n ?: 1).minus(1).takeIf { it > 0 } }
    }
  }
}

/** What a stream sends: one event at a time (from any of its coroutines), and a keepalive while it is quiet. */
internal class StreamSender(private val session: ServerSSESession) {
  private val sending = Mutex()
  @Volatile private var lastSent = TimeSource.Monotonic.markNow()

  suspend fun send(event: ServerSentEvent) = sending.withLock {
    session.send(event)
    lastSent = TimeSource.Monotonic.markNow()
  }

  /**
   * The stream's loop, every [tick] while [running]: [step], then a keepalive comment if nothing was sent for
   * [KEEPALIVE] (an idle proxy would cut it), and every [LOGIN_CHECK] whether [stillAllowed]; ends when that says no.
   */
  suspend fun loop(
    tick: Duration,
    stillAllowed: suspend () -> Boolean,
    running: () -> Boolean = { true },
    step: suspend () -> Unit = {},
  ) {
    var unchecked = Duration.ZERO
    while (running()) {
      step()
      delay(tick)
      unchecked += tick
      if (unchecked >= LOGIN_CHECK) {
        unchecked = Duration.ZERO
        if (!stillAllowed()) return
      }
      if (lastSent.elapsedNow() >= KEEPALIVE) send(ServerSentEvent(comments = "keepalive"))
    }
  }
}

/**
 * Sends [live]'s summary ([json]) whenever it changes ([LiveSession.changes], one poller for all its streams), with a
 * keepalive while nothing does, until the client goes or [stillAllowed] says no more.
 */
internal suspend fun ServerSSESession.follow(
  live: LiveSession,
  stillAllowed: suspend () -> Boolean = { true },
  json: suspend () -> String,
) = coroutineScope {
  // The summaries and the keepalive come from two coroutines
  val sender = StreamSender(this@follow)
  // A finished session's summary no longer changes, but finishing (and reopening) changes the version
  val updates = launch { live.changes.filter { it != NO_VERSION }.collect { sender.send(ServerSentEvent(data = json(), event = "state")) } }
  sender.loop(KEEPALIVE_CHECK, stillAllowed, running = { updates.isActive })
  updates.cancel()
}
