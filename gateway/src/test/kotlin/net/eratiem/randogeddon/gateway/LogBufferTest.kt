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
    // Asked from before the first kept one, or from beyond the last
    assertEquals((3L..7L).toList(), log.after(1).lines.map { it.n })
    assertEquals(emptyList(), log.after(99).lines)
  }

  @Test
  fun `a process's lines are cut as they are read, and end as readLine ends them`() {
    val text = "a".repeat(1 shl 20) + "\nshort\r\nmac\rlast"
    val lines = text.reader().cutLines(MAX_LOG_LINE).toList()
    assertEquals(listOf("a".repeat(MAX_LOG_LINE), "short", "mac", "last"), lines)
    // Empty lines stay; a last line break makes no line of its own
    assertEquals(listOf("", "x"), "\nx\n".reader().cutLines(10).toList())
    assertEquals(emptyList(), "".reader().cutLines(10).toList())
  }
}
