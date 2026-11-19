package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What [load] gives, asked again once it is older than [maxAge] (e.g. a setting the tracker changes). */
class Cached<T>(private val maxAge: Duration, private val load: suspend () -> T) {
  private val mutex = Mutex()
  private var value: Pair<TimeMark, T>? = null

  suspend fun get(): T = mutex.withLock {
    value?.let { (at, v) -> if (at.elapsedNow() < maxAge) return@withLock v }
    load().also { value = TimeSource.Monotonic.markNow() to it }
  }
}
