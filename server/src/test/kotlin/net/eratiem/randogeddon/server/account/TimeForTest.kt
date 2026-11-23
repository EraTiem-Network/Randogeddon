package net.eratiem.randogeddon.server.account

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeForTest {
  private val at = Instant.parse("2026-11-25T14:00:00Z")

  @Test
  fun `a time is given in the account's time zone, by its name`() {
    assertEquals("2026-11-25 15:00 Europe/Berlin", timeFor(at, "Europe/Berlin"))
    // Summer time where it is summer
    assertEquals("2026-07-01 16:00 Europe/Berlin", timeFor(Instant.parse("2026-07-01T14:00:00Z"), "Europe/Berlin"))
  }

  @Test
  fun `without a time zone, or with one that is none, in UTC, said so`() {
    assertEquals("2026-11-25 14:00 UTC", timeFor(at, null))
    assertEquals("2026-11-25 14:00 UTC", timeFor(at, "Mars/Olympus_Mons"))
    assertEquals(ZoneOffset.UTC, zoneOf(null))
  }
}
