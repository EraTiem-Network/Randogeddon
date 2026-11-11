package net.eratiem.randogeddon.server

import ch.qos.logback.classic.Level
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.SessionTimeStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.account.PasswordGenerator
import net.eratiem.randogeddon.server.account.PasswordHasher
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.CurrentSession
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.server.session.SessionFileWatcher
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private const val USAGE = """Usage: randogeddon [command]
  (no command)              run the tracker
  logins                    create logins for the people of session.yaml that have none, and print them once
  logins --reset <person>   a new password for <person>; their logins end
  admins                    who is an admin
  admins --grant <person>   make <person> an admin (sees and reaches the admin panel)
  admins --revoke <person>  take it back
  slots [--json]            the slot passwords of the current seed"""

/**
 * The host's commands, run in the tracker container: `docker compose exec tracker randogeddon logins`. They use the
 * same configuration (database, session.yaml, room.json) as the running tracker. Passwords go to this terminal only,
 * never to a log.
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
            args.size == 1 -> createLogins(config, db)
            args.size == 3 && args[1] == "--reset" -> resetLogin(args[2], UserStore(db))
            else -> usage()
          }
          "admins" -> when {
            args.size == 1 -> listAdmins(UserStore(db))
            args.size == 3 && args[1] == "--grant" -> setAdmin(args[2], true, UserStore(db))
            args.size == 3 && args[1] == "--revoke" -> setAdmin(args[2], false, UserStore(db))
            else -> usage()
          }
          "slots" -> when {
            args.size == 1 -> printSlots(config, db, json = false)
            args.size == 2 && args[1] == "--json" -> printSlots(config, db, json = true)
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

/** The people and setup of the current seed, as the running tracker sees them. */
private suspend fun currentSession(config: TrackerConfig, db: RandogeddonDatabase) =
  CurrentSession(config, SessionTimeStore(db), SessionSetupStore(db), SessionFileWatcher(config)).apply { refresh() }

private fun usage(): Int {
  System.err.println(USAGE)
  return 2
}

private suspend fun createLogins(config: TrackerConfig, db: RandogeddonDatabase): Int {
  val users = UserStore(db)
  val people = currentSession(config, db).people().keys
  if (people.isEmpty()) {
    System.err.println("No people in the session (created in the admin panel, or ${config.sessionYaml}).")
    return 1
  }
  val existing = users.active().map { it.name.lowercase() }.toSet()
  val missing = people.filter { it.lowercase() !in existing }
  if (missing.isEmpty()) {
    println("All ${people.size} people have a login. A new password: randogeddon logins --reset <person>")
    return 0
  }
  val created = missing.map { person ->
    val password = PasswordGenerator.generate()
    users.create(person, PasswordHasher.hash(password))
    person to password
  }
  printLogins(created)
  if (missing.size < people.size) println("${people.size - missing.size} people already had a login and are not shown.")
  return 0
}

private suspend fun resetLogin(person: String, users: UserStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'. Create the missing ones with: randogeddon logins")
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
  println(if (admins.isEmpty()) "No admins yet: randogeddon admins --grant <person>" else "Admins: ${admins.joinToString()}")
  return 0
}

private suspend fun setAdmin(person: String, admin: Boolean, users: UserStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'. Create the missing ones with: randogeddon logins")
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

private suspend fun printSlots(config: TrackerConfig, db: RandogeddonDatabase, json: Boolean): Int {
  val store = SlotPasswordStore(db)
  val room = Room.read(config.roomJson) ?: run {
    System.err.println("No seed yet (${config.roomJson} is missing)")
    return 1
  }
  val passwords = SlotPasswords(config, store, currentSession(config, db)).current()
  if (json) {
    println(buildJsonObject {
      put("seed", room.seedName)
      putJsonObject("slots") { passwords.toSortedMap().forEach { (slot, password) -> put(slot, password) } }
    })
  } else {
    val width = maxOf(4, passwords.keys.maxOfOrNull { it.length } ?: 0)
    println("Seed ${room.seedName}")
    println("Slot".padEnd(width) + "  Password")
    passwords.toSortedMap().forEach { (slot, password) -> println(slot.padEnd(width) + "  " + password) }
  }
  return 0
}
