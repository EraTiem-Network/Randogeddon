package net.eratiem.archipelago.server.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The logger of [type]. Called as `logger(MethodHandles.lookup().lookupClass())` at the top of a file, so the logger
 * is named after the file's class and follows moves and renames.
 */
fun logger(type: Class<*>): Logger = LOGGERS.get(type)

/** Caches one logger per class, so logging does not look up the logger on every call. */
private val LOGGERS = object : ClassValue<Logger>() {
  override fun computeValue(type: Class<*>): Logger = LoggerFactory.getLogger(type)
}
