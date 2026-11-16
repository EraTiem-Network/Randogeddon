package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.config.sessionsPerUser
import org.koin.core.annotation.Single
import kotlin.uuid.Uuid

/** Whether someone with [running] running sessions of their own is at the [limit] (can't have one more). */
internal fun atLimit(running: Int, limit: Int): Boolean = running >= limit

/**
 * How many running sessions of their own a user may have at once: the account's own number if an Administrator gave
 * it one, else the default (the setting). It counts the sessions someone created, while they run; whose
 * Session-Manager they are doesn't matter. Asked when a session is created, and when one is reopened (for its
 * creator). Administrators have no limit, and what they do isn't held against anyone's.
 */
@Single
class SessionLimit(private val setups: SessionSetupStore, private val users: UserStore, private val settings: AppSettingsStore) {
  /** Where an account stands: its [running] sessions, its [limit], and whether it has none ([unlimited]). */
  data class Standing(val name: String?, val running: Int, val limit: Int, val unlimited: Boolean) {
    /** They can't have one more running session. */
    val reached: Boolean get() = !unlimited && atLimit(running, limit)

    /** How many more they may have; `null`: as many as they like. */
    val left: Int? get() = if (unlimited) null else (limit - running).coerceAtLeast(0)
  }

  /** The default for accounts without a number of their own. */
  suspend fun default(): Int = sessionsPerUser(settings)

  /** Where [userId] stands; `null`: no such account (any more). */
  suspend fun of(userId: Uuid): Standing? {
    val account = users.account(userId) ?: return null
    return Standing(account.name, setups.runningCreatedBy(userId), account.sessionLimit ?: default(), account.administrator)
  }
}
