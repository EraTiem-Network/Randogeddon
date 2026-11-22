package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.server.config.english
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.server.session.PlayerYamls
import net.eratiem.randogeddon.shared.GenerateResult
import net.eratiem.randogeddon.shared.Suspect
import net.eratiem.randogeddon.shared.WorldPlan
import net.eratiem.randogeddon.shared.YamlWorld
import net.eratiem.randogeddon.database.SessionSetup
import java.time.Instant
import kotlin.random.Random
import kotlin.uuid.Uuid
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
    val refusal = failure(
      GenerateResult(
        log = listOf("KeyError: 'Dash'"),
        suspects = listOf(Suspect("Celeste", "named in the error", "celeste.apworld", "0.6.7")),
      )
    )
    assertEquals(Problem.GENERATION_SUSPECTS, refusal.problem)
    assertEquals("• Celeste (celeste.apworld, Archipelago 0.6.7): named in the error", refusal.args["suspects"])
    assertEquals("KeyError: 'Dash'", refusal.detail)
    val message = refusal.english()
    assertTrue(message.startsWith("Generation failed, possibly because of:\n• Celeste"), message)
    assertTrue(message.endsWith("Generate.py said:\nKeyError: 'Dash'"), message)
    assertEquals(Problem.GENERATION_FAILED, failure(GenerateResult(log = listOf("boom"))).problem)
    // Stopped for its time: that is the reason, not a game
    val stopped = failure(GenerateResult(log = listOf("Current fill step (Progression) at 4000/5086 items placed."), stoppedAfterMinutes = 60))
    assertEquals(Problem.GENERATION_TOO_LONG, stopped.problem)
    assertTrue(stopped.english().startsWith("Generating took longer than 60 minutes and was stopped"), stopped.english())
  }

  private val ann = Uuid.random()
  private val ben = Uuid.random()

  /** Ann and Ben play together, Ben alone; Team is open to all, Hollow hosted elsewhere, Dig hosted here. */
  private val setup = SessionSetup(
    "seed", "0.6.8", "{}", Instant.EPOCH,
    worlds = mapOf("Pair" to listOf("Ann", "Ben"), "Ben-HK" to listOf("Ben"), "Hollow" to listOf("Ben"), "Dig" to listOf("Ben")),
    members = mapOf(ann to "Ann", ben to "Ben"),
    hosted = setOf("Hollow", "Dig", "Open-Hollow"),
    direct = setOf("Dig"),
    open = setOf("Team", "Open-Hollow"),
  )

  @Test
  fun `whoever is left without a slot loses the open slots' passwords too`() {
    // Ann loses her last slot: Team gets a new password, the hosted open slot whose server runs elsewhere has none
    assertEquals(setOf("Team"), openSlotsToRenew(setup, "Pair", setOf("Ben"), open = false, hosted = false, direct = false))
    // Ben still plays others; nobody loses anything when a slot gets more players
    assertEquals(emptySet(), openSlotsToRenew(setup, "Ben-HK", emptySet(), open = false, hosted = false, direct = false))
    assertEquals(emptySet(), openSlotsToRenew(setup, "Ben-HK", setOf("Ben", "Ann"), open = false, hosted = false, direct = false))
    // The slot becomes open to all, and Ann has no other one: it is among the open ones renewed
    assertEquals(setOf("Team", "Pair"), openSlotsToRenew(setup, "Pair", emptySet(), open = true, hosted = false, direct = false))
  }

  @Test
  fun `an account kept out or deleted renews only the slots nobody else plays`() {
    // Ben's own (Dig's server runs here, its tunnel comes from the password); Pair is Ann's too, Hollow's server elsewhere
    assertEquals(setOf("Ben-HK", "Dig"), slotsOnlyOf(setup, ben))
    assertEquals(emptySet(), slotsOnlyOf(setup, ann))
    assertEquals(emptySet(), slotsOnlyOf(setup, Uuid.random()))
    // With Ann's account gone, the open slot is Ben's alone
    assertEquals(setOf("Ben-HK", "Dig", "Team"), slotsOnlyOf(setup.copy(deleted = setOf(ann)), ben))
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
