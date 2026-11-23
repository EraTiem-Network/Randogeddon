package net.eratiem.randogeddon.server.account

import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.TrackerHarness
import net.eratiem.randogeddon.server.config.OidcConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An account following the single sign-on provider (its webhook, a login through it) on the real stores. */
class ProviderSyncTest {
  private lateinit var tracker: TrackerHarness

  @BeforeTest
  fun start() {
    // Never asked here: the webhook and the sync don't talk to the provider
    val provider = OidcConfig("http://localhost:9/application/o/randogeddon/", "randogeddon", "secret")
    tracker = TrackerHarness(oidc = provider)
  }

  @AfterTest
  fun stop() {
    tracker.close()
  }

  /** [user]'s two-factor login of before (set up and confirmed here). */
  private suspend fun twoFactorOn(user: User) {
    tracker.twoFactorStore.setUp(user.id, "sealed-secret")
    tracker.twoFactorStore.enable(user.id, listOf("code-hash"))
  }

  @Test
  fun `the provider's change of an Administrator gives a unique name, a free address, no second factor here`() = runBlocking<Unit> {
    val ada = tracker.user("ada", administrator = true, email = "ada@example.org")
    tracker.user("Ada_Lovelace")
    tracker.user("Bob", email = "bob@example.org")
    assertTrue(tracker.users.linkSubject(ada.id, "sub-ada"))
    twoFactorOn(ada)
    assertTrue(tracker.users.findActive(ada.id)!!.twoFactor)

    assertEquals(ada.id, tracker.singleSignOn.updated("sub-ada", "Ada Lovelace", null, "lovelace@example.org")?.id)
    val synced = tracker.users.findActive(ada.id)!!
    assertEquals("Ada_Lovelace_2", synced.name)
    assertEquals("lovelace@example.org", synced.email)
    assertFalse(synced.twoFactor)
    assertNull(tracker.twoFactorStore.of(ada.id))

    // Another account's address, and what isn't one, stay out; the name it has already stays
    tracker.singleSignOn.updated("sub-ada", "Ada Lovelace", null, "bob@example.org")
    tracker.singleSignOn.updated("sub-ada", "Ada Lovelace", null, "ada>lovelace@example.org")
    val again = tracker.users.findActive(ada.id)!!
    assertEquals("Ada_Lovelace_2" to "lovelace@example.org", again.name to again.email)
    assertNull(tracker.singleSignOn.updated("sub-nobody", "Nobody", null, null))
  }

  @Test
  fun `someone who is no Administrator any more keeps their name, address and second factor`() = runBlocking<Unit> {
    val bob = tracker.user("bob", email = "bob@example.org")
    assertTrue(tracker.users.linkSubject(bob.id, "sub-bob"))
    twoFactorOn(bob)
    // The webhook leaves them be
    assertNull(tracker.singleSignOn.updated("sub-bob", "Robert", null, "robert@example.org"))
    val same = tracker.users.findActive(bob.id)!!
    assertEquals("bob" to "bob@example.org", same.name to same.email)
    assertTrue(same.twoFactor)

    // A sync of an account that isn't an Administrator's keeps its second factor; only the address's verification is
    // the provider's to say
    tracker.accounts.syncFromProvider(same, "bob", "bob@example.org", emailVerified = true)
    assertTrue(tracker.users.findActive(bob.id)!!.twoFactor)
    assertNotNull(tracker.twoFactorStore.of(bob.id)?.enabledAt)
    assertEquals(true, tracker.users.account(bob.id)?.emailVerifiedByProvider)
  }
}
