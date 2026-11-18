package net.eratiem.randogeddon.server.admin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameServerLimitTest {
  @Test
  fun `one more may start below the limit, never at it, and always without one`() {
    assertTrue(roomForOneMore(running = 0, limit = 1))
    assertTrue(roomForOneMore(running = 7, limit = 8))
    assertFalse(roomForOneMore(running = 8, limit = 8))
    assertFalse(roomForOneMore(running = 9, limit = 8))
    // 0: no limit
    assertTrue(roomForOneMore(running = 50, limit = 0))
  }
}
