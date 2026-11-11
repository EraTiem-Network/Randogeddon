package net.eratiem.randogeddon.server.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.database.SessionTime
import net.eratiem.randogeddon.database.SessionTimeStore
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.Room
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.seconds

/**
 * The current seed's session time, as the gateway counts it in the database (how long its game server ran, without the
 * times it was stopped). Read every 10 s; the dashboard counts on by itself while the server runs.
 */
@Single
class SessionClock(private val config: TrackerConfig, private val store: SessionTimeStore) {
  @Volatile
  var current: SessionTime? = null
    private set

  fun watch(scope: CoroutineScope) = scope.launch {
    while (true) {
      current = Room.read(config.roomJson)?.seedName?.let { seed -> runCatching { store.of(seed) }.getOrNull() }
      delay(10.seconds)
    }
  }
}
