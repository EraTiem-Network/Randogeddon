package net.eratiem.randogeddon.shared.i18n

import kotlin.math.abs
import kotlin.math.roundToLong

/** `12.3` (`12,3` in German): one decimal, without the platform's number formatting. */
fun Texts.decimal(value: Double): String {
  val tenths = (value * 10).roundToLong()
  val sign = if (tenths < 0) "-" else ""
  return "$sign${abs(tenths / 10)}${format.decimalSeparator}${abs(tenths % 10)}"
}

/** `12,345` (`12.345` in German). */
fun Texts.number(value: Long): String {
  val digits = abs(value).toString().reversed().chunked(3).joinToString(format.groupSeparator.toString()).reversed()
  return if (value < 0) "-$digits" else digits
}

fun Texts.number(value: Int): String = number(value.toLong())

/** "just now", "5m ago", "2h ago", "3d ago" or "never" (in the language). */
fun Texts.ago(epochMillis: Long?, now: Long): String {
  if (epochMillis == null) return format.never
  val seconds = ((now - epochMillis) / 1000).coerceAtLeast(0)
  return when {
    seconds < 60 -> format.justNow
    seconds < 3600 -> format.minutesAgo(seconds / 60)
    seconds < 86400 -> format.hoursAgo(seconds / 3600)
    else -> format.daysAgo(seconds / 86400)
  }
}

/** "4h 12m", "12m" or "<1m" (in the language). */
fun Texts.duration(millis: Long): String {
  val minutes = (millis / 60_000).coerceAtLeast(0)
  return when {
    minutes >= 60 -> format.hoursMinutes(minutes / 60, minutes % 60)
    minutes == 0L && millis > 0 -> format.underAMinute
    else -> format.minutes(minutes)
  }
}

/** An age: "3d 4h" from a day on, else as [duration] ("4h 12m", "12m"). */
fun Texts.age(millis: Long): String {
  val hours = (millis / 3_600_000).coerceAtLeast(0)
  return if (hours >= 24) format.daysHours(hours / 24, hours % 24) else duration(millis)
}

/** A file's size: `840 B`, `88.5 KB`, `4.2 MB` (`88,5 KB` in German). */
fun Texts.fileSize(bytes: Long): String = when {
  bytes < 1024 -> "$bytes B"
  bytes < 1024 * 1024 -> "${decimal(bytes / 1024.0)} KB"
  else -> "${decimal(bytes / (1024.0 * 1024))} MB"
}
