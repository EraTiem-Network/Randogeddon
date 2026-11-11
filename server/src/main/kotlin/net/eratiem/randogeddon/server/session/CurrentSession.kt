package net.eratiem.randogeddon.server.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.database.SessionSetup
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.SessionTime
import net.eratiem.randogeddon.database.SessionTimeStore
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.SessionOptions
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.seconds

/**
 * The session of the seed the game server serves (room.json): its session time as the gateway counts it, and, for a
 * session created in the admin panel, its setup (who plays which world, the options). Read every 10 s, and right
 * away after the admin panel changed something ([refresh]).
 */
@Single
class CurrentSession(
  private val config: TrackerConfig,
  private val times: SessionTimeStore,
  private val setups: SessionSetupStore,
  private val sessionFile: SessionFileWatcher,
) {
  @Volatile
  var time: SessionTime? = null
    private set

  @Volatile
  var setup: SessionSetup? = null
    private set

  /** The options it was created with; `null` for a seed deployed with the CLI (the `AP_*` variables apply). */
  val options: SessionOptions?
    get() = setup?.options?.let { runCatching { ApiJson.decodeFromString(SessionOptions.serializer(), it) }.getOrNull() }

  /**
   * Person -> their worlds. From the session's setup (a world played together shows up for each of its users), or
   * from `session.yaml` for a seed deployed with the CLI.
   */
  fun people(): Map<String, List<String>> {
    val worlds = setup?.worlds
    if (worlds.isNullOrEmpty()) return sessionFile.people()
    val people = LinkedHashMap<String, MutableList<String>>()
    for ((slot, users) in worlds) for (user in users) people.getOrPut(user) { mutableListOf() }.add(slot)
    return people
  }

  suspend fun refresh() {
    val seed = Room.read(config.roomJson)?.seedName
    time = seed?.let { runCatching { times.of(it) }.getOrNull() }
    setup = seed?.let { runCatching { setups.of(it) }.getOrNull() }
  }

  fun watch(scope: CoroutineScope) = scope.launch {
    while (true) {
      refresh()
      delay(10.seconds)
    }
  }
}
