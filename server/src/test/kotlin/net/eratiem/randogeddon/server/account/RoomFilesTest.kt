package net.eratiem.randogeddon.server.account

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RoomFilesTest {
  private fun room(seed: String) = """{"seed_name":"$seed","slots":[{"slot":1,"name":"Alice-HK","game":"Hollow Knight","type":1}]}"""

  @Test
  fun `a room json is read once while it stays as it is, again once it changed, and is gone with its file`() {
    val path = Files.createTempDirectory("rooms").resolve("room.json")
    val rooms = RoomFiles()
    assertNull(rooms.read(path))

    path.writeText(room("111"))
    val first = rooms.read(path)
    assertEquals("111", first?.seedName)
    assertEquals(listOf("Alice-HK"), first?.players?.map { it.name })
    // Not parsed again while the file is the same
    assertSame(first, rooms.read(path))

    // A new seed (its size differs, so even within the same file time)
    path.writeText(room("22222"))
    assertEquals("22222", rooms.read(path)?.seedName)

    path.deleteIfExists()
    assertNull(rooms.read(path))
  }
}
