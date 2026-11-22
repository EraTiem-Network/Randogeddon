package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.database.SessionSetup
import net.eratiem.randogeddon.database.User
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class LiveSessionsTest {
  private val alice = User(Uuid.random(), "Alice-HK", "hash")
  private val bob = User(Uuid.random(), "Bob", "hash")

  /** Its archive (or a seed from before) names its slots as people. */
  private val archived = { listOf("Alice-HK", "Bob-SM64") }

  private fun setup(options: String?, members: Map<Uuid, String> = emptyMap()) =
    SessionSetup("seed", "0.6.8", options, Instant.EPOCH, emptyMap(), members)

  @Test
  fun `a session made in the panel knows its players by their accounts, never by a name alone`() {
    // Every slot open to all: no members, and an account named like a slot isn't a player for that
    assertEquals(null, personIn(setup("{}"), alice, archived))
    assertEquals("Bobby", personIn(setup("{}", mapOf(bob.id to "Bobby")), bob, archived))
    assertEquals(null, personIn(setup("{}", mapOf(bob.id to "Bobby")), alice, archived))
  }

  @Test
  fun `a seed from before knows its players by name`() {
    assertEquals("Alice-HK", personIn(null, alice, archived))
    assertEquals("Alice-HK", personIn(setup(null), alice, archived))
    assertEquals(null, personIn(setup(null), bob, archived))
    // With members of its own: by them
    assertEquals(null, personIn(setup(null, mapOf(bob.id to "Bob")), alice, archived))
  }

  @Test
  fun `a failed read of every session keeps what each one had, a read without it is none`() {
    val read = Result.success(mapOf("seed" to 2))
    assertEquals(2, read.forSeed("seed", 1))
    assertEquals(null, read.forSeed("other", 1))
    val failed = Result.failure<Map<String, Int>>(IllegalStateException("the database is gone"))
    assertEquals(1, failed.forSeed("seed", 1))
    assertEquals(null, failed.forSeed("seed", null))
  }
}
