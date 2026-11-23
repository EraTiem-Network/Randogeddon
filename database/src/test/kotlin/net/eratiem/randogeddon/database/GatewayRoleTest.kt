package net.eratiem.randogeddon.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayRoleTest {
  private val role = "randogeddon_gateway"
  private val verifier = scramVerifier("pw")

  /** As it is once [GatewayRole.ensure] ran: nothing left to change. */
  private val done = GatewayRole.Current(
    exists = true, tables = GatewayRole.TABLE_RIGHTS, columns = GatewayRole.COLUMN_RIGHTS, schemaUsage = true,
    policy = GatewayRole.policyMark(role),
  )

  @Test
  fun `the gateway's role reads the sessions, the slot passwords and the settings, and writes only the session time and events`() {
    val statements = GatewayRole.statements(role, verifier, GatewayRole.Current())
    assertEquals("CREATE ROLE randogeddon_gateway", statements.first())
    val grants = statements.filter { it.startsWith("GRANT") }
    assertEquals(
      listOf(
        "GRANT USAGE ON SCHEMA public TO randogeddon_gateway",
        "GRANT SELECT ON sessions, session_hosted_slots, slot_passwords, app_settings TO randogeddon_gateway",
        "GRANT UPDATE (run_time_ms, counted_until) ON sessions TO randogeddon_gateway",
        // Never the ID: the table's own, always
        "GRANT INSERT (session, at_ms, kind, packet) ON session_events TO randogeddon_gateway",
      ),
      grants,
    )
    // Nothing of the accounts
    for (table in listOf("users", "login_sessions", "recovery_codes", "invitations", "email_changes", "login_links", "share_links")) {
      assertFalse(grants.any { table in it.substringAfter("ON ").substringBefore(" TO").split(", ", " ") }, table)
    }
    // What it had before is taken first
    assertTrue(statements.indexOfFirst { it.startsWith("REVOKE ALL ON ALL TABLES") } < statements.indexOfFirst { it.startsWith("GRANT SELECT") })
    // A login, with a limit of connections
    assertTrue(statements.any { it.startsWith("ALTER ROLE randogeddon_gateway LOGIN CONNECTION LIMIT ${GatewayRole.CONNECTION_LIMIT} PASSWORD") })
  }

  @Test
  fun `the attributes only a superuser may name are asked for only if one runs them`() {
    val elevated = done.copy(elevated = true)
    assertTrue(GatewayRole.statements(role, verifier, elevated).none { "NOCREATEDB" in it })
    assertTrue(GatewayRole.statements(role, verifier, elevated.copy(callerSuperuser = true)).any { "NOCREATEDB NOCREATEROLE" in it })
  }

  @Test
  fun `the gateway's role adds only the gateway's kinds of events, never a cheat`() {
    val statements = GatewayRole.statements(role, verifier, GatewayRole.Current())
    val policy = statements.single { it.startsWith("CREATE POLICY") }
    assertEquals(
      "CREATE POLICY gateway_event_kinds ON session_events FOR INSERT TO randogeddon_gateway " +
        "WITH CHECK (kind IN ('packet', 'start', 'stop'))",
      policy,
    )
    assertFalse(SessionEventStore.CHEAT in policy)
    // The one before goes first; the new one says what it was made for
    val drop = statements.indexOf("DROP POLICY IF EXISTS gateway_event_kinds ON session_events")
    assertTrue(drop in 0..<statements.indexOf(policy))
    assertTrue(statements.last().startsWith("COMMENT ON POLICY gateway_event_kinds"))
  }

  @Test
  fun `at a start where everything is as it should be, only the login is set again`() {
    assertEquals(listOf("ALTER ROLE randogeddon_gateway LOGIN CONNECTION LIMIT 10 PASSWORD '$verifier'"), GatewayRole.statements(role, verifier, done))
    // The policy made for another role (or V28's): made again, the rights left alone
    val other = GatewayRole.statements(role, verifier, done.copy(policy = GatewayRole.policyMark("old_gateway")))
    assertTrue(other.any { it.startsWith("CREATE POLICY") })
    assertTrue(other.none { it.startsWith("REVOKE") || it.startsWith("GRANT") })
    // A right missing (a restore without them) or too many: all of them again, the policy left alone
    val missing = GatewayRole.statements(role, verifier, done.copy(columns = emptySet()))
    assertTrue(missing.any { it.startsWith("GRANT INSERT") })
    assertTrue(missing.none { it.startsWith("CREATE POLICY") })
    val more = GatewayRole.statements(role, verifier, done.copy(tables = GatewayRole.TABLE_RIGHTS + "users:SELECT"))
    assertTrue(more.any { it.startsWith("REVOKE ALL ON ALL TABLES") })
  }

  @Test
  fun `the password never travels as text, only its SCRAM verifier`() {
    val password = "it's; DROP TABLE users; --"
    val statements = GatewayRole.statements(role, scramVerifier(password), GatewayRole.Current())
    assertTrue(statements.none { password in it || "DROP TABLE users" in it })
    assertFailsWith<IllegalArgumentException> { GatewayRole.statements(role, "plain-password", GatewayRole.Current()) }
    assertFailsWith<IllegalArgumentException> { GatewayRole.statements("gateway; DROP TABLE users", verifier, GatewayRole.Current()) }
    assertFailsWith<IllegalArgumentException> { GatewayRole.statements("Gateway", verifier, GatewayRole.Current()) }
  }

  @Test
  fun `the verifier is SCRAM-SHA-256's, as another implementation makes it`() {
    // Python's hashlib.pbkdf2_hmac and hmac, by RFC 7677's steps
    assertEquals(
      "SCRAM-SHA-256\$4096:AAECAwQFBgcICQoLDA0ODw==\$zHCdol2044/ZyWzPLi7oxApCkamKw9Z+E4U/QApd/5Y=:" +
        "dd5peBOitVnLNFu7VmwP+HiDaaw4OUCv396eVCWhYiE=",
      scramVerifier("pencil", ByteArray(16) { it.toByte() }),
    )
    // A new salt each time
    assertTrue(scramVerifier("pencil") != scramVerifier("pencil"))
  }
}
