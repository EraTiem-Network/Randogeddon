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
import androidx.compose.foundation.layout.fillMaxWidth
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
import net.eratiem.randogeddon.shared.InspectResult
import net.eratiem.randogeddon.shared.NewLogin
import net.eratiem.randogeddon.shared.SessionInfo
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.UploadedYaml
import net.eratiem.randogeddon.shared.WorldPlan
import net.eratiem.randogeddon.shared.YamlWorld

/** Archipelago's choices for release and collect (see .env.example). */
private val RELEASE_MODES = listOf("auto", "enabled", "disabled", "goal", "auto-enabled")
private val REMAINING_MODES = listOf("enabled", "disabled", "goal")

/** An answer of the admin API: the decoded body, or the error message. */
private sealed interface Answer<out T> {
  data class Ok<T>(val value: T) : Answer<T>
  data class Failed(val message: String) : Answer<Nothing>
}

private suspend fun <T> call(method: String, path: String, body: String?, decode: (String) -> T): Answer<T> {
  val result = Platform.request(method, path, body) ?: return Answer.Failed("The server can't be reached")
  if (result.status in 200..299) return runCatching { Answer.Ok(decode(result.body)) }.getOrElse { Answer.Failed("Unexpected answer") }
  val message = runCatching { ApiJson.decodeFromString(ApiError.serializer(), result.body).message }.getOrNull()
  return Answer.Failed(message ?: "Failed (${result.status})")
}

/**
 * The admin panel's session: without one, the form to create it (YAMLs, who plays which world, locks, options);
 * with one, what it is and a delete button. [onChanged] after creating or deleting (the game server restarts).
 */
@Composable
fun SessionSection(onChanged: () -> Unit) {
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

  val current = view
  Section("Session", subtitle = current?.session?.let { "Seed ${it.seedName}" } ?: "No session yet") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      created?.let { result ->
        NewLogins(result.logins, onDone = { created = null })
      }
      when {
        current == null -> Txt("…", color = p.muted)
        current.session != null -> SessionSummary(current.session!!, onDelete = {
          scope.launch {
            when (val answer = call("DELETE", "api/admin/session", null) { }) {
              is Answer.Ok -> { load(); onChanged() }
              is Answer.Failed -> problem = answer.message
            }
          }
        })
        else -> CreateSession(current, onCreated = { result ->
          created = result
          load()
          onChanged()
        })
      }
    }
  }
}

@Composable
private fun SessionSummary(session: SessionInfo, onDelete: () -> Unit) {
  val p = LocalPalette.current
  var confirm by remember { mutableStateOf(false) }
  val details = listOfNotNull(
    session.apVersion?.let { "Archipelago $it" } ?: "deployed with the CLI",
    session.createdAt?.let { "created ${Platform.clock(it)}" },
    session.options?.let { o ->
      listOfNotNull(
        if (o.pointHints) "point hints (${o.hintCost} %)" else null,
        if (o.autoHints) "automatic hints" else null,
        "release ${o.releaseMode}",
      ).joinToString(", ")
    },
  )
  Txt(details.joinToString(" · "), size = 12.5.sp, color = p.muted)
  session.worlds.forEach { world ->
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(world.name, size = 13.sp, weight = FontWeight.SemiBold, modifier = Modifier.width(180.dp), maxLines = 1)
      Txt(world.users.joinToString(", ").ifEmpty { "nobody" }, size = 13.sp, color = if (world.users.isEmpty()) p.muted else p.text)
    }
  }
  if (!confirm) {
    Row { Button("Delete session", danger = true) { confirm = true } }
  } else {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(
        "Delete it? The game server stops; the seed and its save go to data/old. The logins stay.",
        size = 13.sp, modifier = Modifier.weight(1f),
      )
      Button("Yes, delete", danger = true) { confirm = false; onDelete() }
      Button("Cancel") { confirm = false }
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
  var options by remember { mutableStateOf(SessionOptions()) }
  var version by remember { mutableStateOf(view.apVersions.firstOrNull()) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  fun inspect() = scope.launch {
    val body = ApiJson.encodeToString(ListSerializer(UploadedYaml.serializer()), yamls.toList())
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

  Txt("1. The player YAMLs", size = 13.sp, weight = FontWeight.SemiBold)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button("Choose YAMLs…") {
      Platform.pickFiles(".yaml,.yml") { json ->
        val picked = runCatching { ApiJson.decodeFromString(ListSerializer(UploadedYaml.serializer()), json) }.getOrDefault(emptyList())
        // Choosing again adds files; one with the same name replaces the earlier one
        picked.forEach { file -> yamls.removeAll { it.fileName == file.fileName }; yamls += file }
        inspect()
      }
    }
    if (yamls.isNotEmpty()) {
      Txt("${yamls.size} files", size = 12.5.sp, color = p.muted)
      SmallButton("Clear", { yamls.clear(); worlds = emptyList(); problems = emptyList(); users.clear(); locked.clear() })
    }
  }
  problems.forEach { Txt(it, size = 12.5.sp, color = p.trap) }

  if (worlds.isNotEmpty()) {
    Txt("2. Who plays which world", size = 13.sp, weight = FontWeight.SemiBold)
    Txt(
      "A world can have several players (they play it together). New names get a login when the session is created.",
      size = 12.sp, color = p.muted,
    )
    worlds.forEach { world ->
      WorldRow(
        world, users[world.name].orEmpty(), view.users, locked[world.name] == true,
        onUsers = { users[world.name] = it },
        onLocked = { locked[world.name] = it },
      )
    }

    Txt("3. Options", size = 13.sp, weight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Check("Point hints (!hint)", options.pointHints) { options = options.copy(pointHints = it) }
      Check("Automatic hints", options.autoHints) { options = options.copy(autoHints = it) }
      if (locked.values.any { it }) Check("Keys early", options.earlyKeys) { options = options.copy(earlyKeys = it) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (options.pointHints) {
        NumberField("Hint cost %", options.hintCost) { options = options.copy(hintCost = it) }
        NumberField("Points per check", options.locationCheckPoints) { options = options.copy(locationCheckPoints = it) }
      }
      Labeled("Release") { Select(RELEASE_MODES.map { it to it }, options.releaseMode, { options = options.copy(releaseMode = it) }) }
      Labeled("Collect") { Select(RELEASE_MODES.map { it to it }, options.collectMode, { options = options.copy(collectMode = it) }) }
      Labeled("Remaining") { Select(REMAINING_MODES.map { it to it }, options.remainingMode, { options = options.copy(remainingMode = it) }) }
      if (view.apVersions.size > 1) {
        Labeled("Archipelago") { Select(view.apVersions.map { it to it }, version ?: "", { version = it }) }
      }
    }
    if (view.apVersions.isEmpty()) Txt("The gateway reports no Archipelago release.", size = 12.5.sp, color = p.trap)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(if (busy) "Generating…" else "Generate and start", enabled = !busy && problems.isEmpty() && view.apVersions.isNotEmpty()) {
        busy = true
        error = null
        scope.launch {
          val request = CreateSessionRequest(
            yamls = yamls.toList(),
            worlds = worlds.map { WorldPlan(it.name, users[it.name].orEmpty(), locked[it.name] == true) },
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
    error?.let { Mono(it, p.trap) }
  }
}

/** One world of the new session: its players (chips; type a name and Enter, or pick a suggestion) and the lock. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldRow(
  world: YamlWorld,
  users: List<String>,
  known: List<String>,
  locked: Boolean,
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
        Check("starts locked", locked, onLocked)
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
        )
      }
      // Logins that match what's typed (or, before typing, those not in this world yet)
      val suggestions = known.filter { k -> users.none { it.equals(k, ignoreCase = true) } }
        .filter { input.isBlank() || it.contains(input.trim(), ignoreCase = true) }.take(8)
      if (input.isNotBlank() && suggestions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { suggestions.forEach { SmallButton(it, { add(it) }) } }
      }
    }
  }
}

@Composable
private fun UserChip(name: String, isNew: Boolean, onRemove: () -> Unit) {
  val p = LocalPalette.current
  Row(
    Modifier.clip(CircleShape).background(p.hue(name).copy(alpha = 0.15f)).padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Txt(name + if (isNew) " (new)" else "", size = 12.5.sp, weight = FontWeight.SemiBold, color = p.hue(name), maxLines = 1)
    Box(Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
      DrawnIcon(Icon.CLOSE, p.hue(name), 10.dp)
    }
  }
}

/** The logins of a session just created; their passwords are shown only now. */
@Composable
private fun NewLogins(logins: List<NewLogin>, onDone: () -> Unit) {
  val p = LocalPalette.current
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.ok.copy(alpha = 0.1f)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Txt("Session created; the game server is starting.", size = 13.sp, weight = FontWeight.SemiBold)
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
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  val p = LocalPalette.current
  Row(
    Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(vertical = 2.dp, horizontal = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
        .background(if (checked) p.accent else Color.Transparent)
        .border(1.5.dp, if (checked) p.accent else p.muted, RoundedCornerShape(4.dp)),
    )
    Txt(label, size = 12.5.sp, maxLines = 1)
  }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Txt(label, size = 12.5.sp, color = LocalPalette.current.muted)
    content()
  }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
  var text by remember(value) { mutableStateOf(value.toString()) }
  Labeled(label) {
    FilterField(text, { typed ->
      text = typed.filter { it.isDigit() }.take(3)
      text.toIntOrNull()?.let(onChange)
    }, "0", Modifier.width(56.dp))
  }
}

@Composable
private fun Mono(text: String, color: Color, modifier: Modifier = Modifier) {
  SelectionContainer(modifier) {
    BasicText(text, style = TextStyle(color = color, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace))
  }
}

@Composable
private fun Button(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
  val p = LocalPalette.current
  val color = if (danger) p.trap else p.accent
  Txt(
    text, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1,
    color = if (enabled) Color.White else p.muted,
    modifier = Modifier.clip(RoundedCornerShape(10.dp))
      .background(if (enabled) color else p.panelAlt)
      .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}
