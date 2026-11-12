package net.eratiem.randogeddon.database

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.uuid.Uuid

data class User(val id: Uuid, val name: String, val passwordHash: String, val admin: Boolean = false)

/** The accounts. Names are compared without regard to case, like the unique index in the schema. */
class UserStore(private val db: RandogeddonDatabase) {
  suspend fun active(): List<User> = db.query {
    UsersTable.selectAll().where { UsersTable.deletedAt.isNull() }.map(::user)
  }

  suspend fun findActive(name: String): User? = db.query {
    UsersTable.selectAll()
      .where { (UsersTable.name.lowerCase() eq name.lowercase()) and UsersTable.deletedAt.isNull() }
      .singleOrNull()?.let(::user)
  }

  suspend fun findActive(id: Uuid): User? = db.query {
    UsersTable.selectAll().where { (UsersTable.id eq id) and UsersTable.deletedAt.isNull() }.singleOrNull()?.let(::user)
  }

  suspend fun create(name: String, passwordHash: String, now: Instant = Instant.now()): User = db.query {
    val id = Uuid.random()
    UsersTable.insert {
      it[UsersTable.id] = id
      it[UsersTable.name] = name
      it[UsersTable.passwordHash] = passwordHash
      it[createdAt] = now
    }
    User(id, name, passwordHash)
  }

  /** Admins reach the admin panel. */
  suspend fun setAdmin(id: Uuid, admin: Boolean) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[isAdmin] = admin }
    Unit
  }

  /** Sets a new password and ends every login of the user. */
  suspend fun setPassword(id: Uuid, passwordHash: String) = db.query {
    UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.passwordHash] = passwordHash }
    LoginSessionsTable.deleteWhere { userId eq id }
  }

  private fun user(row: ResultRow) =
    User(row[UsersTable.id], row[UsersTable.name], row[UsersTable.passwordHash], row[UsersTable.isAdmin])
}

/** Logged-in browsers, by the SHA-256 of their cookie token. */
class LoginSessionStore(private val db: RandogeddonDatabase) {
  suspend fun create(tokenHash: String, userId: Uuid, expiresAt: Instant, now: Instant = Instant.now()) = db.query {
    LoginSessionsTable.insert {
      it[LoginSessionsTable.tokenHash] = tokenHash
      it[LoginSessionsTable.userId] = userId
      it[createdAt] = now
      it[LoginSessionsTable.expiresAt] = expiresAt
    }
    Unit
  }

  /** The user of a session that hasn't expired yet. */
  suspend fun userOf(tokenHash: String, now: Instant = Instant.now()): Uuid? = db.query {
    LoginSessionsTable.selectAll()
      .where { (LoginSessionsTable.tokenHash eq tokenHash) and (LoginSessionsTable.expiresAt greater now) }
      .singleOrNull()?.get(LoginSessionsTable.userId)
  }

  suspend fun delete(tokenHash: String) = db.query {
    LoginSessionsTable.deleteWhere { LoginSessionsTable.tokenHash eq tokenHash }
    Unit
  }

  suspend fun deleteExpired(now: Instant = Instant.now()): Int = db.query {
    LoginSessionsTable.deleteWhere { expiresAt less now }
  }
}

/** How long a seed's game server has run: [runTimeMs] up to [countedUntil], which is `null` while it isn't running. */
data class SessionTime(val runTimeMs: Long, val countedUntil: Instant?)

/**
 * The running time of each seed's game server, kept by the gateway: [started] when MultiServer accepts clients,
 * [tick] while it runs, [stopped] when it ends. Times it was stopped don't count.
 */
class SessionTimeStore(private val db: RandogeddonDatabase) {
  suspend fun started(seedName: String, now: Instant = Instant.now()) = db.query {
    sessionId(seedName, now)
    SessionsTable.update({ SessionsTable.seedName eq seedName }) { it[countedUntil] = now }
    Unit
  }

  /** Adds the time since the last count; [running] = false ends the count. */
  suspend fun tick(seedName: String, now: Instant = Instant.now(), running: Boolean = true) = db.query {
    val row = SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull() ?: return@query
    val since = row[SessionsTable.countedUntil] ?: return@query
    val added = (now.toEpochMilli() - since.toEpochMilli()).coerceAtLeast(0)
    SessionsTable.update({ SessionsTable.seedName eq seedName }) {
      it[runTimeMs] = row[SessionsTable.runTimeMs] + added
      it[countedUntil] = if (running) now else null
    }
    Unit
  }

  suspend fun stopped(seedName: String, now: Instant = Instant.now()) = tick(seedName, now, running = false)

  /**
   * After the gateway itself ended without counting (killed): what was counted stays, the open stretch is dropped,
   * since nobody knows how long the server really ran after the last count.
   */
  suspend fun dropOpenCounts() = db.query {
    SessionsTable.update({ SessionsTable.countedUntil.isNotNull() }) { it[countedUntil] = null }
    Unit
  }

  suspend fun of(seedName: String): SessionTime? = db.query {
    SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()
      ?.let { SessionTime(it[SessionsTable.runTimeMs], it[SessionsTable.countedUntil]) }
  }
}

/**
 * A session as the admin panel set it up: the Archipelago version, the options (JSON), and which users play which
 * world ([worlds]: slot name -> user names, in the order they were given).
 */
data class SessionSetup(
  val seedName: String,
  val apVersion: String?,
  val options: String?,
  val createdAt: Instant,
  val worlds: Map<String, List<String>>,
)

/** The sessions the admin panel creates and deletes. */
class SessionSetupStore(private val db: RandogeddonDatabase) {
  /** Records [seedName]'s session as [publicId]; [worlds]: slot name -> the users who play it. */
  suspend fun create(
    publicId: String,
    seedName: String,
    apVersion: String,
    options: String,
    worlds: Map<String, List<Uuid>>,
    now: Instant = Instant.now(),
  ) =
    db.query {
      val id = sessionId(seedName, now, publicId)
      SessionsTable.update({ SessionsTable.id eq id }) {
        it[SessionsTable.publicId] = publicId
        it[SessionsTable.apVersion] = apVersion
        it[SessionsTable.optionsJson] = options
      }
      for ((slot, users) in worlds) {
        for (user in users.distinct()) {
          SessionWorldsTable.insert {
            it[sessionId] = id
            it[slotName] = slot
            it[userId] = user
          }
        }
      }
    }

  suspend fun of(seedName: String): SessionSetup? = db.query {
    val row = SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull() ?: return@query null
    val worlds = (SessionWorldsTable innerJoin UsersTable).selectAll()
      .where { SessionWorldsTable.sessionId eq row[SessionsTable.id] }
      .groupBy({ it[SessionWorldsTable.slotName] }, { it[UsersTable.name] })
    SessionSetup(seedName, row[SessionsTable.apVersion], row[SessionsTable.optionsJson], row[SessionsTable.createdAt], worlds)
  }

  /** Removes the session with its slot passwords, world assignments and session time; the users stay. */
  suspend fun delete(seedName: String): Boolean = db.query {
    SessionsTable.deleteWhere { SessionsTable.seedName eq seedName } > 0
  }
}

/**
 * The ID a session of [seedName] gets when nothing names it otherwise (a seed from before Phase 4): `s-<seed>`, as
 * migration V6 gives them.
 */
fun legacySessionId(seedName: String): String =
  "s-" + seedName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(38)

/** The id of [seedName]'s session, created as [publicId] if it doesn't exist yet (inside a transaction). */
internal fun sessionId(seedName: String, now: Instant, publicId: String = legacySessionId(seedName)): Uuid =
  SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()?.get(SessionsTable.id)
    ?: Uuid.random().also { id ->
      SessionsTable.insert {
        it[SessionsTable.id] = id
        it[SessionsTable.publicId] = publicId
        it[SessionsTable.seedName] = seedName
        it[createdAt] = now
      }
    }

/** A session by its ID: its seed, when it was created and finished, its Archipelago release. */
data class SessionRecord(
  val id: String,
  val seedName: String,
  val createdAt: Instant,
  val apVersion: String?,
  val finishedAt: Instant?,
)

/** The sessions by their IDs (see `SessionIds` in the shared module). */
class SessionStore(private val db: RandogeddonDatabase) {
  /** Every session, the newest first. */
  suspend fun all(): List<SessionRecord> = db.query {
    SessionsTable.selectAll().orderBy(SessionsTable.createdAt, SortOrder.DESC).map(::record)
  }

  suspend fun byId(id: String): SessionRecord? = db.query {
    SessionsTable.selectAll().where { SessionsTable.publicId eq id }.singleOrNull()?.let(::record)
  }

  /** The ID of [seedName]'s session; `null` if there is none. */
  suspend fun idOf(seedName: String): String? = db.query {
    SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()?.get(SessionsTable.publicId)
  }

  private fun record(row: ResultRow) = SessionRecord(
    row[SessionsTable.publicId], row[SessionsTable.seedName], row[SessionsTable.createdAt], row[SessionsTable.apVersion],
    row[SessionsTable.finishedAt],
  )
}

/** The game passwords of each seed's slots. */
class SlotPasswordStore(private val db: RandogeddonDatabase) {
  /**
   * The passwords of [seedName], creating the session and a password for every slot of [slots] that has none yet.
   * Slots keep their password across restarts; a new seed gets new ones.
   */
  suspend fun ensure(seedName: String, slots: Collection<String>, generate: () -> String, now: Instant = Instant.now()):
    Map<String, String> = db.query {
    val sessionId = sessionId(seedName, now)
    val existing = passwords(sessionId)
    for (slot in slots - existing.keys) {
      SlotPasswordsTable.insert {
        it[SlotPasswordsTable.sessionId] = sessionId
        it[slotName] = slot
        it[password] = generate()
      }
    }
    passwords(sessionId)
  }

  /** Slot name -> password of [seedName]; empty if the seed is unknown. */
  suspend fun passwords(seedName: String): Map<String, String> = db.query {
    SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()
      ?.let { passwords(it[SessionsTable.id]) }.orEmpty()
  }

  private fun passwords(sessionId: Uuid): Map<String, String> =
    SlotPasswordsTable.selectAll().where { SlotPasswordsTable.sessionId eq sessionId }
      .associate { it[SlotPasswordsTable.slotName] to it[SlotPasswordsTable.password] }
}

/**
 * Whether a session is finished ([finishedAt], and by whom: `null` by itself), when it finishes by itself ([dueAt]:
 * every slot is done), whether its auto-deletion is disabled ([keep]), and since when its retention counts
 * ([retainedSince], once that was enabled again; `null`: since it finished).
 */
data class FinishState(
  val finishedAt: Instant?,
  val finishedBy: String?,
  val dueAt: Instant?,
  val keep: Boolean,
  val retainedSince: Instant? = null,
)

data class FinishedSession(
  val seedName: String,
  val createdAt: Instant,
  val finishedAt: Instant,
  val finishedBy: String?,
  val keep: Boolean,
  val retainedSince: Instant? = null,
)

/** Finishing and reopening sessions; the tracker decides when, the gateway only asks whether one is finished. */
class SessionFinishStore(private val db: RandogeddonDatabase) {
  suspend fun of(seedName: String): FinishState? = db.query {
    SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()?.let {
      FinishState(
        it[SessionsTable.finishedAt], it[SessionsTable.finishedBy], it[SessionsTable.finishDueAt], it[SessionsTable.keep],
        it[SessionsTable.retainedSince],
      )
    }
  }

  /** The session finishes by itself at [at]; `null`: not (any more). */
  suspend fun setDue(seedName: String, at: Instant?) = db.query {
    SessionsTable.update({ SessionsTable.seedName eq seedName }) { it[finishDueAt] = at }
    Unit
  }

  suspend fun finish(seedName: String, by: String?, now: Instant = Instant.now()) = db.query {
    SessionsTable.update({ SessionsTable.seedName eq seedName }) {
      it[finishedAt] = now
      it[finishedBy] = by
      it[finishDueAt] = null
    }
    Unit
  }

  suspend fun reopen(seedName: String) = db.query {
    SessionsTable.update({ SessionsTable.seedName eq seedName }) {
      it[finishedAt] = null
      it[finishedBy] = null
      it[finishDueAt] = null
    }
    Unit
  }

  /** [keep]: its auto-deletion is disabled. Enabling it again starts its retention anew, at [now]. */
  suspend fun setKeep(seedName: String, keep: Boolean, now: Instant = Instant.now()): Boolean = db.query {
    SessionsTable.update({ SessionsTable.seedName eq seedName }) {
      it[SessionsTable.keep] = keep
      if (!keep) it[retainedSince] = now
    } > 0
  }

  /** Every finished session, the newest first. */
  suspend fun finished(): List<FinishedSession> = db.query {
    SessionsTable.selectAll().where { SessionsTable.finishedAt.isNotNull() }
      .orderBy(SessionsTable.finishedAt, SortOrder.DESC)
      .map {
        FinishedSession(
          it[SessionsTable.seedName], it[SessionsTable.createdAt], it[SessionsTable.finishedAt]!!,
          it[SessionsTable.finishedBy], it[SessionsTable.keep], it[SessionsTable.retainedSince],
        )
      }
  }
}

/** One cheat of an admin; [value]: the item or location, if the action has one. */
data class CheatEntry(val at: Instant, val admin: String, val action: String, val slotName: String, val value: String?)

/** Every cheat from the admin panel, per session, with the admin's name at the time. */
class CheatLogStore(private val db: RandogeddonDatabase) {
  suspend fun add(seedName: String, entry: CheatEntry) = db.query {
    val id = sessionId(seedName, entry.at)
    CheatLogTable.insert {
      it[sessionId] = id
      it[at] = entry.at
      it[admin] = entry.admin
      it[action] = entry.action
      it[slotName] = entry.slotName
      it[value] = entry.value?.take(255)
    }
    Unit
  }

  /** The newest first. */
  suspend fun of(seedName: String, limit: Int = 50): List<CheatEntry> = db.query {
    (CheatLogTable innerJoin SessionsTable).selectAll().where { SessionsTable.seedName eq seedName }
      .orderBy(CheatLogTable.at, SortOrder.DESC).limit(limit)
      .map {
        CheatEntry(it[CheatLogTable.at], it[CheatLogTable.admin], it[CheatLogTable.action], it[CheatLogTable.slotName], it[CheatLogTable.value])
      }
  }
}
