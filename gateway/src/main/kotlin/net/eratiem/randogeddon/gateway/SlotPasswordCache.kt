package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.eratiem.randogeddon.database.SlotPasswordStore
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.readAttributes
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val logger = LoggerFactory.getLogger(SlotPasswordCache::class.java)
private val REFRESH = 10.seconds

/** How soon passwords that aren't there (none yet, or the database didn't answer) are asked for again. */
private val RETRY = 1.seconds

/**
 * The slot passwords of the seed MultiServer serves (named in room.json, [room]), read with [readPasswords] and
 * [readLocked] at most every 10 s. The tracker creates them when it sees a new seed; until then the map is empty and
 * every Connect is refused, and it is asked again at most once a second ([retry]).
 *
 * The locks don't fail open: when they can't be read for a seed whose locks weren't read before, every slot counts as
 * locked until they can.
 */
class SlotPasswordCache(
  private val readPasswords: suspend (seedName: String) -> Map<String, String>,
  private val readLocked: suspend (seedName: String) -> Set<String>,
  private val room: RoomJson,
  private val retry: Duration = RETRY,
) {
  constructor(store: SlotPasswordStore, room: RoomJson) : this({ store.passwords(it) }, { store.locked(it) }, room)

  private val mutex = Mutex()
  private var seed: String? = null
  private var passwords: Map<String, String> = emptyMap()
  private var locked: Set<String> = emptySet()

  /** Whether [seed]'s passwords and its locks were read once (its locks: until then, a failed read locks every slot). */
  private var passwordsRead = false
  private var locksRead = false

  /** When both were read for [seed]; `null`: never (or invalidated). */
  private var loadedAt: TimeSource.Monotonic.ValueTimeMark? = null

  /** The last try, whatever came of it: what isn't there is asked for at most every [retry]. */
  private var triedAt: TimeSource.Monotonic.ValueTimeMark? = null

  /** The slots still locked (their key not found yet), read with the passwords. */
  suspend fun locked(): Set<String> {
    current()
    return mutex.withLock { locked }
  }

  /** Read again at the next look (a slot's password changed). */
  suspend fun invalidate() = mutex.withLock {
    loadedAt = null
    triedAt = null
  }

  suspend fun current(): Map<String, String> = mutex.withLock {
    val currentSeed = room.seedName()
    if (currentSeed != seed) {
      // Nothing of the seed before holds for this one
      seed = currentSeed
      passwords = emptyMap()
      locked = emptySet()
      passwordsRead = false
      locksRead = false
      loadedAt = null
      triedAt = null
    }
    if (currentSeed == null) return@withLock passwords
    val fresh = loadedAt?.let { it.elapsedNow() < REFRESH } == true && passwords.isNotEmpty()
    val triedLately = triedAt?.let { it.elapsedNow() < retry } == true
    if (!fresh && !triedLately) load(currentSeed)
    passwords
  }

  /** Reads both for [seedName]; what can't be read keeps what was read before (the locks: all locked, if nothing). */
  private suspend fun load(seedName: String) {
    triedAt = TimeSource.Monotonic.markNow()
    val newPasswords = runCatching { readPasswords(seedName) }
      .onFailure { logger.warn("Could not read the slot passwords: {}", it.message) }
      .getOrNull()
    val newLocked = runCatching { readLocked(seedName) }
      .onFailure { logger.warn("Could not read the slot locks: {}", it.message) }
      .getOrNull()
    if (newPasswords != null) {
      if (!passwordsRead) logger.info("Seed {}: {} slot passwords", seedName, newPasswords.size)
      passwords = newPasswords
      passwordsRead = true
    }
    if (newLocked != null) {
      locked = newLocked
      locksRead = true
    } else if (!locksRead) {
      locked = passwords.keys
    }
    if (newPasswords != null && newLocked != null) loadedAt = TimeSource.Monotonic.markNow()
  }
}

/**
 * A session's room.json (written before each start), parsed again only when it changed (its modification time or
 * size): its seed's name and the slot a tracker connects as.
 */
class RoomJson(val path: Path) {
  private class Parsed(val key: Pair<FileTime, Long>, val seedName: String?, val firstSlot: String?)

  @Volatile
  private var parsed: Parsed? = null

  /** The seed's name (not its file name): what the tracker and the database know it by; `null`: no room.json. */
  fun seedName(): String? = read()?.seedName

  /** The first player's slot: the gateway's listener connects as it. */
  fun firstSlot(): String? = read()?.firstSlot

  private fun read(): Parsed? {
    val attributes = runCatching { path.readAttributes<BasicFileAttributes>() }.getOrNull() ?: return null
    val key = attributes.lastModifiedTime() to attributes.size()
    parsed?.takeIf { it.key == key }?.let { return it }
    val room = runCatching { Json.parseToJsonElement(path.readText()) as JsonObject }.getOrNull()
    return Parsed(key, room?.let(::seedNameIn), room?.let(::firstSlotOf)).also { parsed = it }
  }
}

/** The seed's name in room.json (not its file name): what the tracker and the database know it by. */
fun readSeedName(roomJson: Path): String? = runCatching {
  if (!roomJson.exists()) return null
  seedNameIn(Json.parseToJsonElement(roomJson.readText()) as JsonObject)
}.getOrNull()

private fun seedNameIn(room: JsonObject): String? = (room["seed_name"] as? JsonPrimitive)?.content

/** The first player's slot (type 1, the lowest number) of a room.json. */
internal fun firstSlotOf(room: JsonObject): String? = runCatching {
  (room["slots"] as? JsonArray).orEmpty().map { it as JsonObject }
    .filter { (it["type"] as? JsonPrimitive)?.content == "1" }
    .minByOrNull { (it["slot"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Int.MAX_VALUE }
    ?.let { (it["name"] as? JsonPrimitive)?.content }
}.getOrNull()
