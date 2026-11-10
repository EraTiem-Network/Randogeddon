package net.eratiem.archipelago.webapp

import kotlin.math.abs
import kotlin.math.roundToLong

/** `12.3`: one decimal, without the platform's number formatting. */
fun oneDecimal(value: Double): String {
  val tenths = (value * 10).roundToLong()
  return "${tenths / 10}.${abs(tenths % 10)}"
}

/** "just now", "5m ago", "2h ago", "3d ago" or "never". */
fun ago(epochMillis: Long?, now: Long): String {
  if (epochMillis == null) return "never"
  val seconds = ((now - epochMillis) / 1000).coerceAtLeast(0)
  return when {
    seconds < 60 -> "just now"
    seconds < 3600 -> "${seconds / 60}m ago"
    seconds < 86400 -> "${seconds / 3600}h ago"
    else -> "${seconds / 86400}d ago"
  }
}

/** "4h 12m", "12m" or "<1m". */
fun duration(millis: Long): String {
  val minutes = (millis / 60_000).coerceAtLeast(0)
  return when {
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    minutes == 0L && millis > 0 -> "<1m"
    else -> "${minutes}m"
  }
}

/** `12,345` with a thin grouping, readable in every locale. */
fun grouped(value: Int): String = value.toString().reversed().chunked(3).joinToString(",").reversed()
