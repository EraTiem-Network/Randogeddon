package net.eratiem.randogeddon.server.account

import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.shared.Problem
import org.koin.core.annotation.Single
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** How many times something happened for each key within a window; in memory (a restart starts afresh). */
internal class Counts(private val window: Duration) {
  private val times = HashMap<String, ArrayDeque<Long>>()

  /** At this many keys, the ones nothing recent is left of go. */
  private var pruneAt = PRUNE_FROM

  /** How often [key] counted within the window before [now]. */
  fun of(key: String, now: Long): Int = synchronized(this) { recent(key, now)?.size ?: 0 }

  fun add(key: String, now: Long) = synchronized(this) {
    if (times.size >= pruneAt) {
      times.keys.toList().forEach { recent(it, now) }
      // Twice as many as are left until the next time: many keys that are all recent aren't looked through at every add
      pruneAt = maxOf(PRUNE_FROM, times.size * 2)
    }
    times.getOrPut(key) { ArrayDeque() }.addLast(now)
  }

  private fun recent(key: String, now: Long): ArrayDeque<Long>? {
    val list = times[key] ?: return null
    while (list.isNotEmpty() && list.first() <= now - window.inWholeMilliseconds) list.removeFirst()
    if (list.isEmpty()) times.remove(key)
    return list.takeIf { it.isNotEmpty() }
  }

  private companion object {
    const val PRUNE_FROM = 10_000
  }
}

/**
 * Wrong passwords and wrong two-factor codes per account, whatever address they come from (the rate limit counts per
 * address): after [MAX] within 10 minutes that account's step is refused until the oldest of them is 10 minutes old.
 * Counted by the name as typed (an unknown one too, so the answer doesn't tell whether it exists), the codes by the
 * account. Someone else's wrong tries can so hold a login up for minutes; guessing gets 10 tries in 10 minutes.
 */
@Single
class LoginAttempts {
  private val failures = Counts(10.minutes)

  fun blocked(key: String, now: Long = System.currentTimeMillis()): Boolean = failures.of(key, now) >= MAX

  fun failed(key: String, now: Long = System.currentTimeMillis()) = failures.add(key, now)

  companion object {
    const val MAX = 10

    /** The key of a password try for the name (or address) as typed. */
    fun password(name: String): String = "password:" + name.trim().lowercase().take(300)

    /**
     * The key of a password try for the account [id], at the login and at a password change: beside the name as typed
     * ([password]), so its name and its address share one count.
     */
    fun accountPassword(id: Uuid): String = "password-of:$id"

    /** The key of a two-factor code try for the account [id]. */
    fun code(id: String): String = "code:$id"
  }
}

/**
 * Mails an account makes the server send (invitations, the confirmation of a new address): at most [SOON] in 10
 * minutes and [DAILY] a day each, so nobody sends mail to strangers in the server's name at will.
 */
@Single
class MailLimits {
  private val soon = Counts(10.minutes)
  private val daily = Counts(1.days)

  /**
   * Counts a mail of [sender] (an account's ID: a new name doesn't start afresh), or a try that would have sent one;
   * [AccountProblem] (429) past a limit.
   */
  fun count(sender: Uuid, now: Long = System.currentTimeMillis()) {
    val key = sender.toString()
    if (soon.of(key, now) >= SOON || daily.of(key, now) >= DAILY) {
      throw AccountProblem(HttpStatusCode.TooManyRequests, Problem.TOO_MANY_MAILS)
    }
    soon.add(key, now)
    daily.add(key, now)
  }

  companion object {
    const val SOON = 10
    const val DAILY = 40
  }
}
