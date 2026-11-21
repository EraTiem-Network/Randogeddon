package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.LogLine
import net.eratiem.randogeddon.shared.MinecraftDirectRequest
import net.eratiem.randogeddon.shared.MinecraftLogs
import net.eratiem.randogeddon.shared.MinecraftServers
import net.eratiem.randogeddon.shared.MinecraftStatus
import net.eratiem.randogeddon.webapp.api.load
import net.eratiem.randogeddon.webapp.api.post
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.dashboard.percentEncoded
import net.eratiem.randogeddon.webapp.ui.Dot
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.Working
import net.eratiem.randogeddon.webapp.ui.card

/** How often the section asks how the servers are. */
private const val ASK_MS = 3_000L

/** How many lines of each server's log the page keeps. */
private const val MAX_LINES = 20_000

/**
 * Session [id]'s hosted Minecraft servers, one per Minecraft Dig slot: each one's state, *Start* and *Stop* (its
 * Session-Managers and Administrators), its log ([administrator] only: pages and a search, newest on top). Not shown
 * for a session without a Minecraft Dig slot.
 */
@Composable
fun MinecraftSection(id: String, administrator: Boolean) {
  val p = LocalPalette.current
  val t = LocalTexts.current.sessionAdmin
  var view by remember(id) { mutableStateOf<MinecraftServers?>(null) }
  // Each slot's log and its last line's number (each server numbers its own lines)
  val logs = remember(id) { mutableStateMapOf<String, List<LogLine>>() }
  val last = remember(id) { mutableStateMapOf<String, Long>() }
  val problems = remember(id) { mutableStateMapOf<String, String>() }
  val scope = rememberCoroutineScope()
  suspend fun refresh() {
    load("api/sessions/$id/manage/minecraft", MinecraftServers.serializer())?.let { view = it }
  }
  LaunchedEffect(id, administrator) {
    while (true) {
      refresh()
      val slots = view?.servers.orEmpty().map { it.slot }
      if (administrator && slots.isNotEmpty()) {
        val since = slots.minOf { last[it] ?: 0L }
        load("api/sessions/$id/manage/minecraft/log?since=$since", MinecraftLogs.serializer())?.logs?.forEach { (slot, part) ->
          val from = last[slot] ?: 0L
          val new = part.lines.filter { it.n > from }
          if (new.isNotEmpty()) logs[slot] = (logs[slot].orEmpty() + new).takeLast(MAX_LINES)
          last[slot] = maxOf(from, part.last)
        }
      }
      delay(ASK_MS)
    }
  }
  val now = view?.takeIf { it.servers.isNotEmpty() } ?: return
  Section(t.minecraftSection, subtitle = t.minecraftSectionSubtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      if (!now.hosting) Txt(t.minecraftNotHosted, size = 12.5.sp, color = p.trap)
      for (server in now.servers) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          when (server.status) {
            MinecraftStatus.STARTING -> Working(p.ok)
            MinecraftStatus.RUNNING -> Dot(p.ok)
            MinecraftStatus.ASLEEP -> Dot(p.muted)
            MinecraftStatus.CRASHED -> Dot(p.trap)
          }
          Column(Modifier.weight(1f)) {
            Txt(server.slot, size = 13.5.sp)
            Txt(if (server.direct) t.minecraftState(server.status, server.players.joinToString()) else t.minecraftNotDirect, size = 12.5.sp,
              color = if (server.direct && server.status == MinecraftStatus.CRASHED) p.trap else p.muted)
            (problems[server.slot] ?: server.problem)?.let { Txt(it, size = 12.5.sp, color = p.trap) }
          }
          // Hosted here: switched on by Administrators only, off by Session-Managers too (its server stops)
          fun direct(on: Boolean) = scope.launch {
            post("api/sessions/$id/manage/minecraft/${server.slot.percentEncoded()}/direct", ApiJson.encodeToString(MinecraftDirectRequest.serializer(), MinecraftDirectRequest(on)))
              ?.let { problems[server.slot] = it } ?: problems.remove(server.slot)
            refresh()
          }
          if (server.direct) SmallButton(t.minecraftDontHost, { direct(false) })
          else if (administrator) SmallButton(t.minecraftHostHere, { direct(true) })
          if (now.hosting && server.direct) when (server.status) {
            MinecraftStatus.ASLEEP, MinecraftStatus.CRASHED -> SmallButton(t.minecraftStart, {
              scope.launch {
                post("api/sessions/$id/manage/minecraft/${server.slot.percentEncoded()}/start")
                  ?.let { problems[server.slot] = it } ?: problems.remove(server.slot)
                refresh()
              }
            })
            MinecraftStatus.STARTING, MinecraftStatus.RUNNING -> SmallButton(t.minecraftStop, {
              scope.launch {
                post("api/sessions/$id/manage/minecraft/${server.slot.percentEncoded()}/stop")?.let { problems[server.slot] = it }
                refresh()
              }
            })
          }
        }
        logs[server.slot]?.takeIf { administrator && server.direct && it.isNotEmpty() }?.let {
          SectionLog("minecraft:${server.slot}", "${t.minecraftSection}: ${server.slot}", LogOrder.MINECRAFT, it)
        }
      }
    }
  }
}
