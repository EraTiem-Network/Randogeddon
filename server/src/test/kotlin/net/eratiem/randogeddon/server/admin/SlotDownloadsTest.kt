package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SlotFile
import kotlin.test.Test
import kotlin.test.assertEquals

class SlotDownloadsTest {
  private val files = listOf(
    SlotFile("AP_1_P1_Alice-SM.apsm", 1, slotName = "Alice-SM"),
    SlotFile("AP_1_P2_Team-LttP.aplttp", 2, slotName = "Team-LttP"),
    SlotFile("AP-1-P3-Bob_0.6.8.zip", 3, slotName = "Bob-Factorio"),
    // Not named by the spoiler log: nobody's but the managers'
    SlotFile("AP_1_P4_Ghost.apbp", 4),
  )

  private fun names(administrator: Boolean, role: SessionRole?, mySlots: List<String>) =
    files.filter { mayDownload(administrator, role, mySlots, it) }.map { it.name }

  @Test
  fun `players get their own slots' files, a shared slot each of its players`() {
    assertEquals(listOf("AP_1_P1_Alice-SM.apsm", "AP_1_P2_Team-LttP.aplttp"), names(false, SessionRole.PLAYER, listOf("Alice-SM", "Team-LttP")))
    assertEquals(listOf("AP_1_P2_Team-LttP.aplttp"), names(false, SessionRole.PLAYER, listOf("Team-LttP")))
    assertEquals(listOf("AP-1-P3-Bob_0.6.8.zip"), names(false, SessionRole.PLAYER, listOf("Bob-Factorio")))
  }

  @Test
  fun `Session-Managers and Administrators get every slot's, support and watchers none`() {
    assertEquals(files.map { it.name }, names(false, SessionRole.MANAGER, emptyList()))
    assertEquals(files.map { it.name }, names(true, null, emptyList()))
    assertEquals(emptyList(), names(false, SessionRole.SUPPORT, emptyList()))
    assertEquals(emptyList(), names(false, SessionRole.WATCHER, emptyList()))
    assertEquals(emptyList(), names(false, null, emptyList()))
  }
}
