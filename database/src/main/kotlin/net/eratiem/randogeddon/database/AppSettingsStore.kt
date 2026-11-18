package net.eratiem.randogeddon.database

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** The key of how many game servers may run at once ([AppSettingsStore.gameServers]), its default and its highest. */
const val GAME_SERVERS = "game_servers"
const val DEFAULT_GAME_SERVERS = 8
const val MAX_GAME_SERVERS = 100

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

  suspend fun set(key: String, value: String) = db.query {
    val updated = AppSettingsTable.update({ AppSettingsTable.key eq key }) { it[AppSettingsTable.value] = value }
    if (updated == 0) {
      AppSettingsTable.insert {
        it[AppSettingsTable.key] = key
        it[AppSettingsTable.value] = value
      }
    }
    Unit
  }
}
