package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.AdminSessionView
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.InviteRequest
import net.eratiem.randogeddon.shared.SlotPlayersRequest
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.WorldPlan
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.api.post
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.dashboard.percentEncoded
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card

/**
 * A running session's slots, for its Session-Managers and Administrators: who plays each one (or, hosted, gets it),
 * changed here, *Open to all* included, and someone new invited by address. Its hosted mode stays as it was created.
 * Someone taken off a slot loses its connection: the tracker gives it a new password (asked once more here).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlotsSection(id: String) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  var view by remember(id) { mutableStateOf<AdminSessionView?>(null) }
  // The accounts to pick from, with those invited from here
  val people = remember(id) { mutableStateListOf<UserRef>() }
  fun load() = scope.launch {
    (call("GET", "api/sessions/$id/manage", null, AdminSessionView.serializer()) as? Answer.Ok)?.value?.let { loaded ->
      view = loaded
      loaded.people.filter { ref -> people.none { it.id == ref.id } }.let { people.addAll(it) }
    }
  }
  LaunchedEffect(id) { load() }
  val worlds = view?.session?.worlds?.takeIf { it.isNotEmpty() } ?: return
  Section(t.sessionAdmin.slotsSection, subtitle = t.sessionAdmin.slotsSectionSubtitle) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      worlds.forEachIndexed { i, world ->
        if (i > 0) Divider()
        SlotEditor(id, world, people, onInvited = { people += it }, onSaved = { load() })
      }
    }
  }
}

/** One slot: its players as chips (or *Open to all*), changed here and saved together. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotEditor(id: String, world: WorldPlan, people: List<UserRef>, onInvited: (UserRef) -> Unit, onSaved: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  // The slot as saved, and as changed here
  val savedIds = world.userIds
  var ids by remember(world) { mutableStateOf(savedIds) }
  var open by remember(world) { mutableStateOf(world.open) }
  var asking by remember(world) { mutableStateOf(false) }
  var problem by remember(world) { mutableStateOf<String?>(null) }
  val changed = open != world.open || ids.toSet() != savedIds.toSet()
  // Someone loses it: no longer open, or a player taken off
  val loses = (world.open && !open) || (!open && !world.open && (savedIds - ids.toSet()).isNotEmpty())
  fun save() = scope.launch {
    problem = post(
      "api/sessions/$id/manage/slots/${world.name.percentEncoded()}",
      ApiJson.encodeToString(SlotPlayersRequest.serializer(), SlotPlayersRequest(if (open) emptyList() else ids, open)),
    )
    if (problem == null) onSaved()
  }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f)) {
        Txt(world.name, size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
        if (world.hosted) Txt(t.sessionAdmin.hostedCheck, size = 11.5.sp, color = p.muted, maxLines = 1)
      }
      Check(t.sessionAdmin.openCheck, open, t.sessionAdmin.openTip) { open = it }
      if (changed) Button(t.sessionAdmin.slotSave, danger = loses) { if (loses) asking = true else save() }
    }
    if (asking) {
      Confirm(t.sessionAdmin.slotLosesQuestion, t.sessionAdmin.slotLosesYes, danger = true,
        onYes = { asking = false; save() }, onCancel = { asking = false })
    }
    when {
      open -> Txt(if (world.hosted) t.sessionAdmin.openHostedLine else t.sessionAdmin.openLine, size = 12.sp, color = p.muted)
      world.hosted -> Txt(t.sessionAdmin.hostedPlayersLine, size = 12.sp, color = p.muted)
    }
    if (!open) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      ids.forEach { userId ->
        val user = people.firstOrNull { it.id == userId }
        UserChip(user?.label ?: userId, invited = user?.name == null, onRemove = { ids = ids - userId })
      }
      PlayerPicker(
        people.filter { it.id !in ids }, if (ids.isEmpty()) t.sessionAdmin.addPlayer else t.sessionAdmin.addAnother,
        onPick = { if (it.id !in ids) ids = ids + it.id },
        onInvite = { email ->
          when (val r = call("POST", "api/sessions/$id/manage/invite", ApiJson.encodeToString(InviteRequest.serializer(), InviteRequest(email.trim(), null)), UserRef.serializer())) {
            is Answer.Ok -> r.value.also { onInvited(it); ids = ids + it.id }
            is Answer.Failed -> null.also { problem = r.message }
          }
        },
      )
    }
    problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
  }
}
