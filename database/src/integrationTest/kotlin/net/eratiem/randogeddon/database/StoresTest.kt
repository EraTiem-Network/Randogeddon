package net.eratiem.randogeddon.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

  /** As on a new database: the gateway starts before the tracker has made its role (after migrating). */
  @Test
  fun `a login that comes a moment later is waited for, one that never comes isn't`() {
    val late = DatabaseConfig(postgres.jdbcUrl, "late_gateway", "late-password")
    assertFailsWith<Exception> { RandogeddonDatabase.connect(late, migrate = false, poolSize = 1) }
    val started = System.nanoTime()
    val maker = kotlin.concurrent.thread {
      Thread.sleep(3000)
      postgres.createConnection("").use { c ->
        c.createStatement().use {
          it.execute("CREATE ROLE late_gateway LOGIN PASSWORD 'late-password'")
          // What the gateway reads: it waits for all of it
          it.execute("GRANT SELECT ON sessions, session_hosted_slots, slot_passwords, app_settings TO late_gateway")
        }
      }
    }
    RandogeddonDatabase.connect(late, migrate = false, poolSize = 1, waitFor = 30.seconds).use { waited ->
      assertTrue((System.nanoTime() - started) / 1_000_000 >= 2500)
      assertEquals(null, runBlocking { SessionSetupStore(waited).of("nope") })
    }
    maker.join()
    // The wait has an end
    val never = DatabaseConfig(postgres.jdbcUrl, "never_there", "x")
    assertFailsWith<Exception> { RandogeddonDatabase.connect(never, migrate = false, poolSize = 1, waitFor = 3.seconds) }
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
    assertEquals(false, users.findActive("dave")?.administrator)
    users.setAdmin(dave.id, true)
    assertEquals(true, users.findActive(dave.id)?.administrator)
    users.setAdmin(dave.id, false)
    assertEquals(false, users.active().single().administrator)
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

  /** A bare session of [seedName] (`s-<seed>`), as the host's CLI makes one for a seed from before Phase 4. */
  private suspend fun bare(seedName: String) = SlotPasswordStore(db).ensure(seedName, emptyList(), { "pw" }, create = true)

  @Test
  fun `the session time counts only while the game server runs`() = runBlocking {
    val times = SessionTimeStore(db)
    val t0 = Instant.parse("2026-11-11T10:00:00Z")
    bare("seed1")
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
      "friday-chaos", "seed1", "0.6.8", """{"hintCost":5}""",
      mapOf("Alice-HK" to listOf(alice.id), "Couch-Coop" to listOf(alice.id, bob.id), "Solo" to emptyList()),
    )
    SlotPasswordStore(db).ensure("seed1", listOf("Alice-HK", "Couch-Coop"), { "pw" })

    val setup = setups.of("seed1")!!
    assertEquals("0.6.8", setup.apVersion)
    assertEquals("""{"hintCost":5}""", setup.options)
    assertEquals(listOf("Alice"), setup.worlds["Alice-HK"])
    assertEquals(setOf("Alice", "Bob"), setup.worlds["Couch-Coop"]?.toSet())

    assertEquals("friday-chaos", SessionStore(db).idOf("seed1"))
    assertEquals("seed1", SessionStore(db).byId("friday-chaos")?.seedName)

    assertTrue(setups.delete("seed1"))
    assertNull(SessionStore(db).byId("friday-chaos"))
    assertNull(setups.of("seed1"))
    assertEquals(emptyMap(), SlotPasswordStore(db).passwords("seed1"))
    assertEquals(setOf("Alice", "Bob"), users.active().map { it.name }.toSet())
  }

  @Test
  fun `the gateway's role reads whether a session sleeps (kept running, its hosted slots, the setting)`() = runBlocking {
    val users = UserStore(db)
    val setups = SessionSetupStore(db)
    val alice = users.create("Alice", "hash")
    setups.create(
      "sleepy-otter", "seed-sleepy", "0.6.8", "{}",
      mapOf("Alice-HK" to listOf(alice.id), "Hosted-Game" to emptyList()), hosted = setOf("Hosted-Game"),
    )
    AppSettingsStore(db).set(IDLE_MINUTES, "45")
    GatewayRole.ensure(db, "sleep_gateway", "sleep-password")
    RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "sleep_gateway", "sleep-password"), migrate = false, poolSize = 1)
      .use { gateway ->
        val read = SessionSetupStore(gateway)
        assertEquals(IdleRules(keepRunning = false, hosted = setOf("Hosted-Game")), read.idleRules("seed-sleepy"))
        assertEquals(45, AppSettingsStore(gateway).idleMinutes())
        assertTrue(setups.setKeepRunning("seed-sleepy", true))
        assertEquals(true, read.idleRules("seed-sleepy")?.keepRunning)
        assertNull(read.idleRules("nope"))
        // It reads, it doesn't change
        assertFailsWith<Exception> { read.setKeepRunning("seed-sleepy", false) }
      }
    assertEquals(true, setups.of("seed-sleepy")?.keepRunning)
    assertFalse(setups.setKeepRunning("nope", true))
    AppSettingsStore(db).set(IDLE_MINUTES, "9999")
    // Out of range: the default
    assertEquals(DEFAULT_IDLE_MINUTES, AppSettingsStore(db).idleMinutes())
    assertTrue(setups.delete("seed-sleepy"))
  }

  @Test
  fun `a game server's events are kept in their order per session, added by the gateway's role, and go with the session`() = runBlocking {
    val events = SessionEventStore(db)
    setUpSessions("loud-otter", "calm-heron")
    GatewayRole.ensure(db, "events_gateway", "events-password")
    RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "events_gateway", "events-password"), migrate = false, poolSize = 1)
      .use { gateway ->
        val heard = SessionEventStore(gateway)
        heard.append("loud-otter", 1_000, SessionEventStore.START)
        heard.append("loud-otter", 2_000, SessionEventStore.PACKET, "{\"cmd\":\"PrintJSON\",\"type\":\"Join\"}")
        heard.append("calm-heron", 2_500, SessionEventStore.START)
        heard.append("loud-otter", 3_000, SessionEventStore.PACKET, "{\"cmd\":\"PrintJSON\",\"type\":\"Part\"}")
        heard.append("loud-otter", 4_000, SessionEventStore.STOP)
        heard.append("loud-otter", 5_000, SessionEventStore.START)
        heard.append("loud-otter", 6_000, SessionEventStore.PACKET, "{\"cmd\":\"PrintJSON\",\"type\":\"Join\"}")
        // It adds; it doesn't read or take away
        assertFailsWith<Exception> { heard.after("loud-otter", 0) }
        assertFailsWith<Exception> { heard.delete("loud-otter") }
      }
    val all = events.after("loud-otter", 0)
    assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L), all.map { it.atMs })
    assertEquals(listOf("start", "packet", "packet", "stop", "start", "packet"), all.map { it.kind })
    assertTrue(all.zipWithNext().all { (a, b) -> a.id < b.id })
    assertEquals(listOf(3_000L, 4_000L), events.after("loud-otter", all[1].id, limit = 2).map { it.atMs })
    assertEquals(emptyList(), events.after("loud-otter", all.last().id))
    events.delete("loud-otter")
    assertEquals(emptyList(), events.after("loud-otter", 0))
    assertEquals(1, events.after("calm-heron", 0).size)
    events.delete("calm-heron")
  }

  /** A session for each of [ids] (its seed: `seed-<id>`), set up as the panel does. */
  private suspend fun setUpSessions(vararg ids: String) {
    for (id in ids) assertTrue(SessionSetupStore(db).create(id, "seed-$id", "0.6.8", "{}", emptyMap()))
  }

  /** As on a server: the tracker logs in as [role], a role of its own, the tables' owner, no superuser (database-roles). */
  private suspend fun trackerRole(role: String, password: String) = db.query {
    exec("CREATE ROLE $role LOGIN NOSUPERUSER CREATEROLE PASSWORD '$password'")
    exec("ALTER SCHEMA public OWNER TO $role")
    exec(
      "DO $$ DECLARE t text; BEGIN FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP " +
        "EXECUTE format('ALTER TABLE public.%I OWNER TO $role', t); END LOOP; END $$",
    )
  }

  @Test
  fun `the gateway's role adds only the gateway's kinds of events, the tracker its cheats too`() = runBlocking {
    setUpSessions("loud-otter", "calm-heron")
    // As on a server: the tracker logs in with a role of its own, the tables' owner, no superuser (database-roles)
    db.query {
      exec("CREATE ROLE kinds_tracker LOGIN NOSUPERUSER CREATEROLE PASSWORD 'tracker-password'")
      exec("ALTER SCHEMA public OWNER TO kinds_tracker")
      exec(
        "DO $$ DECLARE t text; BEGIN FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP " +
          "EXECUTE format('ALTER TABLE public.%I OWNER TO kinds_tracker', t); END LOOP; END $$",
      )
    }
    RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "kinds_tracker", "tracker-password"), migrate = false, poolSize = 1)
      .use { tracker ->
        GatewayRole.ensure(tracker, "kinds_gateway", "gateway-password")
        RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "kinds_gateway", "gateway-password"), migrate = false, poolSize = 1)
          .use { gateway ->
            val heard = SessionEventStore(gateway)
            heard.append("loud-otter", 1_000, SessionEventStore.START)
            heard.append("loud-otter", 2_000, SessionEventStore.PACKET, "{\"cmd\":\"PrintJSON\",\"type\":\"Join\"}")
            heard.append("calm-heron", 2_500, SessionEventStore.STOP)
            // Not the tracker's kind, nor one nobody writes
            assertFailsWith<Exception> { heard.append("loud-otter", 3_000, SessionEventStore.CHEAT, "{\"admin\":\"Mallory\"}") }
            assertFailsWith<Exception> { heard.append("loud-otter", 3_000, "seen") }
          }
        // The tracker adds its cheats and reads everything
        val events = SessionEventStore(tracker)
        events.append("loud-otter", 4_000, SessionEventStore.CHEAT, "{\"admin\":\"Ada\"}")
        assertEquals(listOf("start", "packet", "cheat"), events.after("loud-otter", 0).map { it.kind })
        assertEquals(listOf(SessionEventStore.STOP), events.after("calm-heron", 0).map { it.kind })
        assertEquals("{\"admin\":\"Ada\"}", events.after("loud-otter", 0).last().packet)
        // Made again at the next start: still one policy, still the same rights
        GatewayRole.ensure(tracker, "kinds_gateway", "gateway-password")
        RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "kinds_gateway", "gateway-password"), migrate = false, poolSize = 1)
          .use { gateway ->
            SessionEventStore(gateway).append("loud-otter", 5_000, SessionEventStore.STOP)
            assertFailsWith<Exception> { SessionEventStore(gateway).append("loud-otter", 6_000, SessionEventStore.CHEAT, "{}") }
          }
        assertEquals(listOf("start", "packet", "cheat", "stop"), events.after("loud-otter", 0).map { it.kind })
        events.delete("loud-otter")
        events.delete("calm-heron")
      }
  }

  @Test
  fun `a session's snapshot is kept live, made final once it finished, reopened, and goes with the session`() = runBlocking {
    val snapshots = SessionSnapshotStore(db)
    setUpSessions("kept-otter", "done-heron", "old-fox")
    assertNull(snapshots.of("kept-otter"))
    assertTrue(snapshots.saveLive("kept-otter", 1_000, "{\"live\":1}"))
    assertEquals(SessionSnapshot("{\"live\":1}", final = false, atMs = 1_000), snapshots.of("kept-otter"))
    assertTrue(snapshots.saveLive("kept-otter", 2_000, "{\"live\":2}"))
    assertEquals("{\"live\":2}", snapshots.of("kept-otter")?.json)
    assertEquals(emptyMap(), snapshots.finals().filterKeys { it == "kept-otter" })
    // Finished: its archive, which a live one doesn't replace
    snapshots.saveFinal("kept-otter", 3_000, "{\"final\":1}")
    assertFalse(snapshots.saveLive("kept-otter", 4_000, "{\"live\":3}"))
    assertEquals(SessionSnapshot("{\"final\":1}", final = true, atMs = 3_000), snapshots.of("kept-otter"))
    assertEquals(mapOf("kept-otter" to "{\"final\":1}"), snapshots.finals().filterKeys { it == "kept-otter" })
    // Reopened: live again
    snapshots.reopen("kept-otter")
    assertTrue(snapshots.saveLive("kept-otter", 5_000, "{\"live\":4}"))
    assertEquals(false, snapshots.of("kept-otter")?.final)
    // A final one for a session that had none yet
    snapshots.saveFinal("done-heron", 6_000, "{\"final\":2}")
    assertEquals(true, snapshots.of("done-heron")?.final)
    // The history of before: kept once, the first one stays; a row with only it has no snapshot
    assertNull(snapshots.baseline("old-fox"))
    snapshots.importBaseline("old-fox", 7_000, "{\"history\":1}")
    snapshots.importBaseline("old-fox", 8_000, "{\"history\":2}")
    assertEquals("{\"history\":1}", snapshots.baseline("old-fox"))
    assertNull(snapshots.of("old-fox"))
    assertTrue(snapshots.saveLive("old-fox", 9_000, "{\"live\":5}"))
    assertEquals("{\"history\":1}", snapshots.baseline("old-fox"))
    snapshots.importBaseline("kept-otter", 10_000, "{\"history\":3}")
    assertEquals("{\"live\":4}", snapshots.of("kept-otter")?.json)
    // The gateway's role has nothing of it
    GatewayRole.ensure(db, "snapshot_gateway", "snapshot-password")
    RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "snapshot_gateway", "snapshot-password"), migrate = false, poolSize = 1)
      .use { gateway -> assertFailsWith<Exception> { SessionSnapshotStore(gateway).of("kept-otter") } }
    for (session in listOf("kept-otter", "done-heron", "old-fox")) snapshots.delete(session)
    assertNull(snapshots.of("kept-otter"))
    assertNull(snapshots.baseline("old-fox"))
  }

  @Test
  fun `a session finishes, is listed as finished, and can be reopened`() = runBlocking {
    val finishes = SessionFinishStore(db)
    val t0 = Instant.parse("2026-11-13T20:00:00Z")
    bare("111")
    bare("222")
    assertEquals(FinishState(null, null, null, false), finishes.of("111"))
    assertNull(finishes.of("unknown"))

    finishes.setDue("111", t0.plusSeconds(120))
    assertEquals(t0.plusSeconds(120), finishes.of("111")?.dueAt)
    finishes.finish("111", by = null, now = t0.plusSeconds(120))
    finishes.finish("222", by = "Ada", now = t0.plusSeconds(300))
    assertEquals(FinishState(t0.plusSeconds(120), null, null, false), finishes.of("111"))
    // The newest first
    assertEquals(listOf("222" to "Ada", "111" to null), finishes.finished().map { it.seedName to it.finishedBy })

    assertTrue(finishes.setKeep("111", true))
    assertEquals(true, finishes.of("111")?.keep)
    assertNull(finishes.of("111")?.retainedSince)
    // Enabled again: the retention counts from then
    assertTrue(finishes.setKeep("111", false, now = t0.plusSeconds(900)))
    assertEquals(FinishState(t0.plusSeconds(120), null, null, false, t0.plusSeconds(900)), finishes.of("111"))
    assertEquals(t0.plusSeconds(900), finishes.finished().single { it.seedName == "111" }.retainedSince)
    assertEquals(false, finishes.setKeep("unknown", true))
    finishes.reopen("222")
    assertEquals(FinishState(null, null, null, false), finishes.of("222"))
    assertEquals(listOf("111"), finishes.finished().map { it.seedName })
  }

  @Test
  fun `the cheats are kept per session, the newest first, and go with it`() = runBlocking {
    val log = CheatLogStore(db)
    val t0 = Instant.parse("2026-11-13T20:00:00Z")
    bare("111")
    bare("222")
    log.add("111", CheatEntry(t0, "Ada", "ITEM", "Alice-HK", "Mantis Claw"))
    log.add("111", CheatEntry(t0.plusSeconds(5), "Ada", "RELEASE", "Bob-Yacht", null))
    log.add("222", CheatEntry(t0, "Bea", "ITEM", "X", "Y"))
    assertEquals(listOf("RELEASE", "ITEM"), log.of("111").map { it.action })
    assertEquals(CheatEntry(t0, "Ada", "ITEM", "Alice-HK", "Mantis Claw"), log.of("111").last())
    SessionSetupStore(db).delete("111")
    assertEquals(emptyList(), log.of("111"))
    assertEquals(1, log.of("222").size)
  }

  @Test
  fun `sessions have IDs, each one once, and a seed from before gets s-seed`() = runBlocking {
    val sessions = SessionStore(db)
    val t0 = Instant.parse("2026-11-13T20:00:00Z")
    // A seed from before Phase 4: its bare session, made by the host's CLI
    SlotPasswordStore(db).ensure("12345", listOf("Alice-HK"), { "pw" }, now = t0, create = true)
    assertEquals("s-12345", sessions.idOf("12345"))
    assertTrue(SessionSetupStore(db).create("brave-otter", "678", "0.6.8", "{}", emptyMap(), now = t0.plusSeconds(60)))
    assertEquals(listOf("brave-otter", "s-12345"), sessions.all().map { it.id })
    assertEquals("0.6.8", sessions.byId("brave-otter")?.apVersion)
    assertNull(sessions.byId("nobody"))
    // An ID is taken once
    assertFailsWith<Exception> { SessionSetupStore(db).create("brave-otter", "999", "0.6.8", "{}", emptyMap()) }
    assertEquals("s-ab-c", legacySessionId("AB_c"))
  }

  @Test
  fun `slot passwords stay for a seed and are new for the next one`() = runBlocking {
    val slots = SlotPasswordStore(db)
    var n = 0
    val generate = { "pw${n++}" }

    // No session of the seed: none, and none made
    assertEquals(emptyMap(), slots.ensure("seed1", listOf("Alice-HK"), generate))
    assertNull(SessionStore(db).idOf("seed1"))
    val first = slots.ensure("seed1", listOf("Alice-HK", "Bob-Yacht"), generate, create = true)
    assertEquals(setOf("Alice-HK", "Bob-Yacht"), first.keys)
    // Again (e.g. after a restart): the same passwords, only new slots get one
    val again = slots.ensure("seed1", listOf("Alice-HK", "Bob-Yacht", "Carol-RoR2"), generate)
    assertEquals(first["Alice-HK"], again["Alice-HK"])
    assertEquals(3, again.size)
    assertEquals(again, slots.passwords("seed1"))
    // Asked for at the same moment (the watcher and a player's page): one password each
    val atOnce = (1..20).map { async(Dispatchers.IO) { slots.ensure("seed1", listOf("Dan-SM64"), { "pw-$it" }) } }.map { it.await() }
    assertEquals(1, atOnce.map { it["Dan-SM64"] }.toSet().size)

    val next = slots.ensure("seed2", listOf("Alice-HK"), generate, create = true)
    assertNotEquals(first["Alice-HK"], next["Alice-HK"])
    assertEquals(emptyMap(), slots.passwords("unknown"))
  }

  @Test
  fun `a session's slots and their players come in one order, by name, whatever order they were stored in`() = runBlocking {
    val users = UserStore(db)
    val setups = SessionSetupStore(db)
    val zoe = users.create("zoe", "hash")
    val adam = users.create("Adam", "hash")
    val mia = users.create("Mia", "hash")
    setups.create(
      "sorted-heron", "seed-sorted", "0.6.8", "{}",
      linkedMapOf("Zed-HK" to listOf(zoe.id, adam.id), "alpha-Yacht" to listOf(mia.id), "Mid-DLC" to listOf(zoe.id, mia.id, adam.id)),
    )
    repeat(3) {
      val worlds = setups.of("seed-sorted")!!.worlds
      assertEquals(listOf("alpha-Yacht", "Mid-DLC", "Zed-HK"), worlds.keys.toList())
      assertEquals(listOf("Adam", "Mia", "zoe"), worlds["Mid-DLC"])
      assertEquals(listOf("Adam", "zoe"), worlds["Zed-HK"])
    }
    assertTrue(setups.delete("seed-sorted"))
  }

  @Test
  fun `every session's setup, time and finish state are read at once, each as read alone`() = runBlocking {
    val users = UserStore(db)
    val setups = SessionSetupStore(db)
    val times = SessionTimeStore(db)
    val finishes = SessionFinishStore(db)
    val t0 = Instant.parse("2026-11-22T10:00:00Z")
    val ada = users.create("Ada", "hash")
    val ben = users.create("Ben", "hash")
    setups.create(
      "busy-otter", "seed-busy", "0.6.8", "{}", mapOf("Ada-HK" to listOf(ada.id), "Couch" to listOf(ada.id, ben.id)),
      now = t0, hosted = setOf("Dig"), direct = setOf("Dig"), open = setOf("Couch"),
    )
    SessionRoleStore(db).add("busy-otter", ben.id, "manager", null, by = "Ada")
    setups.create("calm-heron", "seed-calm", "0.6.8", "{}", mapOf("Ben-SM64" to listOf(ben.id)), now = t0.plusSeconds(60))
    times.started("seed-busy", t0)
    times.stopped("seed-busy", t0.plusSeconds(30))
    finishes.finish("seed-calm", by = "Ben", now = t0.plusSeconds(90))
    // A seed from before, without a setup of its own
    bare("12345")
    times.started("12345", t0)

    val seeds = listOf("seed-busy", "seed-calm", "12345")
    assertEquals(seeds.associateWith { setups.of(it)!! }, setups.all())
    assertEquals(seeds.associateWith { times.of(it)!! }, times.all())
    assertEquals(seeds.associateWith { finishes.of(it)!! }, finishes.all())
    // Each session's rows stay its own
    assertEquals(mapOf(ben.id to "Ben"), setups.all().getValue("seed-calm").members)
    assertEquals(setOf(ben.id), setups.all().getValue("seed-busy").roles.keys)
    assertEquals(emptyMap(), setups.all().getValue("12345").worlds)
  }

  @Test
  fun `an invited player is in a session without anything of their address, then by their name`() = runBlocking {
    val users = UserStore(db)
    val setups = SessionSetupStore(db)
    val invited = users.invite("john.smith@example.org")
    setups.create("quiet-heron", "seed-heron", "0.6.8", "{}", mapOf("John-HK" to listOf(invited.id)))
    val before = setups.of("seed-heron")!!.worlds["John-HK"].orEmpty()
    assertEquals(listOf("Invited-${invited.id.toString().take(4)}"), before)
    assertTrue(before.none { "john" in it.lowercase() || "@" in it })
    assertTrue(users.signUp(invited.id, "Johnny", "hash", "6m"))
    assertEquals(listOf("Johnny"), setups.of("seed-heron")!!.worlds["John-HK"])
    assertTrue(setups.delete("seed-heron"))
  }

  @Test
  fun `an invited user can't log in until they sign up, then by name or address`() = runBlocking {
    val users = UserStore(db)
    val invited = users.invite("Erin@example.org")
    assertNull(users.findLogin("erin@example.org"))
    assertEquals(emptyList(), users.active())
    assertEquals(listOf(null), users.accounts().map { it.name })
    // One account per address, without regard to case
    assertFailsWith<Exception> { users.invite("erin@example.org") }

    assertTrue(users.signUp(invited.id, "Erin", "hash", "6m"))
    assertEquals(invited.id, users.findLogin("ERIN@example.org")?.id)
    assertEquals(invited.id, users.findLogin("erin")?.id)
    assertEquals("6m", users.findActive(invited.id)?.autoDelete)
    // A link works once
    assertEquals(false, users.signUp(invited.id, "Erin2", "hash", "never"))
  }

  @Test
  fun `a new invitation replaces the old one, and a link is used once`() = runBlocking {
    val users = UserStore(db)
    val invitations = InvitationStore(db)
    val frank = users.invite("frank@example.org")
    val later = Instant.now().plus(7, ChronoUnit.DAYS)
    invitations.create("first", frank.id, "frank@example.org", "Frank", later)
    invitations.create("second", frank.id, "frank@example.org", "Frank", later)
    assertNull(invitations.find("first"))
    assertEquals("Frank", invitations.of(frank.id)?.suggestedName)
    // All of them at once, one per user
    assertEquals(mapOf(frank.id to invitations.of(frank.id)), invitations.all())
    assertTrue(invitations.use("second"))
    assertEquals(false, invitations.use("second"))
    assertTrue(invitations.find("second")?.usedAt != null)
  }

  @Test
  fun `a sign-up uses its link in the same transaction, and one whose name was taken leaves the link as it was`() = runBlocking {
    val users = UserStore(db)
    val invitations = InvitationStore(db)
    val later = Instant.now().plus(7, ChronoUnit.DAYS)
    users.create("Quinn", "hash")
    val rae = users.invite("rae@example.org")
    invitations.create("rae-link", rae.id, "rae@example.org", null, later)
    // The name went to someone else a moment before: the link still works, the retry with another name signs up
    assertEquals(SignUp.NAME_TAKEN, users.signUpInvited("rae-link", rae.id, "quinn", "hash", "never"))
    assertNull(invitations.find("rae-link")?.usedAt)
    assertNull(users.findActive(rae.id))
    assertEquals(SignUp.DONE, users.signUpInvited("rae-link", rae.id, "Rae", "hash", "6m"))
    assertEquals("Rae", users.findActive(rae.id)?.name)
    assertEquals(SignUp.LINK_USED, users.signUpInvited("rae-link", rae.id, "Rae2", "hash", "never"))
    // A new link of an account that signed up meanwhile
    invitations.create("rae-again", rae.id, "rae@example.org", null, later)
    assertEquals(SignUp.SIGNED_UP_ALREADY, users.signUpInvited("rae-again", rae.id, "Rae3", "hash", "never"))
    assertEquals("Rae", users.findActive(rae.id)?.name)
  }

  @Test
  fun `one account's activity is read alone, as in the list of all`() = runBlocking {
    val users = UserStore(db)
    val sol = users.create("Sol", "hash")
    val tia = users.create("Tia", "hash")
    users.setAutoDelete(sol.id, "3m")
    users.setAutoDelete(tia.id, "1y")
    SessionSetupStore(db).create("sol-one", "555", "0.6.8", "{}", mapOf("Sol-HK" to listOf(sol.id)))
    assertEquals(users.activity().single { it.user.id == sol.id }, users.activityOf(sol.id))
    assertEquals(true, users.activityOf(sol.id)?.playing)
    assertEquals(false, users.activityOf(tia.id)?.playing)
    // "never" has none to count
    users.setAutoDelete(tia.id, "never")
    assertNull(users.activityOf(tia.id))
  }

  @Test
  fun `the other logins of an account end, one kept and the others' left`() = runBlocking {
    val users = UserStore(db)
    val logins = LoginSessionStore(db)
    val uma = users.create("Uma", "hash")
    val vic = users.create("Vic", "hash")
    val later = Instant.now().plus(1, ChronoUnit.DAYS)
    logins.create("uma-1", uma.id, later)
    logins.create("uma-2", uma.id, later, method = "link")
    logins.create("uma-key", uma.id, later, method = "bridge")
    logins.create("vic-1", vic.id, later)
    assertEquals(1, logins.deleteOthers(uma.id, listOf("password", "link"), keep = "uma-1"))
    assertEquals(uma.id, logins.userOf("uma-1"))
    assertNull(logins.userOf("uma-2"))
    assertEquals(1, logins.deleteOthers(uma.id, listOf("password", "link")))
    assertNull(logins.userOf("uma-1"))
    // A method not named stays, and so do another account's logins
    assertEquals(uma.id, logins.userOf("uma-key"))
    assertEquals(vic.id, logins.userOf("vic-1"))
  }

  @Test
  fun `a session shows the names of then, invited users by their suggested name until they sign up`() = runBlocking {
    val users = UserStore(db)
    val setups = SessionSetupStore(db)
    val finishes = SessionFinishStore(db)
    val alice = users.create("Alice", "hash")
    val gina = users.invite("gina@example.org")
    InvitationStore(db).create("token", gina.id, "gina@example.org", "Gina", Instant.now().plus(7, ChronoUnit.DAYS))
    setups.create("old-one", "111", "0.6.8", "{}", mapOf("Alice-HK" to listOf(alice.id)))
    setups.create("new-one", "222", "0.6.8", "{}", mapOf("Alice-HK" to listOf(alice.id), "Gina-RoR2" to listOf(gina.id)))
    assertEquals(mapOf(alice.id to "Alice", gina.id to "Gina"), setups.of("222")?.members)

    finishes.finish("111", by = null)
    users.rename(alice.id, "Alicia")
    users.signUp(gina.id, "Georgina", "hash", "never")
    // The finished session keeps the name of then; the running one follows
    assertEquals(mapOf(alice.id to "Alice"), setups.of("111")?.members)
    assertEquals(mapOf(alice.id to "Alicia", gina.id to "Georgina"), setups.of("222")?.members)
    assertEquals(mapOf("Alice-HK" to listOf("Alicia"), "Gina-RoR2" to listOf("Georgina")), setups.of("222")?.worlds)
  }

  @Test
  fun `deleting an account clears it, frees its name and address, and the sessions keep its name`() = runBlocking {
    val users = UserStore(db)
    val logins = LoginSessionStore(db)
    val hank = users.create("Hank", "hash", email = "hank@example.org")
    logins.create("token", hank.id, Instant.now().plus(1, ChronoUnit.DAYS))
    SessionSetupStore(db).create("hanks", "333", "0.6.8", "{}", mapOf("Hank-HK" to listOf(hank.id)))

    users.delete(hank.id)
    assertNull(users.findLogin("hank"))
    assertNull(logins.userOf("token"))
    assertNull(users.account(hank.id))
    assertEquals(mapOf(hank.id to "Hank"), SessionSetupStore(db).of("333")?.members)
    // The name and the address are free again
    users.create("Hank", "hash", email = "hank@example.org")
    Unit
  }

  @Test
  fun `the deletion goes by the last finished session, and waits while one runs`() = runBlocking {
    val users = UserStore(db)
    val setups = SessionSetupStore(db)
    val finishes = SessionFinishStore(db)
    val ivy = users.create("Ivy", "hash")
    users.setAutoDelete(ivy.id, "3m")
    val never = users.create("Jo", "hash")
    assertEquals(listOf(ivy.id), users.activity().map { it.user.id })
    assertNull(users.activity().single().lastSessionEnd)

    setups.create("ivy-one", "444", "0.6.8", "{}", mapOf("Ivy-HK" to listOf(ivy.id, never.id)))
    assertTrue(users.activity().single().playing)
    val end = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    finishes.finish("444", by = null, now = end)
    val activity = users.activity().single()
    assertEquals(false, activity.playing)
    assertEquals(end, activity.lastSessionEnd?.truncatedTo(ChronoUnit.MILLIS))

    users.markWarned(ivy.id, end)
    assertEquals(end, users.activity().single().warnedAt?.truncatedTo(ChronoUnit.MILLIS))
    // A new period takes the warning back
    users.setAutoDelete(ivy.id, "1y")
    assertNull(users.activity().single().warnedAt)
  }

  @Test
  fun `two-factor login counts once confirmed, each step and recovery code once`() = runBlocking {
    val users = UserStore(db)
    val store = TwoFactorStore(db)
    val kai = users.create("Kai", "hash")
    store.setUp(kai.id, "sealed-secret")
    assertEquals(false, users.findActive(kai.id)?.twoFactor)
    assertNull(store.of(kai.id)?.enabledAt)

    store.enable(kai.id, listOf("h1", "h2", "h2"))
    assertEquals(true, users.findActive(kai.id)?.twoFactor)
    assertEquals(2, store.codesLeft(kai.id))
    // A step works once, an older one never again
    assertTrue(store.useStep(kai.id, 100))
    assertEquals(false, store.useStep(kai.id, 100))
    assertEquals(false, store.useStep(kai.id, 99))
    assertTrue(store.useStep(kai.id, 101))
    assertTrue(store.useCode(kai.id, "h1"))
    assertEquals(false, store.useCode(kai.id, "h1"))
    assertEquals(false, store.useCode(kai.id, "nope"))
    assertEquals(1, store.codesLeft(kai.id))

    store.replaceCodes(kai.id, listOf("h3"))
    assertEquals(false, store.useCode(kai.id, "h2"))
    store.disable(kai.id)
    assertNull(store.of(kai.id))
    assertEquals(0, store.codesLeft(kai.id))
    assertEquals(false, users.findActive(kai.id)?.twoFactor)

    // Deleting the account takes it along
    store.setUp(kai.id, "sealed")
    store.enable(kai.id, listOf("h4"))
    users.delete(kai.id)
    assertNull(store.of(kai.id))
    assertEquals(0, store.codesLeft(kai.id))
  }

  @Test
  fun `a session's roles are one per person, with names that follow a rename and managed sessions counted`() = runBlocking {
    val users = UserStore(db)
    val roles = SessionRoleStore(db)
    val setups = SessionSetupStore(db)
    val lea = users.create("Lea", "hash")
    val max = users.create("Max", "hash")
    setups.create("lea-one", "555", "0.6.8", "{}", mapOf("Max-HK" to listOf(max.id)))
    assertTrue(roles.add("lea-one", lea.id, "manager", "Lea", "Lea"))
    assertEquals(false, roles.add("lea-one", lea.id, "watcher", "Lea", "Lea"))
    assertTrue(roles.add("lea-one", max.id, "support", "Max", "Lea"))
    assertEquals(mapOf(lea.id to "manager", max.id to "support"), setups.of("555")?.roles?.mapValues { it.value.role })
    assertEquals(1, roles.runningManagedBy(lea.id))

    users.rename(lea.id, "Leandra")
    assertEquals("Leandra", setups.of("555")?.roles?.get(lea.id)?.name)
    // A watched (or supported) session postpones the automatic deletion like a played one
    users.setAutoDelete(max.id, "2w")
    assertTrue(users.activity().single { it.user.id == max.id }.playing)

    SessionFinishStore(db).finish("555", by = null)
    assertEquals(0, roles.runningManagedBy(lea.id))
    assertTrue(roles.remove("lea-one", max.id))
    assertEquals(false, roles.remove("lea-one", max.id))
  }

  @Test
  fun `the last Session-Manager keeps their part, also when two take each other's at once`() = runBlocking {
    val users = UserStore(db)
    val roles = SessionRoleStore(db)
    val setups = SessionSetupStore(db)
    val uma = users.create("Uma", "hash")
    val vic = users.create("Vic", "hash")
    val wes = users.create("Wes", "hash")
    setups.create("uma-one", "556", "0.6.8", "{}", emptyMap())
    assertTrue(roles.add("uma-one", uma.id, "manager", "Uma", "Uma"))
    assertTrue(roles.add("uma-one", vic.id, "manager", "Vic", "Uma"))
    assertTrue(roles.add("uma-one", wes.id, "watcher", "Wes", "Uma"))
    suspend fun managers() = setups.of("556")?.roles.orEmpty().filterValues { it.role == "manager" }.keys

    // Each takes the other's part at the same moment: one of them stays
    val both = listOf(async(Dispatchers.IO) { roles.leave("uma-one", vic.id) }, async(Dispatchers.IO) { roles.leave("uma-one", uma.id) })
      .map { it.await() }
    assertEquals(setOf(RoleLeaving.DONE, RoleLeaving.LAST_MANAGER), both.toSet())
    assertEquals(1, managers().size)
    val last = managers().single()
    assertEquals(RoleLeaving.LAST_MANAGER, roles.leave("uma-one", last, "support"))
    // Anyone else's part changes or goes; one that isn't there is told so
    assertEquals(RoleLeaving.DONE, roles.leave("uma-one", wes.id, "support"))
    assertEquals("support", setups.of("556")?.roles?.get(wes.id)?.role)
    assertEquals(RoleLeaving.DONE, roles.leave("uma-one", wes.id))
    assertEquals(RoleLeaving.NONE, roles.leave("uma-one", wes.id))
    assertEquals(RoleLeaving.NONE, roles.leave("no-such-one", last))
  }

  @Test
  fun `an account kept out loses its logins, is let in again, and a session knows its manager isn't there`() = runBlocking {
    val users = UserStore(db)
    val logins = LoginSessionStore(db)
    val nora = users.create("Nora", "hash", email = "nora@example.org")
    val later = Instant.now().plusSeconds(3600)
    logins.create("nora-login", nora.id, later)
    logins.create("nora-bridge", nora.id, later, method = "bridge")
    SessionSetupStore(db).create("nora-one", "777", "0.6.8", "{}", emptyMap())
    assertTrue(SessionRoleStore(db).add("nora-one", nora.id, "manager", "Nora", "Nora"))
    assertEquals(false, SessionSetupStore(db).of("777")?.roles?.get(nora.id)?.unavailable())

    // A time-out: the logins are gone (the bridge's too), the account says until when, why and by whom
    val until = Instant.now().plusSeconds(86400)
    users.block(nora.id, until, "Spam", "Admin")
    assertNull(logins.loginOf("nora-login"))
    assertNull(logins.loginOf("nora-bridge"))
    val timedOut = users.findActive(nora.id)!!
    assertTrue(timedOut.keptOut())
    assertEquals("Spam" to "Admin", timedOut.block?.reason to timedOut.block?.by)
    assertEquals(false, timedOut.keptOut(until.plusSeconds(1)))
    assertEquals(true, users.account(nora.id)?.block?.holds())
    assertEquals(true, SessionSetupStore(db).of("777")?.roles?.get(nora.id)?.unavailable())
    // They are still found for a login (to be told why), and nothing else of the account changed
    assertEquals(nora.id, users.findLogin("nora@example.org")?.id)

    // A suspension has no end; taking it back clears everything
    users.block(nora.id, null, null, "Admin")
    assertTrue(users.findActive(nora.id)!!.keptOut(Instant.now().plusSeconds(10L * 365 * 86400)))
    users.unblock(nora.id)
    assertNull(users.findActive(nora.id)?.block)
    assertEquals(false, SessionSetupStore(db).of("777")?.roles?.get(nora.id)?.unavailable())

    // A deleted account is no longer kept out (its row stays for the sessions); its manager's part says it is gone
    users.block(nora.id, null, "x", "Admin")
    users.delete(nora.id)
    assertNull(users.account(nora.id))
    assertEquals(true, SessionSetupStore(db).of("777")?.roles?.get(nora.id)?.unavailable())
  }

  @Test
  fun `the sessions someone created count while they run, and an account can have its own limit`() = runBlocking {
    val users = UserStore(db)
    val setups = SessionSetupStore(db)
    val roles = SessionRoleStore(db)
    val ida = users.create("Ida", "hash")
    val jo = users.create("Jo", "hash")
    setups.create("ida-one", "801", "0.6.8", "{}", emptyMap(), createdBy = ida.id)
    setups.create("ida-two", "802", "0.6.8", "{}", emptyMap(), createdBy = ida.id)
    setups.create("cli-seed", "803", "0.6.8", "{}", emptyMap())
    // Jo manages one of Ida's: it still counts for Ida, who created it, and not for Jo
    assertTrue(roles.add("ida-one", jo.id, "manager", "Jo", "Ida"))
    assertEquals(2, setups.runningCreatedBy(ida.id))
    assertEquals(0, setups.runningCreatedBy(jo.id))
    assertEquals(mapOf(ida.id to 2), setups.runningCreated())
    assertEquals(ida.id, setups.of("801")?.createdBy)
    assertNull(setups.of("803")?.createdBy)
    // A finished one no longer counts; reopened it does again
    SessionFinishStore(db).finish("801", by = null)
    assertEquals(1, setups.runningCreatedBy(ida.id))
    SessionFinishStore(db).reopen("801")
    assertEquals(2, setups.runningCreatedBy(ida.id))

    // An account's own limit: none (the default) until set, and the default again when taken back
    assertNull(users.account(ida.id)?.sessionLimit)
    users.setSessionLimit(ida.id, 5)
    assertEquals(5, users.account(ida.id)?.sessionLimit)
    assertEquals(5, users.findActive(ida.id)?.sessionLimit)
    users.setSessionLimit(ida.id, 0)
    assertEquals(0, users.account(ida.id)?.sessionLimit)
    users.setSessionLimit(ida.id, null)
    assertNull(users.account(ida.id)?.sessionLimit)
  }

  @Test
  fun `a share link is one per session, replaced by a new one and gone with its session`() = runBlocking {
    val links = ShareLinkStore(db)
    val setups = SessionSetupStore(db)
    setups.create("shared-one", "777", "0.6.8", "{}", emptyMap())
    assertEquals(false, links.replace("nothing-here", "a".repeat(64), "Lea"))
    assertNull(links.of("shared-one"))
    assertTrue(links.replace("shared-one", "a".repeat(64), "Lea"))
    assertEquals("shared-one", links.sessionWith("a".repeat(64)))
    assertTrue(links.replace("shared-one", "b".repeat(64), "Max"))
    assertNull(links.sessionWith("a".repeat(64)))
    assertEquals("Max", links.of("shared-one")?.createdBy)
    assertTrue(links.remove("shared-one"))
    assertEquals(false, links.remove("shared-one"))
    assertNull(links.sessionWith("b".repeat(64)))

    assertTrue(links.replace("shared-one", "c".repeat(64), "Lea"))
    setups.delete("777")
    assertNull(links.sessionWith("c".repeat(64)))
  }

  @Test
  fun `an account is linked to one Authentik user, logins keep their method, a login link works once`() = runBlocking {
    val users = UserStore(db)
    val lea = users.create("Lea", "hash", email = "lea@example.org")
    val max = users.create("Max", "hash")
    assertNull(users.findBySubject("sub-1"))
    assertTrue(users.linkSubject(lea.id, "sub-1"))
    assertEquals(lea.id, users.findBySubject("sub-1")?.id)
    // Another account can't take it, and Lea keeps hers
    assertEquals(false, users.linkSubject(max.id, "sub-1"))
    assertEquals(false, users.linkSubject(lea.id, "sub-2"))
    assertTrue(users.linkSubject(lea.id, "sub-1"))

    val logins = LoginSessionStore(db)
    val later = Instant.now().plus(1, ChronoUnit.DAYS)
    logins.create("a".repeat(64), lea.id, later, method = "oidc")
    logins.create("b".repeat(64), max.id, later)
    assertEquals(lea.id to "oidc", logins.loginOf("a".repeat(64)))
    assertEquals(max.id to "password", logins.loginOf("b".repeat(64)))

    val links = LoginLinkStore(db)
    links.create("c".repeat(64), lea.id, later)
    links.create("d".repeat(64), lea.id, Instant.now().minusSeconds(1))
    assertEquals(lea.id, links.use("c".repeat(64)))
    assertNull(links.use("c".repeat(64)))
    // Expired, or none
    assertNull(links.use("d".repeat(64)))
    assertNull(links.use("e".repeat(64)))
  }

  @Test
  fun `settings have their defaults and keep what is set`() = runBlocking {
    val settings = AppSettingsStore(db)
    assertEquals("1", settings.get("sessions_per_user"))
    settings.set("sessions_per_user", "3")
    assertEquals("3", settings.get("sessions_per_user"))
    settings.set("something_new", "x")
    assertEquals("x", settings.get("something_new"))
  }
}
