package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.eratiem.randogeddon.database.SlotPasswordStore
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val logger = LoggerFactory.getLogger(SlotPasswordCache::class.java)
private val REFRESH = 10.seconds

/**
 * The slot passwords of the seed MultiServer serves (named in room.json), read from the database at most every 10 s.
 * The tracker creates them when it sees a new seed; until then the map is empty and every Connect is refused.
 */
class SlotPasswordCache(private val store: SlotPasswordStore, private val roomJson: Path) {
  private val mutex = Mutex()
  private var seed: String? = null
  private var passwords: Map<String, String> = emptyMap()
  private var locked: Set<String> = emptySet()
  private var loadedAt: TimeSource.Monotonic.ValueTimeMark? = null

  /** The slots still locked (their key not found yet), read with the passwords. */
  suspend fun locked(): Set<String> {
    current()
    return mutex.withLock { locked }
  }

  suspend fun current(): Map<String, String> = mutex.withLock {
    val currentSeed = readSeedName(roomJson)
    val fresh = loadedAt?.let { it.elapsedNow() < REFRESH } == true
    // Reloaded when stale, for a new seed, and quickly while still empty (the tracker is about to create them)
    if (!fresh || currentSeed != seed || passwords.isEmpty()) {
      passwords = currentSeed?.let { seedName ->
        runCatching { store.passwords(seedName) }
          .onFailure { logger.warn("Could not read the slot passwords: {}", it.message) }
          .getOrDefault(passwords.takeIf { currentSeed == seed }.orEmpty())
      }.orEmpty()
      locked = currentSeed?.let { seedName -> runCatching { store.locked(seedName) }.getOrDefault(locked) }.orEmpty()
      if (currentSeed != seed) logger.info("Seed {}: {} slot passwords", currentSeed, passwords.size)
      seed = currentSeed
      loadedAt = TimeSource.Monotonic.markNow()
    }
    passwords
  }
}

/** The seed's name in room.json (not its file name): what the tracker and the database know it by. */
fun readSeedName(roomJson: Path): String? = runCatching {
  if (!roomJson.exists()) return null
  ((Json.parseToJsonElement(roomJson.readText()) as JsonObject)["seed_name"] as? JsonPrimitive)?.content
}.getOrNull()
