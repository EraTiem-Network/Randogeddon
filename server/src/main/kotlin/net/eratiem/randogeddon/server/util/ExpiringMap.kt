package net.eratiem.randogeddon.server.util

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * A map whose entries last [keep] from when they were put: one that old is gone for [get] and [remove], and the old
 * ones are dropped whenever one is put (no timer runs). Thread-safe; in memory, so a restart starts afresh. [clock]
 * gives the time in milliseconds; each call takes its `now` from it unless given one.
 */
class ExpiringMap<K : Any, V : Any>(
  private val keep: Duration,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private class Entry<V>(val value: V, val expiresAt: Long)

  private val entries = ConcurrentHashMap<K, Entry<V>>()

  /** [value] under [key] for [keep] from [now], in place of what was there; the expired entries go first. */
  fun put(key: K, value: V, now: Long = clock()) {
    forgetOld(now)
    entries[key] = Entry(value, now + keep.inWholeMilliseconds)
  }

  /** As [put], unless [key] has an entry that hasn't expired: `false` then, and that one stays as it is. */
  fun putIfAbsent(key: K, value: V, now: Long = clock()): Boolean {
    forgetOld(now)
    return entries.putIfAbsent(key, Entry(value, now + keep.inWholeMilliseconds)) == null
  }

  /** The value under [key]; `null` once it expired (or if there is none). */
  fun get(key: K, now: Long = clock()): V? = entries[key]?.takeIf { it.expiresAt > now }?.value

  /** The value under [key] whatever its age: an expired one is there until the next [put] drops it. */
  fun peek(key: K): V? = entries[key]?.value

  /** Removes [key]'s entry; its value if it hadn't expired, else `null`. */
  fun remove(key: K, now: Long = clock()): V? = entries.remove(key)?.takeIf { it.expiresAt > now }?.value

  /** How many entries there are, expired ones not dropped yet too. */
  internal val size: Int get() = entries.size

  private fun forgetOld(now: Long) {
    entries.values.removeIf { it.expiresAt <= now }
  }
}
