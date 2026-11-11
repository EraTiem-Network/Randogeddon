package net.eratiem.randogeddon.database

import kotlinx.coroutines.runBlocking
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Migrations and stores against a real PostgreSQL (Testcontainers; Docker must be running). */
class StoresTest {
  private val postgres = PostgreSQLContainer("postgres:18-alpine")
  private lateinit var db: RandogeddonDatabase

  @BeforeTest
  fun start() {
    postgres.start()
    db = RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), migrate = true)
  }

  @AfterTest
  fun stop() {
    db.close()
    postgres.stop()
  }

  /** The host commands ask for the smallest pool; Flyway needs two connections (lock and migration). */
  @Test
  fun `migrating works with a pool of one`() = runBlocking {
    val config = DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password)
    RandogeddonDatabase.connect(config, migrate = true, poolSize = 1).use { small ->
      assertEquals(emptyList(), UserStore(small).active())
    }
  }

  @Test
  fun `names are unique without regard to case`() = runBlocking {
    val users = UserStore(db)
    val alice = users.create("Alice", "hash")
    assertEquals(alice.id, users.findActive("aLiCe")?.id)
    assertFailsWith<Exception> { users.create("alice", "other") }
    assertEquals(listOf("Alice"), users.active().map { it.name })
  }

  @Test
  fun `users are no admins until granted`() = runBlocking {
    val users = UserStore(db)
    val dave = users.create("Dave", "hash")
    assertEquals(false, users.findActive("dave")?.admin)
    users.setAdmin(dave.id, true)
    assertEquals(true, users.findActive(dave.id)?.admin)
    users.setAdmin(dave.id, false)
    assertEquals(false, users.active().single().admin)
  }

  @Test
  fun `a new password ends the user's logins`() = runBlocking {
    val users = UserStore(db)
    val logins = LoginSessionStore(db)
    val bob = users.create("Bob", "old")
    logins.create("token", bob.id, Instant.now().plus(1, ChronoUnit.DAYS))
    assertEquals(bob.id, logins.userOf("token"))

    users.setPassword(bob.id, "new")
    assertNull(logins.userOf("token"))
    assertEquals("new", users.findActive(bob.id)?.passwordHash)
  }

  @Test
  fun `expired logins are not accepted and get cleaned up`() = runBlocking {
    val users = UserStore(db)
    val logins = LoginSessionStore(db)
    val carol = users.create("Carol", "hash")
    val now = Instant.now()
    logins.create("old", carol.id, now.minusSeconds(1))
    logins.create("fresh", carol.id, now.plusSeconds(3600))

    assertNull(logins.userOf("old", now))
    assertEquals(carol.id, logins.userOf("fresh", now))
    assertEquals(1, logins.deleteExpired(now))
  }

  @Test
  fun `the session time counts only while the game server runs`() = runBlocking {
    val times = SessionTimeStore(db)
    val t0 = Instant.parse("2026-11-11T10:00:00Z")
    times.started("seed1", t0)
    times.tick("seed1", t0.plusSeconds(30))
    times.stopped("seed1", t0.plusSeconds(45))
    assertEquals(SessionTime(45_000, null), times.of("seed1"))

    // Stopped for an hour: doesn't count
    times.tick("seed1", t0.plusSeconds(3645))
    times.started("seed1", t0.plusSeconds(3645))
    times.stopped("seed1", t0.plusSeconds(3660))
    assertEquals(45_000L + 15_000, times.of("seed1")?.runTimeMs)

    // Killed while running: the stretch since the last count is dropped on the next start
    times.started("seed1", t0.plusSeconds(4000))
    times.tick("seed1", t0.plusSeconds(4030))
    times.dropOpenCounts()
    assertEquals(SessionTime(90_000, null), times.of("seed1"))
    assertNull(times.of("unknown"))
  }

  @Test
  fun `a session keeps its worlds, shared ones too, and deleting it keeps the users`() = runBlocking {
    val users = UserStore(db)
    val setups = SessionSetupStore(db)
    val alice = users.create("Alice", "hash")
    val bob = users.create("Bob", "hash")
    setups.create(
      "seed1", "0.6.8", """{"hintCost":5}""",
      mapOf("Alice-HK" to listOf(alice.id), "Couch-Coop" to listOf(alice.id, bob.id), "Solo" to emptyList()),
    )
    SlotPasswordStore(db).ensure("seed1", listOf("Alice-HK", "Couch-Coop"), { "pw" })

    val setup = setups.of("seed1")!!
    assertEquals("0.6.8", setup.apVersion)
    assertEquals("""{"hintCost":5}""", setup.options)
    assertEquals(listOf("Alice"), setup.worlds["Alice-HK"])
    assertEquals(setOf("Alice", "Bob"), setup.worlds["Couch-Coop"]?.toSet())

    assertTrue(setups.delete("seed1"))
    assertNull(setups.of("seed1"))
    assertEquals(emptyMap(), SlotPasswordStore(db).passwords("seed1"))
    assertEquals(setOf("Alice", "Bob"), users.active().map { it.name }.toSet())
  }

  @Test
  fun `slot passwords stay for a seed and are new for the next one`() = runBlocking {
    val slots = SlotPasswordStore(db)
    var n = 0
    val generate = { "pw${n++}" }

    val first = slots.ensure("seed1", listOf("Alice-HK", "Bob-Yacht"), generate)
    assertEquals(setOf("Alice-HK", "Bob-Yacht"), first.keys)
    // Again (e.g. after a restart): the same passwords, only new slots get one
    val again = slots.ensure("seed1", listOf("Alice-HK", "Bob-Yacht", "Carol-RoR2"), generate)
    assertEquals(first["Alice-HK"], again["Alice-HK"])
    assertEquals(3, again.size)
    assertEquals(again, slots.passwords("seed1"))

    val next = slots.ensure("seed2", listOf("Alice-HK"), generate)
    assertNotEquals(first["Alice-HK"], next["Alice-HK"])
    assertEquals(emptyMap(), slots.passwords("unknown"))
  }
}
