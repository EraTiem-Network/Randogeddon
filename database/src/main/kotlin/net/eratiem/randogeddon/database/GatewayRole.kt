package net.eratiem.randogeddon.database

/**
 * The gateway's own database role. The gateway runs Archipelago's Python next to itself (Generate.py, MultiServer, the
 * apworlds); in its image as another user, which can't read the gateway's database login, but should one get it (a
 * gateway started otherwise, a hole in the kernel), its role gets only what the gateway needs: reading the sessions,
 * their hosted slots, the slot passwords and the settings that apply to everyone (how many game servers run at once,
 * when one sleeps), counting a session's time, and adding what it hears on the game servers (`session_events`, of the
 * kinds the gateway writes only: [EVENT_KINDS]). Nothing of the accounts (password hashes, addresses, logins,
 * two-factor secrets), and no other writes.
 */
object GatewayRole {
  private val NAME = Regex("[a-z_][a-z0-9_]{0,62}")

  /** The kinds of `session_events` the gateway writes; the others (a `cheat`) are the tracker's. */
  internal val EVENT_KINDS = listOf(SessionEventStore.PACKET, SessionEventStore.START, SessionEventStore.STOP)

  /** The policy on `session_events` (row-level security, V28) that lets the role add [EVENT_KINDS]. */
  private const val EVENT_POLICY = "gateway_event_kinds"

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
      // Of the gateway's kinds only, for any session: the table's row-level security (V28) lets a role add only what a
      // policy allows. Made again for this role (its name is a setting), in place of V28's for every role (which kept
      // the gateway's inserts going from the migration to here); the tracker, the table's owner, isn't bound
      "DROP POLICY IF EXISTS $EVENT_POLICY ON session_events",
      "CREATE POLICY $EVENT_POLICY ON session_events FOR INSERT TO $role " +
        "WITH CHECK (kind IN (${EVENT_KINDS.joinToString { "'$it'" }}))",
    )
  }

  /** Makes [role] with [password] and its rights; run by the tracker (which migrates) at each start. */
  suspend fun ensure(db: RandogeddonDatabase, role: String, password: String) {
    require(password.isNotBlank()) { "The gateway's database role needs a password" }
    db.query { statements(role, password).forEach { exec(it) } }
  }
}
