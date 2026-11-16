package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.Problem
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SingleSignOnTest {
  private val group = "randogeddon-administrators"
  private val ada = User(Uuid.random(), "Ada", "hash", administrator = true)
  private val bob = User(Uuid.random(), "Bob", "hash")

  private fun identity(groups: List<String> = listOf(group), emailVerified: Boolean = true, username: String? = "ada") =
    OidcIdentity("sub-1", "ada@example.org", emailVerified, username, "Ada Lovelace", groups)

  @Test
  fun `members of the group get in with their account, the first time by their address, else a new one`() {
    assertEquals(SsoAccount.Linked(ada), ssoAccount(identity(), group, linked = ada, byEmail = bob))
    assertEquals(SsoAccount.ByEmail(bob), ssoAccount(identity(), group, linked = null, byEmail = bob))
    // An address the provider didn't verify links nothing
    assertEquals(SsoAccount.New("ada"), ssoAccount(identity(emailVerified = false), group, linked = null, byEmail = bob))
    assertEquals(SsoAccount.New("ada"), ssoAccount(identity(), group, linked = null, byEmail = null))
  }

  @Test
  fun `others are refused, and an Administrator who left the group is one no more`() {
    val left = ssoAccount(identity(groups = listOf("infrastructure")), group, linked = ada, byEmail = null)
    assertIs<SsoAccount.Refused>(left)
    assertEquals(ada, left.demote)
    val player = ssoAccount(identity(groups = emptyList()), group, linked = null, byEmail = bob)
    assertIs<SsoAccount.Refused>(player)
    assertNull(player.demote)
  }

  @Test
  fun `a new account's name fits the rules and is unique`() = runBlocking {
    assertEquals("ada", wantedName(identity()))
    assertEquals("Ada Lovelace", wantedName(identity(username = null)))
    assertEquals("Administrator", wantedName(identity(username = "x@")))
    val taken = setOf("ada", "ada 2")
    assertEquals("ada 3", uniqueName("ada") { it in taken })
    assertEquals("Ada", uniqueName("Ada") { false })
  }

  @Test
  fun `with single sign-on only a login through it acts as an Administrator`() {
    assertNull(ssoAdministratorProblem(ada.copy(loginMethod = LoginMethods.OIDC), "Authentik"))
    // Not a CLI link, a bridge key or a password (two-factor or not)
    for (method in listOf(LoginMethods.LINK, LoginMethods.BRIDGE)) {
      assertEquals(
        Refusal.of(Problem.ADMINISTRATOR_USE_SSO, "provider" to "Authentik"),
        ssoAdministratorProblem(ada.copy(loginMethod = method), "Authentik"), method,
      )
    }
    assertEquals(
      Refusal.of(Problem.ADMINISTRATOR_USE_SSO, "provider" to "Authentik"),
      ssoAdministratorProblem(ada.copy(loginMethod = LoginMethods.PASSWORD, twoFactor = true), "Authentik"),
    )
    assertEquals(Refusal(Problem.ONLY_ADMINISTRATORS), ssoAdministratorProblem(bob.copy(loginMethod = LoginMethods.OIDC), "Authentik"))
  }

  @Test
  fun `the provider's webhook needs its bearer token, and none works without one`() {
    assertTrue(bearerMatches("s3cret", "Bearer s3cret"))
    assertFalse(bearerMatches("s3cret", "Bearer s3cre"))
    assertFalse(bearerMatches("s3cret", "s3cret"))
    assertFalse(bearerMatches("s3cret", null))
    assertFalse(bearerMatches("", "Bearer "))
  }
}
