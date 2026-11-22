package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.require
import net.eratiem.randogeddon.shared.MinecraftLogs
import net.eratiem.randogeddon.shared.MinecraftServers
import org.koin.core.annotation.Single

/**
 * A session's hosted Minecraft servers (its Minecraft Dig slots'): their state, started and stopped by hand for its
 * Session-Managers and Administrators, their logs for Administrators. They also start when a player joins through
 * the bridge and sleep without players, as the gateway runs them.
 */
@Single
class MinecraftRuns(private val gameServer: GameServerClient, private val sessions: LiveSessions) {
  suspend fun view(id: String): MinecraftServers = gameServer.minecraft(sessions.require(id).id)

  /** `null`, or why it can't start (the EULA, no room for another server). */
  suspend fun start(id: String, slot: String): String? = gameServer.startMinecraft(sessions.require(id).id, slot)

  suspend fun stop(id: String, slot: String): Boolean = gameServer.stopMinecraft(sessions.require(id).id, slot)

  suspend fun log(id: String, since: Long): MinecraftLogs = gameServer.minecraftLog(sessions.require(id).id, since)
}
