package net.eratiem.randogeddon.server.admin

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.eratiem.randogeddon.database.SessionFinishStore
import net.eratiem.randogeddon.server.ap.TrackerService
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.CurrentSession
import net.eratiem.randogeddon.server.session.REOPEN_WINDOW
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.server.session.Summaries
import net.eratiem.randogeddon.server.session.completed
import net.eratiem.randogeddon.server.session.reopenUntil
import net.eratiem.randogeddon.server.state.WORLD_LOCKS_GAME
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.ArchivedSession
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val logger = logger(MethodHandles.lookup().lookupClass())

/** How long after every slot is done the session finishes by itself. */
val FINISH_DELAY = 2.minutes

/** What the finishing watch does on a tick. */
internal enum class FinishStep { NONE, ARM, DISARM, FINISH }

/**
 * [done]: every slot is at its goal or released. The countdown starts when that happens (unless an admin cancelled it
 * while it stayed so: [cancelled]), stops when it no longer is, and finishes the session when it runs out.
 */
internal fun finishStep(done: Boolean, dueAt: Instant?, cancelled: Boolean, now: Instant): FinishStep = when {
  !done -> if (dueAt != null) FinishStep.DISARM else FinishStep.NONE
  dueAt == null -> if (cancelled) FinishStep.NONE else FinishStep.ARM
  !now.isBefore(dueAt) -> FinishStep.FINISH
  else -> FinishStep.NONE
}

/**
 * Finishing and reopening the session. It finishes by itself [FINISH_DELAY] after every slot is done (admins see the
 * countdown and can cancel it), or by hand. Finishing freezes what the dashboard shows (the archive), keeps the
 * tracker's history and stops the game server; the gateway then refuses to serve the seed. A finished session that
 * wasn't completed (100 %) can be reopened within [REOPEN_WINDOW], also after a newer session replaced it.
 */
@Single
class SessionLifecycle(
  private val config: TrackerConfig,
  private val tracker: TrackerService,
  private val session: CurrentSession,
  private val finishes: SessionFinishStore,
  private val archives: Archives,
  private val summaries: Summaries,
  private val gameServer: GameServerClient,
) {
  private val mutex = Mutex()

  /** The seed whose countdown an admin cancelled; it doesn't start again until a slot isn't done any more. */
  @Volatile
  private var cancelledFor: String? = null

  fun watch(scope: CoroutineScope) = scope.launch {
    while (true) {
      try {
        tick()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn("Finishing check failed: {}", e.message)
      }
      delay(2.seconds)
    }
  }

  private suspend fun tick(now: Instant = Instant.now()) = mutex.withLock {
    val seed = Room.read(config.roomJson)?.seedName ?: return@withLock
    val state = finishes.of(seed) ?: return@withLock
    if (state.finishedAt != null) return@withLock
    // Without the connection the tracker doesn't know; nothing changes then (e.g. while the server restarts)
    val done = tracker.read { if (connected) allDone() else null } ?: return@withLock
    if (!done) cancelledFor = null
    when (finishStep(done, state.dueAt, cancelledFor == seed, now)) {
      FinishStep.ARM -> {
        finishes.setDue(seed, now.plus(FINISH_DELAY.toJavaDuration()))
        logger.info("Every slot of {} is done; it finishes in {}", seed, FINISH_DELAY)
      }
      FinishStep.DISARM -> {
        finishes.setDue(seed, null)
        logger.info("A slot of {} isn't done any more; it doesn't finish", seed)
      }
      FinishStep.FINISH -> finish(seed, by = null, now)
      FinishStep.NONE -> return@withLock
    }
    session.refresh()
  }

  /** Finishes the current session now ([admin] did). */
  suspend fun finishNow(admin: String) = mutex.withLock {
    val seed = current()
    val state = finishes.of(seed)
    if (state?.finishedAt != null) throw SessionProblem(HttpStatusCode.Conflict, "The session is finished already")
    finish(seed, admin, Instant.now())
    session.refresh()
  }

  /** Stops the countdown; it starts again only after a slot wasn't done for a while. */
  suspend fun cancel(admin: String) = mutex.withLock {
    val seed = current()
    finishes.setDue(seed, null)
    cancelledFor = seed
    session.refresh()
    logger.info("{} cancelled the finishing of {}", admin, seed)
  }

  /**
   * Reopens a finished session: the current seed again, or an archived one (then the current session must be
   * finished or gone; it is archived). The game server starts on it.
   */
  suspend fun reopen(seed: String, admin: String) = mutex.withLock {
    val state = finishes.of(seed)
    val finishedAt = state?.finishedAt ?: throw SessionProblem(HttpStatusCode.Conflict, "$seed isn't finished")
    val until = reopenUntil(finishedAt, completed(archives.read(seed)?.summary?.totals?.percent))
      ?: throw SessionProblem(HttpStatusCode.Conflict, "$seed was completed (100 %); a completed session can't be reopened")
    if (Instant.now().isAfter(until)) {
      throw SessionProblem(HttpStatusCode.Conflict, "$seed finished more than ${REOPEN_WINDOW.inWholeDays} days ago")
    }
    val current = Room.read(config.roomJson)?.seedName
    if (current == seed) {
      finishes.reopen(seed)
      gameServer.control("start")
    } else {
      if (current != null && finishes.of(current)?.finishedAt == null) {
        throw SessionProblem(HttpStatusCode.Conflict, "Session $current is running; finish or delete it first")
      }
      if (current != null) gameServer.archiveSession()
      finishes.reopen(seed)
      gameServer.activate(seed)
      archives.history(seed)?.let { tracker.restore(it) } ?: tracker.clear()
    }
    archives.delete(seed)
    // Every slot may still be done: no new countdown until one isn't
    cancelledFor = seed
    session.refresh()
    logger.info("{} reopened session {}", admin, seed)
  }

  private fun current(): String =
    Room.read(config.roomJson)?.seedName ?: throw SessionProblem(HttpStatusCode.Conflict, "There is no session")

  /** Freezes the dashboard's view of [seed] into its archive, then stops the game server for good. */
  private suspend fun finish(seed: String, by: String?, now: Instant) {
    val live = summaries.build()
    val ms = now.toEpochMilli()
    // The session time ends here
    val time = live.sessionTimeMs + (live.sessionRunningSince?.let { (ms - it).coerceAtLeast(0) } ?: 0)
    // Nobody plays on in the archive: connections still open end here
    val worlds = live.players.map { w ->
      w.copy(playTimeMs = w.playTimeMs + (w.onlineSince?.let { (ms - it).coerceAtLeast(0) } ?: 0), onlineSince = null, online = false)
    }
    val people = live.people.map { person ->
      person.copy(online = false, playTimeMs = worlds.filter { it.slot in person.slots }.sumOf { it.playTimeMs })
    }
    val summary = live.copy(
      players = worlds, people = people, totals = live.totals.copy(online = 0),
      finishedAt = ms, finishesAt = null, sessionTimeMs = time, sessionRunningSince = null,
    )
    val details = tracker.read { players.values.filter { it.game != WORLD_LOCKS_GAME }.mapNotNull { playerDetail(it.slot) } }
      .map { d -> d.copy(sessions = d.sessions.map { if (it.end == null) it.copy(end = ms) else it }) }
    tracker.saveNow()
    archives.keepHistory(seed)
    archives.write(seed, ArchivedSession(summary, details, by))
    finishes.finish(seed, by, now)
    runCatching { gameServer.control("stop") }.onFailure { logger.warn("Could not stop the game server: {}", it.message) }
    logger.info("Session {} finished{}", seed, by?.let { " by $it" } ?: " (every slot done)")
  }
}
