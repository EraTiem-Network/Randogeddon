package net.eratiem.randogeddon.server.account

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteLocksTest {
  @Test
  fun `the gateway is told of new locks only once they are written`() = runBlocking<Unit> {
    val steps = mutableListOf<String>()
    val result = writeLocks("brave-otter", { steps += "written" }, { steps += "told" })
    assertTrue(result.isSuccess)
    assertEquals(listOf("written", "told"), steps)
  }

  @Test
  fun `a gateway that can't be told leaves the locks written`() = runBlocking<Unit> {
    val steps = mutableListOf<String>()
    val result = writeLocks("brave-otter", { steps += "written" }, { error("the gateway can't be reached") })
    assertTrue(result.isSuccess)
    assertEquals(listOf("written"), steps)
  }

  @Test
  fun `locks that can't be written aren't told to the gateway`() = runBlocking<Unit> {
    val steps = mutableListOf<String>()
    val result = writeLocks("brave-otter", { error("no database") }, { steps += "told" })
    assertTrue(result.isFailure)
    assertEquals(emptyList(), steps)
  }
}
