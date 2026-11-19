package net.eratiem.randogeddon.database

import java.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

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
 * world ([worlds]: slot name -> the names they have in this session, both by name; [members]: user
 * ID -> that name; [deleted]: the members whose account is deleted; [roles]: who else has a role in it).
 */
data class SessionSetup(
  val seedName: String,
  val apVersion: String?,
  val options: String?,
  val createdAt: Instant,
  val worlds: Map<String, List<String>>,
  val members: Map<Uuid, String> = emptyMap(),
  val deleted: Set<Uuid> = emptySet(),
  val roles: Map<Uuid, SessionRoleEntry> = emptyMap(),
  /** Its hosted slots: games that run as a server of their own, played by no account. */
  val hosted: Set<String> = emptySet(),
  /** Who created it: it counts towards their limit while it runs. `null`: nobody known. */
  val createdBy: Uuid? = null,
  /** Its game server keeps running without players (else it sleeps when idle). */
  val keepRunning: Boolean = false,
)

/** What decides whether a session's game server may sleep: [keepRunning], and its [hosted] slots (they don't count). */
data class IdleRules(val keepRunning: Boolean, val hosted: Set<String>)

/** Someone's role in a session besides playing: `manager`, `support` or `watcher`, with their name in it. */
data class SessionRoleEntry(val role: String, val name: String, val deleted: Boolean = false, val block: Block? = null) {
  /** They can't act for the session at [now]: their account is gone, or an Administrator keeps it out. */
  fun unavailable(now: Instant = Instant.now()): Boolean = deleted || block?.holds(now) == true
}

/**
 * What an invited account is called in a session until its person signs up (then their own name, see
 * `nameInRunningSessions`): the name suggested for it, else `Invited-` and its ID's start; nothing of its address.
 */
fun invitedName(id: Uuid, suggested: String?): String =
  suggested?.trim()?.takeIf { it.isNotEmpty() } ?: "Invited-${id.toString().take(4)}"

/** The sessions the admin panel creates and deletes. */
class SessionSetupStore(private val db: RandogeddonDatabase) {
  /** Records [seedName]'s session as [publicId]; [worlds]: slot name -> the users who play it; [hosted]: its hosted slots. */
  suspend fun create(
    publicId: String,
    seedName: String,
    apVersion: String,
    options: String,
    worlds: Map<String, List<Uuid>>,
    now: Instant = Instant.now(),
    hosted: Set<String> = emptySet(),
    createdBy: Uuid? = null,
  ) =
    db.query {
      val id = sessionId(seedName, now, publicId)
      SessionsTable.update({ SessionsTable.id eq id }) {
        it[SessionsTable.publicId] = publicId
        it[SessionsTable.apVersion] = apVersion
        it[SessionsTable.optionsJson] = options
        it[SessionsTable.createdBy] = createdBy
      }
      val names = names(worlds.values.flatten().distinct())
      for ((slot, users) in worlds) {
        for (user in users.distinct()) {
          SessionWorldsTable.insert {
            it[sessionId] = id
            it[slotName] = slot
            it[userId] = user
            it[userName] = names[user]
          }
        }
      }
      for (slot in hosted) {
        SessionHostedSlotsTable.insert {
          it[sessionId] = id
          it[slotName] = slot
        }
      }
    }

  /**
   * What [users] are called in a new session: their name, or for an invited user the name the admin suggested (or
   * [invitedName]: never anything of their address, which only Administrators see) until they sign up.
   */
  private fun names(users: List<Uuid>): Map<Uuid, String> {
    if (users.isEmpty()) return emptyMap()
    val suggested = InvitationsTable.selectAll().where { InvitationsTable.userId inList users }
      .associate { it[InvitationsTable.userId] to it[InvitationsTable.suggestedName] }
    return UsersTable.selectAll().where { UsersTable.id inList users }.associate { row ->
      val id = row[UsersTable.id]
      id to (row[UsersTable.name] ?: invitedName(id, suggested[id]))
    }
  }

  /**
   * What the gateway starts [seedName]'s game server with: its Archipelago version and its options (JSON); `null`: no
   * such session. From `sessions` alone: the gateway's database role ([GatewayRole]) reads nothing of the accounts.
   */
  suspend fun launchOf(seedName: String): Pair<String?, String?>? = db.query {
    SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull()
      ?.let { it[SessionsTable.apVersion] to it[SessionsTable.optionsJson] }
  }

  /**
   * Whether [seedName]'s game server may sleep without players, and which of its slots are hosted (their connections
   * don't count as players); `null`: no such session. For the gateway, from `sessions` and `session_hosted_slots`.
   */
  suspend fun idleRules(seedName: String): IdleRules? = db.query {
    val row = SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull() ?: return@query null
    val hosted = SessionHostedSlotsTable.selectAll().where { SessionHostedSlotsTable.sessionId eq row[SessionsTable.id] }
      .map { it[SessionHostedSlotsTable.slotName] }.toSet()
    IdleRules(row[SessionsTable.keepRunning], hosted)
  }

  /** Lets [seedName]'s game server keep running without players, or sleep when idle; `false`: no such session. */
  suspend fun setKeepRunning(seedName: String, keep: Boolean): Boolean = db.query {
    SessionsTable.update({ SessionsTable.seedName eq seedName }) { it[keepRunning] = keep } > 0
  }

  /** How many sessions that aren't finished each account created (for the limit per user); none: not in it. */
  suspend fun runningCreated(): Map<Uuid, Int> = db.query {
    SessionsTable.selectAll().where { SessionsTable.finishedAt.isNull() and SessionsTable.createdBy.isNotNull() }
      .groupingBy { it[SessionsTable.createdBy]!! }.eachCount()
  }

  /** How many sessions that aren't finished [userId] created. */
  suspend fun runningCreatedBy(userId: Uuid): Int = db.query {
    SessionsTable.selectAll().where { SessionsTable.finishedAt.isNull() and (SessionsTable.createdBy eq userId) }.count().toInt()
  }

  suspend fun of(seedName: String): SessionSetup? = db.query {
    val row = SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull() ?: return@query null
    // The name of then; rows from before V7 without one: the current name
    fun name(r: ResultRow) = r[SessionWorldsTable.userName] ?: r[UsersTable.name] ?: r[SessionWorldsTable.userId].toString().take(8)
    // The database gives the rows in no order of its own (it changed from one read to the next, and the dashboard's
    // slots with it): by slot, then by player
    val rows = (SessionWorldsTable innerJoin UsersTable).selectAll()
      .where { SessionWorldsTable.sessionId eq row[SessionsTable.id] }.toList()
      .sortedWith(compareBy({ it[SessionWorldsTable.slotName].lowercase() }, { it[SessionWorldsTable.slotName] }, { name(it).lowercase() }))
    val worlds = rows.groupBy({ it[SessionWorldsTable.slotName] }, ::name)
    val members = rows.associate { it[SessionWorldsTable.userId] to name(it) }
    val deleted = rows.filter { it[UsersTable.deletedAt] != null }.map { it[SessionWorldsTable.userId] }.toSet()
    val roles = (SessionRolesTable innerJoin UsersTable).selectAll()
      .where { SessionRolesTable.sessionId eq row[SessionsTable.id] }
      .associate {
        val name = it[SessionRolesTable.userName] ?: it[UsersTable.name] ?: it[SessionRolesTable.userId].toString().take(8)
        it[SessionRolesTable.userId] to SessionRoleEntry(
          it[SessionRolesTable.role], name, it[UsersTable.deletedAt] != null,
          it[UsersTable.blockedAt]?.let { at -> Block(at, it[UsersTable.blockedUntil], null, null) },
        )
      }
    val hosted = SessionHostedSlotsTable.selectAll().where { SessionHostedSlotsTable.sessionId eq row[SessionsTable.id] }
      .map { it[SessionHostedSlotsTable.slotName] }.toSet()
    SessionSetup(
      seedName, row[SessionsTable.apVersion], row[SessionsTable.optionsJson], row[SessionsTable.createdAt], worlds, members,
      deleted, roles, hosted, row[SessionsTable.createdBy], row[SessionsTable.keepRunning],
    )
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

  /** The slots of [seedName] that are still locked (their key isn't found yet). */
  suspend fun locked(seedName: String): Set<String> = db.query {
    val row = SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull() ?: return@query emptySet()
    SlotPasswordsTable.selectAll()
      .where { (SlotPasswordsTable.sessionId eq row[SessionsTable.id]) and SlotPasswordsTable.locked }
      .map { it[SlotPasswordsTable.slotName] }.toSet()
  }

  /** Marks exactly [locked] of [seedName]'s slots as locked, the others as open. */
  suspend fun setLocked(seedName: String, locked: Set<String>) = db.query {
    val row = SessionsTable.selectAll().where { SessionsTable.seedName eq seedName }.singleOrNull() ?: return@query
    val id = row[SessionsTable.id]
    SlotPasswordsTable.update({ (SlotPasswordsTable.sessionId eq id) and (SlotPasswordsTable.slotName inList locked) }) {
      it[SlotPasswordsTable.locked] = true
    }
    SlotPasswordsTable.update({ (SlotPasswordsTable.sessionId eq id) and (SlotPasswordsTable.slotName notInList locked) }) {
      it[SlotPasswordsTable.locked] = false
    }
    Unit
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
data class CheatEntry(
  val at: Instant,
  val admin: String,
  val action: String,
  val slotName: String,
  val value: String?,
  /** `administrator`, `manager` or `support`. */
  val byRole: String = "administrator",
)

/** Every cheat from the admin panel, per session, with the admin's name at the time. */
class CheatLogStore(private val db: RandogeddonDatabase) {
  suspend fun add(seedName: String, entry: CheatEntry) = db.query {
    val id = sessionId(seedName, entry.at)
    CheatLogTable.insert {
      it[sessionId] = id
      it[at] = entry.at
      it[admin] = entry.admin
      it[byRole] = entry.byRole
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
        CheatEntry(
          it[CheatLogTable.at], it[CheatLogTable.admin], it[CheatLogTable.action], it[CheatLogTable.slotName],
          it[CheatLogTable.value], it[CheatLogTable.byRole],
        )
      }
  }
}

/** Who has which role in a session besides the players (Session-Managers, support, watchers). */
class SessionRoleStore(private val db: RandogeddonDatabase) {
  /**
   * Gives [userId] [role] in the session of [publicId] ([name]: what it shows of them); `false` if they have a role
   * there already. Whether they may get it is the caller's to check.
   */
  suspend fun add(publicId: String, userId: Uuid, role: String, name: String?, by: String?, now: Instant = Instant.now()): Boolean =
    db.query {
      val session = SessionsTable.selectAll().where { SessionsTable.publicId eq publicId }.singleOrNull()?.get(SessionsTable.id)
        ?: return@query false
      val exists = SessionRolesTable.selectAll()
        .where { (SessionRolesTable.sessionId eq session) and (SessionRolesTable.userId eq userId) }.any()
      if (exists) return@query false
      SessionRolesTable.insert {
        it[sessionId] = session
        it[SessionRolesTable.userId] = userId
        it[SessionRolesTable.role] = role
        it[userName] = name
        it[addedBy] = by
        it[addedAt] = now
      }
      true
    }

  /** Gives [userId] [role] in place of the one they have in the session of [publicId]; `false` if they had none. */
  suspend fun change(publicId: String, userId: Uuid, role: String): Boolean = db.query {
    val session = SessionsTable.selectAll().where { SessionsTable.publicId eq publicId }.singleOrNull()?.get(SessionsTable.id)
      ?: return@query false
    SessionRolesTable.update({ (SessionRolesTable.sessionId eq session) and (SessionRolesTable.userId eq userId) }) {
      it[SessionRolesTable.role] = role
    } > 0
  }

  /** Takes [userId]'s role in the session of [publicId] away; `false` if they had none. */
  suspend fun remove(publicId: String, userId: Uuid): Boolean = db.query {
    val session = SessionsTable.selectAll().where { SessionsTable.publicId eq publicId }.singleOrNull()?.get(SessionsTable.id)
      ?: return@query false
    SessionRolesTable.deleteWhere { (sessionId eq session) and (SessionRolesTable.userId eq userId) } > 0
  }

  /** How many sessions that aren't finished [userId] manages (for the per-user limit). */
  suspend fun runningManagedBy(userId: Uuid): Int = db.query {
    (SessionRolesTable innerJoin SessionsTable).selectAll()
      .where { (SessionRolesTable.userId eq userId) and (SessionRolesTable.role eq "manager") and SessionsTable.finishedAt.isNull() }
      .count().toInt()
  }
}

/** A session's share link as it is on: who made it, and when. */
data class ShareLinkInfo(val createdBy: String?, val createdAt: Instant)

/** Share links: a session followed without a login. One per session, stored only as the token's SHA-256. */
class ShareLinkStore(private val db: RandogeddonDatabase) {
  /** A new link for the session of [publicId] (the old one stops working); `false` if there's no such session. */
  suspend fun replace(publicId: String, tokenHash: String, by: String?, now: Instant = Instant.now()): Boolean = db.query {
    val session = sessionOf(publicId) ?: return@query false
    ShareLinksTable.deleteWhere { sessionId eq session }
    ShareLinksTable.insert {
      it[sessionId] = session
      it[ShareLinksTable.tokenHash] = tokenHash
      it[createdBy] = by
      it[createdAt] = now
    }
    true
  }

  /** Turns the session's link off; `false` if it had none. */
  suspend fun remove(publicId: String): Boolean = db.query {
    val session = sessionOf(publicId) ?: return@query false
    ShareLinksTable.deleteWhere { sessionId eq session } > 0
  }

  /** The session's link, if it has one. */
  suspend fun of(publicId: String): ShareLinkInfo? = db.query {
    (ShareLinksTable innerJoin SessionsTable).selectAll().where { SessionsTable.publicId eq publicId }.singleOrNull()
      ?.let { ShareLinkInfo(it[ShareLinksTable.createdBy], it[ShareLinksTable.createdAt]) }
  }

  /** The public ID of the session whose link has [tokenHash]; `null`: no such link (any more). */
  suspend fun sessionWith(tokenHash: String): String? = db.query {
    (ShareLinksTable innerJoin SessionsTable).select(SessionsTable.publicId)
      .where { ShareLinksTable.tokenHash eq tokenHash }.singleOrNull()?.get(SessionsTable.publicId)
  }

  private fun sessionOf(publicId: String): Uuid? =
    SessionsTable.selectAll().where { SessionsTable.publicId eq publicId }.singleOrNull()?.get(SessionsTable.id)
}
