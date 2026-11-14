package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.eratiem.randogeddon.database.SessionFinishStore
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.REOPEN_WINDOW
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
 * Finishing and reopening sessions. A session finishes by itself [FINISH_DELAY] after every slot is done (admins see
 * the countdown and can cancel it), or by hand. Finishing freezes what the dashboard shows (the archive), stops its
 * tracker connection and its game server; the gateway then refuses to serve the seed. A finished session that wasn't
 * completed (100 %) can be reopened within [REOPEN_WINDOW], while a game server is free.
 */
@Single
class SessionLifecycle(
  private val sessions: LiveSessions,
  private val finishes: SessionFinishStore,
  private val archives: Archives,
  private val gameServer: GameServerClient,
) {
  private val mutex = Mutex()

  fun watch(scope: CoroutineScope) = scope.launch {
    while (true) {
      for (session in sessions.all().filter { !it.finished }) {
        try {
          tick(session)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger.warn("Finishing check of {} failed: {}", session.id, e.message)
        }
      }
      delay(2.seconds)
    }
  }

  private suspend fun tick(session: LiveSession, now: Instant = Instant.now()) = mutex.withLock {
    val seed = session.seedName
    val state = finishes.of(seed) ?: return@withLock
    if (state.finishedAt != null) return@withLock
    // Without the connection the tracker doesn't know; nothing changes then (e.g. while the server restarts)
    val done = session.tracker.read { if (connected) allDone() else null } ?: return@withLock
    if (!done) session.countdownCancelled = false
    when (finishStep(done, state.dueAt, session.countdownCancelled, now)) {
      FinishStep.ARM -> {
        finishes.setDue(seed, now.plus(FINISH_DELAY.toJavaDuration()))
        logger.info("Every slot of {} is done; it finishes in {}", session.id, FINISH_DELAY)
      }
      FinishStep.DISARM -> {
        finishes.setDue(seed, null)
        logger.info("A slot of {} isn't done any more; it doesn't finish", session.id)
      }
      FinishStep.FINISH -> finish(session, by = null, now)
      FinishStep.NONE -> return@withLock
    }
    sessions.refresh()
  }

  /** Finishes session [id] now ([admin] did). */
  suspend fun finishNow(id: String, admin: String) = mutex.withLock {
    val session = session(id)
    if (finishes.of(session.seedName)?.finishedAt != null) throw SessionProblem(HttpStatusCode.Conflict, Problem.ALREADY_FINISHED, "id" to id)
    finish(session, admin, Instant.now())
    sessions.refresh()
  }

  /** Stops the countdown; it starts again only after a slot wasn't done for a while. */
  suspend fun cancel(id: String, admin: String) = mutex.withLock {
    val session = session(id)
    finishes.setDue(session.seedName, null)
    session.countdownCancelled = true
    sessions.refresh()
    logger.info("{} cancelled the finishing of {}", admin, id)
  }

  /** Reopens a finished session: the game server starts on it again (if one is free), and so does its tracker. */
  suspend fun reopen(id: String, admin: String) = mutex.withLock {
    val session = session(id)
    val finishedAt = finishes.of(session.seedName)?.finishedAt ?: throw SessionProblem(HttpStatusCode.Conflict, Problem.NOT_FINISHED, "id" to id)
    val until = reopenUntil(finishedAt, completed(archives.read(id)?.summary?.totals?.percent))
      ?: throw SessionProblem(HttpStatusCode.Conflict, Problem.COMPLETED_NO_REOPEN, "id" to id)
    if (Instant.now().isAfter(until)) {
      throw SessionProblem(HttpStatusCode.Conflict, Problem.TOO_OLD_TO_REOPEN, "id" to id, "days" to REOPEN_WINDOW.inWholeDays)
    }
    finishes.reopen(session.seedName)
    try {
      gameServer.control(id, "start")
    } catch (e: Exception) {
      // No free game server (or no gateway): it stays finished
      finishes.finish(session.seedName, session.finish?.finishedBy, finishedAt)
      throw e
    }
    archives.forget(id)
    // Every slot may still be done: no new countdown until one isn't
    session.countdownCancelled = true
    sessions.refresh()
    logger.info("{} reopened session {}", admin, id)
  }

  private fun session(id: String): LiveSession =
    sessions.get(id) ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)

  /** Freezes the dashboard's view of [session] into its archive, then stops its tracker and game server for good. */
  private suspend fun finish(session: LiveSession, by: String?, now: Instant) {
    val live = session.build()
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
    val details = session.tracker.read { players.values.filter { it.game != WORLD_LOCKS_GAME }.mapNotNull { playerDetail(it.slot) } }
      .map { d -> d.copy(sessions = d.sessions.map { if (it.end == null) it.copy(end = ms) else it }) }
    archives.write(session.id, ArchivedSession(summary, details, by))
    finishes.finish(session.seedName, by, now)
    session.disconnect()
    runCatching { gameServer.control(session.id, "stop") }.onFailure { logger.warn("Could not stop the game server of {}: {}", session.id, it.message) }
    logger.info("Session {} finished{}", session.id, by?.let { " by $it" } ?: " (every slot done)")
  }
}
