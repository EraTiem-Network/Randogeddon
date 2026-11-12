package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.shared.CheatAction
import kotlin.test.Test
import kotlin.test.assertEquals

class CheatsTest {
  @Test
  fun `names go to the console in double quotes, with quotes and backslashes escaped`() {
    assertEquals("\"Mantis Claw\"", consoleWord("Mantis Claw"))
    assertEquals("\"Queen's Gardens\"", consoleWord("Queen's Gardens"))
    assertEquals("\"A \\\"B\\\" C\"", consoleWord("A \"B\" C"))
    assertEquals("\"back\\\\slash\"", consoleWord("back\\slash"))
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
  }

  @Test
  fun `the feed says who did what`() {
    assertEquals("Ada gave Mantis Claw to Alice-HK", cheatText("Ada", CheatAction.ITEM, "Alice-HK", "Mantis Claw"))
    assertEquals("Ada released Bob-Yacht", cheatText("Ada", CheatAction.RELEASE, "Bob-Yacht", null))
  }
}
