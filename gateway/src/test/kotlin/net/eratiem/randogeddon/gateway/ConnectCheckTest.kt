package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConnectCheckTest {
  @Test
  fun `a locked slot is refused even with its password, until its key is found`() {
    val passwords = mapOf("Alice-HK" to "s3cr3t!", "Bob-Yacht" to "pw")
    fun connectWith(locked: Set<String>): Verdict =
      runBlocking { net.eratiem.randogeddon.gateway.checkClientFrame(connect("Alice-HK", "s3cr3t!"), { passwords }, { locked }) }
    assertEquals(Verdict.Refuse("Alice-HK", Verdict.Why.LOCKED), connectWith(setOf("Alice-HK")))
    assertEquals(Verdict.Join, connectWith(setOf("Bob-Yacht")))
    assertEquals(Verdict.Join, connectWith(emptySet()))
  }

  private val passwords = mapOf("Alice-HK" to "s3cr3t!", "Bob-Yacht" to "other")

  /** The check with a fixed password map. */
  private fun checkClientFrame(text: String, passwords: Map<String, String>) =
    runBlocking { net.eratiem.randogeddon.gateway.checkClientFrame(text, { passwords }) }

  private fun connect(name: String, password: String) =
    """[{"cmd":"Connect","password":"$password","game":"","name":"$name","uuid":"x","items_handling":7,"tags":[]}]"""

  @Test
  fun `the right password passes`() {
    assertEquals(Verdict.Join, checkClientFrame(connect("Alice-HK", "s3cr3t!"), passwords))
  }

  @Test
  fun `a wrong password, another slot's password or none is refused`() {
    assertEquals(Verdict.Refuse("Alice-HK"), checkClientFrame(connect("Alice-HK", "wrong"), passwords))
    assertEquals(Verdict.Refuse("Alice-HK"), checkClientFrame(connect("Alice-HK", "other"), passwords))
    assertEquals(Verdict.Refuse("Alice-HK"), checkClientFrame(connect("Alice-HK", ""), passwords))
    assertEquals(
      Verdict.Refuse("Alice-HK"),
      checkClientFrame("""[{"cmd":"Connect","name":"Alice-HK","password":12}]""", passwords),
    )
    assertEquals(Verdict.Refuse("Alice-HK"), checkClientFrame("""[{"cmd":"Connect","name":"Alice-HK"}]""", passwords))
  }

  @Test
  fun `escaped spellings are checked like plain ones`() {
    // MultiServer reads Connect as Connect and Alice as Alice
    val escaped = """[{"cmd":"Connect","name":"Alice-HK","password":"wrong"}]"""
    assertEquals(Verdict.Refuse("Alice-HK"), checkClientFrame(escaped, passwords))
  }

  @Test
  fun `a Connect hidden behind other packets is found`() {
    val frame = """[{"cmd":"Sync"},{"cmd":"Connect","name":"Bob-Yacht","password":"s3cr3t!"}]"""
    assertEquals(Verdict.Refuse("Bob-Yacht"), checkClientFrame(frame, passwords))
  }

  @Test
  fun `a name without a password is refused here, whatever MultiServer knows it as`() {
    // A typo; but also an item link's group, which MultiServer takes in under its name without any password
    assertEquals(Verdict.Refuse("Typo-HK", Verdict.Why.UNKNOWN), checkClientFrame(connect("Typo-HK", "whatever"), passwords))
    assertEquals(Verdict.Refuse("Everyone", Verdict.Why.UNKNOWN), checkClientFrame(connect("Everyone", ""), passwords))
    val tracker = """[{"cmd":"Connect","name":"Everyone","password":null,"game":"","tags":["TextOnly"]}]"""
    assertEquals(Verdict.Refuse("Everyone", Verdict.Why.UNKNOWN), checkClientFrame(tracker, passwords))
    // No name, or one that isn't text
    assertEquals(Verdict.Refuse(null, Verdict.Why.UNKNOWN), checkClientFrame("""[{"cmd":"Connect","password":"x"}]""", passwords))
    assertEquals(Verdict.Refuse(null, Verdict.Why.UNKNOWN), checkClientFrame("""[{"cmd":"Connect","name":7}]""", passwords))
    assertEquals("""[{"cmd":"ConnectionRefused","errors":["InvalidSlot"]}]""", Verdict.Why.UNKNOWN.answer)
  }

  @Test
  fun `one Connect that doesn't pass refuses the frame, also behind one that does`() {
    val frame = """[{"cmd":"Connect","name":"Alice-HK","password":"s3cr3t!"},{"cmd":"Connect","name":"Everyone","password":""}]"""
    assertEquals(Verdict.Refuse("Everyone", Verdict.Why.UNKNOWN), checkClientFrame(frame, passwords))
  }

  @Test
  fun `without known passwords every Connect is refused`() {
    assertEquals(Verdict.Refuse("Alice-HK"), checkClientFrame(connect("Alice-HK", "s3cr3t!"), emptyMap()))
    // Other packets still pass (MultiServer ignores them before a Connect)
    assertEquals(Verdict.Forward, checkClientFrame("""[{"cmd":"GetDataPackage"}]""", emptyMap()))
  }

  @Test
  fun `passwords are only looked up for a Connect`() = runBlocking {
    var lookups = 0
    val lookup: suspend () -> Map<String, String> = { lookups++; passwords }
    checkClientFrame("""[{"cmd":"LocationChecks","locations":[1]}]""", lookup)
    assertEquals(0, lookups)
    checkClientFrame(connect("Alice-HK", "s3cr3t!"), lookup)
    assertEquals(1, lookups)
  }

  @Test
  fun `other packets pass untouched`() {
    assertEquals(Verdict.Forward, checkClientFrame("""[{"cmd":"LocationChecks","locations":[1,2,3]}]""", passwords))
    assertEquals(Verdict.Forward, checkClientFrame("""[{"cmd":"Say","text":"Connect me pls"}]""", passwords))
  }

  @Test
  fun `anything that isn't a JSON array of packets closes the connection`() {
    assertIs<Verdict.Close>(checkClientFrame("""{"cmd":"Connect"}""", passwords))
    assertIs<Verdict.Close>(checkClientFrame("not json", passwords))
  }

  @Test
  fun `duplicate keys are read like MultiServer reads them`() {
    // Python's json.loads keeps the last of duplicate keys, and so does this parser: both see the same password
    assertEquals(
      Verdict.Refuse("Alice-HK"),
      checkClientFrame("""[{"cmd":"Connect","name":"Alice-HK","password":"s3cr3t!","password":"x"}]""", passwords),
    )
    assertEquals(
      Verdict.Join,
      checkClientFrame("""[{"cmd":"Connect","name":"Alice-HK","password":"x","password":"s3cr3t!"}]""", passwords),
    )
  }
}
