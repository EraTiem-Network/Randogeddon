package net.eratiem.randogeddon.server

import ch.qos.logback.classic.Level
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.SessionStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.account.PasswordGenerator
import net.eratiem.randogeddon.server.account.PasswordHasher
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.Room
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private const val USAGE = """Usage: randogeddon [command]
  (no command)              run the tracker
  logins --reset <person>   a new password for <person>; their logins end
  admins                    who is an admin
  admins --create <person>  a new login for <person> as an admin (the first one, to create sessions with)
  admins --grant <person>   make <person> an admin (sees and reaches the admin panel)
  admins --revoke <person>  take it back
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
            args.size == 3 && args[1] == "--reset" -> resetLogin(args[2], UserStore(db))
            else -> usage()
          }
          "admins" -> when {
            args.size == 1 -> listAdmins(UserStore(db))
            args.size == 3 && args[1] == "--create" -> createAdmin(args[2], UserStore(db))
            args.size == 3 && args[1] == "--grant" -> setAdmin(args[2], true, UserStore(db))
            args.size == 3 && args[1] == "--revoke" -> setAdmin(args[2], false, UserStore(db))
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

private suspend fun createAdmin(person: String, users: UserStore): Int {
  if (person.isBlank() || person.length > 64) {
    System.err.println("A name of 1 to 64 characters")
    return 2
  }
  users.findActive(person)?.let { user ->
    System.err.println("${user.name} has a login already. An admin: randogeddon admins --grant ${user.name}")
    return 1
  }
  val password = PasswordGenerator.generate()
  val user = users.create(person, PasswordHasher.hash(password))
  users.setAdmin(user.id, true)
  printLogins(listOf(user.name to password))
  println("${user.name} is an admin.")
  return 0
}

private suspend fun listSessions(store: SessionStore): Int {
  val sessions = store.all()
  if (sessions.isEmpty()) {
    println("No sessions yet; an admin creates them in the admin panel.")
    return 0
  }
  val width = maxOf(7, sessions.maxOf { it.id.length })
  println("Session".padEnd(width) + "  Seed".padEnd(24) + "  Finished")
  sessions.forEach { println(it.id.padEnd(width) + "  " + it.seedName.padEnd(22) + "  " + (it.finishedAt ?: "no")) }
  return 0
}

private suspend fun resetLogin(person: String, users: UserStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'. Logins are made when an admin creates a session with them.")
    return 1
  }
  val password = PasswordGenerator.generate()
  users.setPassword(user.id, PasswordHasher.hash(password))
  printLogins(listOf(user.name to password))
  println("${user.name}'s previous logins have ended.")
  return 0
}

private suspend fun listAdmins(users: UserStore): Int {
  val admins = users.active().filter { it.admin }.map { it.name }
  println(if (admins.isEmpty()) "No admins yet: randogeddon admins --create <person>" else "Admins: ${admins.joinToString()}")
  return 0
}

private suspend fun setAdmin(person: String, admin: Boolean, users: UserStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'. A new admin: randogeddon admins --create $person")
    return 1
  }
  users.setAdmin(user.id, admin)
  println(if (admin) "${user.name} is an admin now." else "${user.name} is no admin any more.")
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
