package net.eratiem.randogeddon.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * What the database decides (Testcontainers; Docker must be running): the gateway's role and its limits, what goes with
 * a session or an account, two writers at once, the time limits, and migration V29 on data of before.
 */
class IntegrityTest {
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

  /** A session for each of [ids] (its seed: `seed-<id>`), set up as the panel does. */
  private suspend fun setUpSessions(vararg ids: String) {
    for (id in ids) assertTrue(SessionSetupStore(db).create(id, "seed-$id", "0.6.8", "{}", emptyMap()))
  }

  /** The gateway's role [role] made by the tracker (here the superuser), and a pool of one logged in as it. */
  private suspend fun gateway(role: String, password: String = "$role-password"): RandogeddonDatabase {
    GatewayRole.ensure(db, role, password)
    return RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, role, password), migrate = false, poolSize = 1)
  }

  /** A plain connection as the container's superuser, or as [user]. */
  private fun raw(user: String = postgres.username, password: String = postgres.password, url: String = postgres.jdbcUrl): Connection =
    DriverManager.getConnection(url, user, password)

  private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

  private fun Connection.number(sql: String): Long = createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getLong(1) } }

  private fun Connection.text(sql: String): String? = createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getString(1) } }

  @Test
  fun `the gateway's role does its work, and nothing else`() = runBlocking<Unit> {
    val ada = UserStore(db).create("Ada", "hash")
    assertTrue(SessionSetupStore(db).create("busy-otter", "seed-busy", "0.6.8", "{}", mapOf("Ada-HK" to listOf(ada.id)), hosted = setOf("Dig")))
    val passwords = SlotPasswordStore(db).ensure("seed-busy", listOf("Ada-HK", "Dig"), { "pw" })
    SlotPasswordStore(db).setLocked("seed-busy", setOf("Dig"))
    gateway("work_gateway").use { gw ->
      val t0 = Instant.parse("2026-11-23T10:00:00Z")
      SessionTimeStore(gw).started("seed-busy", t0)
      SessionTimeStore(gw).tick("seed-busy", t0.plusSeconds(30))
      SessionTimeStore(gw).stopped("seed-busy", t0.plusSeconds(45))
      SessionTimeStore(gw).dropOpenCounts()
      assertEquals(SessionTime(45_000, null), SessionTimeStore(gw).of("seed-busy"))
      assertEquals(passwords, SlotPasswordStore(gw).passwords("seed-busy"))
      assertEquals(setOf("Dig"), SlotPasswordStore(gw).locked("seed-busy"))
      assertNotNull(SessionFinishStore(gw).of("seed-busy"))
      assertEquals("0.6.8" to "{}", SessionSetupStore(gw).launchOf("seed-busy"))
      assertEquals("busy-otter", SessionStore(gw).idOf("seed-busy"))
      assertEquals(DEFAULT_GAME_SERVERS, AppSettingsStore(gw).gameServers())
      // Nothing of the accounts, the people of a session, the cheats, the snapshots; no finishing, no new passwords
      assertFailsWith<Exception> { UserStore(gw).active() }
      assertFailsWith<Exception> { LoginSessionStore(gw).userOf("token") }
      assertFailsWith<Exception> { PasswordResetStore(gw).find("token") }
      assertFailsWith<Exception> { SessionSetupStore(gw).of("seed-busy") }
      assertFailsWith<Exception> { CheatLogStore(gw).of("seed-busy") }
      assertFailsWith<Exception> { SessionSnapshotStore(gw).of("busy-otter") }
      assertFailsWith<Exception> { SessionFinishStore(gw).finish("seed-busy", "Mallory") }
      assertFailsWith<Exception> { SlotPasswordStore(gw).renew("seed-busy", "Ada-HK", "mine") }
    }
    assertNull(SessionFinishStore(db).of("seed-busy")?.finishedAt)
    // At most so many connections at once
    raw().use { assertEquals(6L, it.number("SELECT rolconnlimit FROM pg_roles WHERE rolname = 'work_gateway'")) }
  }

  @Test
  fun `the gateway's role names no event's ID, writes none over 2 MiB, and none for a session that isn't there`() = runBlocking<Unit> {
    setUpSessions("loud-otter")
    gateway("rows_gateway").use { gw ->
      val heard = SessionEventStore(gw)
      heard.append("loud-otter", 1_000, SessionEventStore.START)
      assertFailsWith<Exception> { heard.append("loud-otter", 2_000, SessionEventStore.PACKET, "x".repeat(3 * 1024 * 1024)) }
      assertFailsWith<Exception> { heard.append("nobody-here", 2_000, SessionEventStore.STOP) }
    }
    raw("rows_gateway", "rows_gateway-password").use { c ->
      // An ID of its own would put it past every later event: refused, also when it insists
      assertFailsWith<Exception> {
        c.run("INSERT INTO session_events (id, session, at_ms, kind, packet) VALUES (9223372036854775000, 'loud-otter', 1, 'stop', '')")
      }
      assertFailsWith<Exception> {
        c.run(
          "INSERT INTO session_events (id, session, at_ms, kind, packet) OVERRIDING SYSTEM VALUE " +
            "VALUES (9223372036854775000, 'loud-otter', 1, 'stop', '')",
        )
      }
    }
    // The tracker neither: the size is the table's
    assertFailsWith<Exception> { SessionEventStore(db).append("loud-otter", 3_000, SessionEventStore.CHEAT, "x".repeat(3 * 1024 * 1024)) }
    assertEquals(listOf(SessionEventStore.START), SessionEventStore(db).after("loud-otter", 0).map { it.kind })
  }

  @Test
  fun `the tracker reads events in batches of a bounded size, at least one`() = runBlocking<Unit> {
    setUpSessions("big-otter")
    val events = SessionEventStore(db)
    val mib = "x".repeat(1024 * 1024)
    repeat(5) { events.append("big-otter", it.toLong(), SessionEventStore.PACKET, mib) }
    assertEquals(listOf(0L, 1L, 2L), events.after("big-otter", 0, bytes = 5L * 1024 * 512).map { it.atMs })
    assertEquals(listOf(0L), events.after("big-otter", 0, bytes = 10).map { it.atMs })
    assertEquals(5, events.after("big-otter", 0).size)
  }

  @Test
  fun `an event isn't passed over when an earlier one commits later`() = runBlocking<Unit> {
    setUpSessions("slow-otter")
    val events = SessionEventStore(db)
    raw().use { c ->
      c.autoCommit = false
      // As append does it: the session's lock, then its row (its ID taken first, committed later)
      c.run("SELECT pg_advisory_xact_lock(${SessionEventStore.APPEND_LOCK}, ${"slow-otter".hashCode()})")
      c.run("INSERT INTO session_events (session, at_ms, kind, packet) VALUES ('slow-otter', 1, 'stop', '')")
      val second = async(Dispatchers.IO) { events.append("slow-otter", 2, SessionEventStore.START) }
      delay(1_000)
      // The second waits for the first: nothing of either is read yet, so the reader can't move past the first
      assertFalse(second.isCompleted)
      assertEquals(emptyList(), events.after("slow-otter", 0))
      c.commit()
      second.await()
    }
    val read = events.after("slow-otter", 0)
    assertEquals(listOf(1L, 2L), read.map { it.atMs })
    assertTrue(read[0].id < read[1].id)
  }

  @Test
  fun `deleting a session takes its events and snapshot along, and nothing more can be written for it`() = runBlocking<Unit> {
    setUpSessions("party")
    val events = SessionEventStore(db)
    val snapshots = SessionSnapshotStore(db)
    events.append("party", 1, SessionEventStore.PACKET, "{\"cmd\":\"PrintJSON\",\"type\":\"Chat\"}")
    assertTrue(snapshots.saveLive("party", 1, "{}"))
    assertTrue(SessionSetupStore(db).delete("seed-party"))
    assertEquals(emptyList(), events.after("party", 0))
    assertNull(snapshots.of("party"))
    // A game server's late stop, after the deletion
    assertFailsWith<Exception> { events.append("party", 2, SessionEventStore.STOP) }
    assertFailsWith<Exception> { snapshots.saveLive("party", 2, "{}") }
    // A new session with the same ID hears nothing of the old one
    assertTrue(SessionSetupStore(db).create("party", "another-seed", "0.6.8", "{}", emptyMap()))
    assertEquals(emptyList(), events.after("party", 0))
    assertNull(snapshots.of("party"))
  }

  @Test
  fun `a seed that is a session's already isn't taken over, a bare one of before is`() = runBlocking<Unit> {
    val setups = SessionSetupStore(db)
    val ada = UserStore(db).create("Ada", "hash")
    assertTrue(setups.create("first-otter", "seed1", "0.6.8", "{}", mapOf("Ada-HK" to listOf(ada.id))))
    assertFalse(setups.create("second-otter", "seed1", "0.6.8", "{}", emptyMap()))
    assertEquals("seed1", SessionStore(db).byId("first-otter")?.seedName)
    assertNull(SessionStore(db).byId("second-otter"))
    assertEquals(mapOf(ada.id to "Ada"), setups.of("seed1")?.members)
    // A seed from before Phase 4 (its bare session, the host's CLI): taken over, with what it heard
    SlotPasswordStore(db).ensure("old-seed", listOf("Ada-HK"), { "pw" }, create = true)
    SessionEventStore(db).append(legacySessionId("old-seed"), 1, SessionEventStore.START)
    assertTrue(setups.create("new-otter", "old-seed", "0.6.8", "{}", emptyMap()))
    assertEquals("new-otter", SessionStore(db).idOf("old-seed"))
    assertEquals(1, SessionEventStore(db).after("new-otter", 0).size)
  }

  @Test
  fun `counting a session's time or a cheat makes no session`() = runBlocking<Unit> {
    SessionTimeStore(db).started("nope")
    CheatLogStore(db).add("nope", CheatEntry(Instant.now(), "Ada", "ITEM", "Ada-HK", "x"))
    assertEquals(emptyList(), SessionStore(db).all())
  }

  @Test
  fun `two at once get the answer one alone gets`() = runBlocking<Unit> {
    val users = UserStore(db)
    val lea = users.create("Lea", "hash")
    setUpSessions("busy-heron")
    // One role per person: one of twenty gets it, the others are told they have one
    val added = (1..20).map { async(Dispatchers.IO) { SessionRoleStore(db).add("busy-heron", lea.id, "watcher", "Lea", "Lea") } }.map { it.await() }
    assertEquals(1, added.count { it })
    // A new setting twenty times at once: no failure, one value
    (1..20).map { n -> async(Dispatchers.IO) { AppSettingsStore(db).set("new_key", "$n") } }.forEach { it.await() }
    assertNotNull(AppSettingsStore(db).get("new_key"))
    // One invitation and one pending address per account, whatever comes at once
    val ivy = users.invite("ivy@example.org")
    val later = Instant.now().plus(1, ChronoUnit.DAYS)
    (1..20).map { n -> async(Dispatchers.IO) { InvitationStore(db).create("invite-$n", ivy.id, "ivy@example.org", null, later) } }.forEach { it.await() }
    assertNotNull(InvitationStore(db).of(ivy.id))
    (1..20).map { n -> async(Dispatchers.IO) { EmailChangeStore(db).create("change-$n", lea.id, "lea-$n@example.org", later) } }.forEach { it.await() }
    assertNotNull(EmailChangeStore(db).pending(lea.id))
    // A confirmation link works once, also twenty times at once
    EmailChangeStore(db).create("once", lea.id, "lea@example.org", later)
    val taken = (1..20).map { async(Dispatchers.IO) { EmailChangeStore(db).take("once") } }.map { it.await() }
    assertEquals(1, taken.count { it != null })
  }

  @Test
  fun `the session time's tick and stop at the same moment end with the stop`() = runBlocking<Unit> {
    setUpSessions("ticking-otter")
    val times = SessionTimeStore(db)
    val t0 = Instant.parse("2026-11-23T10:00:00Z")
    repeat(20) { round ->
      val start = t0.plusSeconds(100L * round)
      times.started("seed-ticking-otter", start)
      listOf(
        async(Dispatchers.IO) { times.tick("seed-ticking-otter", start.plusSeconds(30)) },
        async(Dispatchers.IO) { times.stopped("seed-ticking-otter", start.plusSeconds(30)) },
      ).forEach { it.await() }
      assertEquals(SessionTime(30_000L * (round + 1), null), times.of("seed-ticking-otter"))
    }
  }

  @Test
  fun `a live snapshot never replaces an archive, and many at once on a new session don't fail`() = runBlocking<Unit> {
    setUpSessions("kept-otter", "new-otter")
    val snapshots = SessionSnapshotStore(db)
    snapshots.saveFinal("kept-otter", 1, "{\"final\":1}")
    assertFalse(snapshots.saveLive("kept-otter", 2, "{\"live\":1}"))
    assertEquals(SessionSnapshot("{\"final\":1}", final = true, atMs = 1), snapshots.of("kept-otter"))
    val saved = (1..20).map { n -> async(Dispatchers.IO) { snapshots.saveLive("new-otter", n.toLong(), "{\"live\":$n}") } }.map { it.await() }
    assertTrue(saved.all { it })
    // With the state its events are replayed onto, and up to which event; a snapshot without one keeps them
    assertTrue(snapshots.saveLive("new-otter", 30, "{\"live\":30}", state = "{\"state\":1}", eventsUntil = 42))
    assertTrue(snapshots.saveLive("new-otter", 31, "{\"live\":31}"))
    assertEquals("{\"state\":1}", snapshots.baseline("new-otter"))
    assertEquals(42L, snapshots.eventsUntil("new-otter"))
    assertEquals("{\"live\":31}", snapshots.of("new-otter")?.json)
  }

  @Test
  fun `finishing writes the archive with it, reopening makes it live again`() = runBlocking<Unit> {
    setUpSessions("done-otter")
    val finishes = SessionFinishStore(db)
    val snapshots = SessionSnapshotStore(db)
    assertTrue(snapshots.saveLive("done-otter", 1, "{\"live\":1}"))
    finishes.finish("seed-done-otter", "Ada", archive = "{\"final\":1}")
    assertNotNull(finishes.of("seed-done-otter")?.finishedAt)
    assertEquals(SessionSnapshot("{\"final\":1}", true, snapshots.of("done-otter")!!.atMs), snapshots.of("done-otter"))
    finishes.reopen("seed-done-otter")
    assertNull(finishes.of("seed-done-otter")?.finishedAt)
    assertEquals(false, snapshots.of("done-otter")?.final)
    assertTrue(snapshots.saveLive("done-otter", 2, "{\"live\":2}"))
  }

  @Test
  fun `the gateway's role is never the tracker's own login, a superuser or the tables' owner`() = runBlocking<Unit> {
    // The tracker as the superuser (the container's): its own login, a superuser at once
    assertFailsWith<IllegalStateException> { GatewayRole.ensure(db, postgres.username, "x") }
    raw().use { it.run("CREATE ROLE other_super SUPERUSER LOGIN PASSWORD 'x'") }
    assertFailsWith<IllegalStateException> { GatewayRole.ensure(db, "other_super", "x") }
    // The tracker with a role of its own, the tables' owner
    raw().use { c ->
      c.run("CREATE ROLE own_tracker LOGIN NOSUPERUSER CREATEROLE PASSWORD 'tracker-password'")
      c.run("ALTER SCHEMA public OWNER TO own_tracker")
      c.run(
        "DO $$ DECLARE t text; BEGIN FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP " +
          "EXECUTE format('ALTER TABLE public.%I OWNER TO own_tracker', t); END LOOP; END $$",
      )
    }
    RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "own_tracker", "tracker-password"), migrate = false, poolSize = 1).use { tracker ->
      assertFalse(tracker.superuser())
      assertFailsWith<IllegalStateException> { GatewayRole.ensure(tracker, "own_tracker", "x") }
      // Its rights are as they were
      UserStore(tracker).create("Ada", "hash")
      assertEquals(listOf("Ada"), UserStore(tracker).active().map { it.name })
      // A role made before by the roles service (no login yet) gets its login and rights
      raw().use { it.run("CREATE ROLE made_gateway NOLOGIN; GRANT made_gateway TO own_tracker WITH ADMIN OPTION") }
      GatewayRole.ensure(tracker, "made_gateway", "made-password")
      RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "made_gateway", "made-password"), migrate = false, poolSize = 1).use { gw ->
        assertEquals(DEFAULT_IDLE_MINUTES, AppSettingsStore(gw).idleMinutes())
      }
    }
    assertTrue(db.superuser())
    assertEquals(listOf("Ada"), UserStore(db).active().map { it.name })
  }

  @Test
  fun `a second start changes nothing, also while a dump reads the events, and makes again what is missing`() = runBlocking<Unit> {
    setUpSessions("calm-otter")
    gateway("start_gateway").close()
    raw().use { dump ->
      dump.autoCommit = false
      dump.run("LOCK TABLE session_events IN ACCESS SHARE MODE")
      val started = System.nanoTime()
      // The policy and the rights are as they should be: nothing waits for the dump's lock
      GatewayRole.ensure(db, "start_gateway", "start_gateway-password")
      assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000)
      RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "start_gateway", "start_gateway-password"), migrate = false, poolSize = 1)
        .use { gw -> SessionEventStore(gw).append("calm-otter", 1, SessionEventStore.START) }
      dump.rollback()
    }
    // A restore without the rights and the policy (pg_dump --no-privileges, the policy dropped): made again
    raw().use { c ->
      c.run("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM start_gateway")
      c.run("DROP POLICY gateway_event_kinds ON session_events")
    }
    GatewayRole.ensure(db, "start_gateway", "start_gateway-password")
    RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, "start_gateway", "start_gateway-password"), migrate = false, poolSize = 1)
      .use { gw ->
        SessionEventStore(gw).append("calm-otter", 2, SessionEventStore.STOP)
        assertFailsWith<Exception> { SessionEventStore(gw).append("calm-otter", 3, SessionEventStore.CHEAT, "{}") }
      }
    assertEquals(2, SessionEventStore(db).after("calm-otter", 0).size)
  }

  @Test
  fun `a gateway's role without a password any more can't log in`() = runBlocking<Unit> {
    gateway("old_gateway").close()
    assertTrue(GatewayRole.retire(db, "old_gateway"))
    assertFailsWith<Exception> { raw("old_gateway", "old_gateway-password").close() }
    assertFalse(GatewayRole.retire(db, "never_made"))
  }

  @Test
  fun `a statement that waits for a lock ends within the limit, and the pool goes on`() = runBlocking<Unit> {
    setUpSessions("locked-otter")
    raw().use { holder ->
      holder.autoCommit = false
      holder.run("LOCK TABLE sessions IN ACCESS EXCLUSIVE MODE")
      val started = System.nanoTime()
      assertFailsWith<Exception> { SessionStore(db).all() }
      // Its lock's limit, for each of Exposed's three tries
      assertTrue((System.nanoTime() - started) / 1_000_000 < 30_000)
      holder.rollback()
    }
    assertEquals(listOf("locked-otter"), SessionStore(db).all().map { it.id })
  }

  @Test
  fun `a deleted account loses its name and settings, and goes for good if no session ever named it`() = runBlocking<Unit> {
    val users = UserStore(db)
    val links = LoginLinkStore(db)
    val later = Instant.now().plus(1, ChronoUnit.DAYS)
    // An invitation nobody accepted: gone for good
    val invited = users.invite("someone@example.org")
    users.delete(invited.id)
    assertEquals(0L, db.query { UsersTable.selectAll().where { UsersTable.id eq invited.id }.count() })
    // A player of a session: the row stays (the session names it), without its name and settings
    val hank = users.create("Hank", "hash")
    users.setLanguage(hank.id, "de")
    users.setTimeZone(hank.id, "Europe/Berlin")
    links.create("a".repeat(64), hank.id, later)
    assertTrue(SessionSetupStore(db).create("hanks", "333", "0.6.8", "{}", mapOf("Hank-HK" to listOf(hank.id))))
    users.delete(hank.id)
    val row = db.query { UsersTable.selectAll().where { UsersTable.id eq hank.id }.single() }
    assertNull(row[UsersTable.name])
    assertNull(row[UsersTable.language])
    assertNull(row[UsersTable.timeZone])
    assertNotNull(row[UsersTable.deletedAt])
    assertNull(links.use("a".repeat(64)))
    // The session still shows the name it had in it
    assertEquals(mapOf(hank.id to "Hank"), SessionSetupStore(db).of("333")?.members)
  }

  @Test
  fun `a login link is gone once used, an expired one when the next is made`() = runBlocking<Unit> {
    val lea = UserStore(db).create("Lea", "hash")
    val links = LoginLinkStore(db)
    links.create("a".repeat(64), lea.id, Instant.now().minusSeconds(1))
    links.create("b".repeat(64), lea.id, Instant.now().plusSeconds(600))
    assertEquals(1L, db.query { LoginLinksTable.selectAll().count() })
    assertEquals(lea.id, links.use("b".repeat(64)))
    assertEquals(0L, db.query { LoginLinksTable.selectAll().count() })
  }

  @Test
  fun `the times are kept as UTC, whatever zone the process was started in`() = runBlocking<Unit> {
    val before = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
      RandogeddonDatabase.connect(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), migrate = false, poolSize = 1).use { other ->
        val ada = UserStore(other).create("Ada", "hash")
        LoginSessionStore(other).create("t".repeat(64), ada.id, Instant.parse("2026-11-25T14:00:00Z"))
        assertEquals(ada.id, LoginSessionStore(other).userOf("t".repeat(64), Instant.parse("2026-11-25T13:59:00Z")))
        assertNull(LoginSessionStore(other).userOf("t".repeat(64), Instant.parse("2026-11-25T14:01:00Z")))
      }
      raw().use { assertEquals("2026-11-25 14:00:00", it.text("SELECT expires_at::text FROM login_sessions")) }
    } finally {
      TimeZone.setDefault(before)
    }
  }

  @Test
  fun `V29 on data of before, where orphans, doubles, old recovery codes and big rows go, the rest stays`() = runBlocking<Unit> {
    raw().use { it.run("CREATE DATABASE before_v29") }
    val url = postgres.jdbcUrl.replace("/${postgres.databaseName}", "/before_v29")
    Flyway.configure().dataSource(url, postgres.username, postgres.password).target("28").load().migrate()
    val ann = Uuid.random()
    val ben = Uuid.random()
    raw(url = url).use { c ->
      c.run("INSERT INTO users (id, name, password_hash, created_at, signed_up_at, totp_enabled_at) VALUES ('$ann', 'Ann', 'h', now(), now(), '2026-11-10')")
      c.run("INSERT INTO users (id, name, password_hash, created_at, signed_up_at, totp_enabled_at) VALUES ('$ben', 'Ben', 'h', now(), now(), '2026-11-20')")
      c.run("INSERT INTO recovery_codes (user_id, code_hash) VALUES ('$ann', 'plain'), ('$ben', 'keyed')")
      c.run(
        "INSERT INTO invitations (token_hash, user_id, email, created_at, expires_at) VALUES " +
          "('old', '$ann', 'a@example.org', '2026-11-01', '2026-12-01'), ('new', '$ann', 'a@example.org', '2026-11-02', '2026-12-01')",
      )
      c.run(
        "INSERT INTO email_changes (token_hash, user_id, email, created_at, expires_at) VALUES " +
          "('old', '$ben', 'b@example.org', '2026-11-01', '2026-12-01'), ('new', '$ben', 'c@example.org', '2026-11-02', '2026-12-01')",
      )
      c.run("INSERT INTO sessions (id, seed_name, public_id, created_at) VALUES ('${Uuid.random()}', 'seed-kept', 'kept', now())")
      c.run("INSERT INTO session_events (session, at_ms, kind, packet) VALUES ('kept', 1, 'start', ''), ('gone', 2, 'start', '')")
      c.run("INSERT INTO session_events (session, at_ms, kind, packet) VALUES ('kept', 3, 'packet', repeat('x', 3 * 1024 * 1024))")
      c.run("INSERT INTO session_snapshots (session, at_ms, snapshot) VALUES ('kept', 1, '{}'), ('gone', 1, '{}')")
      // A gateway's role as 0.23.3 made it: INSERT on the whole table
      c.run("CREATE ROLE old_rows LOGIN PASSWORD 'old-password'")
      c.run("GRANT SELECT ON sessions TO old_rows")
      c.run("GRANT INSERT ON session_events TO old_rows")
    }
    RandogeddonDatabase.connect(DatabaseConfig(url, postgres.username, postgres.password), migrate = true).close()
    raw(url = url).use { c ->
      assertEquals("new", c.text("SELECT string_agg(token_hash, ',') FROM invitations"))
      assertEquals("new", c.text("SELECT string_agg(token_hash, ',') FROM email_changes"))
      assertEquals("keyed", c.text("SELECT string_agg(code_hash, ',') FROM recovery_codes"))
      assertEquals("1", c.text("SELECT string_agg(at_ms::text, ',') FROM session_events"))
      assertEquals("kept", c.text("SELECT string_agg(session, ',') FROM session_snapshots"))
      assertEquals(0L, c.number("SELECT count(*) FROM users WHERE time_zone IS NOT NULL"))
    }
    raw("old_rows", "old-password", url).use { c ->
      assertFailsWith<Exception> { c.run("INSERT INTO session_events (id, session, at_ms, kind, packet) VALUES (99, 'kept', 4, 'stop', '')") }
      c.run("INSERT INTO session_events (session, at_ms, kind, packet) VALUES ('kept', 4, 'stop', '')")
    }
  }
}
