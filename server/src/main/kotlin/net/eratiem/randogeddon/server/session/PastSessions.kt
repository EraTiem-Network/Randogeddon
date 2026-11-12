package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.database.SessionFinishStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.shared.PastSession
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

/**
 * The finished sessions, kept until an admin deletes them or their auto-deletion ([TrackerConfig.sessionRetentionDays]
 * after they finished, unless disabled). Their players see them (and their spoiler log), admins see all. Who played
 * one comes from its archive, so it stays right when the logins change later.
 */
@Single
class PastSessions(
  private val config: TrackerConfig,
  private val finishes: SessionFinishStore,
  private val archives: Archives,
) {
  /** What [user] may see, the newest first. */
  suspend fun visibleTo(user: User): List<PastSession> {
    val current = Room.read(config.roomJson)?.seedName
    return finishes.finished().mapNotNull { f ->
      if (!user.admin && user.name.lowercase() !in archives.players(f.seedName)) return@mapNotNull null
      val s = archives.read(f.seedName)?.summary
      PastSession(
        seedName = f.seedName,
        title = s?.title ?: config.title,
        createdAt = f.createdAt.toEpochMilli(),
        finishedAt = f.finishedAt.toEpochMilli(),
        finishedBy = f.finishedBy,
        players = s?.people.orEmpty().map { it.name },
        slots = s?.totals?.players ?: 0,
        percent = s?.totals?.percent ?: 0.0,
        goals = s?.totals?.goals ?: 0,
        current = f.seedName == current,
        reopenUntil = reopenUntil(f.finishedAt, completed(s?.totals?.percent))?.toEpochMilli(),
        keep = f.keep,
        deletesAt = deletesAt(f.finishedAt, f.keep, config.sessionRetentionDays, f.retainedSince)?.toEpochMilli(),
      )
    }
  }

  /** [user] may see the finished session [seed]: an admin, or one of its players. */
  suspend fun canSee(user: User, seed: String): Boolean {
    if (finishes.of(seed)?.finishedAt == null) return user.admin
    return user.admin || user.name.lowercase() in archives.players(seed)
  }

  /** [user] played in some finished session (and may log in to see it). */
  suspend fun playedIn(user: User): Boolean =
    finishes.finished().any { user.name.lowercase() in archives.players(it.seedName) }
}
