package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.database.FinishState
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionStatus
import org.koin.core.annotation.Single
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/** A finished session can be reopened this long, unless it was completed. */
val REOPEN_WINDOW = 14.days

/** Until when a session finished at [finishedAt] can be reopened; `null`: not at all, it was [completed] (100 %). */
fun reopenUntil(finishedAt: Instant, completed: Boolean): Instant? =
  if (completed) null else finishedAt.plus(REOPEN_WINDOW.toJavaDuration())

/**
 * When a session finished at [finishedAt] is deleted by itself: [days] after it finished, or after its auto-deletion
 * was enabled again ([since]), whichever is later; `null`: never ([keep], or [days] = 0).
 */
fun deletesAt(finishedAt: Instant, keep: Boolean, days: Int, since: Instant? = null): Instant? =
  if (keep || days <= 0) null
  else maxOf(finishedAt, since ?: finishedAt).plus(java.time.Duration.ofDays(days.toLong()))

/** Every check of every slot is done. */
fun completed(percent: Double?): Boolean = (percent ?: 0.0) >= 100.0

/** Running (about to end by itself: every slot is done), or finished: paused before it was complete, ended after. */
fun statusOf(finish: FinishState?, percent: Double): SessionStatus = when {
  finish?.finishedAt == null -> if (finish?.dueAt != null) SessionStatus.FINISHING else SessionStatus.LIVE
  completed(percent) -> SessionStatus.ENDED
  else -> SessionStatus.PAUSED
}

/**
 * The sessions a user sees: those they play in, every session for admins. Finished ones are kept until an admin
 * deletes them or their auto-deletion ([TrackerConfig.sessionRetentionDays] after they finished, unless disabled).
 */
@Single
class SessionList(private val config: TrackerConfig, private val sessions: LiveSessions) {
  /** What [user] may see, the newest first. */
  suspend fun visibleTo(user: User): List<SessionEntry> = sessions.all().mapNotNull { live ->
    val person = live.personOf(user)
    if (person == null && !user.admin) null else entry(live, person, admin = user.admin)
  }

  /** [live] as the lists show it; [person]: who asks, in it; [admin]: with its game server. */
  suspend fun entry(live: LiveSession, person: String?, admin: Boolean): SessionEntry {
    val s = runCatching { live.summary() }.getOrNull()
    val finish = live.finish
    val finishedAt = finish?.finishedAt
    val percent = s?.totals?.percent ?: 0.0
    val people = live.people()
    return SessionEntry(
      id = live.id,
      seedName = live.seedName,
      title = s?.title ?: config.title,
      status = statusOf(finish, percent),
      createdAt = live.record.createdAt.toEpochMilli(),
      finishedAt = finishedAt?.toEpochMilli(),
      finishedBy = finish?.finishedBy,
      players = people.keys.toList(),
      mySlots = person?.let { people[it] }.orEmpty(),
      slots = s?.totals?.players ?: 0,
      percent = percent,
      goals = s?.totals?.goals ?: 0,
      online = s?.totals?.online ?: 0,
      reopenUntil = finishedAt?.let { reopenUntil(it, completed(percent))?.toEpochMilli() },
      keep = finish?.keep == true,
      deletesAt = finishedAt?.let { deletesAt(it, finish.keep, config.sessionRetentionDays, finish.retainedSince)?.toEpochMilli() },
      server = if (admin) live.server?.state else null,
    )
  }

  /** [user] may see session [id]: an admin, or one of its players. */
  fun canSee(user: User, id: String): Boolean = user.admin || sessions.get(id)?.personOf(user) != null

  /** [user] plays in some session (and may log in to see it). */
  fun playsIn(user: User): Boolean = sessions.all().any { it.personOf(user) != null }
}
