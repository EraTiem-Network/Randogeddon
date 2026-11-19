package net.eratiem.randogeddon.webapp.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.AddPersonRequest
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.PlayerStatus
import net.eratiem.randogeddon.shared.RoleRequest
import net.eratiem.randogeddon.shared.SessionPeople
import net.eratiem.randogeddon.shared.SessionPerson
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.ShareLinkView
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.WatcherRequest
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.duration
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.Route
import net.eratiem.randogeddon.webapp.app.clock
import net.eratiem.randogeddon.webapp.app.date
import net.eratiem.randogeddon.webapp.app.hash
import net.eratiem.randogeddon.webapp.dashboard.Fact
import net.eratiem.randogeddon.webapp.dashboard.PersonName
import net.eratiem.randogeddon.webapp.dashboard.playTime
import net.eratiem.randogeddon.webapp.dashboard.statusColor
import net.eratiem.randogeddon.webapp.dashboard.statusLabel
import net.eratiem.randogeddon.webapp.ui.Avatar
import net.eratiem.randogeddon.webapp.ui.AvatarPill
import net.eratiem.randogeddon.webapp.ui.DialogCard
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.GameChip
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Mono
import net.eratiem.randogeddon.webapp.ui.Pill
import net.eratiem.randogeddon.webapp.ui.ProgressBar
import net.eratiem.randogeddon.webapp.ui.SearchableSelect
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.scrolling

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
  // Who looks (their own Session-Manager part isn't theirs to take, unless an Administrator)
  var you by remember { mutableStateOf<SessionPeople?>(null) }
  var choices by remember { mutableStateOf<List<UserRef>>(emptyList()) }
  var problem by remember { mutableStateOf<String?>(null) }
  suspend fun load() {
    when (val r = call("GET", "api/sessions/$id/people", null, SessionPeople.serializer())) {
      is Answer.Ok -> { people = r.value.people; you = r.value; problem = null }
      is Answer.Failed -> problem = r.message
    }
    (call("GET", "api/sessions/$id/people/choices", null, ListSerializer(UserRef.serializer())) as? Answer.Ok)
      ?.let { choices = it.value }
  }
  LaunchedEffect(id) { load() }
  val list = people.orEmpty()
  // The person whose panel is open
  var open by remember(id) { mutableStateOf<String?>(null) }
  list.firstOrNull { it.id == open }?.let { person ->
    val managers = list.count { it.role == SessionRole.MANAGER }
    PersonPanel(
      id, person,
      // Their own Session-Manager part isn't theirs to take (unless an Administrator), the last one's nobody's
      mayLeave = person.role != SessionRole.MANAGER || (managers > 1 && (person.id != you?.you || you?.mayLeave == true)),
      lastManager = person.role == SessionRole.MANAGER && managers <= 1,
      onChange = { role ->
        val body = role?.let { ApiJson.encodeToString(RoleRequest.serializer(), RoleRequest(it)) }
        val r = if (role == null) call("DELETE", "api/sessions/$id/people/${person.id}", null) { }
        else call("PUT", "api/sessions/$id/people/${person.id}", body) { }
        load()
        (r as? Answer.Failed)?.message
      },
      onClose = { open = null },
    )
  }
  Section(texts.title, subtitle = list.groupBy { it.role }.entries.sortedBy { it.key.ordinal }
    .joinToString(" · ") { (role, all) -> texts.roleCount(role, all.size) }.ifEmpty { "…" }) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      AddPerson(GIVEN, choices) { role, userId, email ->
        val body = ApiJson.encodeToString(AddPersonRequest.serializer(), AddPersonRequest(role, userId, email))
        when (val r = call("POST", "api/sessions/$id/people", body, SessionPerson.serializer())) {
          is Answer.Ok -> { load(); null }
          is Answer.Failed -> r.message
        }
      }
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      if (list.isNotEmpty()) {
        Divider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          list.forEach { person -> PersonPill(person) { open = person.id } }
        }
      }
      Divider()
      ShareLinkPart(id)
    }
  }
}

/**
 * For the session's players (on the dashboard) and its support (in the panel): who watches it with them, adding watchers (by name, or a new address)
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
    when (val r = call("GET", "api/sessions/$id/watchers", null, SessionPeople.serializer())) {
      is Answer.Ok -> { watchers = r.value.people; problem = null }
      is Answer.Failed -> problem = r.message
    }
    (call("GET", "api/sessions/$id/watchers/choices", null, ListSerializer(UserRef.serializer())) as? Answer.Ok)
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
        when (val r = call("POST", "api/sessions/$id/watchers", body, SessionPerson.serializer())) {
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

/** The colour of [role]'s pill. */
@Composable
private fun roleColor(role: SessionRole) = LocalPalette.current.let { p ->
  when (role) {
    SessionRole.MANAGER -> p.accent
    SessionRole.SUPPORT -> p.useful
    SessionRole.WATCHER -> p.muted
    SessionRole.PLAYER -> p.ok
  }
}

/** One person of the session as a pill: avatar, name, their part; a click opens their panel. */
@Composable
private fun PersonPill(person: SessionPerson, onClick: () -> Unit) {
  val t = LocalTexts.current
  val color = roleColor(person.role)
  AvatarPill(person.name, color.copy(alpha = 0.14f), null, onClick) {
    PersonName(person.name, person.id, deleted = person.deleted, size = 12.5.sp)
    Txt(t.people.role(person.role), size = 11.5.sp, color = color, maxLines = 1)
  }
}

/**
 * One person's panel, floating over the panel: their part, their slots (each with its game, how far and how it stands)
 * and how far they are in the session; another part for them, or taking theirs. [onChange] gives them a role (`null`:
 * takes their part, a player only plays then) and answers the problem, or `null`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonPanel(
  id: String,
  person: SessionPerson,
  mayLeave: Boolean,
  lastManager: Boolean,
  onChange: suspend (SessionRole?) -> String?,
  onClose: () -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val texts = t.people
  val scope = rememberCoroutineScope()
  var summary by remember(id) { mutableStateOf<Summary?>(null) }
  var problem by remember(person.id) { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var confirm by remember(person.id) { mutableStateOf(false) }
  LaunchedEffect(id) {
    (call("GET", "api/sessions/$id/state", null, Summary.serializer()) as? Answer.Ok)
      ?.let { summary = it.value }
  }
  val now = Platform.now()
  val stats = summary?.people?.firstOrNull { it.userId == person.id || (it.userId == null && it.name == person.name) }
  val worlds = summary?.players.orEmpty().filter { it.name in person.slots }
  fun change(role: SessionRole?) {
    busy = true
    scope.launch {
      problem = onChange(role)
      busy = false
      confirm = false
    }
  }
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    SelectionContainer {
      DialogCard(560.dp, header = {
        Avatar(person.name, 40.dp)
        Column(Modifier.weight(1f)) {
          PersonName(person.name, person.id, deleted = person.deleted, size = 18.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Pill(texts.role(person.role), roleColor(person.role)) }
        }
        stats?.let { Txt("${t.decimal(it.percent)}%", size = 22.sp, weight = FontWeight.Bold, brush = p.brand) }
        IconButton(onClose, Icon.CLOSE)
      }) {
        Column(Modifier.scrolling().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          if (stats != null) {
            ProgressBar((stats.percent / 100).toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Fact(texts.progressLabel, "${stats.checked} / ${stats.total}")
              Fact(t.dialogs.goals, "${stats.goals} / ${worlds.size}", if (stats.goals > 0) p.ok else p.text)
              Fact(texts.playedLabel, t.duration(worlds.sumOf { playTime(it, now) }))
            }
            Txt(
              "${texts.lastSeenLabel}: " + when {
                stats.online -> texts.nowOnline
                else -> stats.lastActivity?.let { t.date(it) } ?: texts.never
              },
              size = 12.5.sp, color = if (stats.online) p.ok else p.muted,
            )
          }
          Txt(texts.slotsLabel, size = 12.sp, color = p.muted, weight = FontWeight.SemiBold)
          if (person.slots.isEmpty()) Txt(texts.noSlots, size = 13.sp, color = p.muted)
          person.slots.forEach { slot ->
            val w = worlds.firstOrNull { it.name == slot }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              GameChip(w?.game.orEmpty(), dimmed = w?.status == PlayerStatus.LOCKED, size = 28.dp)
              Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Txt(slot, size = 13.5.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                  w?.let { Pill(statusLabel(t, it), statusColor(it.status)) }
                }
                w?.let {
                  Txt(
                    t.dialogs.slotLine(it.checked, it.total, t.duration(playTime(it, now)), it.goalAt?.let { at -> t.clock(at) }),
                    size = 11.5.sp, color = p.muted, maxLines = 1,
                  )
                  ProgressBar((it.percent / 100).toFloat(), height = 4.dp, dimmed = it.status == PlayerStatus.LOCKED)
                }
              }
            }
          }
          Divider()
          Txt(texts.partLabel, size = 12.sp, color = p.muted, weight = FontWeight.SemiBold)
          if (lastManager) Txt(texts.lastManager, size = 12.5.sp, color = p.warn)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A player gets a part besides their slots; leaving Session-Manager only where allowed
            val roles = listOf(SessionRole.MANAGER, SessionRole.SUPPORT, SessionRole.WATCHER)
              .filter { it != person.role && (person.slots.isEmpty() || it != SessionRole.WATCHER) }
            if (mayLeave) roles.forEach { role -> SmallButton(texts.makeRole(role), { if (!busy) change(role) }) }
            if (person.role != SessionRole.PLAYER && mayLeave) {
              if (confirm) {
                Txt(texts.takeAway(person.role), size = 12.5.sp)
                Button(texts.yes, danger = true, enabled = !busy) { change(null) }
                Button(t.common.cancel) { confirm = false }
              } else {
                SmallButton(if (person.slots.isEmpty()) texts.remove else texts.onlyPlayer, { if (!busy) confirm = true })
              }
            }
          }
          problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
        }
      }
    }
  }
}

/** A watcher: avatar, name, role; [onRemove] takes the part. */
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
        Pill(t.people.role(person.role), roleColor(person.role))
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
    when (val r = call("GET", "api/sessions/$id/share", null, ShareLinkView.serializer())) {
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
          when (val r = call("POST", "api/sessions/$id/share", null, ShareLinkView.serializer())) {
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
        Mono(it, Modifier.weight(1f))
        SmallButton(t.common.copy, { Platform.copy(it) })
      }
      Txt(t.people.shownOnlyNow, size = 12.sp, color = p.muted)
    }
    problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
  }
}
