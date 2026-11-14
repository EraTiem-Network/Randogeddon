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

/** The parts a Session-Manager gives (players come with the slots). */
private val GIVEN = listOf(SessionRole.WATCHER, SessionRole.SUPPORT, SessionRole.MANAGER)

/**
 * The session's people, in the panel (its Session-Managers and Administrators): everyone with a part in it, each with
 * their role and slots; giving someone a part (Session-Manager, support or watcher) by name or a new address, and
 * taking it again. Below: the share link.
 */
@Composable
fun PeopleSection(id: String) {
  val p = LocalPalette.current
  val texts = LocalTexts.current.people
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
  Section(texts.title, subtitle = list.groupBy { it.role }.entries.sortedBy { it.key.ordinal }
    .joinToString(" · ") { (role, all) -> texts.roleCount(role, all.size) }.ifEmpty { "…" }) {
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
  val texts = LocalTexts.current.people
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
    texts.watchers, subtitle = if (watchers == null) "…" else if (list.isEmpty()) texts.nobodyWatches else texts.watching(list.size),
    actions = { InfoTip(texts.roleTip(SessionRole.WATCHER)) },
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
  val texts = LocalTexts.current.people
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
        ?: (true to if (byAddress) texts.invited(address, role) else texts.isNow(name, role))
      if (state?.first == true) { user = null; email = "" }
      busy = false
    }
  }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    if (roles.isNotEmpty()) Tip(texts.roleTip(role)) {
      SearchableSelect(roles.map { Triple(it, texts.role(it), "") }, role, { role = it }, Modifier.width(170.dp), chip = false, minHeight = FIELD_HEIGHT)
    }
    SearchableSelect(
      choices.map { Triple<String?, String, String>(it.id, it.label, "") }, user, { user = it; email = "" },
      Modifier.width(200.dp), chip = false, minHeight = FIELD_HEIGHT,
      placeholder = if (choices.isEmpty()) texts.nobodyLeft else texts.someone,
    )
    Txt(texts.or, size = 12.5.sp, color = p.muted)
    FilterField(email, { email = it; user = null; state = null }, texts.newAddress, Modifier.width(220.dp), minHeight = FIELD_HEIGHT)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Button(if (busy) texts.adding else if (byAddress) texts.invite else texts.add, enabled = !busy && (if (byAddress) EmailAddresses.valid(address) else user != null)) { add() }
      InfoTip(texts.inviteTip)
    }
  }
  state?.let { (ok, text) -> Txt(text, size = 12.5.sp, color = if (ok) p.ok else p.trap) }
}

/** One person with a part in the session: avatar, name, role, slots; [onRemove] takes the part (not a player's). */
@Composable
private fun PersonRow(person: SessionPerson, removable: Boolean, onRemove: () -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var confirm by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Avatar(person.name, 28.dp)
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PersonName(person.name, person.id, deleted = person.deleted, size = 13.5.sp)
        Pill(t.people.role(person.role), when (person.role) {
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
        Txt(t.people.takeAway(person.role), size = 12.5.sp)
        Button(t.people.yes, danger = true) { confirm = false; onRemove() }
        Button(t.common.cancel) { confirm = false }
      } else {
        SmallButton(t.people.remove, { confirm = true })
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
      Txt(t.people.shareLink, size = 13.5.sp, weight = FontWeight.SemiBold)
      InfoTip(t.people.shareLinkTip)
      val current = view
      Txt(
        when {
          current == null -> "…"
          !current.on -> t.people.off
          else -> t.people.onSince(current.createdAt?.let { t.date(it) } ?: "…", current.createdBy)
        },
        size = 12.5.sp, color = p.muted, modifier = Modifier.weight(1f),
      )
      Button(if (current?.on == true) t.people.newLink else t.people.makeLink) {
        scope.launch {
          when (val r = call("POST", "api/sessions/$id/share", null) { ApiJson.decodeFromString(ShareLinkView.serializer(), it) }) {
            is Answer.Ok -> { view = r.value; link = r.value.token?.let { Platform.pageUrl() + Route.Watch(it).hash }; problem = null }
            is Answer.Failed -> problem = r.message
          }
        }
      }
      if (current?.on == true) {
        if (confirm) {
          Button(t.people.yesOff, danger = true) {
            confirm = false
            scope.launch {
              problem = (call("DELETE", "api/sessions/$id/share", null) { } as? Answer.Failed)?.message
              link = null
              load()
            }
          }
          Button(t.common.cancel) { confirm = false }
        } else {
          Button(t.people.turnOff, danger = true) { confirm = true }
        }
      }
    }
    link?.let {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Mono(it, p.text, Modifier.weight(1f))
        SmallButton(t.common.copy, { Platform.copy(it) })
      }
      Txt(t.people.shownOnlyNow, size = 12.sp, color = p.muted)
    }
    problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
  }
}
