package net.eratiem.randogeddon.server.account

import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.shared.Problem
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** How many times something happened for each key within a window; in memory (a restart starts afresh). */
internal class Counts(private val window: Duration) {
  private val times = ConcurrentHashMap<String, ArrayDeque<Long>>()

  /** How often [key] counted within the window before [now]. */
  fun of(key: String, now: Long): Int = synchronized(this) { recent(key, now)?.size ?: 0 }

  fun add(key: String, now: Long) = synchronized(this) {
    // Now and then, the keys nothing recent is left of
    if (times.size > 10_000) times.keys.toList().forEach { recent(it, now) }
    times.getOrPut(key) { ArrayDeque() }.addLast(now)
  }

  private fun recent(key: String, now: Long): ArrayDeque<Long>? {
    val list = times[key] ?: return null
    while (list.isNotEmpty() && list.first() <= now - window.inWholeMilliseconds) list.removeFirst()
    if (list.isEmpty()) times.remove(key)
    return list.takeIf { it.isNotEmpty() }
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

  /** Counts a mail of [sender] (an account's ID or name); [AccountProblem] (429) past a limit. */
  fun count(sender: String, now: Long = System.currentTimeMillis()) {
    if (soon.of(sender, now) >= SOON || daily.of(sender, now) >= DAILY) {
      throw AccountProblem(HttpStatusCode.TooManyRequests, Problem.TOO_MANY_MAILS)
    }
    soon.add(sender, now)
    daily.add(sender, now)
  }

  companion object {
    const val SOON = 10
    const val DAILY = 40
  }
}
