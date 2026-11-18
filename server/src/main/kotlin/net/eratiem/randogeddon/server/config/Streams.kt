package net.eratiem.randogeddon.server.config

import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
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

/** The live streams open per account (each holds a connection for as long as it is open): only so many at once. */
object OpenStreams {
  const val MAX = 20
  private val open = ConcurrentHashMap<String, Int>()

  /** Runs [block] as one of [account]'s streams; not at all if they have [MAX] open already. */
  suspend fun <T> counted(account: String, block: suspend () -> T): T? {
    val mine = open.merge(account, 1, Int::plus) ?: 1
    try {
      return if (mine > MAX) null else block()
    } finally {
      open.compute(account) { _, n -> (n ?: 1).minus(1).takeIf { it > 0 } }
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
  // The summaries and the keepalive come from two coroutines: one at a time on the stream
  val sending = Mutex()
  var lastSent = TimeSource.Monotonic.markNow()
  suspend fun emit(event: ServerSentEvent) = sending.withLock {
    send(event)
    lastSent = TimeSource.Monotonic.markNow()
  }
  // A finished session's summary no longer changes, but finishing (and reopening) changes the version
  val updates = launch { live.changes.filter { it != NO_VERSION }.collect { emit(ServerSentEvent(data = json(), event = "state")) } }
  var unchecked = 0.milliseconds
  while (updates.isActive) {
    delay(KEEPALIVE_CHECK)
    unchecked += KEEPALIVE_CHECK
    if (unchecked >= LOGIN_CHECK) {
      unchecked = 0.milliseconds
      if (!stillAllowed()) break
    }
    if (lastSent.elapsedNow() >= KEEPALIVE) emit(ServerSentEvent(comments = "keepalive"))
  }
  updates.cancel()
}
