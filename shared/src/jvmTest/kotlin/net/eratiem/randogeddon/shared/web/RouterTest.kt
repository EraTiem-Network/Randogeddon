package net.eratiem.randogeddon.shared.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SessionStatus

class RouterTest {
  private val token = "AbCdEfGh_ij-KLmn0123"

  @Test
  fun `each hash is its route`() {
    assertEquals(Route.Home, Route.parse(""))
    assertEquals(Route.Home, Route.parse("#/"))
    assertEquals(Route.Dashboard("friday-chaos"), Route.parse("#/friday-chaos"))
    assertEquals(Route.Dashboard("friday-chaos"), Route.parse("#/friday-chaos/"))
    assertEquals(Route.Spoiler("friday-chaos", fromAdmin = false), Route.parse("#/friday-chaos/spoiler"))
    assertEquals(Route.Admin(null), Route.parse("#/manage"))
    assertEquals(Route.Admin(null), Route.parse("#/admin"))
    assertEquals(Route.Admin(NEW_PAGE), Route.parse("#/manage/new"))
    assertEquals(Route.Admin("friday-chaos"), Route.parse("#/manage/friday-chaos"))
    assertEquals(Route.Spoiler("friday-chaos", fromAdmin = true), Route.parse("#/manage/friday-chaos/spoiler"))
    assertEquals(Route.Settings, Route.parse("#/settings"))
    assertEquals(Route.Invite(token), Route.parse("#/invite/$token"))
    assertEquals(Route.ConfirmEmail(token), Route.parse("#/email/$token"))
    assertEquals(Route.Watch(token), Route.parse("#/watch/$token"))
  }

  @Test
  fun `a hash with anything but an ID or a token in its place is the sessions page`() {
    for (hash in listOf("#/..", "#/a%2Fb", "#/a b", "#/" + "a".repeat(65), "#/login/$token", "#/login-problem")) {
      assertEquals(Route.Home, Route.parse(hash), hash)
    }
    // A link of the mails or a share link without a token, or with a broken one
    for (kind in listOf("invite", "email", "watch")) {
      assertEquals(Route.Home, Route.parse("#/$kind"), kind)
      assertEquals(Route.Home, Route.parse("#/$kind/short"), kind)
      assertEquals(Route.Home, Route.parse("#/$kind/${token}%2E"), kind)
    }
    // The panel without a session it can name
    assertEquals(Route.Admin(null), Route.parse("#/manage/.."))
    assertEquals(Route.Admin(null), Route.parse("#/manage/a%3Fb/spoiler"))
  }

  @Test
  fun `a route read back from its hash is the same route`() {
    val routes = listOf(
      Route.Home, Route.Dashboard("s1"), Route.Spoiler("s1", fromAdmin = false), Route.Spoiler("s1", fromAdmin = true),
      Route.Admin(null), Route.Admin(NEW_PAGE), Route.Admin("s1"), Route.Settings, Route.Invite(token),
      Route.ConfirmEmail(token), Route.Watch(token),
    )
    for (route in routes) assertEquals(route, Route.parse(route.hash), route.hash)
  }

  @Test
  fun `the links of the login form are told apart`() {
    assertTrue(loginLink("#/login/$token"))
    assertTrue(loginLink("#/login-problem"))
    assertFalse(loginLink("#/loginx"))
    assertFalse(loginLink("#/friday-chaos"))
  }

  private fun entry(
    id: String,
    role: SessionRole? = SessionRole.PLAYER,
    slots: List<String> = emptyList(),
    status: SessionStatus = SessionStatus.LIVE,
  ) = SessionEntry(id = id, seedName = "seed", title = id, status = status, mySlots = slots, myRole = role)

  @Test
  fun `the sessions page opens the only session by itself, always`() {
    assertEquals("a", homeTarget(listOf(entry("a")), arriving = false))
    assertEquals("a", homeTarget(listOf(entry("a", status = SessionStatus.ENDED)), arriving = true))
    // An Administrator's other sessions, without a role there, don't count
    assertEquals("a", homeTarget(listOf(entry("a"), entry("b", role = null)), arriving = false))
    assertNull(homeTarget(emptyList(), arriving = true))
  }

  @Test
  fun `arriving, it opens the only running session with a slot of the users`() {
    val sessions = listOf(
      entry("watched", role = SessionRole.WATCHER),
      entry("played", slots = listOf("Link")),
      entry("over", slots = listOf("Samus"), status = SessionStatus.PAUSED),
    )
    assertEquals("played", homeTarget(sessions, arriving = true))
    // Asked for on purpose: the list
    assertNull(homeTarget(sessions, arriving = false))
    // Two of them running: there is a choice to make
    assertNull(homeTarget(sessions + entry("also", slots = listOf("Mario"), status = SessionStatus.FINISHING), arriving = true))
  }
}
