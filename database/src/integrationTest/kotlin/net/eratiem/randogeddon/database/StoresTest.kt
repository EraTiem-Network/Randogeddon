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
  fun `a session finishes, is listed as finished, and can be reopened`() = runBlocking {
    val finishes = SessionFinishStore(db)
    val t0 = Instant.parse("2026-11-13T20:00:00Z")
    SessionTimeStore(db).started("111", t0)
    SessionTimeStore(db).started("222", t0)
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
    // Seen first by the gateway (a seed from before Phase 4)
    SessionTimeStore(db).started("12345", t0)
    assertEquals("s-12345", sessions.idOf("12345"))
    SessionSetupStore(db).create("brave-otter", "678", "0.6.8", "{}", emptyMap(), now = t0.plusSeconds(60))
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
    assertTrue(invitations.use("second"))
    assertEquals(false, invitations.use("second"))
    assertTrue(invitations.find("second")?.usedAt != null)
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
  fun `settings have their defaults and keep what is set`() = runBlocking {
    val settings = AppSettingsStore(db)
    assertEquals("1", settings.get("sessions_per_user"))
    settings.set("sessions_per_user", "3")
    assertEquals("3", settings.get("sessions_per_user"))
    settings.set("something_new", "x")
    assertEquals("x", settings.get("something_new"))
  }
}
