package net.eratiem.randogeddon.server.admin

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.TrackerHarness
import net.eratiem.randogeddon.shared.CreateSessionRequest
import net.eratiem.randogeddon.shared.Creation
import net.eratiem.randogeddon.shared.CreationState
import net.eratiem.randogeddon.shared.GenerateResult
import net.eratiem.randogeddon.shared.GenerationStage
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.UploadedYaml
import net.eratiem.randogeddon.shared.WorldPlan
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** New sessions' creations on the real stores, with the gateway's answers held where the test says. */
class CreationTest {
  private lateinit var tracker: TrackerHarness

  @BeforeTest
  fun start() {
    tracker = TrackerHarness()
  }

  @AfterTest
  fun stop() {
    tracker.close()
  }

  /** A new session [id] of one slot, [player]'s. */
  private fun request(id: String, player: User) = CreateSessionRequest(
    id,
    listOf(UploadedYaml("${player.name}.yaml", "name: ${player.name}-HK\ngame: Hollow Knight\n")),
    listOf(WorldPlan("${player.name}-HK", userIds = listOf(player.id.toString()))),
  )

  /** [block] until it no longer throws (what a creation does after it ended, in the background), for 10 s at most. */
  private suspend fun <T> eventually(block: suspend () -> T): T = withTimeout(10.seconds) {
    var result = runCatching { block() }
    while (result.isFailure) {
      delay(50.milliseconds)
      result = runCatching { block() }
    }
    result.getOrThrow()
  }

  /** New session [id]'s creation once it ended, as [user] (its creator) sees it. */
  private suspend fun ended(id: String, user: User): Creation = withTimeout(30.seconds) {
    var creation = tracker.admin.creation(id, user)
    while (creation?.state == CreationState.RUNNING) {
      delay(50.milliseconds)
      creation = tracker.admin.creation(id, user)
    }
    creation!!
  }

  /** Why the form's check refuses [id]. */
  private suspend fun taken(id: String): Problem =
    assertFailsWith<SessionProblem> { tracker.admin.checkId(id) }.refusal.problem

  @Test
  fun `an ID is reserved from the creation's checks on, a second creation is refused, the first goes on`() = runBlocking<Unit> {
    val ann = tracker.user("Ann")
    val ben = tracker.user("Ben")
    val gateway = tracker.gateway
    gateway.listing.close()
    gateway.generating.close()
    withTimeout(60.seconds) {
      val first = async(Dispatchers.IO) { tracker.admin.create(request("brave-otter", ann), ann) }
      // Ann's creation is in its checks (the gateway lists the release's games), long before the session has the ID
      gateway.listing.reached.await()
      val second = assertFailsWith<SessionProblem> { tracker.admin.create(request("brave-otter", ben), ben) }
      assertEquals(HttpStatusCode.Conflict, second.status)
      assertEquals(Problem.ID_TAKEN, second.refusal.problem)
      // Ben's refusal leaves Ann's stage as it was
      assertEquals(GenerationStage.CHECKING, tracker.admin.generationProgress("brave-otter")?.stage)

      gateway.listing.open()
      assertEquals(CreationState.RUNNING, first.await().state)
      gateway.generating.reached.await()
      // While its seed generates: taken, for the form's check too
      assertEquals(Problem.ID_TAKEN, taken("brave-otter"))
      gateway.generating.open()
      val done = ended("brave-otter", ann)
      assertEquals(CreationState.DONE, done.state)
      assertEquals("seed-brave-otter", done.created?.seedName)
    }
    // The session has it now; Ann manages it, and it counts as hers
    assertEquals("seed-brave-otter", tracker.sessionStore.byId("brave-otter")?.seedName)
    val setup = tracker.setups.of("seed-brave-otter")!!
    assertEquals("manager", setup.roles[ann.id]?.role)
    assertEquals(ann.id, setup.createdBy)
    assertEquals(Problem.ID_TAKEN, taken("brave-otter"))
  }

  @Test
  fun `a creation that failed leaves its ID free again`() = runBlocking<Unit> {
    val ben = tracker.user("Ben")
    tracker.gateway.results["quiet-heron"] = GenerateResult(log = listOf("Exception: no seed"))
    tracker.admin.create(request("quiet-heron", ben), ben)
    assertEquals(CreationState.FAILED, ended("quiet-heron", ben).state)
    eventually { tracker.admin.checkId("quiet-heron") }
    // And Ben may create it again
    tracker.gateway.results.remove("quiet-heron")
    tracker.admin.create(request("quiet-heron", ben), ben)
    assertEquals(CreationState.DONE, ended("quiet-heron", ben).state)
    assertEquals("seed-quiet-heron", tracker.sessionStore.byId("quiet-heron")?.seedName)
  }
}
