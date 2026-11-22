package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class SlotPasswordCacheTest {
  private val passwords = mapOf("Alice-HK" to "s3cr3t!", "Bob-Yacht" to "pw")

  private fun room(seed: String): Path =
    Files.createTempDirectory("room").resolve("room.json").also { it.writeText("""{"seed_name":"$seed","slots":[]}""") }

  /** A database stand-in that counts its reads and fails while told to. */
  private class Store(var passwords: Map<String, String>, var locked: Set<String> = emptySet()) {
    var reads = 0
    var passwordsFail = false
    var locksFail = false

    suspend fun passwords(seed: String): Map<String, String> {
      reads++
      if (passwordsFail) error("no database")
      return passwords
    }

    suspend fun locked(seed: String): Set<String> {
      if (locksFail) error("no database")
      return locked
    }
  }

  private fun cache(store: Store, room: Path) =
    SlotPasswordCache({ store.passwords(it) }, { store.locked(it) }, RoomJson(room), retry = 200.milliseconds)

  @Test
  fun `locks that can't be read at first lock every slot, until they can`() = runBlocking {
    val store = Store(passwords, locked = setOf("Bob-Yacht")).apply { locksFail = true }
    val cache = cache(store, room("111"))
    assertEquals(passwords, cache.current())
    assertEquals(passwords.keys, cache.locked())
    store.locksFail = false
    delay(300)
    assertEquals(setOf("Bob-Yacht"), cache.locked())
    // Read once: a later failure keeps what was read
    store.locksFail = true
    cache.invalidate()
    assertEquals(setOf("Bob-Yacht"), cache.locked())
  }

  @Test
  fun `passwords that aren't there yet are asked for at most every so often`() = runBlocking {
    val store = Store(emptyMap())
    val cache = cache(store, room("222"))
    repeat(100) { assertEquals(emptyMap(), cache.current()) }
    assertEquals(1, store.reads)
    // The tracker made them
    store.passwords = passwords
    delay(300)
    assertEquals(passwords, cache.current())
    assertEquals(2, store.reads)
    // Then kept for a while, read again at once when one changed
    repeat(100) { cache.current() }
    assertEquals(2, store.reads)
    cache.invalidate()
    cache.current()
    assertEquals(3, store.reads)
  }

  @Test
  fun `a database that doesn't answer isn't asked at every Connect`() = runBlocking {
    val store = Store(passwords).apply { passwordsFail = true }
    val cache = cache(store, room("333"))
    repeat(100) { assertEquals(emptyMap(), cache.current()) }
    assertEquals(1, store.reads)
  }

  @Test
  fun `a new seed in room json gets its own passwords`() = runBlocking {
    val store = Store(passwords)
    val file = room("444")
    val cache = cache(store, file)
    assertEquals(passwords, cache.current())
    store.passwords = mapOf("Carol-SM" to "x")
    file.writeText("""{"seed_name":"55555","slots":[]}""")
    assertEquals(mapOf("Carol-SM" to "x"), cache.current())
    // No room.json: no seed, no passwords, no reads
    Files.delete(file)
    val reads = store.reads
    assertEquals(emptyMap(), cache.current())
    assertEquals(reads, store.reads)
  }

  @Test
  fun `room json is read for its seed and first slot`() {
    val file = room("666")
    file.writeText(
      """{"seed_name":"666","slots":[{"slot":2,"name":"Bob","type":"1"},{"slot":1,"name":"Alice","type":"1"},{"slot":3,"name":"G","type":"2"}]}""",
    )
    val room = RoomJson(file)
    assertEquals("666", room.seedName())
    assertEquals("Alice", room.firstSlot())
    assertEquals("666", readSeedName(file))
  }
}
