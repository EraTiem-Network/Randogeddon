package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.database.FinishState
import net.eratiem.randogeddon.database.legacySessionId
import net.eratiem.randogeddon.server.admin.suggestId
import net.eratiem.randogeddon.shared.SessionIds
import net.eratiem.randogeddon.shared.SessionStatus
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionIdsTest {
  @Test
  fun `a session ID is a DNS label that starts with a letter`() {
    for (id in listOf("abc", "friday-chaos", "s-12345", "a1b2", "x".repeat(40))) assertTrue(SessionIds.valid(id), id)
    for (id in listOf("ab", "12345", "-abc", "abc-", "Friday", "a--b", "a_b", "a.b", "x".repeat(41), "admin", "inspect")) {
      assertFalse(SessionIds.valid(id), id)
    }
    assertNull(SessionIds.problem("friday-chaos"))
    assertEquals(Problem.ID_LETTER_FIRST, SessionIds.problem("1abc")?.problem)
    assertEquals(Problem.ID_CHARACTERS, SessionIds.problem("friDay")?.problem)
    // Typed: only what a subdomain allows
    assertEquals("friday-chaos", SessionIds.typed("Friday Chaos"))
    assertEquals("my-run-2", SessionIds.typed("My_Run.2"))
    assertEquals("a-b", SessionIds.typed("a--b"))
    assertEquals("grn-spiel", SessionIds.typed("Grün!? Spiel"))
    assertEquals("ab-", SessionIds.typed("ab-"))
    assertEquals("ab", SessionIds.trimEnd(SessionIds.typed("ab-")))
    // Nothing before the first letter
    assertEquals("run", SessionIds.typed("2024 run"))
    assertEquals("x1", SessionIds.typed("--x1"))
    assertEquals("", SessionIds.typed("123"))
    // Typed key by key: the space at the end is its hyphen at once
    assertEquals("friday-", SessionIds.typed("friday "))
    assertEquals("friday-c", SessionIds.typed(SessionIds.typed("friday ") + "c"))
    assertEquals(SessionIds.MAX_LENGTH, SessionIds.typed("x".repeat(60)).length)
    // Every suggestion's words pass the unwanted words
    repeat(300) { n ->
      val id = net.eratiem.randogeddon.server.admin.suggestId(kotlin.random.Random(n)) { false }
      assertEquals(false, net.eratiem.randogeddon.server.account.UnwantedWords.hit(id), id)
    }
    assertEquals(Refusal.of(Problem.ID_RESERVED, "id" to "inspect"), SessionIds.problem("inspect"))
    // The reset page's link (#/reset/<token>)
    assertEquals(Refusal.of(Problem.ID_RESERVED, "id" to "reset"), SessionIds.problem("reset"))
    // A seed from before gets an ID that passes too
    assertTrue(SessionIds.valid(legacySessionId("23018521088033805910")))
  }

  @Test
  fun `a suggested ID is free and valid`() {
    val taken = mutableSetOf<String>()
    repeat(100) {
      val id = suggestId(Random(it)) { candidate -> candidate in taken }
      assertTrue(SessionIds.valid(id), id)
      assertTrue(taken.add(id), id)
    }
    // With every pair taken: numbered
    assertEquals("session-2", suggestId(Random(1)) { !it.startsWith("session-") })
  }

  @Test
  fun `a session is live, finishing, paused or ended`() {
    val t0 = Instant.parse("2026-11-13T20:00:00Z")
    assertEquals(SessionStatus.LIVE, statusOf(null, 0.0))
    assertEquals(SessionStatus.LIVE, statusOf(FinishState(null, null, null, false), 50.0))
    assertEquals(SessionStatus.FINISHING, statusOf(FinishState(null, null, t0, false), 100.0))
    assertEquals(SessionStatus.PAUSED, statusOf(FinishState(t0, "Ada", null, false), 99.9))
    assertEquals(SessionStatus.ENDED, statusOf(FinishState(t0, null, null, false), 100.0))
  }
}
