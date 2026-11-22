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
    assertEquals(Verdict.Join("Alice-HK"), connectWith(setOf("Bob-Yacht")))
    assertEquals(Verdict.Join("Alice-HK"), connectWith(emptySet()))
  }

  private val passwords = mapOf("Alice-HK" to "s3cr3t!", "Bob-Yacht" to "other")

  @Test
  fun `the room asks for a password, so clients that send theirs only then do`() {
    val roomInfo = """[{"cmd":"RoomInfo","password":false,"games":["Brotato"],"version":{"major":0,"minor":6,"build":8,"class":"Version"},"time":1763577600.1234567}]"""
    assertEquals(
      """[{"cmd":"RoomInfo","password":true,"games":["Brotato"],"version":{"major":0,"minor":6,"build":8,"class":"Version"},"time":1763577600.1234567}]""",
      askingForPassword(roomInfo),
    )
    // Anything else as it is, also a text that only mentions it
    val print = """[{"cmd":"PrintJSON","data":[{"text":"RoomInfo"}],"type":"Chat",  "slot":1}]"""
    assertEquals(print, askingForPassword(print))
    assertEquals("not json RoomInfo", askingForPassword("not json RoomInfo"))
    val other = """[{"cmd":"Connected","slot":1}]"""
    assertEquals(other, askingForPassword(other))
  }

  /** The check with a fixed password map. */
  private fun checkClientFrame(text: String, passwords: Map<String, String>) =
    runBlocking { net.eratiem.randogeddon.gateway.checkClientFrame(text, { passwords }) }

  private fun connect(name: String, password: String) =
    """[{"cmd":"Connect","password":"$password","game":"","name":"$name","uuid":"x","items_handling":7,"tags":[]}]"""

  @Test
  fun `the right password passes`() {
    assertEquals(Verdict.Join("Alice-HK"), checkClientFrame(connect("Alice-HK", "s3cr3t!"), passwords))
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

  /** The check before the client's Connect passed, with [before] for what it may still send. */
  private fun checkBefore(text: String, before: BeforeConnect = BeforeConnect(32)) =
    runBlocking { net.eratiem.randogeddon.gateway.checkClientFrame(text, { passwords }, before = before) }

  @Test
  fun `a packet that isn't an object with a command closes the connection`() {
    // MultiServer would log each whole, then end the connection
    for (frame in listOf("""[{}]""", """[[1]]""", """[1]""", """[{"cmd":"GetDataPackage"},{"text":"x"}]""")) {
      assertIs<Verdict.Close>(checkBefore(frame), frame)
      assertIs<Verdict.Close>(checkClientFrame(frame, passwords), frame)
    }
    // Before the Connect the command is a string; after it, MultiServer answers anything else itself
    assertIs<Verdict.Close>(checkBefore("""[{"cmd":5}]"""))
    assertEquals(Verdict.Forward, checkClientFrame("""[{"cmd":5}]""", passwords))
  }

  @Test
  fun `before the Connect only the data package and the Connect go on`() {
    assertEquals(Verdict.Forward, checkBefore("""[{"cmd":"GetDataPackage","games":["Hollow Knight"]}]"""))
    assertEquals(Verdict.Forward, checkBefore("""[]"""))
    assertEquals(Verdict.Only(null), checkBefore("""[{"cmd":"Say","text":"Connect me pls"}]"""))
    assertEquals(
      Verdict.Only("""[{"cmd":"GetDataPackage","games":["A"]}]"""),
      checkBefore("""[{"cmd":"LocationChecks","locations":[1]},{"cmd":"GetDataPackage","games":["A"]},{"cmd":"Sync"}]"""),
    )
    // A frame with a Connect that passes goes on whole: the packets after it are the slot's
    val withConnect = """[{"cmd":"GetDataPackage"},{"cmd":"Connect","name":"Alice-HK","password":"s3cr3t!"},{"cmd":"Sync"}]"""
    assertEquals(Verdict.Join("Alice-HK"), checkBefore(withConnect))
    // After the Connect, everything
    assertEquals(Verdict.Forward, checkClientFrame("""[{"cmd":"Say","text":"hi"}]""", passwords))
  }

  @Test
  fun `before the Connect the packets are counted over every frame`() {
    val before = BeforeConnect(3)
    assertEquals(Verdict.Forward, checkBefore("""[{"cmd":"GetDataPackage"},{"cmd":"GetDataPackage"}]""", before))
    assertEquals(1, before.packetsLeft)
    assertIs<Verdict.Close>(checkBefore("""[{"cmd":"GetDataPackage"},{"cmd":"GetDataPackage"}]""", before))
    // One frame full of them
    assertIs<Verdict.Close>(checkBefore((1..33).joinToString(",", "[", "]") { """{"cmd":"GetDataPackage"}""" }))
  }

  @Test
  fun `real clients' openings pass`() {
    // CommonClient: the games it lacks in one request, then the Connect
    val before = BeforeConnect(Limits().packetsBeforeConnect)
    assertEquals(Verdict.Forward, checkBefore("""[{"cmd":"GetDataPackage","games":["Archipelago","Hollow Knight"]}]""", before))
    val connect = """[{"cmd":"Connect","password":"s3cr3t!","name":"Alice-HK","version":{"major":0,"minor":6,"build":8,"class":"Version"},""" +
      """"tags":["AP"],"items_handling":7,"uuid":"6c1f","game":"Hollow Knight","slot_data":true}]"""
    assertEquals(Verdict.Join("Alice-HK"), checkBefore(connect, before))
    // A client asking one game a frame, for a multiworld of twenty games
    val oneByOne = BeforeConnect(Limits().packetsBeforeConnect)
    repeat(20) { assertEquals(Verdict.Forward, checkBefore("""[{"cmd":"GetDataPackage","games":["Game $it"]}]""", oneByOne)) }
    assertEquals(Verdict.Join("Alice-HK"), checkBefore(connect, oneByOne))
    // The Connect in the same frame as the request
    assertEquals(
      Verdict.Join("Alice-HK"),
      checkBefore("""[{"cmd":"GetDataPackage","games":["Hollow Knight"]},{"cmd":"Connect","name":"Alice-HK","password":"s3cr3t!","game":""}]"""),
    )
  }

  @Test
  fun `the password is compared as a whole, in any script`() {
    val unicode = mapOf("Alice-HK" to "pässwörd-日本")
    assertEquals(Verdict.Join("Alice-HK"), checkClientFrame(connect("Alice-HK", "pässwörd-日本"), unicode))
    assertEquals(Verdict.Refuse("Alice-HK"), checkClientFrame(connect("Alice-HK", "pässwörd-日"), unicode))
    assertEquals(Verdict.Refuse("Alice-HK"), checkClientFrame(connect("Alice-HK", "pässwörd-日本x"), unicode))
    assertEquals(Verdict.Refuse("Alice-HK"), checkClientFrame(connect("Alice-HK", "passwörd-日本"), unicode))
  }

  @Test
  fun `duplicate keys are read like MultiServer reads them`() {
    // Python's json.loads keeps the last of duplicate keys, and so does this parser: both see the same password
    assertEquals(
      Verdict.Refuse("Alice-HK"),
      checkClientFrame("""[{"cmd":"Connect","name":"Alice-HK","password":"s3cr3t!","password":"x"}]""", passwords),
    )
    assertEquals(
      Verdict.Join("Alice-HK"),
      checkClientFrame("""[{"cmd":"Connect","name":"Alice-HK","password":"x","password":"s3cr3t!"}]""", passwords),
    )
  }
}
