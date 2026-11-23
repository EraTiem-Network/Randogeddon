package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import net.eratiem.randogeddon.webapp.dashboard.dotSegment
import net.eratiem.randogeddon.webapp.dashboard.percentEncoded
import net.eratiem.randogeddon.webapp.ui.Button
import net.eratiem.randogeddon.webapp.ui.Check
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.Select
import net.eratiem.randogeddon.webapp.ui.Selectable
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.rememberAction

/**
 * A running session's slots, for its Session-Managers and Administrators: one at a time, picked in the heading (*Edit
 * slot:*), with who plays it (or, hosted, gets it), *Open to all* and *Hosted*; someone new invited by address. Someone
 * taken off a slot loses its connection: the tracker gives it a new password (asked once more here). Making it hosted
 * is asked once more, no longer hosted twice (a directly hosted Minecraft server stops).
 */
@Composable
fun SlotsSection(id: String) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  var view by remember(id) { mutableStateOf<AdminSessionView?>(null) }
  // The accounts to pick from, with those invited from here
  val people = remember(id) { mutableStateListOf<UserRef>() }
  var chosen by remember(id) { mutableStateOf<String?>(null) }
  var problem by remember(id) { mutableStateOf<String?>(null) }
  fun load() = scope.launch {
    when (val answer = call("GET", "api/sessions/$id/manage", null, AdminSessionView.serializer())) {
      is Answer.Ok -> {
        val loaded = answer.value
        view = loaded
        problem = null
        loaded.people.filter { ref -> people.none { it.id == ref.id } }.let { people.addAll(it) }
      }
      is Answer.Failed -> problem = answer.message
    }
  }
  LaunchedEffect(id) { load() }
  // Not loaded: why, in place of the slots
  if (view == null) {
    problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
    return
  }
  val worlds = view?.session?.worlds?.takeIf { it.isNotEmpty() } ?: return
  val world = worlds.firstOrNull { it.name == chosen } ?: worlds.first()
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // The heading: which slot is edited
    Row(Modifier.heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Txt(t.sessionAdmin.editSlot, size = 17.sp, weight = FontWeight.Bold, maxLines = 1)
      Select(worlds.map { it.name to it.name }, world.name, { chosen = it }, maxLabel = 260.dp)
    }
    // Not in a section: a selection of its own
    Selectable {
      Column(Modifier.fillMaxWidth().card(p).padding(16.dp)) {
        key(world) { SlotEditor(id, world, people, onInvited = { people += it }, onSaved = { load() }) }
      }
    }
  }
}

/** One slot: its players as chips (or *Open to all*), saved together; *Hosted* at the right, switched at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotEditor(id: String, world: WorldPlan, people: List<UserRef>, onInvited: (UserRef) -> Unit, onSaved: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // The slot as saved, and as changed here
  val savedIds = world.userIds
  var ids by remember(world) { mutableStateOf(savedIds) }
  var open by remember(world) { mutableStateOf(world.open) }
  var asking by remember(world) { mutableStateOf(false) }
  // Hosted switched: on asked once (1), off twice (2, then 3)
  var hostedStep by remember(world) { mutableStateOf(0) }
  // Saving, one at a time; its problem (an invitation's too)
  val saving = rememberAction()
  val changed = open != world.open || ids.toSet() != savedIds.toSet()
  // Someone loses it: no longer open, or a player taken off
  val loses = (world.open && !open) || (!open && !world.open && (savedIds - ids.toSet()).isNotEmpty())
  fun save(hosted: Boolean? = null) = saving.run {
    if (dotSegment(world.name)) return@run null
    post(
      "api/sessions/$id/manage/slots/${world.name.percentEncoded()}",
      ApiJson.encodeToString(SlotPlayersRequest.serializer(), SlotPlayersRequest(if (open) emptyList() else ids, open, hosted)),
    ).also { if (it == null) onSaved() }
  }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f)) {
        Txt(world.name, size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
      }
      // A slot named `.` or `..` can't be named in a request's path (the browser resolves it away, encoded too): it
      // isn't changed from here (the tracker takes no such names any more)
      val pathSafe = !dotSegment(world.name)
      if (changed) Button(t.sessionAdmin.slotSave, enabled = !saving.busy && pathSafe, danger = loses) { if (loses) asking = true else save() }
      Check(t.sessionAdmin.openCheck, open, t.sessionAdmin.openTip, enabled = pathSafe) { open = it }
      Check(t.sessionAdmin.hostedCheck, world.hosted, t.sessionAdmin.hostedTip, enabled = pathSafe) { yes -> hostedStep = if (yes) 1 else 2 }
    }
    if (asking) {
      Confirm(t.sessionAdmin.slotLosesQuestion, t.sessionAdmin.slotLosesYes, danger = true,
        onYes = { asking = false; save() }, onCancel = { asking = false })
    }
    when (hostedStep) {
      1 -> Confirm(t.sessionAdmin.hostedOnQuestion, t.sessionAdmin.yesHosted, danger = true,
        onYes = { hostedStep = 0; save(hosted = true) }, onCancel = { hostedStep = 0 })
      2 -> Confirm(t.sessionAdmin.hostedOffQuestion(world.direct), t.sessionAdmin.yesNotHosted, danger = true,
        onYes = { hostedStep = 3 }, onCancel = { hostedStep = 0 })
      3 -> Confirm(t.sessionAdmin.hostedOffSure, t.sessionAdmin.yesNotHosted, danger = true,
        onYes = { hostedStep = 0; save(hosted = false) }, onCancel = { hostedStep = 0 })
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
            is Answer.Failed -> null.also { saving.problem = r.message }
          }
        },
      )
    }
    saving.problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
  }
}
