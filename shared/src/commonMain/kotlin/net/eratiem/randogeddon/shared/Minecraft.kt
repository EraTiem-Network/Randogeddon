package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The game whose hosted slots can have their Minecraft server run by Randogeddon. */
const val MINECRAFT_DIG = "Minecraft Dig"

/** A hosted Minecraft server (a Minecraft Dig slot's) as the gateway runs it. */
@Serializable
enum class MinecraftStatus {
  /** Not running: it starts when one of its players joins through the bridge. */
  @SerialName("asleep") ASLEEP,
  @SerialName("starting") STARTING,
  @SerialName("running") RUNNING,
  /** Ended on its own (see its log); it starts again when a player joins. */
  @SerialName("crashed") CRASHED,
}

/**
 * Session `{id}`'s hosted Minecraft Dig slot [slot] and its server: [status] since [since] (epoch milliseconds), the
 * Minecraft players on it now. [problem]: why it can't run (Mojang's EULA not accepted, no room for another server).
 * [direct]: Randogeddon runs its server (reached through the bridge's tunnel); else its Session-Managers run their own.
 */
@Serializable
data class MinecraftServerView(
  val slot: String,
  val status: MinecraftStatus,
  val since: Long? = null,
  val players: List<String> = emptyList(),
  val problem: String? = null,
  val direct: Boolean = false,
)

/** `POST /api/sessions/{id}/manage/minecraft/{slot}/direct`: Randogeddon runs the hosted slot's server itself, or not. */
@Serializable
data class MinecraftDirectRequest(val direct: Boolean)

/**
 * `GET /internal/sessions/{id}/minecraft`: its Minecraft Dig slots' servers (none: it has no such slot). [hosting]:
 * the servers can run here (the minecraft service is there and the operator accepted Mojang's EULA).
 */
@Serializable
data class MinecraftServers(val servers: List<MinecraftServerView> = emptyList(), val hosting: Boolean = false)

/** `GET /internal/sessions/{id}/minecraft/log?since=`: each Minecraft Dig slot's server log, by slot. */
@Serializable
data class MinecraftLogs(val logs: Map<String, LogLines> = emptyMap())
