package net.eratiem.randogeddon.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class LogBufferTest {
  @Test
  fun `a big log goes out a part at a time, and only its last lines are kept`() {
    val log = LogBuffer(capacity = 5)
    repeat(7) { log.add("line ${it + 1}") }
    // The first two are gone; numbers go on
    assertEquals((3L..7L).toList(), log.all().map { it.n })
    val first = log.after(0, max = 2)
    assertEquals(listOf("line 3", "line 4"), first.lines.map { it.text })
    assertEquals(4, first.last)
    val rest = log.after(first.last, max = 2)
    assertEquals(listOf("line 5", "line 6"), rest.lines.map { it.text })
    val end = log.after(rest.last, max = 2)
    assertEquals(listOf("line 7"), end.lines.map { it.text })
    assertEquals(7, end.last)
    assertEquals(emptyList(), log.after(end.last, max = 2).lines)
  }
}
