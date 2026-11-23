package net.eratiem.randogeddon.database

import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

/**
 * The gateway's own database role. The gateway runs Archipelago's Python next to itself (Generate.py, MultiServer, the
 * apworlds); in its image as another user, which can't read the gateway's database login, but should one get it (a
 * gateway started otherwise, a hole in the kernel), its role gets only what the gateway needs: reading the sessions,
 * their hosted slots, the slot passwords and the settings that apply to everyone (how many game servers run at once,
 * when one sleeps), counting a session's time, and adding what it hears on the game servers (`session_events`, of the
 * kinds the gateway writes only: [EVENT_KINDS]; its four columns, never an ID of its own). Nothing of the accounts
 * (password hashes, addresses, logins, two-factor secrets), no other writes, and at most [CONNECTION_LIMIT]
 * connections at once.
 */
object GatewayRole {
  private val NAME = Regex("[a-z_][a-z0-9_]{0,62}")

  /** The kinds of `session_events` the gateway writes; the others (a `cheat`) are the tracker's. */
  internal val EVENT_KINDS = listOf(SessionEventStore.PACKET, SessionEventStore.START, SessionEventStore.STOP)

  /** The policy on `session_events` (row-level security, V28) that lets the role add [EVENT_KINDS]. */
  private const val EVENT_POLICY = "gateway_event_kinds"

  /**
   * Its pool has 3 (the gateway opens no other); as many again are room for a restart, whose old connections may
   * linger a moment. Temporary tables it can't make either: neither program uses one, and the compose service
   * database-roles takes that right from everyone but the superuser (only the database's owner can).
   */
  internal const val CONNECTION_LIMIT = 6

  /** What the role reads whole, and what it may write column by column (table -> columns). */
  private val READS = listOf("sessions", "session_hosted_slots", "slot_passwords", "app_settings")
  private val UPDATES = mapOf("sessions" to listOf("run_time_ms", "counted_until"))
  private val INSERTS = mapOf("session_events" to listOf("session", "at_ms", "kind", "packet"))

  /**
   * How the role and its rights are now, as [state] reads them: whether it [exists], whether it has an attribute only
   * a superuser gives ([elevated]), whether the one who runs this is a superuser ([callerSuperuser]), its rights on
   * the tables (`table:PRIVILEGE`, [tables]) and on single columns (`table.column:PRIVILEGE`, [columns]), whether it
   * may use the schema ([schemaUsage]), and what the policy on `session_events` is made for ([policy], its comment).
   */
  internal data class Current(
    val exists: Boolean = false,
    val elevated: Boolean = false,
    val callerSuperuser: Boolean = false,
    val tables: Set<String> = emptySet(),
    val columns: Set<String> = emptySet(),
    val schemaUsage: Boolean = false,
    val policy: String? = null,
  )

  /** The rights on whole tables the role should have, as [Current.tables] says them. */
  internal val TABLE_RIGHTS: Set<String> = READS.map { "$it:SELECT" }.toSet()

  /** The rights on single columns it should have, as [Current.columns] says them. */
  internal val COLUMN_RIGHTS: Set<String> =
    UPDATES.flatMap { (t, cs) -> cs.map { "$t.$it:UPDATE" } }.toSet() + INSERTS.flatMap { (t, cs) -> cs.map { "$t.$it:INSERT" } }

  /** What the policy's comment says when it is made for [role] as this code makes it. */
  internal fun policyMark(role: String) = "$role may add ${EVENT_KINDS.joinToString(", ")}"

  /**
   * The statements that bring [role] to exactly its rights, with the SCRAM [verifier] of its password (never the
   * password itself: a statement's text can end up in a log), from how things are ([now]). Only what differs is
   * changed: the rights and the policy are left alone when they are as they should be (the policy needs the table to
   * itself for a moment, which a running dump would make everyone wait for).
   */
  internal fun statements(role: String, verifier: String, now: Current): List<String> {
    require(NAME.matches(role)) { "Not a role name: $role" }
    require(verifier.startsWith("SCRAM-SHA-256$") && "'" !in verifier) { "Not a SCRAM verifier" }
    return buildList {
      if (!now.exists) add("CREATE ROLE $role")
      // Back to no more than a login. Only a superuser may name these (the tracker with its own role isn't one: there
      // the compose service database-roles made it so)
      if (now.callerSuperuser && now.elevated) add("ALTER ROLE $role NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS")
      add("ALTER ROLE $role LOGIN CONNECTION LIMIT $CONNECTION_LIMIT PASSWORD '$verifier'")
      if (!now.schemaUsage) add("GRANT USAGE ON SCHEMA public TO $role")
      if (now.tables != TABLE_RIGHTS || now.columns != COLUMN_RIGHTS) {
        // Whatever it had before (a table it no longer needs, the whole of session_events): gone, then its own
        add("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM $role")
        add("REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM $role")
        add("GRANT SELECT ON ${READS.joinToString()} TO $role")
        for ((table, columns) in UPDATES) add("GRANT UPDATE (${columns.joinToString()}) ON $table TO $role")
        // What it hears on the game servers, for the tracker to read: added, never read or changed here; the ID is
        // always the table's own (V29)
        for ((table, columns) in INSERTS) add("GRANT INSERT (${columns.joinToString()}) ON $table TO $role")
      }
      // Of the gateway's kinds only, for a session that is there (its foreign key, V29): the table's row-level security
      // (V28) lets a role add only what a policy allows. Made for this role (its name is a setting), in place of V28's
      // for every role; the tracker, the table's owner, isn't bound. Its comment says what it was made for
      if (now.policy != policyMark(role)) {
        add("DROP POLICY IF EXISTS $EVENT_POLICY ON session_events")
        add(
          "CREATE POLICY $EVENT_POLICY ON session_events FOR INSERT TO $role " +
            "WITH CHECK (kind IN (${EVENT_KINDS.joinToString { "'$it'" }}))",
        )
        add("COMMENT ON POLICY $EVENT_POLICY ON session_events IS '${policyMark(role)}'")
      }
    }
  }

  /**
   * Makes [role] with [password] and its rights, or brings it back to them (it may be there already, also without a
   * login: the compose service database-roles makes it so); run by the tracker (which migrates) at each start. Refused
   * for the login that runs it, for the tables' owner and for a superuser: their rights would be taken away.
   */
  suspend fun ensure(db: RandogeddonDatabase, role: String, password: String) {
    require(password.isNotBlank()) { "The gateway's database role needs a password" }
    require(NAME.matches(role)) { "Not a role name: $role" }
    retried {
      db.jdbc { c ->
        refuseOwn(c, role)
        statements(role, scramVerifier(password), state(c, role)).forEach { sql -> c.createStatement().use { it.execute(sql) } }
      }
    }
  }

  /**
   * [role] is no longer the gateway's (no password for it is set any more): it can't log in, and has no rights and no
   * policy left. Nothing if there is no such role. Refused as [ensure] is.
   */
  suspend fun retire(db: RandogeddonDatabase, role: String): Boolean {
    require(NAME.matches(role)) { "Not a role name: $role" }
    return retried {
      db.jdbc { c ->
        refuseOwn(c, role)
        val now = state(c, role)
        if (!now.exists) return@jdbc false
        val statements = buildList {
          add("ALTER ROLE $role NOLOGIN")
          add("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM $role")
          add("REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM $role")
          if (now.policy == policyMark(role)) add("DROP POLICY IF EXISTS $EVENT_POLICY ON session_events")
        }
        statements.forEach { sql -> c.createStatement().use { it.execute(sql) } }
        true
      }
    }
  }

  /** A lock another one holds (a dump, a migration) a moment longer than the connection's limit: tried again. */
  private suspend fun <T> retried(block: suspend () -> T): T {
    var tries = 0
    while (true) {
      try {
        return block()
      } catch (e: SQLException) {
        if (e.sqlState !in RETRIED || ++tries >= 3) throw e
        delay(2.seconds)
      }
    }
  }

  /** Lock not available, a deadlock, a catalog row two changed at once. */
  private val RETRIED = setOf("55P03", "40P01", "XX000")

  private fun refuseOwn(c: Connection, role: String) {
    c.prepareStatement(
      "SELECT current_user, (SELECT rolsuper FROM pg_roles WHERE rolname = ?), " +
        "(SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'sessions')",
    ).use { s ->
      s.setString(1, role)
      s.executeQuery().use { r ->
        r.next()
        val current = r.getString(1)
        val superuser = r.getBoolean(2)
        val owner = r.getString(3)
        check(role != current) { "The gateway's database role can't be $role: that is the tracker's own login" }
        check(role != owner) { "The gateway's database role can't be $role: it owns the tables" }
        check(!superuser) { "The gateway's database role can't be $role: it is a superuser" }
      }
    }
  }

  private fun state(c: Connection, role: String): Current {
    fun strings(sql: String): Set<String> = c.prepareStatement(sql).use { s ->
      s.setString(1, role)
      s.executeQuery().use { r -> buildSet { while (r.next()) add(r.getString(1)) } }
    }
    val (exists, elevated) = c.prepareStatement(
      "SELECT rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = ?",
    ).use { s ->
      s.setString(1, role)
      s.executeQuery().use { r -> if (r.next()) true to r.getBoolean(1) else false to false }
    }
    if (!exists) return Current()
    val callerSuperuser = c.createStatement().use { s ->
      s.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user").use { it.next() && it.getBoolean(1) }
    }
    val tables = strings(
      "SELECT c.relname || ':' || a.privilege_type FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
        "CROSS JOIN LATERAL aclexplode(c.relacl) a " +
        "WHERE n.nspname = 'public' AND a.grantee = (SELECT oid FROM pg_roles WHERE rolname = ?)",
    )
    val columns = strings(
      "SELECT c.relname || '.' || t.attname || ':' || a.privilege_type FROM pg_attribute t " +
        "JOIN pg_class c ON c.oid = t.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace " +
        "CROSS JOIN LATERAL aclexplode(t.attacl) a " +
        "WHERE n.nspname = 'public' AND NOT t.attisdropped AND a.grantee = (SELECT oid FROM pg_roles WHERE rolname = ?)",
    )
    val schemaUsage = c.prepareStatement("SELECT has_schema_privilege(?, 'public', 'USAGE')").use { s ->
      s.setString(1, role)
      s.executeQuery().use { it.next() && it.getBoolean(1) }
    }
    val policy = c.createStatement().use { s ->
      s.executeQuery(
        "SELECT obj_description(p.oid, 'pg_policy') FROM pg_policy p " +
          "WHERE p.polrelid = 'public.session_events'::regclass AND p.polname = '$EVENT_POLICY'",
      ).use { if (it.next()) it.getString(1) else null }
    }
    return Current(true, elevated, callerSuperuser, tables, columns, schemaUsage, policy)
  }
}

/**
 * The SCRAM-SHA-256 verifier of [password] as PostgreSQL keeps it (`SCRAM-SHA-256$<iterations>:<salt>$<StoredKey>:
 * <ServerKey>`, RFC 5802 and 7677): given in place of the password, PostgreSQL stores it as it is, and the password
 * itself never travels in a statement. A password of printable ASCII is taken as it is (as SASLprep leaves it); any
 * other is normalized (NFKC) first, as SASLprep does for most text.
 */
internal fun scramVerifier(password: String, salt: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes), iterations: Int = 4096): String {
  val text = if (password.all { it.code in 0x20..0x7e }) password else Normalizer.normalize(password, Normalizer.Form.NFKC)
  fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
  // PBKDF2 with HMAC-SHA-256, one block (32 bytes, the hash's length)
  val key = text.toByteArray(Charsets.UTF_8)
  var u = hmac(key, salt + byteArrayOf(0, 0, 0, 1))
  val salted = u.copyOf()
  repeat(iterations - 1) {
    u = hmac(key, u)
    for (i in salted.indices) salted[i] = (salted[i].toInt() xor u[i].toInt()).toByte()
  }
  val stored = MessageDigest.getInstance("SHA-256").digest(hmac(salted, "Client Key".toByteArray()))
  val server = hmac(salted, "Server Key".toByteArray())
  val b64 = Base64.getEncoder()
  return "SCRAM-SHA-256$$iterations:${b64.encodeToString(salt)}$${b64.encodeToString(stored)}:${b64.encodeToString(server)}"
}
