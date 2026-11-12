package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.AdminSessionView
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CreateSessionRequest
import net.eratiem.randogeddon.shared.CreateSessionResult
import net.eratiem.randogeddon.shared.InspectRequest
import net.eratiem.randogeddon.shared.InspectResult
import net.eratiem.randogeddon.shared.NewLogin
import net.eratiem.randogeddon.shared.PastSession
import net.eratiem.randogeddon.shared.KeepRequest
import net.eratiem.randogeddon.shared.SessionInfo
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.UploadedYaml
import net.eratiem.randogeddon.shared.WorldPlan
import net.eratiem.randogeddon.shared.YamlWorld

/** Archipelago's choices for release and collect (see .env.example). */
private val RELEASE_MODES = listOf("auto", "enabled", "disabled", "goal", "auto-enabled")
private val REMAINING_MODES = listOf("enabled", "disabled", "goal")

/** What each option of a new session does, for its info tip. */
private object Tips {
  const val PLAYERS = "Who plays this slot. A slot with several players is played together: they share it and its " +
    "slot password. A name without a login gets one when the session is created."
  const val LOCKS = "A locked slot can't be played until its key is found, somewhere in another slot (World Locks)."
  val LOCK_MODES = listOf(
    "Choose" to "Tick \"starts locked\" for each slot that waits for its key.",
    "Random" to "The given number of slots, picked at random, start open; all others start locked.",
  )
  const val OPEN_SLOTS = "How many slots start open; at least one, and at least one stays locked. Which ones is " +
    "picked at random when the session is generated."
  const val STARTS_LOCKED = "This slot waits for its key, which lies somewhere in another slot."
  const val POINT_HINTS = "Players buy hints with !hint in their client and pay with points they earn by checking " +
    "locations. Off: !hint and !hint_location are refused; the server console can still give hints."
  const val AUTO_HINTS = "Games may create hints by themselves, e.g. a shop that shows whose item it sells. Off: " +
    "those only show the item to the game, no hint is made."
  const val EARLY_KEYS = "The keys of the locked slots are placed early: somewhere reachable from the start, so " +
    "the locked slots open soon instead of whenever the logic needs them."
  const val HINT_COST = "What one hint costs, in % of the slot's locations. With 10 % and 200 locations a hint " +
    "costs 20 points. 0: hints are free."
  const val CHECK_POINTS = "Points a slot earns for each location it checks; hints are paid with them."
  const val RELEASE = "When a slot's remaining items are sent to their owners."
  val RELEASE_CHOICES = listOf(
    "auto" to "By itself when the slot reaches its goal.",
    "enabled" to "Any time, with !release.",
    "disabled" to "Never; only an admin can, with a cheat.",
    "goal" to "With !release, once the slot reached its goal.",
    "auto-enabled" to "By itself at the goal, and any time with !release.",
  )
  const val COLLECT = "When a slot gets its own items that are still in other slots."
  val COLLECT_CHOICES = listOf(
    "auto" to "By itself when the slot reaches its goal.",
    "enabled" to "Any time, with !collect.",
    "disabled" to "Never; only an admin can, with a cheat.",
    "goal" to "With !collect, once the slot reached its goal.",
    "auto-enabled" to "By itself at the goal, and any time with !collect.",
  )
  const val REMAINING = "Whether a player may ask which of their items are still out there."
  val REMAINING_CHOICES = listOf(
    "enabled" to "Any time.",
    "disabled" to "Never.",
    "goal" to "Once the slot reached its goal.",
  )
  const val RELEASE_VERSION = "The Archipelago release that generates and serves this session; the session stays on " +
    "it. Which games it has is listed under APWorlds."
  const val CHEAT_HINTS = "Admins may give free hints from the admin panel's cheats. Every one is logged and shown in " +
    "the live feed. Giving items and checks works without this."
  const val PAUSE = "Pausing stops the game server: the session is read-only, the dashboard shows how far it got, " +
    "and its players see the spoiler log. It can be reopened within 14 days."
  const val END = "Every check is done. Ending stops the game server for good: the dashboard keeps showing how it " +
    "ended, and its players see the spoiler log. A completed session can't be reopened. It also ends by itself 2 " +
    "minutes after every slot is done."
  const val REOPEN = "A finished session can be reopened within 14 days, unless it was completed (100 %): the game " +
    "server serves it again. Reopening a past session archives the current one, which has to be finished."
  const val KEEP = "A past session is deleted by itself some time after it finished (SESSION_RETENTION_DAYS, 90 by " +
    "default). With its auto-deletion disabled it stays until an admin deletes it; enabled again, the time counts " +
    "from then."
  const val SPOILER = "The spoiler log shows where every item of the seed is, and the playthrough sphere by sphere. " +
    "Players see it once the session is finished."
}

/** POST without a body; the error message, or `null`. */
private suspend fun post(path: String, body: String? = null): String? =
  (call("POST", path, body) { } as? Answer.Failed)?.message

/** An answer of the admin API: the decoded body, or the error message. */
internal sealed interface Answer<out T> {
  data class Ok<T>(val value: T) : Answer<T>
  data class Failed(val message: String) : Answer<Nothing>
}

internal suspend fun <T> call(method: String, path: String, body: String?, decode: (String) -> T): Answer<T> {
  val result = Platform.request(method, path, body) ?: return Answer.Failed("The server can't be reached")
  if (result.status in 200..299) return runCatching { Answer.Ok(decode(result.body)) }.getOrElse { Answer.Failed("Unexpected answer") }
  val message = runCatching { ApiJson.decodeFromString(ApiError.serializer(), result.body).message }.getOrNull()
  return Answer.Failed(message ?: "Failed (${result.status})")
}

/**
 * The admin panel's session: without one, the form to create it (YAMLs, who plays which slot, locks, options);
 * with one, what it is and a delete button. [onChanged] after creating or deleting (the game server restarts).
 */
@Composable
fun SessionSection(onChanged: () -> Unit, onSpoiler: (String) -> Unit) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var view by remember { mutableStateOf<AdminSessionView?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  // The logins of a session just created: shown once
  var created by remember { mutableStateOf<CreateSessionResult?>(null) }
  fun load() = scope.launch {
    when (val answer = call("GET", "api/admin/session", null) { ApiJson.decodeFromString(AdminSessionView.serializer(), it) }) {
      is Answer.Ok -> { view = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  LaunchedEffect(Unit) { load() }

  // The finished sessions, for reopening and deleting them
  var past by remember { mutableStateOf(emptyList<PastSession>()) }
  fun loadPast() = scope.launch {
    (call("GET", "api/sessions", null) { ApiJson.decodeFromString(ListSerializer(PastSession.serializer()), it) } as? Answer.Ok)
      ?.let { past = it.value }
  }
  LaunchedEffect(Unit) { loadPast() }
  fun act(path: String, body: String? = null) = scope.launch {
    problem = post(path, body)
    load()
    loadPast()
    onChanged()
  }

  val current = view
  val session = current?.session
  Section("Session", subtitle = session?.let(::sessionDetails) ?: "No session yet") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      created?.let { result ->
        NewLogins(result.logins, result.locked, onDone = { created = null })
      }
      val onCreated: (CreateSessionResult) -> Unit = { result ->
        created = result
        load()
        loadPast()
        onChanged()
      }
      when {
        current == null -> Txt("…", color = p.muted)
        session != null -> {
          SessionSummary(
            session,
            onDelete = {
              scope.launch {
                when (val answer = call("DELETE", "api/admin/session", null) { }) {
                  is Answer.Ok -> { load(); loadPast(); onChanged() }
                  is Answer.Failed -> problem = answer.message
                }
              }
            },
            onFinish = { act("api/admin/session/finish") },
            onCancelFinish = { act("api/admin/session/finish/cancel") },
            onReopen = { act("api/admin/sessions/${session.seedName}/reopen") },
            onKeep = { keep ->
              act("api/admin/sessions/${session.seedName}/keep", ApiJson.encodeToString(KeepRequest.serializer(), KeepRequest(keep)))
            },
            onSpoiler = { onSpoiler(session.seedName) },
          )
          // A finished session makes way for the next one
          if (session.finishedAt != null) {
            Divider()
            Txt("The next session", size = 14.sp, weight = FontWeight.SemiBold)
            Txt("Creating it keeps this one as a past session.", size = 12.sp, color = p.muted)
            CreateSession(current, onCreated)
          }
        }
        else -> CreateSession(current, onCreated)
      }
    }
  }
  val older = past.filter { !it.current }
  if (older.isNotEmpty()) {
    PastSessionsAdmin(
      older,
      onReopen = { act("api/admin/sessions/$it/reopen") },
      onKeep = { seed, keep ->
        act("api/admin/sessions/$seed/keep", ApiJson.encodeToString(KeepRequest.serializer(), KeepRequest(keep)))
      },
      onDelete = { seed ->
        scope.launch {
          problem = (call("DELETE", "api/admin/sessions/$seed", null) { } as? Answer.Failed)?.message
          loadPast()
        }
      },
      onSpoiler = onSpoiler,
    )
  }
}

/** Archipelago release, when it was created, the main options: the session section's subtitle. */
private fun sessionDetails(session: SessionInfo): String = listOfNotNull(
  session.apVersion?.let { "Archipelago $it" } ?: "deployed with the CLI",
  session.createdAt?.let { "created ${Platform.date(it)}" },
  session.options?.let { o ->
    listOfNotNull(
      if (o.pointHints) "point hints (${o.hintCost} %)" else null,
      if (o.autoHints) "automatic hints" else null,
      "release ${o.releaseMode}",
    ).joinToString(", ")
  },
).joinToString(" · ")

/** When a finished session is deleted by itself, or that it isn't. */
private fun deletion(deletesAt: Long?, keep: Boolean): String? = when {
  keep -> "auto-deletion disabled"
  deletesAt != null -> "deleted automatically on ${Platform.date(deletesAt)}"
  else -> null
}

/** Reopening is possible until [until]; `null`: not at all (it was completed). */
private fun reopenable(until: Long?): Boolean = until != null && Platform.now() < until

/** A question before something is done, in place of the buttons: Yes (red if [danger]) or Cancel. */
@Composable
private fun Confirm(question: String, yes: String, danger: Boolean, onYes: () -> Unit, onCancel: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Txt(question, size = 13.sp, modifier = Modifier.weight(1f))
    Button(yes, danger = danger) { onYes() }
    Button("Cancel") { onCancel() }
  }
}

/** The finished sessions that are no longer the current one: spoiler, reopen, auto-deletion, delete. */
@Composable
private fun PastSessionsAdmin(
  sessions: List<PastSession>,
  onReopen: (String) -> Unit,
  onKeep: (String, Boolean) -> Unit,
  onDelete: (String) -> Unit,
  onSpoiler: (String) -> Unit,
) {
  val p = LocalPalette.current
  // (seed, "delete" or "keep"): the question shown in place of that session's buttons
  var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
  Section("Past sessions", subtitle = "deleted automatically after a while, unless that is disabled", actions = {
    InfoTip(Tips.KEEP)
  }) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      sessions.forEachIndexed { i, past ->
        if (i > 0) Divider()
        val seed = past.seedName
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.weight(1f)) {
            Txt("Seed $seed", size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
            Txt(
              listOfNotNull(
                "finished ${Platform.date(past.finishedAt)}" + (past.finishedBy?.let { " by $it" } ?: ""),
                "${oneDecimal(past.percent)}%",
                past.players.joinToString(", ").ifEmpty { null },
                deletion(past.deletesAt, past.keep),
              ).joinToString(" · "),
              size = 12.sp, color = p.muted, maxLines = 2,
            )
          }
          if (pending?.first != seed) {
            if (reopenable(past.reopenUntil)) Tip(Tips.REOPEN) { SmallButton("Reopen", { onReopen(seed) }) }
            Tip(Tips.SPOILER) { SmallButton("Spoiler log", { onSpoiler(seed) }) }
            Tip(Tips.KEEP) {
              SmallButton(if (past.keep) "Enable auto-deletion" else "Disable auto-deletion", { pending = seed to "keep" })
            }
            SmallButton("Delete", { pending = seed to "delete" })
          }
        }
        when (pending?.takeIf { it.first == seed }?.second) {
          "delete" -> Confirm(
            "Delete it for good? Its seed, save and history go; the logins stay.", "Yes, delete", danger = true,
            onYes = { pending = null; onDelete(seed) }, onCancel = { pending = null },
          )
          "keep" -> Confirm(
            if (past.keep) "Enable its auto-deletion again? Its time counts from now."
            else "Disable its auto-deletion? It stays until an admin deletes it.",
            if (past.keep) "Yes, enable" else "Yes, disable", danger = false,
            onYes = { pending = null; onKeep(seed, !past.keep) }, onCancel = { pending = null },
          )
        }
      }
    }
  }
}

/**
 * The current session: who plays which slot, how it stands (it ends by itself soon, or it is paused or ended), and one
 * line of actions: pause or end it (reopen it), its auto-deletion, delete it, and on the right its spoiler log. What
 * needs a second thought asks in place of that line.
 */
@Composable
private fun SessionSummary(
  session: SessionInfo,
  onDelete: () -> Unit,
  onFinish: () -> Unit,
  onCancelFinish: () -> Unit,
  onReopen: () -> Unit,
  onKeep: (Boolean) -> Unit,
  onSpoiler: () -> Unit,
) {
  val p = LocalPalette.current
  // "finish", "spoiler", "delete" or "keep": the question shown instead of the actions
  var pending by remember { mutableStateOf<String?>(null) }
  session.worlds.forEach { world ->
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(world.name, size = 13.sp, weight = FontWeight.SemiBold, modifier = Modifier.width(180.dp), maxLines = 1)
      Txt(world.users.joinToString(", ").ifEmpty { "nobody" }, size = 13.sp, color = if (world.users.isEmpty()) p.muted else p.text)
    }
  }
  val finishedAt = session.finishedAt
  val finishesAt = session.finishesAt
  val complete = session.percent >= 100.0
  val until = session.reopenUntil
  when {
    finishedAt != null -> Txt(
      listOfNotNull(
        "${if (complete) "Ended" else "Paused"} ${Platform.date(finishedAt)}.",
        when {
          until == null -> "It was completed, so it can't be reopened."
          reopenable(until) -> "It can be reopened until ${Platform.date(until)}."
          else -> "It was paused more than 14 days ago and can't be reopened any more."
        },
        deletion(session.deletesAt, session.keep)?.let { "Once a newer session exists: ${it.replaceFirstChar { c -> c.lowercase() }}." },
      ).joinToString(" "),
      size = 13.sp,
    )
    finishesAt != null -> Txt("Every slot is done: it ends by itself at ${Platform.clock(finishesAt)}.", size = 13.sp)
  }
  when (pending) {
    "finish" -> Confirm(
      if (complete) "End it? The game server stops for good; a completed session can't be reopened."
      else "Pause it? The game server stops; it can be reopened within 14 days.",
      if (complete) "Yes, end it" else "Yes, pause it", danger = true,
      onYes = { pending = null; onFinish() }, onCancel = { pending = null },
    )
    "spoiler" -> Confirm(
      "A full spoiler: where every item of the running session is. Show it?", "Show it", danger = true,
      onYes = { pending = null; onSpoiler() }, onCancel = { pending = null },
    )
    "delete" -> Confirm(
      "Delete it? The game server stops; the seed and its save go to data/old. The logins stay.", "Yes, delete", danger = true,
      onYes = { pending = null; onDelete() }, onCancel = { pending = null },
    )
    "keep" -> Confirm(
      if (session.keep) "Enable its auto-deletion again? Its time counts from now." else "Disable its auto-deletion? It stays until an admin deletes it.",
      if (session.keep) "Yes, enable" else "Yes, disable", danger = false,
      onYes = { pending = null; onKeep(!session.keep) }, onCancel = { pending = null },
    )
    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      when {
        finishedAt != null -> if (reopenable(until)) Tip(Tips.REOPEN) { Button("Reopen") { onReopen() } }
        finishesAt != null -> {
          Tip(Tips.END) { Button("End now") { onFinish() } }
          Button("Cancel") { onCancelFinish() }
        }
        else -> Tip(if (complete) Tips.END else Tips.PAUSE) {
          Button(if (complete) "End session" else "Pause session") { pending = "finish" }
        }
      }
      Button("Delete session", danger = true) { pending = "delete" }
      Spacer(Modifier.weight(1f))
      if (finishedAt != null) {
        Tip(Tips.KEEP) { SmallButton(if (session.keep) "Enable auto-deletion" else "Disable auto-deletion", { pending = "keep" }) }
      }
      Tip(Tips.SPOILER) { SmallButton("Spoiler log…", { if (finishedAt != null) onSpoiler() else pending = "spoiler" }) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateSession(view: AdminSessionView, onCreated: (CreateSessionResult) -> Unit) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  val yamls = remember { mutableStateListOf<UploadedYaml>() }
  var worlds by remember { mutableStateOf<List<YamlWorld>>(emptyList()) }
  var problems by remember { mutableStateOf<List<String>>(emptyList()) }
  val users = remember { mutableStateMapOf<String, List<String>>() }
  val locked = remember { mutableStateMapOf<String, Boolean>() }
  // Hints are off until switched on for the session
  var options by remember { mutableStateOf(SessionOptions(pointHints = false, autoHints = false)) }
  var version by remember { mutableStateOf(view.apVersions.firstOrNull()) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  // The YAML to remove, asked once more; "": all of them
  var removing by remember { mutableStateOf<String?>(null) }

  fun inspect() = scope.launch {
    val body = ApiJson.encodeToString(InspectRequest.serializer(), InspectRequest(yamls.toList(), version))
    when (val answer = call("POST", "api/admin/session/inspect", body) { ApiJson.decodeFromString(InspectResult.serializer(), it) }) {
      is Answer.Ok -> {
        worlds = answer.value.worlds
        problems = answer.value.problems
        // A guess to start from: "Alice-HK" belongs to Alice, if she has a login or no one else is meant
        worlds.forEach { w -> if (w.name !in users) users[w.name] = listOf(w.name.substringBefore('-').trim()).filter { it.isNotEmpty() } }
      }
      is Answer.Failed -> problems = listOf(answer.message)
    }
  }
  // Another release may have other games
  LaunchedEffect(version) { if (yamls.isNotEmpty()) inspect() }

  Txt("1. The YAMLs, one per slot", size = 13.sp, weight = FontWeight.SemiBold)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button("Add YAMLs…") {
      Platform.pickFiles(".yaml,.yml") { json ->
        val picked = runCatching { ApiJson.decodeFromString(ListSerializer(UploadedYaml.serializer()), json) }.getOrDefault(emptyList())
        // Choosing again adds files; one with the same name replaces the earlier one
        picked.forEach { file -> yamls.removeAll { it.fileName == file.fileName }; yamls += file }
        inspect()
      }
    }
    if (yamls.isNotEmpty() && removing == null) Button("Clear all", danger = true) { removing = "" }
  }
  // Asked once more: removing all of them right below their buttons, one of them below the YAMLs
  @Composable
  fun ConfirmRemoval(file: String) = Confirm(
    if (file.isEmpty()) "Remove all ${yamls.size} YAMLs? The players and locks chosen so far go too." else "Remove $file?",
    "Yes, remove", danger = true,
    onYes = {
      removing = null
      if (file.isEmpty()) yamls.clear() else yamls.removeAll { it.fileName == file }
      if (yamls.isEmpty()) {
        worlds = emptyList(); problems = emptyList(); users.clear(); locked.clear()
      } else {
        inspect()
      }
    },
    onCancel = { removing = null },
  )
  if (removing == "") ConfirmRemoval("")
  if (yamls.isNotEmpty()) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      yamls.forEach { yaml ->
        YamlChip(yaml.fileName, worlds.filter { it.fileName == yaml.fileName }.map { it.game }.distinct()) { removing = yaml.fileName }
      }
    }
  }
  removing?.takeIf { it.isNotEmpty() }?.let { ConfirmRemoval(it) }
  problems.forEach { Txt(it, size = 12.5.sp, color = p.trap) }
  if (problems.any { it.startsWith("No APWorld for") }) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt("Upload the missing APWorlds below, then:", size = 12.5.sp, color = p.muted)
      SmallButton("Check again", { inspect() })
    }
  }

  if (worlds.isNotEmpty()) {
    val random = options.openSlots != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Txt("2. Who plays which slot", size = 13.sp, weight = FontWeight.SemiBold)
      InfoTip(Tips.PLAYERS)
    }
    Txt(
      "One slot per YAML. A slot can have several players (they play it together). New names get a login when the " +
        "session is created.",
      size = 12.sp, color = p.muted,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Labeled("Locks", Tips.LOCKS, Tips.LOCK_MODES) {
        Select(
          listOf("choose" to "Choose per slot", "random" to "Random"), if (random) "random" else "choose",
          { mode ->
            options = options.copy(openSlots = if (mode == "random") (worlds.size / 2).coerceIn(1, maxOf(1, worlds.size - 1)) else null)
          },
          minHeight = FIELD_HEIGHT,
        )
      }
      options.openSlots?.let { open ->
        NumberField("slots start open", open, Tips.OPEN_SLOTS) { options = options.copy(openSlots = it) }
        if (open < 1 || open >= worlds.size) {
          Txt("Between 1 and ${worlds.size - 1}", size = 12.5.sp, color = p.trap)
        }
      }
    }
    worlds.forEach { world ->
      WorldRow(
        world, users[world.name].orEmpty(), view.users, !random && locked[world.name] == true,
        lockable = !random,
        onUsers = { users[world.name] = it },
        onLocked = { locked[world.name] = it },
      )
    }

    Txt("3. Options", size = 13.sp, weight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Check("Point hints", options.pointHints, Tips.POINT_HINTS) { options = options.copy(pointHints = it) }
      Check("Automatic hints", options.autoHints, Tips.AUTO_HINTS) { options = options.copy(autoHints = it) }
      if (random || locked.values.any { it }) {
        Check("Keys early", options.earlyKeys, Tips.EARLY_KEYS) { options = options.copy(earlyKeys = it) }
      }
      Check("Cheat hints", options.cheatHints, Tips.CHEAT_HINTS) { options = options.copy(cheatHints = it) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (options.pointHints) {
        NumberField("Hint cost %", options.hintCost, Tips.HINT_COST) { options = options.copy(hintCost = it) }
        NumberField("Points per check", options.locationCheckPoints, Tips.CHECK_POINTS) { options = options.copy(locationCheckPoints = it) }
      }
      Labeled("Release", Tips.RELEASE, Tips.RELEASE_CHOICES) { Select(RELEASE_MODES.map { it to it }, options.releaseMode, { options = options.copy(releaseMode = it) }, minHeight = FIELD_HEIGHT) }
      Labeled("Collect", Tips.COLLECT, Tips.COLLECT_CHOICES) { Select(RELEASE_MODES.map { it to it }, options.collectMode, { options = options.copy(collectMode = it) }, minHeight = FIELD_HEIGHT) }
      Labeled("Remaining", Tips.REMAINING, Tips.REMAINING_CHOICES) { Select(REMAINING_MODES.map { it to it }, options.remainingMode, { options = options.copy(remainingMode = it) }, minHeight = FIELD_HEIGHT) }
      when {
        view.apVersions.size > 1 -> Labeled("Archipelago", Tips.RELEASE_VERSION) {
          Select(view.apVersions.map { it to it }, version ?: "", { version = it }, minHeight = FIELD_HEIGHT)
        }
        version != null -> Labeled("Archipelago $version", Tips.RELEASE_VERSION) {}
      }
    }
    if (view.apVersions.isEmpty()) Txt("The gateway reports no Archipelago release.", size = 12.5.sp, color = p.trap)
    val openOk = options.openSlots?.let { it in 1 until worlds.size } ?: true

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        if (busy) "Generating…" else "Generate and start",
        enabled = !busy && problems.isEmpty() && view.apVersions.isNotEmpty() && openOk,
      ) {
        busy = true
        error = null
        scope.launch {
          val request = CreateSessionRequest(
            yamls = yamls.toList(),
            worlds = worlds.map { WorldPlan(it.name, users[it.name].orEmpty(), !random && locked[it.name] == true) },
            options = options,
            apVersion = version,
          )
          val body = ApiJson.encodeToString(CreateSessionRequest.serializer(), request)
          when (val answer = call("POST", "api/admin/session", body) { ApiJson.decodeFromString(CreateSessionResult.serializer(), it) }) {
            is Answer.Ok -> onCreated(answer.value)
            is Answer.Failed -> error = answer.message
          }
          busy = false
        }
      }
      if (busy) Txt("Archipelago generates the seed; that can take a minute.", size = 12.5.sp, color = p.muted)
    }
    error?.let { GenerationError(it) }
  }
}

/** A failed generation: the suspected games as text, Generate.py's output below in monospace. */
@Composable
private fun GenerationError(message: String) {
  val p = LocalPalette.current
  val marker = "Generate.py said:"
  val split = message.indexOf(marker)
  if (split < 0) {
    Mono(message, p.trap)
  } else {
    Txt(message.substring(0, split + marker.length).trim(), size = 12.5.sp, color = p.trap)
    Mono(message.substring(split + marker.length).trim(), p.text)
  }
}

/**
 * One slot of the new session: its players (chips; type a name and Enter, or pick a suggestion) and the lock (unless
 * the locks are random: not [lockable]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldRow(
  world: YamlWorld,
  users: List<String>,
  known: List<String>,
  locked: Boolean,
  lockable: Boolean,
  onUsers: (List<String>) -> Unit,
  onLocked: (Boolean) -> Unit,
) {
  val p = LocalPalette.current
  var input by remember { mutableStateOf("") }
  fun add(name: String) {
    val clean = name.trim()
    if (clean.isNotEmpty() && users.none { it.equals(clean, ignoreCase = true) }) onUsers(users + clean)
    input = ""
  }
  Divider()
  Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    GameChip(world.game)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
          Txt(world.name, size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
          Txt("${world.game} · ${world.fileName}", size = 11.5.sp, color = p.muted, maxLines = 1)
        }
        if (lockable) Check("starts locked", locked, Tips.STARTS_LOCKED, onLocked)
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        users.forEach { name ->
          val isNew = known.none { it.equals(name, ignoreCase = true) }
          UserChip(name, isNew, onRemove = { onUsers(users - name) })
        }
        FilterField(
          input, { input = it }, if (users.isEmpty()) "Add a player…" else "Add another…",
          Modifier.width(170.dp).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) { add(input); true } else false
          },
          minHeight = FIELD_HEIGHT,
        )
      }
      // Logins that match what's typed (or, before typing, those not in this slot yet)
      val suggestions = known.filter { k -> users.none { it.equals(k, ignoreCase = true) } }
        .filter { input.isBlank() || it.contains(input.trim(), ignoreCase = true) }.take(8)
      if (input.isNotBlank() && suggestions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { suggestions.forEach { SmallButton(it, { add(it) }) } }
      }
    }
  }
}

/** A chosen YAML: its file and its game(s), × to remove it. */
@Composable
private fun YamlChip(fileName: String, games: List<String>, onRemove: () -> Unit) {
  val p = LocalPalette.current
  Row(
    Modifier.clip(CircleShape).background(p.accent.copy(alpha = 0.12f)).padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Txt(fileName, size = 12.5.sp, weight = FontWeight.SemiBold, color = p.accent, maxLines = 1)
    if (games.isNotEmpty()) Txt(games.joinToString(", "), size = 11.5.sp, color = p.accent.copy(alpha = 0.8f), maxLines = 1)
    Box(Modifier.size(18.dp).clip(CircleShape).hand().clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
      DrawnIcon(Icon.CLOSE, p.accent, 10.dp)
    }
  }
}

@Composable
private fun UserChip(name: String, isNew: Boolean, onRemove: () -> Unit) {
  val p = LocalPalette.current
  Row(
    Modifier.heightIn(min = FIELD_HEIGHT).clip(CircleShape).background(p.hue(name).copy(alpha = 0.15f))
      .padding(start = 12.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Txt(name + if (isNew) " (new)" else "", size = 12.5.sp, weight = FontWeight.SemiBold, color = p.hue(name), maxLines = 1)
    Box(Modifier.size(18.dp).clip(CircleShape).hand().clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
      DrawnIcon(Icon.CLOSE, p.hue(name), 10.dp)
    }
  }
}

/** The logins of a session just created (their passwords are shown only now) and the slots that start locked. */
@Composable
private fun NewLogins(logins: List<NewLogin>, locked: List<String>, onDone: () -> Unit) {
  val p = LocalPalette.current
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.ok.copy(alpha = 0.1f)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Txt("Session created; the game server is starting.", size = 13.sp, weight = FontWeight.SemiBold)
    if (locked.isNotEmpty()) Txt("Start locked: ${locked.joinToString(", ")}", size = 12.5.sp)
    if (logins.isEmpty()) {
      Txt("Everyone already had a login.", size = 12.5.sp, color = p.muted)
    } else {
      Txt("New logins. The passwords are shown only now; hand them to the players.", size = 12.5.sp, color = p.muted)
      logins.forEach { login ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt(login.name, size = 13.sp, weight = FontWeight.SemiBold, modifier = Modifier.width(120.dp), maxLines = 1)
          Mono(login.password, p.text, Modifier.weight(1f))
          SmallButton("Copy", { Platform.copy(login.password) })
        }
      }
    }
    Row { Button("Done") { onDone() } }
  }
}

@Composable
private fun Check(label: String, checked: Boolean, tip: String? = null, onChange: (Boolean) -> Unit) {
  val p = LocalPalette.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      Modifier.clip(RoundedCornerShape(8.dp)).hand().clickable { onChange(!checked) }.padding(vertical = 2.dp, horizontal = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
          .background(if (checked) p.accent else Color.Transparent)
          .border(1.5.dp, if (checked) p.accent else p.muted, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
      ) {
        if (checked) DrawnIcon(Icon.CHECK, Color.White, 12.dp)
      }
      Txt(label, size = 12.5.sp, maxLines = 1)
    }
    if (tip != null) InfoTip(tip)
  }
}

@Composable
private fun Labeled(
  label: String,
  tip: String? = null,
  choices: List<Pair<String, String>> = emptyList(),
  content: @Composable () -> Unit,
) {
  // One height for every option, so labels, dropdowns and number fields line up in their rows
  Row(Modifier.heightIn(min = FIELD_HEIGHT), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Txt(label, size = 12.5.sp, color = LocalPalette.current.muted)
    if (tip != null) InfoTip(tip, options = choices)
    content()
  }
}

@Composable
private fun NumberField(label: String, value: Int, tip: String? = null, onChange: (Int) -> Unit) {
  var text by remember(value) { mutableStateOf(value.toString()) }
  Labeled(label, tip) {
    FilterField(text, { typed ->
      text = typed.filter { it.isDigit() }.take(3)
      text.toIntOrNull()?.let(onChange)
    }, "0", Modifier.width(56.dp), minHeight = FIELD_HEIGHT)
  }
}

/** The height of the form's text fields, dropdowns and player chips, so they line up. */
private val FIELD_HEIGHT = 34.dp

@Composable
internal fun Mono(text: String, color: Color, modifier: Modifier = Modifier) {
  SelectionContainer(modifier) {
    BasicText(text, style = TextStyle(color = color, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace))
  }
}

@Composable
internal fun Button(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
  val p = LocalPalette.current
  val color = if (danger) p.trap else p.accent
  Txt(
    text, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1,
    color = if (enabled) Color.White else p.muted,
    modifier = Modifier.clip(RoundedCornerShape(10.dp))
      .background(if (enabled) color else p.panelAlt)
      .then(if (enabled) Modifier.hand().clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}
