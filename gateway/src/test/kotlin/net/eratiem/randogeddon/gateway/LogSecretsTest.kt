package net.eratiem.randogeddon.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSecretsTest {
  @Test
  fun `a refused Connect is logged without its password`() {
    // As MultiServer logs it: the packet as Python prints a dict
    val line = "A client connection was refused due to: {'InvalidGame'}, the sent connect information was " +
      "{'cmd': 'Connect', 'password': 's3cr3t!#x', 'game': 'Wrong', 'name': 'Alice-HK', 'tags': []}."
    val shown = withoutPasswords(line)
    assertFalse("s3cr3t" in shown, shown)
    assertEquals(line.replace("s3cr3t!#x", "***"), shown)
    // As JSON, and with the other kind of quotes
    assertEquals("""{"cmd":"Connect","password":"***","name":"Bob"}""", withoutPasswords("""{"cmd":"Connect","password":"pw","name":"Bob"}"""))
    assertEquals("""'password': "***", 'x': 1""", withoutPasswords("""'password': "it's", 'x': 1"""))
  }

  @Test
  fun `a line cut inside a password keeps no part of it`() {
    val line = "A client connection was refused due to: {'InvalidSlot'}, the sent connect information was " +
      "{'cmd': 'Connect', 'name': 'Alice', 'password': '" + "s3cr3t".repeat(1000) + "', 'game': 'Wrong'}."
    val shown = withoutPasswords(line.reader().cutLines(MAX_LOG_LINE).single())
    assertFalse("s3c" in shown, shown)
    assertTrue(shown.endsWith("'password': '***"), shown)
    // Cut right after the quote, and a closed one before an open one
    assertEquals("""{"password": "***""", withoutPasswords("""{"password": """"))
    assertEquals("'password': '***', 'x': 'password': \"***", withoutPasswords("'password': 'a', 'x': 'password': \"bc"))
  }

  @Test
  fun `the console's options are logged without the passwords`() {
    assertEquals("Set option server_password to ***", withoutPasswords("Set option server_password to hunter2 and more"))
    assertEquals("Set option password to ***", withoutPasswords("Set option password to hunter2"))
    assertEquals("Option server_password is set to ***", withoutPasswords("Option server_password is set to hunter2"))
    assertEquals("[2026-11-16 10:00:00,000]: Option password is set to ***", withoutPasswords("[2026-11-16 10:00:00,000]: Option password is set to None"))
    // What a manager typed
    assertEquals("> /option server_password ***", withoutPasswords("> /option server_password \"hunter2\""))
    // Other options stay
    assertEquals("Set option hint_cost to 10", withoutPasswords("Set option hint_cost to 10"))
    assertEquals("Option release_mode is set to auto", withoutPasswords("Option release_mode is set to auto"))
  }

  @Test
  fun `a console line is one line, whatever it brings`() {
    assertEquals("/say hi /exit", oneLine("/say hi\r/exit"))
    assertEquals("/say hi /exit", oneLine("/say hi\n/exit"))
    assertEquals("/say a b", oneLine("/say a\u0000b"))
  }

  @Test
  fun `the room password MultiServer names at its start isn't logged`() {
    // A save of before holds the room password of its last run: MultiServer names it as it starts listening
    assertEquals(
      "[2026-11-22 10:00:00,000]: Hosting game at 0.0.0.0:38282 (Password: ***)",
      withoutPasswords("[2026-11-22 10:00:00,000]: Hosting game at 0.0.0.0:38282 (Password: 9f2c4e1a)"),
    )
    assertEquals("Hosting game at 0.0.0.0:38282 (No password)", withoutPasswords("Hosting game at 0.0.0.0:38282 (No password)"))
    // Cut inside it
    assertEquals("Hosting game at 0.0.0.0:38282 (Password: ***", withoutPasswords("Hosting game at 0.0.0.0:38282 (Password: 9f2c"))
  }

  @Test
  fun `a console line that would set a password of MultiServer's is the gateway's alone`() {
    assertTrue(setsPassword("/option password open"))
    assertTrue(setsPassword("  /OPTION server_password hunter2"))
    // MultiServer's console splits like a shell: quotes and escapes don't hide it
    assertTrue(setsPassword("\"/option\" 'password' \"\""))
    assertTrue(setsPassword("/option server_pass\\word x"))
    assertFalse(setsPassword("/option hint_cost 10"))
    assertFalse(setsPassword("/say /option password x"))
    assertFalse(setsPassword("/options"))
  }
}
