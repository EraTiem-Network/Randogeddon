package net.eratiem.randogeddon.database

/**
 * The gateway's own database role. The gateway runs Archipelago's Python next to itself (Generate.py, MultiServer, the
 * apworlds), which could read its database login; so its role gets only what the gateway needs: reading the sessions,
 * their hosted slots, the slot passwords and the settings that apply to everyone (how many game servers run at once,
 * when one sleeps), counting a session's time, and adding what it hears on the game servers (`session_events`).
 * Nothing of the accounts (password hashes, addresses, logins,
 * two-factor secrets), and no other writes.
 */
object GatewayRole {
  private val NAME = Regex("[a-z_][a-z0-9_]{0,62}")

  /** The statements that make [role] (or bring it back) to exactly its rights, with [password]. */
  internal fun statements(role: String, password: String): List<String> {
    require(NAME.matches(role)) { "Not a role name: $role" }
    // A string literal: quotes doubled (standard_conforming_strings: a backslash is just a backslash)
    val secret = "'" + password.replace("'", "''") + "'"
    return listOf(
      "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$role') THEN CREATE ROLE $role; END IF; END $$",
      // Back to no more than a login. Only a superuser may name these (the tracker with its own role isn't one: there
      // the compose service database-roles does it, as the superuser)
      "DO $$ BEGIN IF (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN " +
        "ALTER ROLE $role NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF; END $$",
      "ALTER ROLE $role LOGIN PASSWORD $secret",
      // Whatever it had before (a table it no longer needs): gone
      "REVOKE ALL ON ALL TABLES IN SCHEMA public FROM $role",
      "REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM $role",
      "GRANT USAGE ON SCHEMA public TO $role",
      "GRANT SELECT ON sessions, session_hosted_slots, slot_passwords, app_settings TO $role",
      "GRANT UPDATE (run_time_ms, counted_until) ON sessions TO $role",
      // What it hears on the game servers, for the tracker to read: added, never read or changed here
      "GRANT INSERT ON session_events TO $role",
    )
  }

  /** Makes [role] with [password] and its rights; run by the tracker (which migrates) at each start. */
  suspend fun ensure(db: RandogeddonDatabase, role: String, password: String) {
    require(password.isNotBlank()) { "The gateway's database role needs a password" }
    db.query { statements(role, password).forEach { exec(it) } }
  }
}
