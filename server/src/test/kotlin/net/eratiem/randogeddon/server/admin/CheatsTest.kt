package net.eratiem.randogeddon.server.admin

import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.DatabaseConfig
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.shared.CheatAction
import net.eratiem.randogeddon.shared.HintPick
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.server.session.Placement
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CheatsTest {
  @Test
  fun `names go to the console in double quotes, with quotes and backslashes escaped`() {
    assertEquals("\"Mantis Claw\"", consoleWord("Mantis Claw"))
    assertEquals("\"Queen's Gardens\"", consoleWord("Queen's Gardens"))
    assertEquals("\"A \\\"B\\\" C\"", consoleWord("A \"B\" C"))
    assertEquals("\"back\\\\slash\"", consoleWord("back\\slash"))
  }

  @Test
  fun `no control character reaches the console`() {
    // A line break would start a command of its own
    assertEquals("\"Mantis Claw/release Bob\"", consoleWord("Mantis Claw\n/release Bob"))
    assertEquals("/release Bob-Yacht/collect Ann", cheatCommand(CheatAction.RELEASE, "Bob-Yacht\r\n/collect Ann", null))
    val client = GameServerClient(
      TrackerConfig(
        0, "Randogeddon", Path.of("."), Path.of("."), Path.of("."), "{id}.randogeddon.eratiem.net:443", "localhost",
        DatabaseConfig("", "", ""), "http://localhost:1", "",
      ),
    )
    // Refused before the gateway is asked (there is none here)
    val refused = assertFailsWith<SessionProblem> { runBlocking { client.command("brave-otter", "/send \"A\" \"B\"\n/release C") } }
    assertEquals(Problem.COMMAND_CONTROL, refused.refusal.problem)
  }

  @Test
  fun `each cheat is its server command`() {
    assertEquals("/send \"Alice-HK\" \"Mantis Claw\"", cheatCommand(CheatAction.ITEM, "Alice-HK", "Mantis Claw"))
    assertEquals("/send_location \"Team-RoR2\" \"Titanic Plains: Chest 1\"",
      cheatCommand(CheatAction.LOCATION, "Team-RoR2", "Titanic Plains: Chest 1"))
    // These two take the rest of the line as the name, quotes included
    assertEquals("/release Bob's Yacht", cheatCommand(CheatAction.RELEASE, "Bob's Yacht", null))
    assertEquals("/collect Bob-Yacht", cheatCommand(CheatAction.COLLECT, "Bob-Yacht", null))
    assertEquals("/hint \"Alice-HK\" \"Mantis Claw\"", cheatCommand(CheatAction.HINT, "Alice-HK", "Mantis Claw"))
    assertEquals("/hint_location \"Alice-HK\" \"King's Pass\"", cheatCommand(CheatAction.HINT_LOCATION, "Alice-HK", "King's Pass"))
    // A locked slot: its key's location in the world it is in, checked (Unlock) or hinted
    assertEquals("/send_location \"Bob\" \"Chest B\"", cheatCommand(CheatAction.UNLOCK, "Bob", "Chest B"))
    assertEquals("/hint_location \"Bob\" \"Chest B\"", cheatCommand(CheatAction.KEY_HINT, "Bob", "Chest B"))
    assertEquals("/send_location \"Bob\" \"Chest B\"", cheatCommand(CheatAction.KEY_LOCATION, "Bob", "Chest B"))
  }

  @Test
  fun `a hint names one copy that is neither checked nor hinted`() {
    val placed = listOf(
      Placement("Crossroads", "Alice-HK", "Progressive Sword", "Bob-LttP"),
      Placement("Tower", "Bob-LttP", "Progressive Sword", "Bob-LttP"),
      Placement("Lake", "Carol-SM", "Progressive Sword", "Bob-LttP"),
      Placement("Cave", "Carol-SM", "Progressive Sword", "Dave-OoT"),
      Placement("Shop", "Alice-HK", "Bombs", "Bob-LttP"),
    )
    val checked = mapOf("Alice-HK" to setOf("Crossroads"))
    val hinted = mapOf("Bob-LttP" to setOf("Tower"))
    // Only Lake is left: Crossroads is checked, Tower hinted, Cave is another slot's sword
    repeat(20) { n ->
      assertEquals(placed[2], pickHint(placed, "Bob-LttP", "Progressive Sword", checked, hinted, Random(n)))
    }
    assertNull(pickHint(placed, "Bob-LttP", "Progressive Sword", checked, hinted + ("Carol-SM" to setOf("Lake")), Random(1)))
    assertNull(pickHint(placed, "Bob-LttP", "Mantis Claw", checked, hinted, Random(1)))
    // Both copies are picked now and then, never another one
    val free = (0 until 50).map { pickHint(placed, "Bob-LttP", "Progressive Sword", emptyMap(), emptyMap(), Random(it))!!.location }.toSet()
    assertEquals(setOf("Crossroads", "Tower", "Lake"), free)
    // The items a hint can still name
    assertEquals(listOf("Progressive Sword", "Bombs"), unhinted(placed, "Bob-LttP", checked, hinted).map { it.item }.distinct())
  }

  @Test
  fun `the next copy is the one the playthrough needs first`() {
    val placed = listOf(
      Placement("Crossroads", "Alice-HK", "Progressive Sword", "Bob-LttP"),
      Placement("Tower", "Bob-LttP", "Progressive Sword", "Bob-LttP"),
      Placement("Lake", "Carol-SM", "Progressive Sword", "Bob-LttP"),
    )
    // Lake in sphere 1, Tower in sphere 3, Crossroads not in the playthrough
    val playthrough = listOf("Alice-HK" to "Shop", "Carol-SM" to "Lake", "Dave-OoT" to "Cave", "Bob-LttP" to "Tower")
    fun next(hinted: Map<String, Set<String>>) =
      pickHint(placed, "Bob-LttP", "Progressive Sword", emptyMap(), hinted, Random(1), HintPick.NEXT, playthrough)?.location
    assertEquals("Lake", next(emptyMap()))
    assertEquals("Tower", next(mapOf("Carol-SM" to setOf("Lake"))))
    // Then one the playthrough doesn't need
    assertEquals("Crossroads", next(mapOf("Carol-SM" to setOf("Lake"), "Bob-LttP" to setOf("Tower"))))
    // Without a playthrough: the spoiler log's order
    assertEquals("Crossroads", pickHint(placed, "Bob-LttP", "Progressive Sword", emptyMap(), emptyMap(), Random(1), HintPick.NEXT)?.location)
  }

  @Test
  fun `the feed says who did what`() {
    assertEquals("Ada gave Mantis Claw to Alice-HK", cheatText("Ada", CheatAction.ITEM, "Alice-HK", "Mantis Claw"))
    assertEquals("Ada released Bob-Yacht", cheatText("Ada", CheatAction.RELEASE, "Bob-Yacht", null))
    assertEquals("Ada unlocked Alice-Locked", cheatText("Ada", CheatAction.UNLOCK, "Alice-Locked", null))
    assertEquals("Ada unlocked Alice-Locked (its key at Chest B (in Bob))", cheatText("Ada", CheatAction.UNLOCK, "Alice-Locked", "Chest B (in Bob)"))
    assertEquals("Ada hinted where the key to Alice-Locked is: Chest B (in Bob)",
      cheatText("Ada", CheatAction.KEY_HINT, "Alice-Locked", "Chest B (in Bob)"))
    assertEquals("Ada checked the key to Alice-Locked at Chest B (in Bob)",
      cheatText("Ada", CheatAction.KEY_LOCATION, "Alice-Locked", "Chest B (in Bob)"))
    // Anyone but an Administrator with their role
    assertEquals("Bob (support) gave Mantis Claw to Alice-HK", cheatText("Bob", CheatAction.ITEM, "Alice-HK", "Mantis Claw", "support"))
    assertEquals("Sam (Session-Manager) hinted Dash for Alice-HK", cheatText("Sam", CheatAction.HINT, "Alice-HK", "Dash", "manager"))
  }
}
