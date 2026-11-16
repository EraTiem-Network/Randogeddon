package net.eratiem.randogeddon.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
  fun `a word for the console is quoted`() {
    assertEquals("\"plain\"", consoleWord("plain"))
    assertEquals("\"two words\"", consoleWord("two words"))
    assertEquals("\"a\\\"b\\\\c\"", consoleWord("a\"b\\c"))
  }
}
