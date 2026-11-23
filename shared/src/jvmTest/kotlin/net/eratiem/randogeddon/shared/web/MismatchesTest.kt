package net.eratiem.randogeddon.shared.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.eratiem.randogeddon.shared.PlayerStatus
import net.eratiem.randogeddon.shared.PlayerSummary

class MismatchesTest {
  private fun world(name: String, mismatch: String?) = PlayerSummary(
    slot = 1, name = name, game = "Game", checked = 0, total = 10, percent = 0.0, status = PlayerStatus.IDLE,
    online = false, itemsReceived = 0, progressionReceived = 0, logicMismatch = mismatch,
  )

  @Test
  fun `the logic warning of several slots names each one that does not fit, with its reason where there is one`() {
    assertNull(mismatches(listOf(world("Link", null), world("Samus", null))))
    assertEquals("Samus: no fit", mismatches(listOf(world("Link", null), world("Samus", "no fit"))))
    assertEquals("Link\nSamus: no fit", mismatches(listOf(world("Link", ""), world("Samus", "no fit"))))
  }

  @Test
  fun `without any reason to read it is a warning without one`() {
    assertEquals("", mismatches(listOf(world("Link", ""), world("Samus", ""), world("Mario", null))))
  }
}
