package net.eratiem.randogeddon.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayRoleTest {
  @Test
  fun `the gateway's role reads the sessions and the slot passwords, and writes only the session time`() {
    val statements = GatewayRole.statements("randogeddon_gateway", "pw")
    val grants = statements.filter { it.startsWith("GRANT") }
    assertEquals(
      listOf(
        "GRANT USAGE ON SCHEMA public TO randogeddon_gateway",
        "GRANT SELECT ON sessions, slot_passwords TO randogeddon_gateway",
        "GRANT UPDATE (run_time_ms, counted_until) ON sessions TO randogeddon_gateway",
      ),
      grants,
    )
    // Nothing of the accounts
    for (table in listOf("users", "login_sessions", "recovery_codes", "invitations", "email_changes", "login_links", "share_links")) {
      assertFalse(grants.any { table in it.substringAfter("ON ").substringBefore(" TO").split(", ", " ") }, table)
    }
    // The tracker may run them without being a superuser: the attributes only a superuser may name are asked for
    // only if one runs them
    assertTrue(statements.none { it.startsWith("ALTER ROLE") && ("NOSUPERUSER" in it || "NOCREATEDB" in it || "NOCREATEROLE" in it) })
    assertTrue(statements.any { it.startsWith("DO") && "rolsuper" in it && "NOSUPERUSER NOCREATEDB NOCREATEROLE" in it })
    // What it had before is taken first
    assertTrue(statements.indexOfFirst { it.startsWith("REVOKE ALL ON ALL TABLES") } < statements.indexOfFirst { it.startsWith("GRANT SELECT") })
  }

  @Test
  fun `a password with quotes stays one string, and only a plain name is a role`() {
    val alter = GatewayRole.statements("randogeddon_gateway", "it's; DROP TABLE users; --").first { it.startsWith("ALTER ROLE") }
    assertTrue(alter.endsWith("PASSWORD 'it" + "''" + "s; DROP TABLE users; --" + "'"), alter)
    assertFailsWith<IllegalArgumentException> { GatewayRole.statements("gateway; DROP TABLE users", "pw") }
    assertFailsWith<IllegalArgumentException> { GatewayRole.statements("Gateway", "pw") }
  }
}
