package net.eratiem.randogeddon.server

import ch.qos.logback.classic.Level
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.eratiem.randogeddon.database.LoginLinkStore
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.SessionStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.TwoFactorStore
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.account.PasswordGenerator
import net.eratiem.randogeddon.server.account.PasswordHasher
import net.eratiem.randogeddon.server.account.createLoginLink
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.shared.UserNames
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private const val USAGE = """Usage: randogeddon [command]
  (no command)              run the tracker
  logins --create <person>  a login for <person> right away (local use, the demo; people are invited otherwise)
  logins --reset <person>   a new password for <person>; their logins end
  admins                    who is an Administrator
  admins --create <person>  a new login for <person> as an Administrator (the first one)
  admins --grant <person>   make <person> an Administrator (every session, the APWorlds, the users, the settings)
  admins --revoke <person>  take it back
  admins --login <person>   a one-time login link for <person> (15 minutes): when single sign-on can't be reached
  admins --reset-2fa <person>  turn off <person>'s two-factor login (lost authenticator and recovery codes); an
                            Administrator sets it up again at the next login
  sessions                  every session: its ID, seed and whether it is finished
  slots [--json] <session>  the slot passwords of a session's seed"""

/**
 * The host's commands, run in the tracker container: `docker compose exec tracker randogeddon admins`. They use the
 * same configuration (database, the gateway's data directory) as the running tracker. Passwords go to this terminal
 * only, never to a log.
 */
fun runCommand(args: List<String>, config: TrackerConfig) {
  // Only the output of the command; INFO logs (connection pool, migrations, the session file) would bury it
  for (name in listOf(org.slf4j.Logger.ROOT_LOGGER_NAME, "net.eratiem")) {
    (LoggerFactory.getLogger(name) as ch.qos.logback.classic.Logger).level = Level.WARN
  }
  val code = runCatching {
    RandogeddonDatabase.connect(config.database, migrate = true, poolSize = 2).use { db ->
      runBlocking {
        when (args.firstOrNull()) {
          "logins" -> when {
            args.size == 3 && args[1] == "--create" -> createLogin(args[2], admin = false, UserStore(db))
            args.size == 3 && args[1] == "--reset" -> resetLogin(args[2], UserStore(db))
            else -> usage()
          }
          "admins" -> when {
            args.size == 1 -> listAdmins(UserStore(db))
            args.size == 3 && args[1] == "--create" -> createLogin(args[2], admin = true, UserStore(db))
            args.size == 3 && args[1] == "--grant" -> setAdmin(args[2], true, UserStore(db))
            args.size == 3 && args[1] == "--revoke" -> setAdmin(args[2], false, UserStore(db))
            args.size == 3 && args[1] == "--reset-2fa" -> resetTwoFactor(args[2], UserStore(db), TwoFactorStore(db))
            args.size == 3 && args[1] == "--login" -> loginLink(config, args[2], UserStore(db), LoginLinkStore(db))
            else -> usage()
          }
          "sessions" -> if (args.size == 1) listSessions(SessionStore(db)) else usage()
          "slots" -> when {
            args.size == 2 -> printSlots(config, db, args[1], json = false)
            args.size == 3 && args[1] == "--json" -> printSlots(config, db, args[2], json = true)
            else -> usage()
          }
          "help", "--help", "-h" -> { println(USAGE); 0 }
          else -> usage()
        }
      }
    }
  }.getOrElse {
    System.err.println("Failed: ${it.message ?: it::class.simpleName}")
    1
  }
  exitProcess(code)
}

private fun usage(): Int {
  System.err.println(USAGE)
  return 2
}

/** A one-time login link for [person] (an Administrator when single sign-on is away): printed, valid 15 minutes. */
private suspend fun loginLink(config: TrackerConfig, person: String, users: UserStore, links: LoginLinkStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'.")
    return 1
  }
  println("A login link for ${user.name} (once, for 15 minutes):")
  println(createLoginLink(links, config.publicUrl, user))
  return 0
}

/** A login for [person] without an invitation: the first Administrator, or a player of the local demo. */
private suspend fun createLogin(person: String, admin: Boolean, users: UserStore): Int {
  UserNames.problem(person)?.let {
    System.err.println("Name: $it")
    return 2
  }
  users.findActive(person)?.let { user ->
    val hint = if (admin) " An Administrator: randogeddon admins --grant ${user.name}" else ""
    System.err.println("${user.name} has a login already.$hint")
    return 1
  }
  val password = PasswordGenerator.generate()
  val user = users.create(person, PasswordHasher.hash(password))
  if (admin) users.setAdmin(user.id, true)
  printLogins(listOf(user.name to password))
  if (admin) println("${user.name} is an Administrator.")
  return 0
}

private suspend fun listSessions(store: SessionStore): Int {
  val sessions = store.all()
  if (sessions.isEmpty()) {
    println("No sessions yet; every user can create one in the web app.")
    return 0
  }
  val width = maxOf(7, sessions.maxOf { it.id.length })
  println("Session".padEnd(width) + "  Seed".padEnd(24) + "  Finished")
  sessions.forEach { println(it.id.padEnd(width) + "  " + it.seedName.padEnd(22) + "  " + (it.finishedAt ?: "no")) }
  return 0
}

private suspend fun resetLogin(person: String, users: UserStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'. People are invited in the web app.")
    return 1
  }
  val password = PasswordGenerator.generate()
  users.setPassword(user.id, PasswordHasher.hash(password))
  printLogins(listOf(user.name to password))
  println("${user.name}'s previous logins have ended.")
  return 0
}

private suspend fun listAdmins(users: UserStore): Int {
  val admins = users.active().filter { it.administrator }.map { it.name }
  println(if (admins.isEmpty()) "No Administrators yet: randogeddon admins --create <person>" else "Administrators: ${admins.joinToString()}")
  return 0
}

private suspend fun resetTwoFactor(person: String, users: UserStore, twoFactor: TwoFactorStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'.")
    return 1
  }
  twoFactor.disable(user.id)
  println("${user.name}'s two-factor login is off; ${if (user.administrator) "they set it up again at their next login" else "they can set it up again"}.")
  return 0
}

private suspend fun setAdmin(person: String, admin: Boolean, users: UserStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'. A new Administrator: randogeddon admins --create $person")
    return 1
  }
  users.setAdmin(user.id, admin)
  println(if (admin) "${user.name} is an Administrator now." else "${user.name} is no Administrator any more.")
  return 0
}

private fun printLogins(logins: List<Pair<String, String>>) {
  val width = maxOf(8, logins.maxOf { it.first.length })
  println("Username".padEnd(width) + "  Password")
  logins.forEach { (name, password) -> println(name.padEnd(width) + "  " + password) }
  println()
  println("These passwords are shown only now; hand them to the players. Stored are only their hashes.")
}

private suspend fun printSlots(config: TrackerConfig, db: RandogeddonDatabase, id: String, json: Boolean): Int {
  val roomJson = config.dataDir.resolve("sessions").resolve(id).resolve("room.json")
  val room = Room.read(roomJson) ?: run {
    System.err.println("No seed for $id yet ($roomJson is missing; its game server writes it when it starts)")
    return 1
  }
  val seed = room.seedName ?: return 1
  val passwords = SlotPasswordStore(db).ensure(seed, room.players.map { it.name }, { PasswordGenerator.generate() })
  if (json) {
    println(buildJsonObject {
      put("session", id)
      put("seed", seed)
      putJsonObject("slots") { passwords.toSortedMap().forEach { (slot, password) -> put(slot, password) } }
    })
  } else {
    val width = maxOf(4, passwords.keys.maxOfOrNull { it.length } ?: 0)
    println("Session $id, seed $seed")
    println("Slot".padEnd(width) + "  Password")
    passwords.toSortedMap().forEach { (slot, password) -> println(slot.padEnd(width) + "  " + password) }
  }
  return 0
}
