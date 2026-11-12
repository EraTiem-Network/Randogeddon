package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.server.session.PlayerYamls
import net.eratiem.randogeddon.shared.GenerateResult
import net.eratiem.randogeddon.shared.Suspect
import net.eratiem.randogeddon.shared.WorldPlan
import net.eratiem.randogeddon.shared.YamlWorld
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionAdminTest {
  private val slots = listOf("Alice-HK", "Alice-Celeste", "Bob-Timespinner", "Bob-Yacht", "Team-RoR2")

  @Test
  fun `random locks leave exactly the asked number open`() {
    repeat(50) { seed ->
      val locked = pickLocked(slots, 2, Random(seed))
      assertEquals(3, locked.size)
      assertTrue(slots.containsAll(locked))
      // In the order of the slots
      assertEquals(slots.filter { it in locked }, locked)
    }
    // Every slot gets locked now and then
    val ever = (0 until 200).flatMap { pickLocked(slots, 1, Random(it)) }.toSet()
    assertEquals(slots.toSet(), ever)
  }

  @Test
  fun `at least one slot starts open, and at least one is locked`() {
    assertFailsWith<SessionProblem> { pickLocked(slots, 0, Random(1)) }
    assertFailsWith<SessionProblem> { pickLocked(slots, 5, Random(1)) }
    assertEquals(4, pickLocked(slots, 1, Random(1)).size)
    // A single slot can't be locked at all
    assertFailsWith<SessionProblem> { pickLocked(listOf("Solo"), 1, Random(1)) }
  }

  @Test
  fun `mixed locks keep the fixed slots, and pick among the random ones`() {
    val plans = listOf(
      WorldPlan("Open"), WorldPlan("Locked", locked = true),
      WorldPlan("R1", random = true), WorldPlan("R2", random = true), WorldPlan("R3", random = true),
    )
    repeat(50) { seed ->
      val locked = pickMixed(plans, 2, Random(seed))
      // "Open" and one of the random ones start open
      assertTrue("Open" !in locked && "Locked" in locked, locked.toString())
      assertEquals(3, locked.size)
    }
    assertEquals(listOf("Locked"), pickMixed(plans, 4, Random(1)))
    assertEquals(listOf("Locked", "R1", "R2", "R3"), pickMixed(plans, 1, Random(1)))
    // Fewer than the sure ones, or more than can be open
    assertFailsWith<SessionProblem> { pickMixed(plans, 0, Random(1)) }
    assertFailsWith<SessionProblem> { pickMixed(plans, 5, Random(1)) }
    // Nothing open for sure: still one at least
    val chance = listOf(WorldPlan("L", locked = true), WorldPlan("R", random = true))
    assertFailsWith<SessionProblem> { pickMixed(chance, 0, Random(1)) }
    assertEquals(listOf("L"), pickMixed(chance, 1, Random(1)))
  }

  @Test
  fun `a failure names the suspects before the log`() {
    val message = failure(
      GenerateResult(
        log = listOf("KeyError: 'Dash'"),
        suspects = listOf(Suspect("Celeste", "named in the error", "celeste.apworld", "0.6.7")),
      )
    )
    assertTrue(message.startsWith("Generation failed, possibly because of:\n• Celeste (APWorld celeste.apworld, from Archipelago 0.6.7): named in the error"), message)
    assertTrue(message.endsWith("Generate.py said:\nKeyError: 'Dash'"), message)
    assertTrue(failure(GenerateResult(log = listOf("boom"))).contains("isn't supported by this Archipelago release"))
  }

  @Test
  fun `games the release lacks, each of a weighted game on its own`() {
    val worlds = listOf(
      YamlWorld("a.yaml", "Alice-HK", "Hollow Knight"),
      YamlWorld("b.yaml", "Bob", "Celeste / Hollow Knight"),
      YamlWorld("c.yaml", "Carol", "Celeste"),
    )
    assertEquals(listOf("Celeste"), PlayerYamls.missingGames(worlds, setOf("Hollow Knight", "Timespinner")))
    assertEquals(emptyList(), PlayerYamls.missingGames(worlds, setOf("Hollow Knight", "Celeste")))
  }
}
