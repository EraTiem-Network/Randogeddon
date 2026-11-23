package net.eratiem.randogeddon.server.account

import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.TrackerHarness
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The logins that end when a login may act as an Administrator by something done in another one, on the real stores:
 * as without single sign-on with `ADMIN_PASSWORD_LOGIN`, where an Administrator's login counts by its second factor.
 */
class AdministratorLoginsTest {
  private lateinit var tracker: TrackerHarness

  @BeforeTest
  fun start() {
    tracker = TrackerHarness(adminPasswordLogin = true)
  }

  @AfterTest
  fun stop() {
    tracker.close()
  }

  /** [user] sets two-factor login up and confirms it with a right code, in the login [keep]. */
  private suspend fun turnOn(user: User, keep: String) {
    val now = Instant.now()
    val secret = Base32.decode(tracker.twoFactor.setUp(user).secret)
    tracker.twoFactor.enable(user, Totp.code(secret, Totp.step(now.toEpochMilli())), now, keep = keep)
  }

  /** Whose login [token] is and how it was made; `null`: it ended. */
  private suspend fun login(token: String) = tracker.loginStore.loginOf(tokenHash(token))

  /** A key of Randogeddon Bridge for [user]. */
  private suspend fun bridgeKey(user: User): String = newToken().also {
    tracker.loginStore.create(tokenHash(it), user.id, Instant.now().plusSeconds(3600), method = LoginMethods.BRIDGE)
  }

  @Test
  fun `an Administrator turning two-factor login on keeps the login that did it and their bridge keys`() = runBlocking<Unit> {
    val ada = tracker.user("Ada", administrator = true)
    val here = tracker.logins.start(ada)
    val elsewhere = tracker.logins.start(ada)
    val link = tracker.logins.start(ada, LoginMethods.LINK)
    val bridge = bridgeKey(ada)
    // Before: no login of theirs acts as an Administrator
    val before = tracker.logins.userOf(elsewhere)!!
    assertEquals(Refusal(Problem.NEEDS_TWO_FACTOR), tracker.logins.administratorProblem(before))

    turnOn(ada, keep = here)
    assertEquals(ada.id to LoginMethods.PASSWORD, login(here))
    assertNull(login(elsewhere))
    assertNull(login(link))
    assertEquals(ada.id to LoginMethods.BRIDGE, login(bridge))
    // The one kept passed the code, and acts as an Administrator now
    val user = tracker.logins.userOf(here)!!
    assertTrue(user.twoFactor)
    assertNull(tracker.logins.administratorProblem(user))
  }

  @Test
  fun `anyone else who turns two-factor login on keeps their logins`() = runBlocking<Unit> {
    val bob = tracker.user("Bob")
    val here = tracker.logins.start(bob)
    val elsewhere = tracker.logins.start(bob)
    turnOn(bob, keep = here)
    assertEquals(bob.id to LoginMethods.PASSWORD, login(here))
    assertEquals(bob.id to LoginMethods.PASSWORD, login(elsewhere))
    assertTrue(tracker.users.findActive(bob.id)!!.twoFactor)
  }
}
