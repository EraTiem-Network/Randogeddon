package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.database.SessionRoleStore
import net.eratiem.randogeddon.server.config.sessionsPerUser
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

/** Whether someone who manages [running] running sessions is at the [limit] (can't manage one more). */
internal fun atLimit(running: Int, limit: Int): Boolean = running >= limit

/**
 * The Administrators' limit of running sessions one user manages at once. Asked wherever a running session starts to
 * count for someone: creating one, reopening one, becoming Session-Manager of one. Only at creating, it had ways
 * around (finish, create, reopen; or pass sessions on to another account). Administrators have no limit, and what
 * they decide for others isn't held against it.
 */
@Single
class SessionLimit(private val roles: SessionRoleStore, private val settings: AppSettingsStore) {
  data class Reached(val running: Int, val limit: Int)

  /** How many running sessions [userId] manages and the limit, if they can't manage one more; `null`: they can. */
  suspend fun reached(userId: Uuid): Reached? {
    val limit = sessionsPerUser(settings)
    val running = roles.runningManagedBy(userId)
    return Reached(running, limit).takeIf { atLimit(running, limit) }
  }
}
