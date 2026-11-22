package net.eratiem.randogeddon.server.account

import java.util.Locale
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.shared.i18n.texts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.database.AccountActivity
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.AutoDelete
import org.koin.core.annotation.Single
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.hours

private val logger = logger(MethodHandles.lookup().lookupClass())

/** The warning mail goes out this long before an account is deleted. */
const val DELETE_WARNING_DAYS = 14L

/** At least this long between the warning and the deletion (for the 2-week period, warned right away). */
const val DELETE_AFTER_WARNING_DAYS = 2L

/**
 * When [activity]'s account is deleted by itself, as of [now]: its period after the end of the user's last session
 * (or the sign-up, without one); `null` while they play in a session that isn't finished, or with "never".
 */
fun deletesAt(activity: AccountActivity, now: Instant = Instant.now()): Instant? {
  val days = AutoDelete.of(activity.user.autoDelete).days ?: return null
  if (activity.playing) return null
  val since = activity.lastSessionEnd ?: activity.user.signedUpAt ?: return null
  val due = since.plus(days.toLong(), ChronoUnit.DAYS)
  // Never before the user had the time to read the warning
  val warned = activity.warnedAt?.takeIf { activity.user.email != null }
  return if (warned != null) maxOf(due, warned.plus(DELETE_AFTER_WARNING_DAYS, ChronoUnit.DAYS)) else due
}

/** What the cleanup does with an account now. */
enum class CleanupStep { NOTHING, WARN, UNWARN, DELETE }

/**
 * The next step for [activity]'s account: warn [DELETE_WARNING_DAYS] before its deletion (once; again after it moved
 * later), delete when it's due. A user without an address can't be warned and is deleted when due.
 */
fun cleanupStep(activity: AccountActivity, now: Instant = Instant.now()): CleanupStep {
  val due = deletesAt(activity, now)
  val warnFrom = due?.minus(DELETE_WARNING_DAYS, ChronoUnit.DAYS)
  val canWarn = activity.user.email != null
  return when {
    due == null || now < warnFrom -> if (activity.warnedAt != null) CleanupStep.UNWARN else CleanupStep.NOTHING
    canWarn && activity.warnedAt == null -> CleanupStep.WARN
    now >= due -> CleanupStep.DELETE
    else -> CleanupStep.NOTHING
  }
}

/** Warns and deletes the accounts whose time has come, every few hours. */
@Single
class AccountCleanup(
  private val users: UserStore,
  private val mailer: Mailer,
  private val sessions: LiveSessions,
  private val config: TrackerConfig,
) {
  fun watch(scope: CoroutineScope) = scope.launch {
    while (true) {
      runCatching { runOnce() }.onFailure { logger.warn("Could not clean up the accounts: {}", it.message) }
      delay(6.hours)
    }
  }

  suspend fun runOnce(now: Instant = Instant.now()) {
    // The sessions' finish state, as the database has it now
    sessions.refresh()
    for (activity in users.activity()) {
      // One account that fails (the database a moment away) doesn't keep the others from their step
      try {
        step(activity, now)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn("Could not clean up the account of {}: {}", activity.user.name ?: activity.user.id, e.message)
      }
    }
  }

  private suspend fun step(activity: AccountActivity, now: Instant) {
    val user = activity.user
    // With single sign-on, Administrators' accounts go when the provider deletes them (its webhook)
    if (config.oidc != null && user.administrator) return
    when (cleanupStep(activity, now)) {
      CleanupStep.NOTHING -> Unit
      CleanupStep.UNWARN -> users.markWarned(user.id, null)
      CleanupStep.WARN -> {
        val due = deletesAt(activity.copy(warnedAt = now), now) ?: return
        val language = Language.of(user.language) ?: Language.DEFAULT
        val date = (if (language.numericDates) NUMERIC_DATE else DATE).format(due)
        val mail = Mails.deletionWarning(user.email ?: return, user.name ?: "", date, "${config.publicUrl}/#/settings", texts(language).mails)
        try {
          mailer.send(mail)
          users.markWarned(user.id, now)
        } catch (e: IOException) {
          logger.warn("Could not warn {} about the deletion: {}", user.name, e.message)
        }
      }
      CleanupStep.DELETE -> {
        users.delete(user.id, now)
        logger.info("Deleted the account of {} (auto-deletion: {})", user.name, AutoDelete.of(user.autoDelete).label)
      }
    }
  }

  private companion object {
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    // Digits only: the native image has no German month names
    val NUMERIC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC)
  }
}
