package net.eratiem.randogeddon.server.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.server.TrackerHarness
import net.eratiem.randogeddon.shared.Problem
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keys of Randogeddon Bridge on the real stores: their limit, and how their use is noted. */
class BridgeKeysTest {
  private lateinit var tracker: TrackerHarness
  private lateinit var keys: BridgeKeys

  @BeforeTest
  fun start() {
    tracker = TrackerHarness()
    keys = BridgeKeys(tracker.users, tracker.loginStore)
  }

  @AfterTest
  fun stop() {
    tracker.close()
  }

  @Test
  fun `keys asked for at the same moment don't pass the limit together`() = runBlocking<Unit> {
    val erin = tracker.user("Erin")
    val made = (1..20).map { async(Dispatchers.IO) { runCatching { keys.make(erin) } } }.awaitAll()
    assertEquals(MAX_KEYS, made.count { it.isSuccess })
    assertTrue(made.mapNotNull { it.exceptionOrNull() }.all { (it as? AccountProblem)?.refusal?.problem == Problem.TOO_MANY_KEYS })
    assertEquals(MAX_KEYS, keys.list(erin).size)
    // One ended makes room for one more; another account has its own ten
    assertTrue(keys.end(erin, keys.list(erin).first().id))
    keys.make(erin)
    assertEquals(MAX_KEYS, keys.list(erin).size)
    keys.make(tracker.user("Finn"))
  }

  @Test
  fun `a key's use is noted at most once in five minutes, without asking the database in between`() = runBlocking<Unit> {
    val gus = tracker.user("Gus")
    val key = keys.make(gus).key
    suspend fun lastUsed() = keys.list(gus).single().lastUsedAt
    val now = Instant.now()
    assertEquals(gus.id, keys.userOf(key, now)?.id)
    assertEquals(now.toEpochMilli(), lastUsed())
    // Taken out of the database meanwhile: a use within the five minutes doesn't note it there again
    tracker.db.query { exec("UPDATE login_sessions SET last_used_at = NULL") }
    assertEquals(gus.id, keys.userOf(key, now.plusSeconds(60))?.id)
    assertNull(lastUsed())
    // After them it is noted again
    val later = now.plusSeconds(6 * 60)
    assertEquals(gus.id, keys.userOf(key, later)?.id)
    assertEquals(later.toEpochMilli(), lastUsed())
  }
}
