package net.eratiem.randogeddon.server.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ExpiringMapTest {
  /** A clock the test moves on by hand. */
  private class Clock(var now: Long = 0) {
    fun read(): Long = now
  }

  @Test
  fun `an entry lasts its time from when it was put, not a millisecond longer`() {
    val clock = Clock()
    val map = ExpiringMap<String, Int>(5.minutes, clock::read)
    map.put("a", 1)
    clock.now = 5 * 60_000L - 1
    assertEquals(1, map.get("a"))
    clock.now = 5 * 60_000L
    assertNull(map.get("a"))
    // Put again, it lasts from then on
    map.put("a", 2)
    clock.now += 60_000
    assertEquals(2, map.get("a"))
  }

  @Test
  fun `a given now counts instead of the clock's`() {
    val map = ExpiringMap<String, Int>(5.minutes) { error("the clock isn't asked") }
    map.put("a", 1, now = 0)
    assertEquals(1, map.get("a", now = 60_000))
    assertNull(map.get("a", now = 5 * 60_000))
    assertNull(map.remove("a", now = 5 * 60_000))
  }

  @Test
  fun `the expired entries go when one is put, and only then`() {
    val clock = Clock()
    val map = ExpiringMap<String, Int>(5.minutes, clock::read)
    map.put("old", 1)
    clock.now = 4 * 60_000L
    map.put("newer", 2)
    clock.now = 6 * 60_000L
    // Expired, though still there to peek at until the next put
    assertNull(map.get("old"))
    assertEquals(1, map.peek("old"))
    assertEquals(2, map.size)
    map.put("newest", 3)
    assertNull(map.peek("old"))
    assertEquals(2, map.peek("newer"))
    assertEquals(2, map.size)
  }

  @Test
  fun `removing gives the value only while it lasts, and removes it either way`() {
    val clock = Clock()
    val map = ExpiringMap<String, Int>(5.minutes, clock::read)
    map.put("a", 1)
    map.put("b", 2)
    assertEquals(1, map.remove("a"))
    assertNull(map.peek("a"))
    clock.now = 5 * 60_000L
    assertNull(map.remove("b"))
    assertNull(map.peek("b"))
    assertEquals(0, map.size)
  }

  @Test
  fun `put if absent refuses a key that lasts, not one that expired`() {
    val clock = Clock()
    val map = ExpiringMap<String, Unit>(10.minutes, clock::read)
    assertTrue(map.putIfAbsent("jti", Unit))
    clock.now = 10 * 60_000L - 1
    assertFalse(map.putIfAbsent("jti", Unit))
    clock.now = 10 * 60_000L
    assertTrue(map.putIfAbsent("jti", Unit))
    // Taken again: it lasts from then on
    clock.now += 10 * 60_000L - 1
    assertFalse(map.putIfAbsent("jti", Unit))
  }
}
