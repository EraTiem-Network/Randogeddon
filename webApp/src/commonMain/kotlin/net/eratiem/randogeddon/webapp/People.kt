package net.eratiem.randogeddon.webapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.AddPersonRequest
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.SessionPeople
import net.eratiem.randogeddon.shared.SessionPerson
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.ShareLinkView
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.WatcherRequest

/** How a role is called on the page. */
internal val SessionRole.label: String
  get() = when (this) {
    SessionRole.PLAYER -> "Player"
    SessionRole.MANAGER -> "Session-Manager"
    SessionRole.SUPPORT -> "Support"
    SessionRole.WATCHER -> "Watcher"
  }

/** The parts a Session-Manager gives (players come with the slots). */
private val GIVEN = listOf(SessionRole.WATCHER, SessionRole.SUPPORT, SessionRole.MANAGER)

private val ROLE_TIPS = mapOf(
  SessionRole.MANAGER to "Manages the session as you do: its state, game server, console, cheats and people.",
  SessionRole.SUPPORT to "Helps the players: gives items, checks locations and free hints, and sees the spoiler log while it runs. Logged in the feed.",
  SessionRole.WATCHER to "Follows the session: dashboard, feed and hints; no slots, the spoiler log once it is finished.",
)

/**
 * The session's people, in the panel (its Session-Managers and Administrators): everyone with a part in it, each with
 * their role and slots; giving someone a part (Session-Manager, support or watcher) by name or a new address, and
 * taking it again. Below: the share link.
 */
@Composable
fun PeopleSection(id: String) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var people by remember { mutableStateOf<List<SessionPerson>?>(null) }
  var choices by remember { mutableStateOf<List<UserRef>>(emptyList()) }
  var problem by remember { mutableStateOf<String?>(null) }
  suspend fun load() {
    when (val r = call("GET", "api/sessions/$id/people", null) { ApiJson.decodeFromString(SessionPeople.serializer(), it) }) {
      is Answer.Ok -> { people = r.value.people; problem = null }
      is Answer.Failed -> problem = r.message
    }
    (call("GET", "api/sessions/$id/people/choices", null) { ApiJson.decodeFromString(ListSerializer(UserRef.serializer()), it) } as? Answer.Ok)
      ?.let { choices = it.value }
  }
  LaunchedEffect(id) { load() }
  val list = people.orEmpty()
  Section("People", subtitle = list.groupBy { it.role }.entries.sortedBy { it.key.ordinal }
    .joinToString(" · ") { (role, all) -> "${all.size} ${role.label.lowercase()}${if (all.size == 1) "" else "s"}" }.ifEmpty { "…" }) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      AddPerson(GIVEN, choices) { role, userId, email ->
        val body = ApiJson.encodeToString(AddPersonRequest.serializer(), AddPersonRequest(role, userId, email))
        when (val r = call("POST", "api/sessions/$id/people", body) { ApiJson.decodeFromString(SessionPerson.serializer(), it) }) {
          is Answer.Ok -> { load(); null }
          is Answer.Failed -> r.message
        }
      }
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      list.forEach { person ->
        Divider()
        PersonRow(person, removable = person.role != SessionRole.PLAYER) {
          scope.launch {
            problem = (call("DELETE", "api/sessions/$id/people/${person.id}", null) { } as? Answer.Failed)?.message
            load()
          }
        }
      }
      Divider()
      ShareLinkPart(id)
    }
  }
}

/**
 * On the dashboard, for the session's players: who watches it with them, adding watchers (by name, or a new address)
 * and taking them away, and the share link.
 */
@Composable
fun WatchersSection(id: String) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var watchers by remember { mutableStateOf<List<SessionPerson>?>(null) }
  var choices by remember { mutableStateOf<List<UserRef>>(emptyList()) }
  var problem by remember { mutableStateOf<String?>(null) }
  suspend fun load() {
    when (val r = call("GET", "api/sessions/$id/watchers", null) { ApiJson.decodeFromString(SessionPeople.serializer(), it) }) {
      is Answer.Ok -> { watchers = r.value.people; problem = null }
      is Answer.Failed -> problem = r.message
    }
    (call("GET", "api/sessions/$id/watchers/choices", null) { ApiJson.decodeFromString(ListSerializer(UserRef.serializer()), it) } as? Answer.Ok)
      ?.let { choices = it.value }
  }
  LaunchedEffect(id) { load() }
  val list = watchers.orEmpty()
  Section(
    "Watchers", subtitle = if (watchers == null) "…" else if (list.isEmpty()) "nobody watches yet" else "${list.size} watching",
    actions = { InfoTip(ROLE_TIPS.getValue(SessionRole.WATCHER)) },
  ) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      AddPerson(emptyList(), choices) { _, userId, email ->
        val body = ApiJson.encodeToString(WatcherRequest.serializer(), WatcherRequest(userId, email))
        when (val r = call("POST", "api/sessions/$id/watchers", body) { ApiJson.decodeFromString(SessionPerson.serializer(), it) }) {
          is Answer.Ok -> { load(); null }
          is Answer.Failed -> r.message
        }
      }
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      list.forEach { person ->
        Divider()
        PersonRow(person, removable = true) {
          scope.launch {
            problem = (call("DELETE", "api/sessions/$id/watchers/${person.id}", null) { } as? Answer.Failed)?.message
            load()
          }
        }
      }
      Divider()
      ShareLinkPart(id)
    }
  }
}

/**
 * Someone gets a part: a role of [roles] (none to pick if empty), and an account of [choices] by name or a new
 * address (an invitation; only for an address without an account). [onAdd] answers the problem, or `null`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPerson(
  roles: List<SessionRole>,
  choices: List<UserRef>,
  onAdd: suspend (role: SessionRole, userId: String?, email: String?) -> String?,
) {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var role by remember { mutableStateOf(roles.firstOrNull() ?: SessionRole.WATCHER) }
  var user by remember { mutableStateOf<String?>(null) }
  var email by remember { mutableStateOf("") }
  var state by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
  var busy by remember { mutableStateOf(false) }
  val address = EmailAddresses.normalize(email)
  val byAddress = email.isNotBlank()
  fun add() {
    busy = true
    scope.launch {
      val name = choices.firstOrNull { it.id == user }?.label ?: address
      state = onAdd(role, user.takeIf { !byAddress }, address.takeIf { byAddress })?.let { false to it }
        ?: (true to if (byAddress) "Invited $address as ${role.label.lowercase()}: the link works for 7 days." else "$name is ${role.label.lowercase()} now.")
      if (state?.first == true) { user = null; email = "" }
      busy = false
    }
  }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    if (roles.isNotEmpty()) Tip(ROLE_TIPS.getValue(role)) {
      SearchableSelect(roles.map { Triple(it, it.label, "") }, role, { role = it }, Modifier.width(170.dp), chip = false, minHeight = FIELD_HEIGHT)
    }
    SearchableSelect(
      choices.map { Triple<String?, String, String>(it.id, it.label, "") }, user, { user = it; email = "" },
      Modifier.width(200.dp), chip = false, minHeight = FIELD_HEIGHT,
      placeholder = if (choices.isEmpty()) "Nobody left to add" else "Someone…",
    )
    Txt("or", size = 12.5.sp, color = p.muted)
    FilterField(email, { email = it; user = null; state = null }, "A new e-mail address", Modifier.width(220.dp), minHeight = FIELD_HEIGHT)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Button(if (busy) "Adding…" else if (byAddress) "Invite" else "Add", enabled = !busy && (if (byAddress) EmailAddresses.valid(address) else user != null)) { add() }
      InfoTip("Only an address that has no account gets an invitation; someone who has one is picked by name.")
    }
  }
  state?.let { (ok, text) -> Txt(text, size = 12.5.sp, color = if (ok) p.ok else p.trap) }
}

/** One person with a part in the session: avatar, name, role, slots; [onRemove] takes the part (not a player's). */
@Composable
private fun PersonRow(person: SessionPerson, removable: Boolean, onRemove: () -> Unit) {
  val p = LocalPalette.current
  var confirm by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Avatar(person.name, 28.dp)
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PersonName(person.name, person.id, deleted = person.deleted, size = 13.5.sp)
        Pill(person.role.label, when (person.role) {
          SessionRole.MANAGER -> p.accent
          SessionRole.SUPPORT -> p.useful
          SessionRole.WATCHER -> p.muted
          SessionRole.PLAYER -> p.ok
        })
      }
      if (person.slots.isNotEmpty()) Txt(person.slots.joinToString(", "), size = 12.sp, color = p.muted, maxLines = 2)
    }
    if (removable) {
      if (confirm) {
        Txt("Take ${person.role.label.lowercase()} away?", size = 12.5.sp)
        Button("Yes", danger = true) { confirm = false; onRemove() }
        Button("Cancel") { confirm = false }
      } else {
        SmallButton("Remove", { confirm = true })
      }
    }
  }
}

/**
 * The session's share link: on (by whom, since when) or off; a new one (shown once here, to copy), turning it off.
 * Whoever has it sees the session's dashboard without a login: no passwords, no spoiler.
 */
@Composable
private fun ShareLinkPart(id: String) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  var view by remember { mutableStateOf<ShareLinkView?>(null) }
  var link by remember { mutableStateOf<String?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  var confirm by remember { mutableStateOf(false) }
  suspend fun load() {
    when (val r = call("GET", "api/sessions/$id/share", null) { ApiJson.decodeFromString(ShareLinkView.serializer(), it) }) {
      is Answer.Ok -> view = r.value
      is Answer.Failed -> problem = r.message
    }
  }
  LaunchedEffect(id) { load() }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt("Share link", size = 13.5.sp, weight = FontWeight.SemiBold)
      InfoTip(
        "For a stream, or friends without an account: whoever has the link sees the dashboard, read-only and without a " +
          "login. No slot passwords, no spoiler log. A new link replaces the old one."
      )
      val current = view
      Txt(
        when {
          current == null -> "…"
          !current.on -> "off"
          else -> "on since ${current.createdAt?.let { t.date(it) } ?: "…"}" + (current.createdBy?.let { ", made by $it" } ?: "")
        },
        size = 12.5.sp, color = p.muted, modifier = Modifier.weight(1f),
      )
      Button(if (current?.on == true) "New link" else "Make a link") {
        scope.launch {
          when (val r = call("POST", "api/sessions/$id/share", null) { ApiJson.decodeFromString(ShareLinkView.serializer(), it) }) {
            is Answer.Ok -> { view = r.value; link = r.value.token?.let { Platform.pageUrl() + Route.Watch(it).hash }; problem = null }
            is Answer.Failed -> problem = r.message
          }
        }
      }
      if (current?.on == true) {
        if (confirm) {
          Button("Yes, off", danger = true) {
            confirm = false
            scope.launch {
              problem = (call("DELETE", "api/sessions/$id/share", null) { } as? Answer.Failed)?.message
              link = null
              load()
            }
          }
          Button("Cancel") { confirm = false }
        } else {
          Button("Turn off", danger = true) { confirm = true }
        }
      }
    }
    link?.let {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Mono(it, p.text, Modifier.weight(1f))
        SmallButton("Copy", { Platform.copy(it) })
      }
      Txt("Shown only now: copy it. The old link doesn't work any more.", size = 12.sp, color = p.muted)
    }
    problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
  }
}
