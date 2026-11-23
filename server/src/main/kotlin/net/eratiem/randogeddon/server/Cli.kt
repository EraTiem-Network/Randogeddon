package net.eratiem.randogeddon.server

import net.eratiem.randogeddon.server.account.nameProblem
import ch.qos.logback.classic.Level
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.eratiem.randogeddon.database.LoginLinkStore
import net.eratiem.randogeddon.database.LoginSessionStore
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.SessionStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.TwoFactorStore
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.account.LoginMethods
import net.eratiem.randogeddon.server.account.PasswordGenerator
import net.eratiem.randogeddon.server.account.PasswordHasher
import net.eratiem.randogeddon.server.account.createLoginLink
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.config.english
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.shared.UserNames
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private const val USAGE = """Usage: randogeddon [command]
  (no command)              run the tracker
  logins --create <person>  a login for <person> right away (local use, the demo; people are invited otherwise)
  logins --reset <person>   a new password for <person>; their logins end
  admins                    who is an Administrator
  admins --create <person>  a new login for <person> as an Administrator (the first one; only with
                            ADMIN_PASSWORD_LOGIN=true, without single sign-on)
  admins --grant <person>   make <person> an Administrator (every session, the APWorlds, the users, the settings);
                            their logins end
  admins --revoke <person>  take it back
  admins --login <person>   a one-time login link for <person> (15 minutes): when single sign-on can't be reached
  admins --reset-2fa <person>  turn off <person>'s two-factor login (lost authenticator and recovery codes); their
                            logins end, an Administrator sets it up again at the next login
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
            args.size == 1 -> listAdmins(config, UserStore(db))
            // With single sign-on, Administrators come from the provider's group alone; without it only with the setting
            args.size == 3 && args[1] in setOf("--create", "--grant", "--login") && !passwordAdministrators(config) -> notHere(config)
            args.size == 3 && args[1] == "--create" -> createLogin(args[2], admin = true, UserStore(db))
            args.size == 3 && args[1] == "--grant" -> setAdmin(args[2], true, UserStore(db), LoginSessionStore(db))
            args.size == 3 && args[1] == "--revoke" -> setAdmin(args[2], false, UserStore(db), LoginSessionStore(db))
            args.size == 3 && args[1] == "--reset-2fa" -> resetTwoFactor(args[2], UserStore(db), TwoFactorStore(db), LoginSessionStore(db))
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

/** Whether Administrators log in with their password here: no single sign-on, and `ADMIN_PASSWORD_LOGIN=true`. */
private fun passwordAdministrators(config: TrackerConfig) = config.oidc == null && config.adminPasswordLogin

/**
 * With single sign-on there is no other way to an Administrator: not made here, and no login link for one. Without
 * it and without `ADMIN_PASSWORD_LOGIN`, there is none at all.
 */
private fun notHere(config: TrackerConfig): Int {
  val oidc = config.oidc
  System.err.println(
    if (oidc != null) {
      "Administrators come from ${oidc.name} alone: add the person to its group ${oidc.adminGroup} " +
        "(they log in through ${oidc.name}, with their second factor there). --revoke still takes the flag here."
    } else {
      "Administrators come from single sign-on (OIDC_ISSUER), or from ADMIN_PASSWORD_LOGIN=true for local testing " +
        "(never on a public server). --revoke still takes the flag here."
    }
  )
  return 1
}

private fun usage(): Int {
  System.err.println(USAGE)
  return 2
}

/**
 * A one-time login link for [person], printed, valid 15 minutes; not with single sign-on, nor without
 * `ADMIN_PASSWORD_LOGIN` ([notHere]).
 */
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
  nameProblem(person)?.let {
    System.err.println("Name: ${it.english()}")
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

private suspend fun listAdmins(config: TrackerConfig, users: UserStore): Int {
  val admins = users.active().filter { it.administrator }.map { it.name }
  val none = "No Administrators yet" + if (passwordAdministrators(config)) ": randogeddon admins --create <person>" else ""
  println(if (admins.isEmpty()) none else "Administrators: ${admins.joinToString()}")
  return 0
}

/**
 * Turns [person]'s two-factor login off; their browser logins end, so none of them (made with the second factor or
 * not) becomes one that may act as an Administrator once it is set up again.
 */
private suspend fun resetTwoFactor(person: String, users: UserStore, twoFactor: TwoFactorStore, logins: LoginSessionStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'.")
    return 1
  }
  twoFactor.disable(user.id)
  logins.deleteOthers(user.id, LoginMethods.BROWSER)
  val again = if (user.administrator) "they set it up again at their next login" else "they can set it up again"
  println("${user.name}'s two-factor login is off and their logins have ended; $again.")
  return 0
}

/**
 * Makes [person] an Administrator ([admin]) or takes it back. Granted, their browser logins end: one made before (with
 * a password only, say) doesn't act as an Administrator's.
 */
private suspend fun setAdmin(person: String, admin: Boolean, users: UserStore, logins: LoginSessionStore): Int {
  val user = users.findActive(person) ?: run {
    System.err.println("No login for '$person'. A new Administrator: randogeddon admins --create $person")
    return 1
  }
  users.setAdmin(user.id, admin)
  if (admin) logins.deleteOthers(user.id, LoginMethods.BROWSER)
  println(if (admin) "${user.name} is an Administrator now; their logins have ended." else "${user.name} is no Administrator any more.")
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
  // A seed from before Phase 4 gets its bare session here (s-<seed>); the panel's sessions have theirs
  val passwords = SlotPasswordStore(db).ensure(seed, room.players.map { it.name }, { PasswordGenerator.generate() }, create = true)
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
