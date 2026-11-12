package net.eratiem.randogeddon.server.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.eratiem.randogeddon.server.ap.TrackerService
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.Summary
import org.koin.core.annotation.Single

/**
 * The dashboard's summary of the current session, serialized at most once per change however many dashboards are
 * open. A finished session's is the one frozen when it finished (its archive), so it no longer changes.
 */
@Single
class Summaries(
  private val tracker: TrackerService,
  private val session: CurrentSession,
  private val archives: Archives,
  private val config: TrackerConfig,
) {
  private val mutex = Mutex()
  private var key: Any? = null
  private var json = ""

  /** Changes with the session state, with its people and options, the session time's count and its finishing. */
  suspend fun version(): Long = tracker.read { version } + session.people().hashCode().toLong().shl(32) +
    listOf(session.time, session.options, session.finish).hashCode().toLong().shl(16)

  /** The live summary, built now. */
  suspend fun build(): Summary {
    val people = session.people()
    val time = session.time
    // A session from the admin panel brings its hint switches; a CLI-deployed seed uses the environment's
    val options = session.options
    val finish = session.finish
    return tracker.read {
      summary(
        config.title, tracker.lastError, people,
        options?.pointHints ?: config.pointHints, options?.autoHints ?: config.autoHints,
      )
    }.copy(
      sessionTimeMs = time?.runTimeMs ?: 0,
      sessionRunningSince = time?.countedUntil?.toEpochMilli(),
      finishesAt = finish?.dueAt?.toEpochMilli(),
      finishedAt = finish?.finishedAt?.toEpochMilli(),
    )
  }

  suspend fun json(): String = mutex.withLock {
    val finished = session.finish?.finishedAt
    val seed = session.seedName
    val archived = if (finished != null && seed != null) archives.read(seed) else null
    val current = if (archived != null) seed to finished else version()
    if (current != key) {
      json = ApiJson.encodeToString(Summary.serializer(), archived?.summary ?: build())
      key = current
    }
    json
  }
}
