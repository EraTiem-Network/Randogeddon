package net.eratiem.randogeddon.database

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** What Administrators set for everyone, by key. */
class AppSettingsStore(private val db: RandogeddonDatabase) {
  suspend fun get(key: String): String? = db.query {
    AppSettingsTable.selectAll().where { AppSettingsTable.key eq key }.singleOrNull()?.get(AppSettingsTable.value)
  }

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
