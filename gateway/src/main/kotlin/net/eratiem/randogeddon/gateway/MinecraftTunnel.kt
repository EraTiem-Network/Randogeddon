package net.eratiem.randogeddon.gateway

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.eratiem.randogeddon.shared.MinecraftStatus
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.MinecraftTunnel")

/** Minecraft 1.19.4's protocol number, for the server list's answer while the server sleeps. */
private const val PROTOCOL_1_19_4 = 762

/** The most a first packet may be (a handshake or a login start is a few dozen bytes). */
private const val MAX_FIRST_PACKET = 4096

/** How long a join waits for its server, and how often the client hears something meanwhile. */
private val START_WAIT = 4.minutes
private val KEEP_WAITING = 10.seconds

/** One Minecraft packet as it came: its bytes (with its length), its ID, its body. */
internal class Packet(val raw: ByteArray, val id: Int, val body: ByteArray)

/** Bytes read from a tunnel's messages, packet by packet (before the server takes over: no compression yet). */
internal class PacketReader(private val next: suspend () -> ByteArray?) {
  private var buffer = ByteArray(0)
  private var at = 0

  private suspend fun byte(): Int {
    while (at >= buffer.size) {
      val more = next() ?: throw EndOfTunnel()
      buffer = buffer.copyOfRange(at, buffer.size) + more
      at = 0
    }
    return buffer[at++].toInt() and 0xff
  }

  private suspend fun varInt(): Int {
    var value = 0
    for (i in 0 until 5) {
      val b = byte()
      value = value or ((b and 0x7f) shl (7 * i))
      if (b and 0x80 == 0) return value
    }
    throw IllegalStateException("a VarInt longer than 5 bytes")
  }

  /** The next packet. */
  suspend fun packet(): Packet {
    val start = ByteArrayOutputStream()
    val length = varInt()
    require(length in 1..MAX_FIRST_PACKET) { "a packet of $length bytes" }
    writeVarInt(start, length)
    val body = ByteArray(length) { byte().toByte() }
    start.write(body)
    val (id, idLength) = readVarInt(body, 0)
    return Packet(start.toByteArray(), id, body.copyOfRange(idLength, body.size))
  }

  /** What was read beyond the last packet (sent on to the server with the held packets). */
  fun rest(): ByteArray = buffer.copyOfRange(at, buffer.size)
}

internal class EndOfTunnel : Exception("the tunnel ended")

/** The header with the slot's password that Randogeddon Bridge's tunnel sends. */
const val SLOT_PASSWORD_HEADER = "X-Randogeddon-Slot-Password"

/**
 * A tunnel's path, `…/minecraft/<slot>` (after the session's path, if it has one): the path before it and the slot;
 * `null`: not a tunnel's.
 */
fun minecraftPath(path: List<String>): Pair<List<String>, String>? {
  val parts = path.filter { it.isNotEmpty() }
  if (parts.size < 2 || parts[parts.size - 2] != "minecraft") return null
  return parts.dropLast(2) to parts.last()
}

/** Whether [given] is session's slot [slot]'s password, and the slot isn't locked. */
internal suspend fun tunnelAllowed(given: String?, slot: String, passwords: suspend () -> Map<String, String>, locked: suspend () -> Set<String>): Boolean {
  val expected = passwords()[slot] ?: return false
  if (given == null || !MessageDigest.isEqual(given.encodeToByteArray(), expected.encodeToByteArray())) return false
  return slot !in locked()
}

internal fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
  var v = value
  while (true) {
    if (v and 0x7f.inv() == 0) {
      out.write(v)
      return
    }
    out.write((v and 0x7f) or 0x80)
    v = v ushr 7
  }
}

/** A VarInt at [from] in [bytes]: its value and its length. */
internal fun readVarInt(bytes: ByteArray, from: Int): Pair<Int, Int> {
  var value = 0
  for (i in 0 until 5) {
    val b = bytes[from + i].toInt() and 0xff
    value = value or ((b and 0x7f) shl (7 * i))
    if (b and 0x80 == 0) return value to i + 1
  }
  throw IllegalStateException("a VarInt longer than 5 bytes")
}

/** A packet of [id] with [body], as it goes over the wire (no compression). */
internal fun packet(id: Int, body: ByteArray): ByteArray {
  val inner = ByteArrayOutputStream().also { writeVarInt(it, id); it.write(body) }.toByteArray()
  return ByteArrayOutputStream().also { writeVarInt(it, inner.size); it.write(inner) }.toByteArray()
}

internal fun mcString(text: String): ByteArray {
  val bytes = text.encodeToByteArray()
  return ByteArrayOutputStream().also { writeVarInt(it, bytes.size); it.write(bytes) }.toByteArray()
}

/** The handshake's next state: 1 the server list's look, 2 a join; `null`: not a handshake. */
internal fun nextState(handshake: Packet): Int? {
  if (handshake.id != 0) return null
  var at = readVarInt(handshake.body, 0).second // protocol
  val (addressLength, lengthBytes) = readVarInt(handshake.body, at)
  at += lengthBytes + addressLength + 2 // address, port
  return readVarInt(handshake.body, at).first
}

/** The server list's answer while the server doesn't run: its state as the description. */
internal fun statusResponse(text: String): ByteArray = packet(
  0x00,
  mcString(
    buildJsonObject {
      putJsonObject("version") { put("name", "1.19.4"); put("protocol", PROTOCOL_1_19_4) }
      putJsonObject("players") { put("max", 20); put("online", 0) }
      putJsonObject("description") { put("text", text) }
    }.toString(),
  ),
)

/** A login's end with [text] shown to the player (before the server took over). */
internal fun loginDisconnect(text: String): ByteArray =
  packet(0x00, mcString(buildJsonObject { put("text", JsonPrimitive(text)) }.toString()))

/** A login plugin request on a channel nobody knows: the client answers "not understood", and keeps waiting. */
internal fun keepWaiting(messageId: Int): ByteArray {
  val body = ByteArrayOutputStream().also { writeVarInt(it, messageId); it.write(mcString("randogeddon:starting")) }.toByteArray()
  return packet(0x04, body)
}

/**
 * A Minecraft connection that came through Randogeddon Bridge's tunnel for session [id]'s Minecraft Dig slot [slot]
 * (its password checked already). Only its first packets are read here: the server list's look is answered while the
 * server sleeps or starts (nothing starts for it); a join starts the server and is held, the client kept waiting with
 * a login plugin request every [KEEP_WAITING], until the server is ready. Then the held packets go on to the server
 * and from there every byte passes both ways: the account check and the encryption are the server's.
 */
suspend fun minecraftTunnel(ws: WebSocketSession, minecraft: Minecraft, id: String, slot: String) {
  val reader = PacketReader { (ws.incoming.receiveCatching().getOrNull() as? Frame.Binary)?.readBytes() }
  suspend fun send(bytes: ByteArray) = ws.send(Frame.Binary(true, bytes))
  try {
    val handshake = reader.packet()
    when (nextState(handshake)) {
      1 -> {
        // Running: the server answers itself
        if (minecraft.address(id, slot) != null) return pass(ws, minecraft, id, slot, listOf(handshake.raw), reader.rest())
        val starting = minecraft.servers(id).servers.firstOrNull { it.slot == slot }?.status == MinecraftStatus.STARTING
        reader.packet() // the status request
        send(statusResponse(if (starting) "Randogeddon: starting…" else "Randogeddon: asleep, join to wake it"))
        withTimeoutOrNull(5.seconds) { reader.packet() }?.takeIf { it.id == 0x01 }?.let { send(packet(0x01, it.body)) }
      }

      2 -> {
        val login = reader.packet()
        if (minecraft.address(id, slot) == null) {
          minecraft.start(id, slot)?.let { why -> send(loginDisconnect("Randogeddon: $why")); return }
          logger.info("A join for {}'s {}: its Minecraft server starts, the join waits", id, slot)
          var asked = 0
          var lastAsked = TimeSource.Monotonic.markNow() - KEEP_WAITING
          val ready = withTimeoutOrNull(START_WAIT) {
            while (minecraft.address(id, slot) == null) {
              if (lastAsked.elapsedNow() >= KEEP_WAITING) {
                send(keepWaiting(++asked))
                lastAsked = TimeSource.Monotonic.markNow()
              }
              delay(1.seconds)
            }
            true
          }
          if (ready == null) {
            send(loginDisconnect("Randogeddon: the Minecraft server didn't start in time. Join again in a moment."))
            return
          }
          // The client's answers (login plugin responses, nothing else comes while it waits) are read only now, whole,
          // and dropped: the server never asked
          for (i in 1..asked) {
            val answer = reader.packet()
            require(answer.id == 0x02) { "a packet ${answer.id} while waiting" }
          }
        }
        pass(ws, minecraft, id, slot, listOf(handshake.raw, login.raw), reader.rest())
      }

      else -> Unit
    }
  } catch (_: EndOfTunnel) {
    // The client went
  } catch (_: ClosedSendChannelException) {
    // The client went while it waited
  } catch (e: IllegalArgumentException) {
    logger.debug("A tunnel for {}'s {}: {}", id, slot, e.message)
  } finally {
    ws.close(CloseReason(CloseReason.Codes.NORMAL, ""))
  }
}

/** The server takes over: [held] first, then every byte both ways until either side ends. */
private suspend fun pass(ws: WebSocketSession, minecraft: Minecraft, id: String, slot: String, held: List<ByteArray>, rest: ByteArray) {
  val (host, port) = minecraft.address(id, slot) ?: return
  SelectorManager(Dispatchers.IO).use { selector ->
    aSocket(selector).tcp().connect(host, port).use { socket ->
      val toServer = socket.openWriteChannel(autoFlush = true)
      val fromServer = socket.openReadChannel()
      held.forEach { toServer.writeFully(it) }
      if (rest.isNotEmpty()) toServer.writeFully(rest)
      coroutineScope {
        val down = launch {
          val buffer = ByteArray(16 * 1024)
          while (true) {
            val n = fromServer.readAvailable(buffer)
            if (n < 0) break
            if (n > 0) ws.send(Frame.Binary(true, buffer.copyOf(n)))
          }
          ws.close(CloseReason(CloseReason.Codes.NORMAL, ""))
        }
        for (frame in ws.incoming) {
          if (frame is Frame.Binary) toServer.writeFully(frame.readBytes())
        }
        down.cancel()
      }
    }
  }
}
