package net.eratiem.randogeddon.server.admin

import io.ktor.http.HttpStatusCode
import java.lang.invoke.MethodHandles
import kotlin.uuid.Uuid
import net.eratiem.randogeddon.database.SessionRoleStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.database.invitedName
import net.eratiem.randogeddon.server.account.AccountProblem
import net.eratiem.randogeddon.server.account.AccountService
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.AddPersonRequest
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.SessionPeople
import net.eratiem.randogeddon.shared.SessionPerson
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.WatcherRequest
import net.eratiem.randogeddon.shared.i18n.Language
import org.koin.core.annotation.Single

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * Who has which part in a session besides playing: its Session-Managers, support and watchers. Given and taken by its
 * Session-Managers (and Administrators); nobody gets a second part, and Administrators none (they reach every session
 * anyway). A session always keeps one Session-Manager, unless an Administrator takes the last one away.
 */
@Single
class SessionPeopleService(
  private val users: UserStore,
  private val roles: SessionRoleStore,
  private val sessions: LiveSessions,
  private val accounts: AccountService,
) {
  /** Everyone with a part in session [id]: players with their slots, and the others with their role. */
  fun list(id: String): SessionPeople {
    val live = session(id)
    val setup = live.setup
    val slots = live.people()
    val players = setup?.members.orEmpty().map { (user, name) ->
      SessionPerson(user.toString(), name, SessionRole.PLAYER, slots[name].orEmpty(), user in setup?.deleted.orEmpty())
    }
    val others = setup?.roles.orEmpty().mapNotNull { (user, entry) ->
      SessionRole.of(entry.role)?.let { SessionPerson(user.toString(), entry.name, it, slots[entry.name].orEmpty(), entry.deleted) }
    }
    // A Session-Manager who also plays is listed once, as a manager (with their slots)
    val managers = others.map { it.id }.toSet()
    return SessionPeople((others + players.filter { it.id !in managers }).sortedWith(compareBy({ it.role.ordinal }, { it.name.lowercase() })))
  }

  /** Who can still be given a part in session [id]: accounts with a name, without one there, no Administrators. */
  suspend fun choices(id: String): List<UserRef> {
    val live = session(id)
    val taken = taken(live)
    return users.active().filter { !it.administrator && it.id !in taken }
      .map { UserRef(it.id.toString(), it.name) }.sortedBy { it.label.lowercase() }
  }

  /** Gives someone a part in session [id]: an account, or a new address (invited). */
  suspend fun add(id: String, request: AddPersonRequest, by: User, language: Language): SessionPerson {
    val live = session(id)
    if (request.role == SessionRole.PLAYER) throw AccountProblem(HttpStatusCode.BadRequest, Problem.PLAYERS_WITH_SLOTS)
    val (userId, name) = when {
      request.userId != null -> {
        val user = runCatching { Uuid.parse(request.userId!!) }.getOrNull()?.let { users.findActive(it) }
          ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_ACCOUNT)
        if (user.administrator) throw AccountProblem(HttpStatusCode.Conflict, Problem.ADMINISTRATOR_EVERYWHERE, "name" to user.name)
        user.id to user.name
      }
      request.email != null -> {
        val invited = inviteNew(request.email!!, request.suggestedName, by, language)
        Uuid.parse(invited.id).let { it to (invited.name ?: invitedName(it, request.suggestedName)) }
      }
      else -> throw AccountProblem(HttpStatusCode.BadRequest, Problem.WHOM)
    }
    if (userId in taken(live)) throw AccountProblem(HttpStatusCode.Conflict, Problem.HAS_PART, "name" to name, "id" to live.id)
    if (!roles.add(live.id, userId, request.role.code, name, by.name)) {
      throw AccountProblem(HttpStatusCode.Conflict, Problem.HAS_PART, "name" to name, "id" to live.id)
    }
    sessions.refresh()
    logger.info("{} made {} {} of {}", by.name, name, request.role.code, live.id)
    return SessionPerson(userId.toString(), name, request.role)
  }

  /**
   * Gives [userId] [role] in session [id] in place of their part: a player gets it besides their slots, someone with a
   * part has it changed. Leaving Session-Manager follows [remove]'s rules.
   */
  suspend fun change(id: String, userId: Uuid, role: SessionRole, by: User): SessionPerson {
    val live = session(id)
    if (role == SessionRole.PLAYER) throw AccountProblem(HttpStatusCode.BadRequest, Problem.PLAYERS_WITH_SLOTS)
    val entry = live.setup?.roles?.get(userId)
    val name = entry?.name ?: live.setup?.members?.get(userId) ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_PART)
    when {
      entry == null -> roles.add(live.id, userId, role.code, name, by.name)
      entry.role == role.code -> return SessionPerson(userId.toString(), name, role)
      else -> {
        if (entry.role == SessionRole.MANAGER.code) mayLeaveManager(live, userId, by)
        roles.change(live.id, userId, role.code)
      }
    }
    sessions.refresh()
    logger.info("{} made {} {} of {} (was {})", by.name, name, role.code, live.id, entry?.role ?: "player")
    return SessionPerson(userId.toString(), name, role)
  }

  /**
   * Takes [userId]'s part (not a player's) in session [id]. The last Session-Manager stays (for Administrators too):
   * someone else becomes one first. A Session-Manager doesn't take their own part (another one or an Administrator does).
   */
  suspend fun remove(id: String, userId: Uuid, by: User) {
    val live = session(id)
    val entry = live.setup?.roles?.get(userId) ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_PART)
    if (entry.role == SessionRole.MANAGER.code) mayLeaveManager(live, userId, by)
    roles.remove(live.id, userId)
    sessions.refresh()
    logger.info("{} took {}'s part ({}) in {}", by.name, entry.name, entry.role, live.id)
  }

  /** Session [id]'s watchers (accounts; the share link is apart). */
  fun watchers(id: String): SessionPeople = SessionPeople(list(id).people.filter { it.role == SessionRole.WATCHER })

  /** A watcher of session [id], added by a player or a Session-Manager: the same rules as [add]. */
  suspend fun addWatcher(id: String, request: WatcherRequest, by: User, language: Language): SessionPerson =
    add(id, AddPersonRequest(SessionRole.WATCHER, request.userId, request.email, request.suggestedName), by, language)

  /** Takes a watcher away; players take only watchers, not support or Session-Managers. */
  suspend fun removeWatcher(id: String, userId: Uuid, by: User) {
    val entry = session(id).setup?.roles?.get(userId) ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NOT_WATCHING)
    if (entry.role != SessionRole.WATCHER.code) throw AccountProblem(HttpStatusCode.Forbidden, Problem.NOT_A_WATCHER, "name" to entry.name)
    remove(id, userId, by)
  }

  /** Invites [email] for a session (a player, or a part in it): only if no account has that address. */
  suspend fun inviteNew(email: String, suggestedName: String?, by: User, language: Language): UserRef {
    val account = accounts.invite(email, suggestedName, by.name, language, existing = Refusal(Problem.ADDRESS_HAS_ACCOUNT))
    return UserRef(account.id.toString(), account.name, account.email.takeIf { account.name == null })
  }

  /** Whether [userId] may stop being a Session-Manager of [live]: not on their own, and never the last one. */
  private fun mayLeaveManager(live: LiveSession, userId: Uuid, by: User) {
    if (userId == by.id && !by.administrator) throw AccountProblem(HttpStatusCode.Conflict, Problem.OWN_MANAGER)
    if (live.managers().size <= 1) throw AccountProblem(HttpStatusCode.Conflict, Problem.KEEP_MANAGER)
  }

  private fun taken(live: LiveSession): Set<Uuid> = live.setup?.members.orEmpty().keys + live.setup?.roles.orEmpty().keys

  private fun session(id: String): LiveSession = sessions.get(id) ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
}
