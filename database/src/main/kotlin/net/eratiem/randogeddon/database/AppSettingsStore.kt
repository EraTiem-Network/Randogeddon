package net.eratiem.randogeddon.database

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** The key of how many game servers may run at once ([AppSettingsStore.gameServers]), its default and its highest. */
const val GAME_SERVERS = "game_servers"
const val DEFAULT_GAME_SERVERS = 8
const val MAX_GAME_SERVERS = 100

/** The key of how long a game server runs without players before it sleeps ([AppSettingsStore.idleMinutes]). */
const val IDLE_MINUTES = "idle_minutes"
const val DEFAULT_IDLE_MINUTES = 30
const val MAX_IDLE_MINUTES = 24 * 60

/** The key of how many live streams a share link's viewers may hold per address ([AppSettingsStore.watchStreams]). */
const val WATCH_STREAMS = "watch_streams"
const val DEFAULT_WATCH_STREAMS = 20
const val MAX_WATCH_STREAMS = 1000

/** What Administrators set for everyone, by key. */
class AppSettingsStore(private val db: RandogeddonDatabase) {
  suspend fun get(key: String): String? = db.query {
    AppSettingsTable.selectAll().where { AppSettingsTable.key eq key }.singleOrNull()?.get(AppSettingsTable.value)
  }

  /**
   * How many game servers may run at once, of every session; 0: no limit. The tracker keeps to it when one starts from
   * the panel, the gateway too (at its own start as well).
   */
  suspend fun gameServers(): Int = get(GAME_SERVERS)?.toIntOrNull()?.takeIf { it in 0..MAX_GAME_SERVERS } ?: DEFAULT_GAME_SERVERS

  /**
   * How many minutes a game server runs without a player before it sleeps (it starts again when one connects); 0:
   * never. A session an Administrator keeps running doesn't sleep.
   */
  suspend fun idleMinutes(): Int = get(IDLE_MINUTES)?.toIntOrNull()?.takeIf { it in 0..MAX_IDLE_MINUTES } ?: DEFAULT_IDLE_MINUTES

  /**
   * How many live streams by share links one address may hold at once (an IPv6 network as one); 0: no limit. Many
   * viewers can share an address (a household, a venue's network).
   */
  suspend fun watchStreams(): Int =
    get(WATCH_STREAMS)?.toIntOrNull()?.takeIf { it in 0..MAX_WATCH_STREAMS } ?: DEFAULT_WATCH_STREAMS

  /** Sets [key] to [value]; two at once for a new key: the later one's value stays, neither fails. */
  suspend fun set(key: String, value: String) = db.query {
    fun updated() = AppSettingsTable.update({ AppSettingsTable.key eq key }) { it[AppSettingsTable.value] = value } > 0
    // None yet: made; made by another at the same moment: this value after all
    if (!updated() && AppSettingsTable.insertIgnore {
        it[AppSettingsTable.key] = key
        it[AppSettingsTable.value] = value
      }.insertedCount == 0
    ) updated()
    Unit
  }
}
