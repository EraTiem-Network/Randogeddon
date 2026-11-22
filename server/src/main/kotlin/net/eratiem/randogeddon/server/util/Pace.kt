package net.eratiem.randogeddon.server.util

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * How fast each key (a user and what they ask for) may go: [burst] calls at once, then one every [spacing] (a bucket
 * that fills again by one each [spacing]). What a person does at a form passes; a loop is held to the [spacing]. In
 * memory: a restart starts afresh.
 */
class Pace(private val burst: Int, private val spacing: Duration) {
  private class Bucket(var tokens: Double, var at: Long)

  private val buckets = ConcurrentHashMap<String, Bucket>()

  /** Whether [key] may go on now, counted; `false`: too fast. */
  fun take(key: String, now: Long = System.currentTimeMillis()): Boolean {
    // Now and then, the keys that have filled up again
    if (buckets.size > 10_000) buckets.values.removeIf { filled(it, now) >= burst }
    var taken = false
    buckets.compute(key) { _, old ->
      val bucket = old ?: Bucket(burst.toDouble(), now)
      bucket.tokens = filled(bucket, now)
      bucket.at = maxOf(bucket.at, now)
      taken = bucket.tokens >= 1
      if (taken) bucket.tokens -= 1
      bucket
    }
    return taken
  }

  private fun filled(bucket: Bucket, now: Long): Double =
    minOf(burst.toDouble(), bucket.tokens + (now - bucket.at).coerceAtLeast(0) / spacing.inWholeMilliseconds.toDouble())
}
