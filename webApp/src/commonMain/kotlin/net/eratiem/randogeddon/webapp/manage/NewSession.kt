package net.eratiem.randogeddon.webapp.manage

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.AdminOverview
import net.eratiem.randogeddon.shared.AdminSessionView
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.CreateSessionRequest
import net.eratiem.randogeddon.shared.CreateSessionResult
import net.eratiem.randogeddon.shared.EmailAddresses
import net.eratiem.randogeddon.shared.GenerationProgress
import net.eratiem.randogeddon.shared.GenerationStage
import net.eratiem.randogeddon.shared.GenerationStep
import net.eratiem.randogeddon.shared.InspectRequest
import net.eratiem.randogeddon.shared.InspectResult
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.SessionIds
import net.eratiem.randogeddon.shared.SessionOptions
import net.eratiem.randogeddon.shared.UploadedYaml
import net.eratiem.randogeddon.shared.UserRef
import net.eratiem.randogeddon.shared.WorldPlan
import net.eratiem.randogeddon.shared.YamlWorld
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.shared.i18n.texts
import net.eratiem.randogeddon.webapp.api.Answer
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.word
import net.eratiem.randogeddon.webapp.ui.AppPopup
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.DropdownSearch
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.GameChip
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.InfoTip
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Mono
import net.eratiem.randogeddon.webapp.ui.ProgressBar
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Select
import net.eratiem.randogeddon.webapp.ui.StepSlider
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.hand
import net.eratiem.randogeddon.webapp.ui.justClosed
import net.eratiem.randogeddon.webapp.ui.scrolling

/** Archipelago's choices for release and collect (see .env.example). */
private val RELEASE_MODES = listOf("auto", "enabled", "disabled", "goal", "auto-enabled")
private val REMAINING_MODES = listOf("enabled", "disabled", "goal")

/** How the locks of a new session are chosen. */
private enum class LockMode { CHOOSE, RANDOM, MIXED }

/** A slot's lock with mixed locks: the switch's three stops, left to right. */
private enum class LockChoice { OPEN, RANDOM, LOCKED }

/**
 * A new session: its ID (a suggestion to start with; checked as it is typed) and the form (YAMLs, who plays which
 * slot, locks, options). Its logins are shown once it is created.
 */
@Composable
fun NewSessionSection(overview: AdminOverview?, administrator: Boolean, onCreated: (CreateSessionResult) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var id by remember { mutableStateOf<String?>(null) }
  var created by remember { mutableStateOf<CreateSessionResult?>(null) }
  // A new form (and a new suggestion) after each session
  var round by remember { mutableStateOf(0) }
  // The first suggestion stays for this form: the panel loads its overview again every 15 s, each time with a new
  // one, and the ID shown (and generated with) mustn't change under the user's hands
  val suggested = remember(round, overview != null) { overview?.suggestedId.orEmpty() }
  val chosen = id ?: suggested
  val taken = overview?.taken?.contains(chosen) == true || overview?.sessions?.any { it.id == chosen } == true
  // What the server says about the ID (a word nobody wants in one), asked a moment after the last key
  var idRefused by remember { mutableStateOf<String?>(null) }
  val formProblem = if (chosen.isEmpty()) null else SessionIds.problem(chosen)?.let { t.problems.text(it) } ?: if (taken) t.sessionAdmin.taken else null
  LaunchedEffect(chosen) {
    idRefused = null
    if (chosen.isEmpty() || formProblem != null) return@LaunchedEffect
    delay(400)
    idRefused = (call("GET", "api/sessions/new/id?id=$chosen", null) { } as? Answer.Failed)?.message
  }
  val idProblem = formProblem ?: idRefused
  // While it generates, the form makes room for the ID and the bar
  var generating by remember { mutableStateOf(false) }
  // As many game servers run as the Administrators allow (0: no limit)
  val full = overview != null && overview.capacity > 0 && overview.inUse >= overview.capacity
  // The user manages as many running sessions as the Administrators allow
  val atLimit = overview?.left == 0
  Section(t.sessionAdmin.newSession, subtitle = overview?.capacity?.let(t.sessionAdmin::newSessionSubtitle) ?: "…") {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      created?.let { result ->
        Created(result.locked, onDone = { created = null })
      }
      if (!generating) {
        if (full) Txt(t.sessionAdmin.everyServerInUse, size = 12.5.sp, color = p.trap)
        if (atLimit) Txt(t.sessionAdmin.atLimit, size = 12.5.sp, color = p.trap)
        overview?.left?.takeIf { it > 0 }?.let { Txt(t.sessionAdmin.mayCreate(it), size = 12.5.sp, color = p.muted) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Txt(t.sessionAdmin.sessionId, size = 13.sp, weight = FontWeight.SemiBold)
          InfoTip(t.tips.sessionId)
          FilterField(chosen, { id = it.lowercase().trim() }, "friday-chaos", Modifier.width(220.dp), minHeight = FIELD_HEIGHT)
          idProblem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
        }
      }
      if (overview != null) key(round) {
        CreateSession(
          AdminSessionView(apVersions = overview.apVersions, users = overview.users, people = overview.people), chosen,
          administrator = administrator,
          ready = idProblem == null && chosen.isNotEmpty() && !full && !atLimit,
          onBusy = { generating = it },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateSession(
  view: AdminSessionView,
  id: String,
  ready: Boolean,
  onCreated: (CreateSessionResult) -> Unit,
  administrator: Boolean,
  /** It generates (or stopped): the form around this hides what it shows besides. */
  onBusy: (Boolean) -> Unit = {},
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  val yamls = remember { mutableStateListOf<UploadedYaml>() }
  var worlds by remember { mutableStateOf<List<YamlWorld>>(emptyList()) }
  var problems by remember { mutableStateOf<List<ApiError>>(emptyList()) }
  // Slot -> the IDs of its players; the accounts to pick from, with those invited from here
  val users = remember { mutableStateMapOf<String, List<String>>() }
  // The hosted slots: no players, their games run as servers of their own
  val hosted = remember { mutableStateMapOf<String, Boolean>() }
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
  // The ID it was sent with: what the bar asks about and shows, whatever happens to the form's meanwhile
  var sentId by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<Answer.Failed?>(null) }
  // How far the generation is while it runs (asked about three times a second)
  var progress by remember { mutableStateOf<GenerationProgress?>(null) }
  LaunchedEffect(busy) {
    onBusy(busy)
    progress = null
    while (busy) {
      delay(300)
      (call("GET", "api/sessions/new/progress/${sentId ?: id}", null) {
        if (it.isBlank()) null else ApiJson.decodeFromString(GenerationProgress.serializer(), it)
      } as? Answer.Ok)?.value?.let { progress = it }
    }
  }
  // The YAML to remove, asked once more; "": all of them
  var removing by remember { mutableStateOf<String?>(null) }
  // Why the files just picked that aren't player YAMLs weren't taken
  var refused by remember { mutableStateOf<List<String>>(emptyList()) }

  fun inspect(): Job = scope.launch {
    val body = ApiJson.encodeToString(InspectRequest.serializer(), InspectRequest(yamls.toList(), version))
    when (val answer = call("POST", "api/sessions/new/inspect", body, InspectResult.serializer())) {
      is Answer.Ok -> {
        val invalid = answer.value.invalid.toSet()
        if (invalid.isNotEmpty()) {
          // Not taken at all; the others are checked again without them
          refused = refused + answer.value.problems.filter { it.args["file"] in invalid }.map { t.word(it) }
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
      }
      is Answer.Failed -> problems = listOf(ApiError(answer.message))
    }
  }
  // Another release may have other games
  LaunchedEffect(version) { if (yamls.isNotEmpty()) inspect() }

  // While it generates: nothing of the form (there is nothing to change now), just the session's ID, the stage
  // and about how far. A failure brings the form back as it was
  if (busy) {
    val now = progress ?: GenerationProgress(GenerationStage.CHECKING, 0)
    Txt(sentId ?: id, size = 18.sp, weight = FontWeight.Bold)
    Txt(t.sessionAdmin.generatesSeed, size = 12.5.sp, color = p.muted)
    // The four steps: the ones done with a check mark, the one it is at in the accent colour
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      GenerationStep.entries.forEach { step ->
        val done = step < now.stage.step
        val current = step == now.stage.step
        val color = if (done) p.ok else if (current) p.accent else p.muted
        Row(
          Modifier.clip(CircleShape).background(color.copy(alpha = if (current) 0.16f else 0.1f)).padding(horizontal = 10.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          if (done) DrawnIcon(Icon.CHECK, color, 11.dp)
          Txt(
            "${step.ordinal + 1}. ${t.sessionAdmin.generationStep(step)}", size = 12.sp, color = color, maxLines = 1,
            weight = if (current) FontWeight.SemiBold else FontWeight.Normal,
          )
        }
      }
    }
    ProgressBar(now.percent / 100f)
    Txt("${t.sessionAdmin.generationStage(now.stage)} ${now.percent} %", size = 12.5.sp, color = p.muted)
    return
  }

  Txt(t.sessionAdmin.stepYamls, size = 13.sp, weight = FontWeight.SemiBold)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(t.sessionAdmin.addYamls) {
      Platform.pickFiles(".yaml,.yml") { json ->
        val picked = runCatching { ApiJson.decodeFromString(ListSerializer(UploadedYaml.serializer()), json) }.getOrDefault(emptyList())
        // Choosing again adds files; one with the same name replaces the earlier one
        picked.forEach { file -> yamls.removeAll { it.fileName == file.fileName }; yamls += file }
        refused = emptyList()
        inspect()
      }
    }
    if (yamls.isNotEmpty() && removing == null) Button(t.sessionAdmin.clearAll, danger = true) { removing = "" }
  }
  // Asked once more: removing all of them right below their buttons, one of them below the YAMLs
  @Composable
  fun ConfirmRemoval(file: String) = Confirm(
    if (file.isEmpty()) t.sessionAdmin.removeAll(yamls.size) else t.sessionAdmin.removeFile(file),
    t.apworlds.yesRemove, danger = true,
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
  refused.forEach { Txt(t.sessionAdmin.notAdded(it), size = 12.5.sp, color = p.trap) }
  problems.filter { it.code != Problem.NO_APWORLD }.forEach { Txt(t.word(it), size = 12.5.sp, color = p.trap) }
  // YAMLs with a game the release has no APWorld for: no slots, one message; checked again by themselves meanwhile (an
  // upload by anyone, anywhere, makes them slots)
  val missingGames = problems.filter { it.code == Problem.NO_APWORLD }.mapNotNull { it.args["game"] }.toSet()
  val waitingFiles = worlds.filter { w -> w.game.split(" / ").any { it in missingGames } }.map { it.fileName }.toSet()
  if (waitingFiles.isNotEmpty()) {
    val files = waitingFiles.joinToString(", ") { file ->
      "$file (${worlds.filter { it.fileName == file }.flatMap { it.game.split(" / ") }.filter { it in missingGames }.distinct().joinToString(", ")})"
    }
    Txt(
      "${t.sessionAdmin.missingApworlds(files, version.orEmpty())} " +
        if (administrator) t.sessionAdmin.uploadThem else t.sessionAdmin.askAdministrator,
      size = 12.5.sp, color = p.warn,
    )
    LaunchedEffect(Unit) {
      while (true) {
        delay(8_000)
        inspect().join()
      }
    }
  }
  val usable = worlds.filter { it.fileName !in waitingFiles }

  if (usable.isNotEmpty()) {
    // A single slot can't be locked; otherwise at least one slot starts open
    val n = usable.size
    val lockable = n > 1
    val mode = if (lockable) lockMode else LockMode.CHOOSE
    // A switch left to chance whose outcome is already given by the others shows that outcome: e.g. of two slots,
    // one open, the other has to be locked
    val chosen = usable.associate { it.name to (choices[it.name] ?: LockChoice.RANDOM) }
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
      LockMode.CHOOSE -> usable.filter { lockable && locked[it.name] == true }
      LockMode.RANDOM -> emptyList()
      LockMode.MIXED -> usable.filter { choice(it) == LockChoice.LOCKED }
    }.map { it.name }.toSet()
    val pool = when (mode) {
      LockMode.CHOOSE -> 0
      LockMode.RANDOM -> n
      LockMode.MIXED -> usable.count { choice(it) == LockChoice.RANDOM }
    }
    val sureOpen = n - lockedNow.size - pool
    // How many start open in all: at least one; with nothing locked for sure, one of the random ones is. Nothing left
    // to chance: every slot says open or locked, as when they are chosen
    val openRange = if (pool == 0) null else maxOf(1, sureOpen)..(if (lockedNow.isEmpty()) minOf(sureOpen + pool, n - 1) else sureOpen + pool)
    val open = openRange?.let { (openCount ?: (n / 2)).coerceIn(it) }
    // A slot can be locked while another one can still start open
    val canLock = n - lockedNow.size > 1
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Txt(t.sessionAdmin.stepPlayers, size = 13.sp, weight = FontWeight.SemiBold)
      InfoTip(t.tips.players)
    }
    Txt(t.sessionAdmin.playersHint, size = 12.sp, color = p.muted)
    if (lockable) {
      Labeled(t.sessionAdmin.locks, t.tips.locks, t.tips.lockModes) {
        Select(
          LockMode.entries.map {
            it to when (it) {
              LockMode.CHOOSE -> t.sessionAdmin.lockChoose
              LockMode.RANDOM -> t.sessionAdmin.lockRandom
              LockMode.MIXED -> t.sessionAdmin.lockMixed
            }
          },
          mode, { lockMode = it }, minHeight = FIELD_HEIGHT)
      }
      // On a line of its own: room for the slider on a phone
      if (open != null && openRange != null) {
        Row(
          Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = FIELD_HEIGHT),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Txt(t.sessionAdmin.startOpen, size = 12.5.sp, color = p.muted, maxLines = 1)
          InfoTip(t.tips.openSlots)
          if (openRange.first < openRange.last) {
            // The slider's own ends, then what it is set to
            Txt("${openRange.first}", size = 12.sp, color = p.muted, maxLines = 1)
            StepSlider(open, openRange, { openCount = it }, Modifier.weight(1f))
            Txt("${openRange.last}", size = 12.sp, color = p.muted, maxLines = 1)
          } else {
            Spacer(Modifier.weight(1f))
          }
          Txt(t.sessionAdmin.openOf(open, n), size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
        }
      }
    }
    usable.forEach { world ->
      val isLocked = world.name in lockedNow
      WorldRow(
        world, users[world.name].orEmpty(), people, hosted = hosted[world.name] == true,
        onHosted = { yes -> hosted[world.name] = yes; if (yes) users[world.name] = emptyList() },
        hostedLabel = t.sessionAdmin.hostedCheck,
        onInvite = { email ->
          when (val r = inviteForSession(email)) {
            is Answer.Ok -> r.value.also { people += it }
            is Answer.Failed -> { error = r; null }
          }
        },
        lock = {
          when (mode) {
            // The last open slot stays open
            LockMode.CHOOSE -> if (lockable) {
              val enabled = isLocked || canLock
              Check(t.sessionAdmin.startsLocked, isLocked, if (enabled) t.tips.startsLocked else t.tips.lastOpen, enabled = enabled) {
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

    Txt(t.sessionAdmin.stepOptions, size = 13.sp, weight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Check(t.sessionAdmin.pointHintsCheck, options.pointHints, t.tips.pointHints) { options = options.copy(pointHints = it) }
      Check(t.sessionAdmin.automaticHintsCheck, options.autoHints, t.tips.autoHints) { options = options.copy(autoHints = it) }
      if (pool > 0 || lockedNow.isNotEmpty()) {
        Check(t.sessionAdmin.keysEarly, options.earlyKeys, t.tips.earlyKeys) { options = options.copy(earlyKeys = it) }
      }
      Check(t.sessionAdmin.cheatHints, options.cheatHints, t.tips.cheatHints) { options = options.copy(cheatHints = it) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (options.pointHints) {
        LabeledNumber(t.sessionAdmin.hintCost, options.hintCost, t.tips.hintCost) { options = options.copy(hintCost = it) }
        LabeledNumber(t.sessionAdmin.pointsPerCheck, options.locationCheckPoints, t.tips.checkPoints) { options = options.copy(locationCheckPoints = it) }
      }
      Labeled(t.sessionAdmin.release, t.tips.release, t.tips.releaseChoices) { Select(RELEASE_MODES.map { it to it }, options.releaseMode, { options = options.copy(releaseMode = it) }, minHeight = FIELD_HEIGHT) }
      Labeled(t.sessionAdmin.collect, t.tips.collect, t.tips.collectChoices) { Select(RELEASE_MODES.map { it to it }, options.collectMode, { options = options.copy(collectMode = it) }, minHeight = FIELD_HEIGHT) }
      Labeled(t.sessionAdmin.remaining, t.tips.remaining, t.tips.remainingChoices) { Select(REMAINING_MODES.map { it to it }, options.remainingMode, { options = options.copy(remainingMode = it) }, minHeight = FIELD_HEIGHT) }
      when {
        view.apVersions.size > 1 -> Labeled(t.sessionAdmin.archipelago, t.tips.releaseVersion) {
          Select(view.apVersions.map { it to it }, version ?: "", { version = it }, minHeight = FIELD_HEIGHT)
        }
        version != null -> Labeled(t.sessionAdmin.archipelagoVersion(version.orEmpty()), t.tips.releaseVersion) {}
      }
    }
    if (view.apVersions.isEmpty()) Txt(t.apworlds.noRelease, size = 12.5.sp, color = p.trap)
    val openOk = lockedNow.size < n
    // Every slot played by someone, or hosted
    val unplayed = usable.filter { hosted[it.name] != true && users[it.name].isNullOrEmpty() }.map { it.name }
    if (unplayed.isNotEmpty()) Txt(t.sessionAdmin.slotsWithoutPlayer(unplayed.joinToString(", ")), size = 12.5.sp, color = p.warn)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        t.sessionAdmin.generateAndStart,
        enabled = ready && problems.none { it.code != Problem.NO_APWORLD } && view.apVersions.isNotEmpty() && openOk &&
          unplayed.isEmpty(),
      ) {
        busy = true
        sentId = id
        error = null
        scope.launch {
          val request = CreateSessionRequest(
            id = id,
            yamls = yamls.filter { it.fileName !in waitingFiles },
            worlds = usable.map {
              WorldPlan(
                it.name, userIds = if (hosted[it.name] == true) emptyList() else users[it.name].orEmpty(),
                locked = it.name in lockedNow, random = mode == LockMode.MIXED && choice(it) == LockChoice.RANDOM,
                hosted = hosted[it.name] == true,
              )
            },
            options = options.copy(openSlots = open),
            apVersion = version,
          )
          val body = ApiJson.encodeToString(CreateSessionRequest.serializer(), request)
          when (val answer = call("POST", "api/sessions", body, CreateSessionResult.serializer())) {
            is Answer.Ok -> onCreated(answer.value)
            is Answer.Failed -> error = answer
          }
          busy = false
        }
      }
    }
    error?.let { GenerationError(it.message, it.detail) }
  }
}

/** A failed generation: why (the suspected games), Generate.py's output ([detail]) below in monospace. */
@Composable
private fun GenerationError(message: String, detail: String?) {
  val p = LocalPalette.current
  if (detail == null) {
    Mono(message, color = p.trap)
  } else {
    Txt(message, size = 12.5.sp, color = p.trap)
    Mono(detail)
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
  /** Hosted (its checkbox, asked once more): no players, and no picker to add them. */
  hosted: Boolean,
  onHosted: (Boolean) -> Unit,
  hostedLabel: String,
  /** Invites an address from here: the new account, or `null` (the form shows why). */
  onInvite: suspend (String) -> UserRef?,
  lock: @Composable () -> Unit,
  onUsers: (List<String>) -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var asking by remember { mutableStateOf(false) }
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
        Check(hostedLabel, hosted, t.sessionAdmin.hostedTip) { yes -> if (yes) asking = true else onHosted(false) }
      }
      if (asking) {
        Confirm(t.sessionAdmin.hostedQuestion, t.sessionAdmin.yesHosted, danger = false,
          onYes = { asking = false; onHosted(true) }, onCancel = { asking = false })
      }
      if (hosted) Txt(t.sessionAdmin.hostedLine, size = 12.sp, color = p.muted)
      else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        users.forEach { id ->
          val user = known.firstOrNull { it.id == id }
          UserChip(user?.label ?: id, invited = user?.name == null, onRemove = { onUsers(users - id) })
        }
        PlayerPicker(
          known.filter { it.id !in users }, if (users.isEmpty()) t.sessionAdmin.addPlayer else t.sessionAdmin.addAnother,
          onPick = { if (it.id !in users) onUsers(users + it.id) }, onInvite = onInvite,
        )
      }
    }
  }
}

/**
 * Adds a player: a dropdown of the accounts ([choices]) with a search field on top; a typed e-mail address that no
 * account has offers its invitation instead ([onInvite]: the new account, or `null`).
 */
@Composable
private fun PlayerPicker(
  choices: List<UserRef>,
  placeholder: String,
  onPick: (UserRef) -> Unit,
  onInvite: suspend (String) -> UserRef?,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val scope = rememberCoroutineScope()
  var open by remember { mutableStateOf(false) }
  var closedAt by remember { mutableLongStateOf(0L) }
  var query by remember { mutableStateOf("") }
  var inviting by remember { mutableStateOf(false) }
  var anchorHeight by remember { mutableStateOf(0) }
  Box(Modifier.width(190.dp).onSizeChanged { anchorHeight = it.height }) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = FIELD_HEIGHT).clip(RoundedCornerShape(10.dp)).background(p.panelAlt)
        .border(1.dp, p.border, RoundedCornerShape(10.dp))
        .hand().clickable { if (!justClosed(closedAt)) { open = !open; query = "" } }
        .padding(start = 10.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Txt(placeholder, size = 12.5.sp, color = p.muted, maxLines = 1, modifier = Modifier.weight(1f))
      DrawnIcon(Icon.CHEVRON, p.muted, 14.dp)
    }
    if (open) AppPopup(
      offset = IntOffset(0, anchorHeight + 6),
      onDismissRequest = { open = false; closedAt = Platform.now() },
      properties = PopupProperties(focusable = true),
    ) {
      Column(
        Modifier.widthIn(min = 240.dp, max = 320.dp).card(p, RoundedCornerShape(12.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        DropdownSearch(query) { query = it }
        val matches = choices.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }.take(200)
        val address = EmailAddresses.normalize(query)
        Column(Modifier.heightIn(max = 260.dp).scrolling()) {
          matches.forEach { user ->
            Txt(
              user.label, size = 13.sp, maxLines = 1, color = if (user.name == null) p.muted else p.text,
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hand()
                .clickable { onPick(user); open = false; query = "" }.padding(horizontal = 8.dp, vertical = 7.dp),
            )
          }
          when {
            matches.isNotEmpty() -> {}
            EmailAddresses.valid(address) -> Txt(
              if (inviting) t.sessionAdmin.inviting else t.sessionAdmin.inviteAddress(address), size = 13.sp, color = p.accent,
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hand().clickable {
                if (!inviting) scope.launch {
                  inviting = true
                  onInvite(address)?.let(onPick)
                  inviting = false
                  open = false
                  query = ""
                }
              }.padding(horizontal = 8.dp, vertical = 7.dp),
            )
            else -> Txt(t.sessionAdmin.noAccountMatches, size = 12.sp, color = p.muted, modifier = Modifier.padding(8.dp))
          }
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
    Txt(if (invited) LocalTexts.current.sessionAdmin.invitedName(name) else name, size = 12.5.sp, weight = FontWeight.SemiBold, color = p.hue(name), maxLines = 1)
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
    val texts = LocalTexts.current.sessionAdmin
    Txt(texts.sessionCreated, size = 13.sp, weight = FontWeight.SemiBold)
    if (locked.isNotEmpty()) Txt(texts.startLocked(locked.joinToString(", ")), size = 12.5.sp)
    Row { Button(texts.done) { onDone() } }
  }
}

/**
 * A slot's lock with mixed locks: a switch with three stops, open, left to chance, locked. Tap a stop or drag the
 * knob; locked only while [lockAllowed].
 */
@Composable
private fun LockSwitch(choice: LockChoice, lockAllowed: Boolean, onChange: (LockChoice) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val current by rememberUpdatedState(choice)
  val change by rememberUpdatedState(onChange)
  val color = when (choice) {
    LockChoice.OPEN -> p.ok
    LockChoice.RANDOM -> p.accent
    LockChoice.LOCKED -> p.locked
  }
  val position by animateFloatAsState(choice.ordinal / 2f, tween(160))
  Tip(t.tips.lockSwitch) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(
        when (choice) {
          LockChoice.OPEN -> t.sessionAdmin.lockOpen
          LockChoice.RANDOM -> t.sessionAdmin.lockChance
          LockChoice.LOCKED -> t.sessionAdmin.lockLocked
        },
        size = 12.5.sp, weight = FontWeight.SemiBold, color = color, maxLines = 1,
      )
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
            LockChoice.OPEN -> DrawnIcon(Icon.CHECK, p.onBrand, 12.dp)
            LockChoice.RANDOM -> Txt("?", size = 12.sp, color = p.onBrand, weight = FontWeight.Bold, maxLines = 1)
            LockChoice.LOCKED -> DrawnIcon(Icon.LOCK, p.onBrand, 12.dp)
          }
        }
      }
    }
  }
}

@Composable
internal fun Check(label: String, checked: Boolean, tip: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
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
        if (checked) DrawnIcon(Icon.CHECK, p.onBrand, 12.dp)
      }
      Txt(label, size = 12.5.sp, maxLines = 1)
    }
    if (tip != null) InfoTip(tip)
  }
}
