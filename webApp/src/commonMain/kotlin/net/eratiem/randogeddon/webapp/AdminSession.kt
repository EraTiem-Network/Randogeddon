package net.eratiem.randogeddon.webapp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.AdminOverview
import net.eratiem.randogeddon.shared.AdminSessionView
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CreateSessionRequest
import net.eratiem.randogeddon.shared.CreateSessionResult
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.InspectRequest
import net.eratiem.randogeddon.shared.InspectResult
import net.eratiem.randogeddon.shared.GameServerStatus
import net.eratiem.randogeddon.shared.KeepRequest
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionIds
import net.eratiem.randogeddon.shared.SessionStatus
import net.eratiem.randogeddon.shared.SessionInfo
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.UploadedYaml
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.WorldPlan
import net.eratiem.randogeddon.shared.YamlWorld

/** Archipelago's choices for release and collect (see .env.example). */
private val RELEASE_MODES = listOf("auto", "enabled", "disabled", "goal", "auto-enabled")
private val REMAINING_MODES = listOf("enabled", "disabled", "goal")

/** What each option of a new session does, for its info tip. */
internal object Tips {
  const val PLAYERS = "Who plays this slot. A slot with several players is played together: they share it and its " +
    "slot password. Someone without an account: type their e-mail address to invite them."
  const val INVITE = "An invitation mail with a link, valid for 7 days: they choose their name and password themselves. " +
    "The name you give is only a suggestion; until they sign up, sessions show them by it."
  const val LOCKS = "A locked slot can't be played until its key is found, somewhere in another slot (World Locks). " +
    "At least one slot starts open."
  val LOCK_MODES = listOf(
    "Choose" to "Tick \"starts locked\" for each slot that waits for its key.",
    "Random" to "The given number of slots, picked at random, start open; all others start locked.",
    "Mixed" to "Each slot starts open, starts locked, or is left to chance (its switch: left, right, middle). The " +
      "slider says how many slots start open in all.",
  )
  const val OPEN_SLOTS = "How many slots start open in all; at least one. Which of the slots left to chance is " +
    "picked at random when the session is generated."
  const val STARTS_LOCKED = "This slot waits for its key, which lies somewhere in another slot."
  const val LAST_OPEN = "The last slot that starts open: someone has to start, and the keys lie in the open slots."
  const val LOCK_SWITCH = "Left: starts open. Middle: left to chance. Right: starts locked, as long as another slot " +
    "can still start open."
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
    "disabled" to "Never; only its Session-Managers can, with a cheat.",
    "goal" to "With !release, once the slot reached its goal.",
    "auto-enabled" to "By itself at the goal, and any time with !release.",
  )
  const val COLLECT = "When a slot gets its own items that are still in other slots."
  val COLLECT_CHOICES = listOf(
    "auto" to "By itself when the slot reaches its goal.",
    "enabled" to "Any time, with !collect.",
    "disabled" to "Never; only its Session-Managers can, with a cheat.",
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
  const val CHEAT_HINTS = "Session-Managers may give free hints with the cheats (support always may). Every one is " +
    "logged and shown in the live feed. Giving items and checks works without this."
  const val SESSION_ID = "The session's name in the links, and its address for game clients (<ID>.<domain>). " +
    "Lowercase letters, digits and -, starting with a letter; it can't be changed later."
  const val DASHBOARD = "The session's dashboard as its players see it (slot passwords only for your own slots)."
  const val PAUSE = "Pausing stops the game server: the session is read-only, the dashboard shows how far it got, " +
    "and its players see the spoiler log. It can be reopened within 14 days."
  const val END = "Every check is done. Ending stops the game server for good: the dashboard keeps showing how it " +
    "ended, and its players see the spoiler log. A completed session can't be reopened. It also ends by itself 2 " +
    "minutes after every slot is done."
  const val REOPEN = "A finished session can be reopened within 14 days, unless it was completed (100 %): the game " +
    "server serves it again, if one is free."
  const val KEEP = "A finished session is deleted by itself some time after it finished (SESSION_RETENTION_DAYS, 90 by " +
    "default). With its auto-deletion disabled it stays until it is deleted by hand; enabled again, the time counts " +
    "from then."
  const val SPOILER = "The spoiler log shows where every item of the seed is, and the playthrough sphere by sphere. " +
    "Players see it once the session is finished."
}

/** How the locks of a new session are chosen. */
private enum class LockMode(val label: String) { CHOOSE("Choose per slot"), RANDOM("Random"), MIXED("Mixed") }

/** A slot's lock with mixed locks: the switch's three stops, left to right. */
private enum class LockChoice(val label: String) { OPEN("open"), RANDOM("random"), LOCKED("locked") }

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
 * Session [id] as the admin panel shows it: how it stands ([overview]: also how many game servers are in use), its
 * actions, its game server ([server]); who plays which slot below. Kept in [cache].
 */
@Composable
fun SessionSection(
  id: String,
  overview: AdminOverview?,
  cache: AdminCache,
  onChanged: () -> Unit,
  onSpoiler: (String) -> Unit,
  onDashboard: (String) -> Unit,
  server: @Composable () -> Unit = {},
) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  // The last one loaded at once, loaded again behind
  val view = cache.views[id]
  var problem by remember { mutableStateOf<String?>(null) }
  var gone by remember { mutableStateOf(false) }
  fun load() = scope.launch {
    when (val answer = call("GET", "api/sessions/$id/manage", null) { ApiJson.decodeFromString(AdminSessionView.serializer(), it) }) {
      is Answer.Ok -> { cache.views[id] = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  LaunchedEffect(Unit) { load() }
  fun act(path: String, body: String? = null) = scope.launch {
    problem = post("api/sessions/$id/manage/$path", body)
    load()
    onChanged()
  }

  val session = view?.session
  Section("State and Control", subtitle = session?.let(::sessionDetails) ?: "…") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      val entry = overview?.sessions?.firstOrNull { it.id == id }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        stateLines(entry, overview).forEachIndexed { i, line ->
          Txt(line, size = if (i == 0) 13.5.sp else 12.sp, weight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (i == 0) p.text else p.muted)
        }
      }
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      when {
        gone -> Txt("Deleted.", color = p.muted)
        session == null -> Txt("…", color = p.muted)
        else -> SessionSummary(
          session,
          onDelete = {
            scope.launch {
              when (val answer = call("DELETE", "api/sessions/$id/manage", null) { }) {
                is Answer.Ok -> { gone = true; onChanged() }
                is Answer.Failed -> problem = answer.message
              }
            }
          },
          onFinish = { act("finish") },
          onCancelFinish = { act("finish/cancel") },
          onReopen = { act("reopen") },
          onKeep = { keep -> act("keep", ApiJson.encodeToString(KeepRequest.serializer(), KeepRequest(keep))) },
          onSpoiler = { onSpoiler(id) },
          onDashboard = { onDashboard(id) },
        )
      }
      if (!gone) server()
    }
  }
  if (session != null && !gone) PlayersAdmin(session.worlds)
}

/** Who plays in a session, each with their slots; slots nobody plays at the end. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayersAdmin(worlds: List<WorldPlan>) {
  val p = LocalPalette.current
  val players = worlds.flatMap { w -> w.users.map { it to w.name } }.groupBy({ it.first }, { it.second })
  val ids = worlds.flatMap { w -> w.users.zip(w.userIds) }.toMap()
  val nobody = worlds.filter { it.users.isEmpty() }.map { it.name }
  Section("Players", subtitle = "${players.size} players, ${worlds.size} slots") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (players.isEmpty() && nobody.isEmpty()) Txt("Nobody is named for its slots.", size = 13.sp, color = p.muted)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        players.entries.sortedBy { it.key.lowercase() }.forEach { (name, slots) -> PlayerPill(name, slots, ids[name]) }
      }
      if (nobody.isNotEmpty()) {
        Txt("Nobody plays", size = 12.sp, color = p.muted, weight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          nobody.forEach { Pill(it, p.muted) }
        }
      }
    }
  }
}

/** A player as a pill in their colour: avatar, name, their slots. */
@Composable
private fun PlayerPill(name: String, slots: List<String>, userId: String?) {
  val hue = LocalPalette.current.hue(name)
  Row(
    Modifier.clip(CircleShape).background(hue.copy(alpha = 0.15f)).padding(start = 3.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Avatar(name, 22.dp)
    PersonName(name, userId, deleted = false, size = 12.5.sp, color = hue)
    Txt(slots.joinToString(", "), size = 11.5.sp, color = hue.copy(alpha = 0.8f), maxLines = 1)
  }
}

/** The admin panel's page besides the sessions; no session ID looks like it. */
internal const val NEW_PAGE = "+new"

/** The admin panel's headings (the session's, the general part's), a little bigger than the sections' below them. */
internal val ADMIN_HEADING = 20.sp

/**
 * The admin panel's heading: "Session" and the running session shown below, picked in it (the newest first, each with
 * how it stands and its game server), and the new session's button on the right.
 */
@Composable
fun AdminSwitch(overview: AdminOverview?, page: String?, onPage: (String) -> Unit) {
  val sessions = overview?.sessions.orEmpty().filter { it.status.running }
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Txt("Session", size = ADMIN_HEADING, weight = FontWeight.Bold)
    SearchableSelect(
      sessions.map { Triple<String?, String, String>(it.id, it.id, switchDetails(it)) },
      page?.takeIf { id -> sessions.any { it.id == id } }, { id -> id?.let(onPage) },
      Modifier.weight(1f), chip = false, heading = ADMIN_HEADING,
      placeholder = if (overview != null && sessions.isEmpty()) "none running" else "pick one…",
    )
    PageButton("New session", page == NEW_PAGE) { onPage(NEW_PAGE) }
  }
}

/** How [session] stands (in the switch's list too), and how many of the gateway's game servers are in use. */
private fun stateLines(session: SessionEntry?, overview: AdminOverview?): List<String> {
  val all = overview?.sessions.orEmpty()
  return listOfNotNull(
    session?.let(::switchDetails)?.replaceFirstChar { it.uppercase() },
    overview?.let {
      "${all.count { s -> s.status.running }} sessions running · ${it.inUse} of " +
        "${it.capacity} game servers in use (a finished session doesn't take one)"
    },
  )
}

/**
 * The finished sessions, the newest first: spoiler log, reopen, auto-deletion, delete; a click shows a session's
 * overview, floating over the panel. [onChanged] after an action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PastSessionsAdmin(sessions: List<SessionEntry>, onChanged: () -> Unit, onSpoiler: (String) -> Unit) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var problem by remember { mutableStateOf<String?>(null) }
  fun act(method: String, path: String, body: String? = null) = scope.launch {
    problem = (call(method, path, body) { } as? Answer.Failed)?.message
    onChanged()
  }
  val onReopen: (String) -> Unit = { act("POST", "api/sessions/$it/manage/reopen") }
  val onKeep: (String, Boolean) -> Unit = { id, keep ->
    act("POST", "api/sessions/$id/manage/keep", ApiJson.encodeToString(KeepRequest.serializer(), KeepRequest(keep)))
  }
  val onDelete: (String) -> Unit = { act("DELETE", "api/sessions/$it/manage") }
  // (session, "delete" or "keep"): the question shown in place of that session's buttons
  var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
  var overview by remember { mutableStateOf<SessionEntry?>(null) }
  overview?.let { PastSessionOverview(it, onClose = { overview = null }) }
  Section("Past sessions", subtitle = "deleted automatically after a while, unless that is disabled", actions = {
    InfoTip(Tips.KEEP)
  }) {
    BoxWithConstraints(Modifier.fillMaxWidth().card(p).padding(16.dp)) {
      // A phone: the title, the details and the buttons one below the other
      val stacked = maxWidth < NARROW
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
        if (sessions.isEmpty()) Txt("None yet.", size = 13.sp, color = p.muted)
        sessions.forEachIndexed { i, past ->
          if (i > 0) Divider()
          val seed = past.id
          // A click shows the overview, with the players
          val title: @Composable (Modifier) -> Unit = { modifier ->
            HoverRow(onClick = { overview = past }, modifier = modifier) {
              Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Txt(seed, size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
                  StatusPill(past.status)
                }
                Txt(
                  listOfNotNull(
                    past.finishedAt?.let { "finished ${Platform.date(it)}" + (past.finishedBy?.let { by -> " by $by" } ?: "") },
                    "${oneDecimal(past.percent)}%",
                    deletion(past.deletesAt, past.keep),
                  ).joinToString(" · "),
                  size = 12.sp, color = p.muted, maxLines = if (stacked) 4 else 2,
                )
              }
              Box(Modifier.padding(end = 4.dp).rotate(180f)) { DrawnIcon(Icon.BACK, p.muted, 14.dp) }
            }
          }
          val buttons: @Composable () -> Unit = {
            if (pending?.first != seed) {
              if (reopenable(past.reopenUntil)) Tip(Tips.REOPEN) { SmallButton("Reopen", { onReopen(seed) }) }
              Tip(Tips.SPOILER) { SmallButton("Spoiler log", { onSpoiler(seed) }) }
              Tip(Tips.KEEP) {
                SmallButton(if (past.keep) "Enable auto-deletion" else "Disable auto-deletion", { pending = seed to "keep" })
              }
              SmallButton("Delete", { pending = seed to "delete" })
            }
          }
          if (stacked) {
            title(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { buttons() }
          } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              title(Modifier.weight(1f))
              buttons()
            }
          }
          when (pending?.takeIf { it.first == seed }?.second) {
            "delete" -> Confirm(
              "Delete it for good? Its seed, save and history go; the logins stay.", "Yes, delete", danger = true,
              onYes = { pending = null; onDelete(seed) }, onCancel = { pending = null },
            )
            "keep" -> Confirm(
              if (past.keep) "Enable its auto-deletion again? Its time counts from now."
              else "Disable its auto-deletion? It stays until it is deleted by hand.",
              if (past.keep) "Yes, enable" else "Yes, disable", danger = false,
              onYes = { pending = null; onKeep(seed, !past.keep) }, onCancel = { pending = null },
            )
          }
        }
      }
    }
  }
}

/** A past session at a glance, floating over the panel: how it went, when it finished, what happens to it, who played. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PastSessionOverview(past: SessionEntry, onClose: () -> Unit) {
  val p = LocalPalette.current
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    SelectionContainer {
      Column(
        Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth().card(p, RoundedCornerShape(20.dp)),
      ) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(Modifier.weight(1f)) {
            Txt(past.id, size = 18.sp, weight = FontWeight.Bold, maxLines = 2)
            Txt("Seed ${past.seedName}", size = 12.5.sp, color = p.muted, maxLines = 1)
          }
          Txt("${oneDecimal(past.percent)}%", size = 22.sp, weight = FontWeight.Bold, brush = p.brand)
          IconButton(onClose, Icon.CLOSE)
        }
        Divider()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          ProgressBar((past.percent / 100).toFloat())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact("Slots", "${past.slots}")
            Fact("Goals", "${past.goals} / ${past.slots}", if (past.goals > 0) p.ok else p.text)
            Fact("Players", "${past.players.size}")
          }
          val until = past.reopenUntil
          listOfNotNull(
            past.createdAt?.let { "Created" to Platform.date(it) },
            "Finished" to (past.finishedAt?.let { Platform.date(it) } ?: "not yet") +
              (past.finishedBy?.let { " by $it" } ?: ", when every slot was done"),
            "Reopen" to when {
              until == null -> "not possible, it was completed"
              reopenable(until) -> "possible until ${Platform.date(until)}"
              else -> "no longer possible"
            },
            "Deletion" to (deletion(past.deletesAt, past.keep) ?: "not automatic"),
          ).forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Txt(label, size = 12.5.sp, color = p.muted, modifier = Modifier.width(72.dp))
              Txt(value, size = 13.sp, modifier = Modifier.weight(1f))
            }
          }
          Txt("PLAYERS", size = 12.sp, color = p.muted, weight = FontWeight.SemiBold)
          if (past.players.isEmpty()) Txt("Nobody was named for its slots.", size = 13.sp, color = p.muted)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            past.players.forEach { name ->
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Avatar(name, 26.dp)
                Txt(name, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
              }
            }
          }
        }
      }
    }
  }
}

/** A session in the switch's list: how it stands, how far it got, its game server, who is online. */
private fun switchDetails(session: SessionEntry): String = listOfNotNull(
  when (session.status) {
    SessionStatus.LIVE -> "live"
    SessionStatus.FINISHING -> "ending"
    SessionStatus.PAUSED -> "paused"
    SessionStatus.ENDED -> "ended"
  },
  "${oneDecimal(session.percent)}%",
  session.server?.status?.let {
    when (it) {
      GameServerStatus.RUNNING -> "server running"
      GameServerStatus.STARTING -> "server starting"
      GameServerStatus.CRASHED -> "server crashed"
      GameServerStatus.STOPPED -> "server stopped"
      GameServerStatus.NO_SEED -> "no seed"
    }
  },
  session.online.takeIf { it > 0 }?.let { "$it online" },
  session.finishedAt?.let { "finished ${Platform.date(it)}" },
).joinToString(" · ")

/** A button of the switch; the page that is shown is filled. */
@Composable
private fun PageButton(text: String, active: Boolean, onClick: () -> Unit) {
  val p = LocalPalette.current
  Txt(
    text, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1,
    color = if (active) Color.White else p.accent,
    modifier = Modifier.heightIn(min = FIELD_HEIGHT).clip(RoundedCornerShape(10.dp))
      .background(if (active) p.accent else p.accent.copy(alpha = 0.1f))
      .hand().clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}

/** The game server states that take one of the gateway's ports. */
private val RUNNING = setOf(GameServerStatus.STARTING, GameServerStatus.RUNNING, GameServerStatus.CRASHED)

/**
 * A new session: its ID (a suggestion to start with; checked as it is typed) and the form (YAMLs, who plays which
 * slot, locks, options). Its logins are shown once it is created.
 */
@Composable
fun NewSessionSection(overview: AdminOverview?, onCreated: (CreateSessionResult) -> Unit) {
  val p = LocalPalette.current
  var id by remember { mutableStateOf<String?>(null) }
  var created by remember { mutableStateOf<CreateSessionResult?>(null) }
  // A new form (and a new suggestion) after each session
  var round by remember { mutableStateOf(0) }
  val suggested = overview?.suggestedId.orEmpty()
  val chosen = id ?: suggested
  val taken = overview?.taken?.contains(chosen) == true || overview?.sessions?.any { it.id == chosen } == true
  val idProblem = if (chosen.isEmpty()) null else SessionIds.problem(chosen) ?: if (taken) "Taken" else null
  val full = overview != null && overview.inUse >= overview.capacity
  // The user manages as many running sessions as the Administrators allow
  val atLimit = overview?.left == 0
  Section("New session", subtitle = "as many run at once as the gateway has game servers (${overview?.capacity ?: "…"})") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      created?.let { result ->
        Created(result.locked, onDone = { created = null })
      }
      if (full) Txt("Every game server is in use: finish or stop a session first.", size = 12.5.sp, color = p.trap)
      if (atLimit) Txt("You manage as many running sessions as you may at once: finish one first.", size = 12.5.sp, color = p.trap)
      overview?.left?.takeIf { it > 0 }?.let { Txt("You may create $it more running session${if (it == 1) "" else "s"}.", size = 12.5.sp, color = p.muted) }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Session ID", size = 13.sp, weight = FontWeight.SemiBold)
        InfoTip(Tips.SESSION_ID)
        FilterField(chosen, { id = it.lowercase().trim() }, "friday-chaos", Modifier.width(220.dp), minHeight = FIELD_HEIGHT)
        idProblem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      }
      if (overview != null) key(round) {
        CreateSession(
          AdminSessionView(apVersions = overview.apVersions, users = overview.users, people = overview.people), chosen,
          ready = idProblem == null && chosen.isNotEmpty() && !full && !atLimit,
          onCreated = { result ->
            created = result
            id = null
            round++
            onCreated(result)
          },
        )
      }
    }
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
  onDashboard: () -> Unit,
) {
  val p = LocalPalette.current
  // "finish", "spoiler", "delete" or "keep": the question shown instead of the actions
  var pending by remember { mutableStateOf<String?>(null) }
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
      if (finishedAt != null) "Delete it for good? Its seed, save and history go; the logins stay."
      else "Delete it? The game server stops; the seed and its save go to data/old. The logins stay.",
      "Yes, delete", danger = true,
      onYes = { pending = null; onDelete() }, onCancel = { pending = null },
    )
    "keep" -> Confirm(
      if (session.keep) "Enable its auto-deletion again? Its time counts from now." else "Disable its auto-deletion? It stays until it is deleted by hand.",
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
      Tip(Tips.DASHBOARD) { SmallButton("Dashboard", onDashboard) }
      Tip(Tips.SPOILER) { SmallButton("Spoiler log…", { if (finishedAt != null) onSpoiler() else pending = "spoiler" }) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateSession(view: AdminSessionView, id: String, ready: Boolean, onCreated: (CreateSessionResult) -> Unit) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  val yamls = remember { mutableStateListOf<UploadedYaml>() }
  var worlds by remember { mutableStateOf<List<YamlWorld>>(emptyList()) }
  var problems by remember { mutableStateOf<List<String>>(emptyList()) }
  // Slot -> the IDs of its players; the accounts to pick from, with those invited from here
  val users = remember { mutableStateMapOf<String, List<String>>() }
  val people = remember { mutableStateListOf<UserRef>().apply { addAll(view.people) } }
  val locked = remember { mutableStateMapOf<String, Boolean>() }
  var lockMode by remember { mutableStateOf(LockMode.CHOOSE) }
  // Mixed locks: each slot's switch (left to chance until moved)
  val choices = remember { mutableStateMapOf<String, LockChoice>() }
  // How many slots start open with random or mixed locks; `null`: about half
  var openCount by remember { mutableStateOf<Int?>(null) }
  // Hints are off until switched on for the session
  var options by remember { mutableStateOf(SessionOptions(pointHints = false, autoHints = false)) }
  var version by remember { mutableStateOf(view.apVersions.firstOrNull()) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  // The YAML to remove, asked once more; "": all of them
  var removing by remember { mutableStateOf<String?>(null) }
  // Why the files just picked that aren't player YAMLs weren't taken
  var refused by remember { mutableStateOf<List<String>>(emptyList()) }

  fun inspect(): Job = scope.launch {
    val body = ApiJson.encodeToString(InspectRequest.serializer(), InspectRequest(yamls.toList(), version))
    when (val answer = call("POST", "api/sessions/new/inspect", body) { ApiJson.decodeFromString(InspectResult.serializer(), it) }) {
      is Answer.Ok -> {
        val invalid = answer.value.invalid.toSet()
        if (invalid.isNotEmpty()) {
          // Not taken at all; the others are checked again without them
          refused = refused + answer.value.problems.filter { problem -> invalid.any { problem.startsWith("$it: ") } }
          yamls.removeAll { it.fileName in invalid }
          if (yamls.isEmpty()) {
            worlds = emptyList(); problems = emptyList()
          } else {
            inspect()
          }
          return@launch
        }
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
        refused = emptyList()
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
        worlds = emptyList(); problems = emptyList(); users.clear(); locked.clear(); choices.clear()
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
  refused.forEach { Txt("Not added: $it", size = 12.5.sp, color = p.trap) }
  problems.forEach { Txt(it, size = 12.5.sp, color = p.trap) }
  if (problems.any { it.startsWith("No APWorld for") }) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt("Upload the missing APWorlds below, then:", size = 12.5.sp, color = p.muted)
      SmallButton("Check again", { inspect() })
    }
  }

  if (worlds.isNotEmpty()) {
    // A single slot can't be locked; otherwise at least one slot starts open
    val n = worlds.size
    val lockable = n > 1
    val mode = if (lockable) lockMode else LockMode.CHOOSE
    // A switch left to chance whose outcome is already given by the others shows that outcome: e.g. of two slots,
    // one open, the other has to be locked
    val chosen = worlds.associate { it.name to (choices[it.name] ?: LockChoice.RANDOM) }
    val settled = run {
      val chance = chosen.values.count { it == LockChoice.RANDOM }
      val sureLocked = chosen.values.count { it == LockChoice.LOCKED }
      val sure = n - sureLocked - chance
      val least = maxOf(1, sure)
      val most = if (sureLocked == 0) minOf(sure + chance, n - 1) else sure + chance
      val outcome = when {
        chance == 0 || least < most -> null
        least - sure == 0 -> LockChoice.LOCKED
        least - sure == chance -> LockChoice.OPEN
        else -> null
      }
      if (outcome == null) chosen else chosen.mapValues { (_, c) -> if (c == LockChoice.RANDOM) outcome else c }
    }
    fun choice(world: YamlWorld) = settled.getValue(world.name)
    // Locked for sure, and how many are left to chance
    val lockedNow = when (mode) {
      LockMode.CHOOSE -> worlds.filter { lockable && locked[it.name] == true }
      LockMode.RANDOM -> emptyList()
      LockMode.MIXED -> worlds.filter { choice(it) == LockChoice.LOCKED }
    }.map { it.name }.toSet()
    val pool = when (mode) {
      LockMode.CHOOSE -> 0
      LockMode.RANDOM -> n
      LockMode.MIXED -> worlds.count { choice(it) == LockChoice.RANDOM }
    }
    val sureOpen = n - lockedNow.size - pool
    // How many start open in all: at least one; with nothing locked for sure, one of the random ones is. Nothing left
    // to chance: every slot says open or locked, as when they are chosen
    val openRange = if (pool == 0) null else maxOf(1, sureOpen)..(if (lockedNow.isEmpty()) minOf(sureOpen + pool, n - 1) else sureOpen + pool)
    val open = openRange?.let { (openCount ?: (n / 2)).coerceIn(it) }
    // A slot can be locked while another one can still start open
    val canLock = n - lockedNow.size > 1
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Txt("2. Who plays which slot", size = 13.sp, weight = FontWeight.SemiBold)
      InfoTip(Tips.PLAYERS)
    }
    Txt(
      "One slot per YAML. A slot can have several players (they play it together). New names get a login when the " +
        "session is created.",
      size = 12.sp, color = p.muted,
    )
    if (lockable) {
      Labeled("Locks", Tips.LOCKS, Tips.LOCK_MODES) {
        Select(LockMode.entries.map { it to it.label }, mode, { lockMode = it }, minHeight = FIELD_HEIGHT)
      }
      // On a line of its own: room for the slider on a phone
      if (open != null && openRange != null) {
        Row(
          Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = FIELD_HEIGHT),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Txt("Start open", size = 12.5.sp, color = p.muted, maxLines = 1)
          InfoTip(Tips.OPEN_SLOTS)
          if (openRange.first < openRange.last) {
            // The slider's own ends, then what it is set to
            Txt("${openRange.first}", size = 12.sp, color = p.muted, maxLines = 1)
            StepSlider(open, openRange, { openCount = it }, Modifier.weight(1f))
            Txt("${openRange.last}", size = 12.sp, color = p.muted, maxLines = 1)
          } else {
            Spacer(Modifier.weight(1f))
          }
          Txt("$open of $n slots", size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
        }
      }
    }
    worlds.forEach { world ->
      val isLocked = world.name in lockedNow
      WorldRow(
        world, users[world.name].orEmpty(), people,
        onInvite = { email ->
          when (val r = inviteForSession(email)) {
            is Answer.Ok -> r.value.also { people += it }
            is Answer.Failed -> { error = r.message; null }
          }
        },
        lock = {
          when (mode) {
            // The last open slot stays open
            LockMode.CHOOSE -> if (lockable) {
              val enabled = isLocked || canLock
              Check("starts locked", isLocked, if (enabled) Tips.STARTS_LOCKED else Tips.LAST_OPEN, enabled = enabled) {
                locked[world.name] = it
              }
            }
            LockMode.RANDOM -> {}
            LockMode.MIXED -> LockSwitch(choice(world), lockAllowed = isLocked || canLock) { choices[world.name] = it }
          }
        },
        onUsers = { users[world.name] = it },
      )
    }

    Txt("3. Options", size = 13.sp, weight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Check("Point hints", options.pointHints, Tips.POINT_HINTS) { options = options.copy(pointHints = it) }
      Check("Automatic hints", options.autoHints, Tips.AUTO_HINTS) { options = options.copy(autoHints = it) }
      if (pool > 0 || lockedNow.isNotEmpty()) {
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
    val openOk = lockedNow.size < n

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        if (busy) "Generating…" else "Generate and start",
        enabled = ready && !busy && problems.isEmpty() && view.apVersions.isNotEmpty() && openOk,
      ) {
        busy = true
        error = null
        scope.launch {
          val request = CreateSessionRequest(
            id = id,
            yamls = yamls.toList(),
            worlds = worlds.map {
              WorldPlan(
                it.name, userIds = users[it.name].orEmpty(), locked = it.name in lockedNow,
                random = mode == LockMode.MIXED && choice(it) == LockChoice.RANDOM,
              )
            },
            options = options.copy(openSlots = open),
            apVersion = version,
          )
          val body = ApiJson.encodeToString(CreateSessionRequest.serializer(), request)
          when (val answer = call("POST", "api/sessions", body) { ApiJson.decodeFromString(CreateSessionResult.serializer(), it) }) {
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
 * One slot of the new session: its players (chips; type to search the accounts, Enter or a click picks one) and its
 * [lock] control (nothing with random locks, or a single slot). [users] are account IDs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldRow(
  world: YamlWorld,
  users: List<String>,
  known: List<UserRef>,
  /** Invites an address from here: the new account, or `null` (the form shows why). */
  onInvite: suspend (String) -> UserRef?,
  lock: @Composable () -> Unit,
  onUsers: (List<String>) -> Unit,
) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var input by remember { mutableStateOf("") }
  var inviting by remember { mutableStateOf(false) }
  // Accounts that match what's typed and aren't in this slot yet
  val suggestions = known.filter { it.id !in users }
    .filter { input.isBlank() || it.label.contains(input.trim(), ignoreCase = true) }.take(8)
  fun add(user: UserRef) {
    if (user.id !in users) onUsers(users + user.id)
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
        lock()
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        users.forEach { id ->
          val user = known.firstOrNull { it.id == id }
          UserChip(user?.label ?: id, invited = user?.name == null, onRemove = { onUsers(users - id) })
        }
        FilterField(
          input, { input = it }, if (users.isEmpty()) "Add a player…" else "Add another…",
          Modifier.width(170.dp).onPreviewKeyEvent { event ->
            val enter = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)
            if (enter) suggestions.firstOrNull()?.takeIf { input.isNotBlank() }?.let(::add)
            enter
          },
          minHeight = FIELD_HEIGHT,
        )
      }
      if (input.isNotBlank()) {
        val address = EmailAddresses.normalize(input)
        when {
          suggestions.isNotEmpty() ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { suggestions.forEach { SmallButton(it.label, { add(it) }) } }
          EmailAddresses.valid(address) -> SmallButton(if (inviting) "Inviting…" else "Invite $address", {
            if (!inviting) scope.launch {
              inviting = true
              onInvite(address)?.let(::add)
              inviting = false
            }
          })
          else -> Txt("No account matches; type their e-mail address to invite them", size = 12.sp, color = p.muted)
        }
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
private fun UserChip(name: String, invited: Boolean, onRemove: () -> Unit) {
  val p = LocalPalette.current
  Row(
    Modifier.heightIn(min = FIELD_HEIGHT).clip(CircleShape).background(p.hue(name).copy(alpha = 0.15f))
      .padding(start = 12.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Txt(name + if (invited) " (invited)" else "", size = 12.5.sp, weight = FontWeight.SemiBold, color = p.hue(name), maxLines = 1)
    Box(Modifier.size(18.dp).clip(CircleShape).hand().clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
      DrawnIcon(Icon.CLOSE, p.hue(name), 10.dp)
    }
  }
}

/** A session just created: its game server starts; the slots that start locked. */
@Composable
private fun Created(locked: List<String>, onDone: () -> Unit) {
  val p = LocalPalette.current
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.ok.copy(alpha = 0.1f)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Txt("Session created; the game server is starting.", size = 13.sp, weight = FontWeight.SemiBold)
    if (locked.isNotEmpty()) Txt("Start locked: ${locked.joinToString(", ")}", size = 12.5.sp)
    Row { Button("Done") { onDone() } }
  }
}

/**
 * A slot's lock with mixed locks: a switch with three stops, open, left to chance, locked. Tap a stop or drag the
 * knob; locked only while [lockAllowed].
 */
@Composable
private fun LockSwitch(choice: LockChoice, lockAllowed: Boolean, onChange: (LockChoice) -> Unit) {
  val p = LocalPalette.current
  val current by rememberUpdatedState(choice)
  val change by rememberUpdatedState(onChange)
  val color = when (choice) {
    LockChoice.OPEN -> p.ok
    LockChoice.RANDOM -> p.accent
    LockChoice.LOCKED -> p.locked
  }
  val position by animateFloatAsState(choice.ordinal / 2f, tween(160))
  Tip(Tips.LOCK_SWITCH) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(choice.label, size = 12.5.sp, weight = FontWeight.SemiBold, color = color, maxLines = 1)
      BoxWithConstraints(
        Modifier.width(78.dp).height(28.dp).clip(CircleShape).background(color.copy(alpha = 0.16f))
          .border(1.dp, color.copy(alpha = 0.5f), CircleShape).hand()
          .pointerInput(lockAllowed) {
            awaitEachGesture {
              val down = awaitFirstDown()
              down.consume()
              var at = down.position.x
              while (true) {
                val moved = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!moved.pressed) break
                if (moved.positionChanged()) {
                  moved.consume()
                  at = moved.position.x
                }
              }
              val picked = LockChoice.entries[(at / size.width * 3).toInt().coerceIn(0, 2)]
              if (picked != LockChoice.LOCKED || lockAllowed || current == LockChoice.LOCKED) change(picked)
            }
          },
        contentAlignment = Alignment.CenterStart,
      ) {
        val travel = maxWidth - 28.dp
        // The stops; locked is faint while it can't be chosen
        LockChoice.entries.forEach { stop ->
          Box(
            Modifier.offset(x = 4.dp + travel * (stop.ordinal / 2f) + 7.dp).size(6.dp).clip(CircleShape)
              .background(p.muted.copy(alpha = if (stop == LockChoice.LOCKED && !lockAllowed) 0.2f else 0.6f))
          )
        }
        Box(
          Modifier.offset(x = 4.dp + travel * position).size(20.dp).clip(CircleShape).background(color),
          contentAlignment = Alignment.Center,
        ) {
          when (choice) {
            LockChoice.OPEN -> DrawnIcon(Icon.CHECK, Color.White, 12.dp)
            LockChoice.RANDOM -> Txt("?", size = 12.sp, color = Color.White, weight = FontWeight.Bold, maxLines = 1)
            LockChoice.LOCKED -> DrawnIcon(Icon.LOCK, Color.White, 12.dp)
          }
        }
      }
    }
  }
}

@Composable
private fun Check(label: String, checked: Boolean, tip: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  val p = LocalPalette.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      Modifier.clip(RoundedCornerShape(8.dp))
        .then(if (enabled) Modifier.hand().clickable { onChange(!checked) } else Modifier.alpha(0.45f))
        .padding(vertical = 2.dp, horizontal = 2.dp),
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
internal val FIELD_HEIGHT = 34.dp

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
