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
  private var loadedAt: TimeSource.Monotonic.ValueTimeMark? = null

  suspend fun current(): Map<String, String> = mutex.withLock {
    val currentSeed = seedName()
    val fresh = loadedAt?.let { it.elapsedNow() < REFRESH } == true
    // Reloaded when stale, for a new seed, and quickly while still empty (the tracker is about to create them)
    if (!fresh || currentSeed != seed || passwords.isEmpty()) {
      passwords = currentSeed?.let { seedName ->
        runCatching { store.passwords(seedName) }
          .onFailure { logger.warn("Could not read the slot passwords: {}", it.message) }
          .getOrDefault(passwords.takeIf { currentSeed == seed }.orEmpty())
      }.orEmpty()
      if (currentSeed != seed) logger.info("Seed {}: {} slot passwords", currentSeed, passwords.size)
      seed = currentSeed
      loadedAt = TimeSource.Monotonic.markNow()
    }
    passwords
  }

  private fun seedName(): String? = runCatching {
    if (!roomJson.exists()) return null
    ((Json.parseToJsonElement(roomJson.readText()) as JsonObject)["seed_name"] as? JsonPrimitive)?.content
  }.getOrNull()
}
