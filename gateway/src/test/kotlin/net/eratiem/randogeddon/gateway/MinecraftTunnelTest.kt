package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinecraftTunnelTest {
  /** A 1.19.4 client's handshake: protocol 762, the address it typed, its port, the next state. */
  private fun handshake(next: Int): ByteArray {
    val body = ByteArrayOutputStream().also {
      writeVarInt(it, 762)
      it.write(mcString("localhost"))
      it.write(byteArrayOf(0x63.toByte(), 0xdd.toByte()))
      writeVarInt(it, next)
    }.toByteArray()
    return packet(0x00, body)
  }

  /** A reader over [chunks], as the tunnel's messages bring them. */
  private fun reader(vararg chunks: ByteArray): PacketReader {
    val left = ArrayDeque(chunks.toList())
    return PacketReader { left.removeFirstOrNull() }
  }

  @Test
  fun `VarInts are written and read as Minecraft has them`() {
    for (value in listOf(0, 1, 127, 128, 255, 25565, 2097151, Int.MAX_VALUE, -1)) {
      val bytes = ByteArrayOutputStream().also { writeVarInt(it, value) }.toByteArray()
      assertEquals(value to bytes.size, readVarInt(bytes, 0))
    }
    assertContentEquals(byteArrayOf(0xdd.toByte(), 0xc7.toByte(), 0x01), ByteArrayOutputStream().also { writeVarInt(it, 25565) }.toByteArray())
    assertEquals(5, ByteArrayOutputStream().also { writeVarInt(it, -1) }.size())
  }

  @Test
  fun `the handshake tells a look at the server list from a join`() = runBlocking {
    assertEquals(1, nextState(reader(handshake(1)).packet()))
    assertEquals(2, nextState(reader(handshake(2)).packet()))
    assertNull(nextState(Packet(byteArrayOf(), 0x05, byteArrayOf())))
  }

  @Test
  fun `packets come whole however the messages cut them, and what follows is kept`() = runBlocking {
    val join = handshake(2)
    val login = packet(0x00, mcString("Erin") + byteArrayOf(0))
    val all = join + login + byteArrayOf(9, 9)
    // One byte per message, then the rest in one
    val r = reader(*(all.take(3).map { byteArrayOf(it) } + listOf(all.copyOfRange(3, all.size))).toTypedArray())
    val first = r.packet()
    assertContentEquals(join, first.raw)
    assertEquals(0x00, first.id)
    val second = r.packet()
    assertContentEquals(login, second.raw)
    assertContentEquals(byteArrayOf(9, 9), r.rest())
  }

  @Test
  fun `a tunnel that ends or sends too much is let go`() = runBlocking {
    assertFailsWith<EndOfTunnel> { reader(handshake(2).copyOf(4)).packet() }
    val tooLong = ByteArrayOutputStream().also { writeVarInt(it, 1 shl 20) }.toByteArray()
    assertFailsWith<IllegalArgumentException> { reader(tooLong).packet() }
    Unit
  }

  @Test
  fun `the answers the client gets while it waits`() = runBlocking {
    val asleep = reader(statusResponse("Randogeddon: asleep")).packet()
    assertEquals(0x00, asleep.id)
    val (length, at) = readVarInt(asleep.body, 0)
    val json = asleep.body.copyOfRange(at, at + length).decodeToString()
    assertTrue(""""protocol":762""" in json && """"text":"Randogeddon: asleep"""" in json, json)

    val waiting = reader(keepWaiting(3)).packet()
    assertEquals(0x04, waiting.id)
    assertEquals(3 to 1, readVarInt(waiting.body, 0))
    assertTrue("randogeddon:starting" in waiting.body.decodeToString())

    assertEquals(0x00, reader(loginDisconnect("x")).packet().id)
  }

  @Test
  fun `a tunnel's path is the session's path, minecraft and the slot`() {
    assertEquals(emptyList<String>() to "Erin-Dig", minecraftPath(listOf("minecraft", "Erin-Dig")))
    assertEquals(listOf("friday") to "Erin Dig", minecraftPath(listOf("friday", "minecraft", "Erin Dig")))
    assertEquals(listOf("minecraft") to "x", minecraftPath(listOf("minecraft", "minecraft", "x")))
    assertNull(minecraftPath(listOf("friday")))
    assertNull(minecraftPath(listOf("minecraft")))
    assertNull(minecraftPath(listOf("friday", "minecraft", "x", "more")))
  }

  @Test
  fun `only the slot's password lets a tunnel in, and not while the slot is locked`() = runBlocking {
    val passwords = suspend { mapOf("Erin-Dig" to "secret", "Erin-HK" to "other") }
    assertTrue(tunnelAllowed("secret", "Erin-Dig", passwords) { emptySet() })
    assertFalse(tunnelAllowed("other", "Erin-Dig", passwords) { emptySet() })
    assertFalse(tunnelAllowed(null, "Erin-Dig", passwords) { emptySet() })
    assertFalse(tunnelAllowed("secret", "Nobody", passwords) { emptySet() })
    assertFalse(tunnelAllowed("secret", "Erin-Dig", passwords) { setOf("Erin-Dig") })
  }
}
