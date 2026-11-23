package net.eratiem.randogeddon.shared.web

import kotlin.random.Random
import net.eratiem.randogeddon.shared.Summary

/**
 * [this] as one segment of a request's path: every byte but ASCII letters, digits, `-`, `_` and `~` percent-encoded,
 * so `/`, `?`, `#` and `%` can't change the path. A segment of dots alone (`.`, `..`) is resolved by the browser even
 * encoded (`%2E%2E` counts as `..`): where a name can be that, the caller refuses it instead of sending it.
 */
fun String.pathSegment(): String = encodeToByteArray().joinToString("") { b ->
  val c = b.toInt() and 0xFF
  if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_~")) c.toChar().toString()
  else "%" + c.toString(16).uppercase().padStart(2, '0')
}

/**
 * Whether [this] summary is shown instead of [previous]: not when it is an older one of the same tracker run (the
 * initial fetch can arrive after a newer pushed state). A summary of another run ([Summary.startedAt]: the tracker
 * restarted, its version counts from 0 again) or with another end always is.
 */
fun Summary.replaces(previous: Summary?): Boolean =
  previous == null || startedAt != previous.startedAt || version >= previous.version || finishedAt != previous.finishedAt

/** How long to wait before the [failures]th attempt in a row: 2 s, 4 s, 8 s … up to 30 s, and up to 1 s more by chance. */
fun retryDelay(failures: Int): Long =
  minOf(30_000L, 2_000L shl (failures - 1).coerceIn(0, 4)) + Random.nextLong(1_000)
