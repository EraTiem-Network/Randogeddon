package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.database.FinishState
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionRole
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
  /** What [user] may see (every session they have a part in; an Administrator all), the newest first. */
  suspend fun visibleTo(user: User): List<SessionEntry> = sessions.all().mapNotNull { live ->
    val role = live.roleOf(user)
    if (role == null && !user.administrator) null
    else entry(live, live.personOf(user), admin = live.managedBy(user), role = role)
  }

  /** [live] as the lists show it; [person]: who asks, in it; [admin]: with its game server; [role]: theirs in it. */
  suspend fun entry(live: LiveSession, person: String?, admin: Boolean, role: SessionRole? = null): SessionEntry {
    val s = runCatching { live.summary() }.getOrNull()
    val finish = live.finish
    val finishedAt = finish?.finishedAt
    val percent = s?.totals?.percent ?: 0.0
    val people = live.people()
    return SessionEntry(
      id = live.id,
      seedName = live.seedName,
      spoilerReady = live.playthroughReady,
      title = s?.title ?: config.title,
      status = statusOf(finish, percent),
      createdAt = live.record.createdAt.toEpochMilli(),
      finishedAt = finishedAt?.toEpochMilli(),
      finishedBy = finish?.finishedBy,
      players = people.keys.toList(),
      mySlots = person?.let { people[it] }.orEmpty(),
      myRole = role,
      slots = s?.totals?.players ?: 0,
      percent = percent,
      checked = s?.totals?.checked ?: 0,
      total = s?.totals?.total ?: 0,
      goals = s?.totals?.goals ?: 0,
      online = s?.totals?.online ?: 0,
      reopenUntil = finishedAt?.let { reopenUntil(it, completed(percent))?.toEpochMilli() },
      keep = finish?.keep == true,
      deletesAt = finishedAt?.let { deletesAt(it, finish.keep, config.sessionRetentionDays, finish.retainedSince)?.toEpochMilli() },
      server = if (admin) live.server?.state else null,
    )
  }

  /** [user] may see session [id]: an Administrator, or anyone with a part in it. */
  fun canSee(user: User, id: String): Boolean = user.administrator || roleIn(user, id) != null

  /** [user]'s part in session [id]; `null`: none (or no such session). */
  fun roleIn(user: User, id: String): SessionRole? = sessions.get(id)?.roleOf(user)

  /** The sessions [user] is a Session-Manager of. */
  fun managedBy(user: User): List<String> = sessions.all().filter { user.id in it.managers() }.map { it.id }
}
