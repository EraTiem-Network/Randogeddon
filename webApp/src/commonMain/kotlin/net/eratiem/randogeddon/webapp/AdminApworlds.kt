package net.eratiem.randogeddon.webapp

import net.eratiem.randogeddon.shared.i18n.fileSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.border
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ApworldFile
import net.eratiem.randogeddon.shared.ApworldRemoval
import net.eratiem.randogeddon.shared.ApworldResult
import net.eratiem.randogeddon.shared.ApworldResults
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.ApworldUploadFile
import net.eratiem.randogeddon.shared.ArchipelagoInfo
import net.eratiem.randogeddon.shared.GameSource
import net.eratiem.randogeddon.shared.ReleaseInfo
import net.eratiem.randogeddon.shared.UploadedYaml

/**
 * The admin panel's apworlds: per Archipelago release of the gateway its games, the apworlds of its library (upload,
 * remove), the ones that didn't load, and the apworlds of other releases to try with it.
 */
@Composable
fun ApworldsSection() {
  val p = LocalPalette.current
  val texts = LocalTexts.current.apworlds
  val scope = rememberCoroutineScope()
  var info by remember { mutableStateOf<ArchipelagoInfo?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  // What the last uploads, try or removal did (one line each); or that one is going on
  var busy by remember { mutableStateOf<String?>(null) }
  var outcome by remember { mutableStateOf<List<Pair<Boolean, String>>>(emptyList()) }
  // An upload that would replace something, and the answer it waits for (true: replace)
  var asking by remember { mutableStateOf<Pair<ApworldResult, CompletableDeferred<Boolean>>?>(null) }

  fun load() = scope.launch {
    when (val answer = call("GET", "api/admin/apworlds", null) { ApiJson.decodeFromString(ArchipelagoInfo.serializer(), it) }) {
      is Answer.Ok -> { info = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  LaunchedEffect(Unit) { load() }

  /** A try: the release lists its games again, which takes a moment. */
  suspend fun change(label: String, path: String, body: String): Answer<ApworldResult> {
    busy = label
    return call("POST", path, body) { ApiJson.decodeFromString(ApworldResult.serializer(), it) }
  }

  fun line(r: ApworldResult): Pair<Boolean, String> =
    if (r.error != null) false to texts.notAdded(r.file, r.error.orEmpty())
    else true to texts.brings(r.file, r.games.joinToString(", "))

  /** [files] of a release together: it lists its games once for all of them. */
  suspend fun send(release: String, files: List<UploadedYaml>, replace: Boolean, label: String): Answer<ApworldResults> {
    busy = label
    val body = ApiJson.encodeToString(
      ApworldUpload.serializer(),
      ApworldUpload(release, files.map { ApworldUploadFile(it.fileName, it.content) }, replace),
    )
    return call("POST", "api/admin/apworlds", body) { ApiJson.decodeFromString(ApworldResults.serializer(), it) }
  }

  /** Whether [held] may replace what it would: the question, until it is answered. */
  suspend fun ask(held: ApworldResult): Boolean {
    val decision = CompletableDeferred<Boolean>()
    asking = held to decision
    return decision.await().also { asking = null }
  }

  fun upload(release: String) {
    Platform.pickFiles(".apworld", binary = true) { json ->
      val files = runCatching { ApiJson.decodeFromString(ListSerializer(UploadedYaml.serializer()), json) }.getOrDefault(emptyList())
      if (files.isEmpty()) return@pickFiles
      outcome = emptyList()
      // As few requests as the size allows; then the ones that would replace something, once they may
      val parts = files.fold(mutableListOf<MutableList<UploadedYaml>>()) { parts, file ->
        val last = parts.lastOrNull()
        if (last != null && last.sumOf { it.content.length } + file.content.length <= BATCH_BASE64) last += file
        else parts += mutableListOf(file)
        parts
      }
      scope.launch {
        parts.forEachIndexed { i, part ->
          val label = texts.uploading(part.joinToString(", ") { it.fileName }, i + 1, parts.size)
          val results = when (val answer = send(release, part, replace = false, label)) {
            is Answer.Ok -> answer.value.results
            is Answer.Failed -> { outcome = outcome + (false to answer.message); return@forEachIndexed }
          }
          outcome = outcome + results.filter { it.conflict == null }.map(::line)
          load()
          val held = part.zip(results).filter { (_, r) -> r.conflict != null }
          val replacing = held.filter { (_, r) -> ask(r) }
          outcome = outcome + (held - replacing.toSet()).map { (_, r) -> false to texts.skipped(r.file) }
          if (replacing.isEmpty()) return@forEachIndexed
          when (val answer = send(release, replacing.map { it.first }, replace = true, label)) {
            is Answer.Ok -> outcome = outcome + answer.value.results.map(::line)
            is Answer.Failed -> outcome = outcome + (false to answer.message)
          }
          load()
        }
        busy = null
      }
    }
  }

  fun tryWith(release: String, file: ApworldFile) {
    outcome = emptyList()
    scope.launch {
      val body = ApiJson.encodeToString(ApworldTry.serializer(), ApworldTry(release, file.release, file.file))
      outcome = listOf(
        when (val answer = change(texts.trying(file.file, release), "api/admin/apworlds/try", body)) {
          is Answer.Ok -> line(answer.value)
          is Answer.Failed -> false to answer.message
        },
      )
      busy = null
      load()
    }
  }

  /** [files] of [release] out of its library, together. */
  fun remove(release: String, files: List<ApworldFile>) {
    val names = files.map { it.file }
    busy = texts.removing(names.joinToString(", "))
    outcome = emptyList()
    scope.launch {
      val body = ApiJson.encodeToString(ApworldRemoval.serializer(), ApworldRemoval(release, names))
      outcome = when (val answer = call("POST", "api/admin/apworlds/remove", body) { }) {
        is Answer.Ok -> names.map { true to texts.removed(it, release) }
        is Answer.Failed -> listOf(false to answer.message)
      }
      busy = null
      load()
    }
  }

  val current = info
  Section(texts.title, subtitle = texts.subtitle, actions = { InfoTip(texts.tip) }) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      // Not while an upload waits for its answer
      busy?.takeIf { asking == null }?.let { Txt(texts.relisting(it), size = 12.5.sp, color = p.muted) }
      outcome.forEach { (ok, text) -> Txt(text, size = 12.5.sp, color = if (ok) p.ok else p.trap) }
      asking?.let { (held, decision) ->
        val conflict = held.conflict!!
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (conflict.sameFile) Txt(texts.replacesFile(held.file), size = 13.sp)
          if (conflict.otherFile != null) {
            Txt(texts.replacesGame(held.file, conflict.game.orEmpty(), conflict.otherFile.orEmpty()), size = 13.sp)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(texts.replace, danger = true) { decision.complete(true) }
            Button(texts.skip) { decision.complete(false) }
          }
        }
      }
      when {
        current == null -> Txt(if (problem == null) texts.listing else "…", size = 13.sp, color = p.muted)
        current.releases.isEmpty() -> Txt(texts.noRelease, size = 13.sp, color = p.trap)
        else -> current.releases.forEachIndexed { i, release ->
          if (i > 0) Divider()
          ReleaseBlock(
            release, current.apworlds, enabled = busy == null,
            onUpload = { upload(release.version) },
            onRemove = { files -> remove(release.version, files) },
            onTry = { file -> tryWith(release.version, file) },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseBlock(
  release: ReleaseInfo,
  files: List<ApworldFile>,
  enabled: Boolean,
  onUpload: () -> Unit,
  onRemove: (List<ApworldFile>) -> Unit,
  onTry: (ApworldFile) -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val texts = t.apworlds
  var filter by remember { mutableStateOf("") }
  // The supported games shown: true bundled, false from an apworld, null all
  var source by remember { mutableStateOf<Boolean?>(null) }
  // Removing: the uploaded files can be picked (by name), and the removal is asked once more
  var picking by remember { mutableStateOf(false) }
  var picked by remember { mutableStateOf(emptySet<String>()) }
  var asking by remember { mutableStateOf(false) }
  fun stopPicking() { picking = false; picked = emptySet(); asking = false }
  val own = files.filter { it.release == release.version }
  // Uploaded for another release and not in this one's library yet
  val others = files.filter { f -> f.release != release.version && f.source == GameSource.UPLOADED && own.none { it.file == f.file } }
    .distinctBy { it.file }

  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Txt(texts.release(release.version), size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    if (enabled && !picking) {
      SmallButton(texts.upload, onUpload)
      if (own.any { it.source == GameSource.UPLOADED }) SmallButton(texts.removeSome, { picking = true })
    }
  }
  release.error?.let { Txt(it, size = 12.5.sp, color = p.trap) }

  // The library: one bubble per file with the games it brings; uploaded ones can be removed (asked once more)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    SubHeading(texts.uploaded)
    InfoTip(
      texts.uploadedTip,
      options = listOf(
        texts.gold to texts.goldMeaning,
        texts.orange to texts.orangeMeaning,
        texts.red to texts.redMeaning,
        texts.grey to texts.greyMeaning,
      ),
    )
  }
  if (own.isEmpty()) Txt(texts.noneYet, size = 12.5.sp, color = p.muted)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    own.forEach { file ->
      val games = release.games.filter { it.apworld == file.file }.map { it.game }
      val pickable = picking && !asking && file.source == GameSource.UPLOADED
      ApworldChip(
        file, games,
        picked = if (picking && file.source == GameSource.UPLOADED) file.file in picked else null,
        onClick = if (pickable) ({ picked = if (file.file in picked) picked - file.file else picked + file.file }) else null,
      )
    }
  }
  if (picking) {
    // On their own lines, below the files: the question and the buttons
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (asking) {
        Txt(texts.removeQuestion(picked.sorted().joinToString(", "), release.version), size = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(texts.yesRemove, danger = true) {
            val chosen = own.filter { it.file in picked }
            stopPicking()
            onRemove(chosen)
          }
          Button(t.common.cancel) { asking = false }
        }
      } else {
        Txt(texts.pickToRemove, size = 12.5.sp, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(texts.removeCount(picked.size), danger = true, enabled = picked.isNotEmpty()) { asking = true }
          Button(t.common.cancel) { stopPicking() }
        }
      }
    }
  }
  release.failed.forEach { failed ->
    Txt(texts.didntLoad(failed.name, failed.reason), size = 12.sp, color = p.trap, maxLines = 4)
  }
  if (others.isNotEmpty()) {
    SubHeading(texts.fromOtherReleases)
    others.forEach { file ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(texts.fromRelease(file.file, file.release), size = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (enabled) SmallButton(texts.tryWith(release.version), { onTry(file) })
      }
    }
  }

  // Everything the release can generate; the games of apworlds in their own colour
  if (release.games.isNotEmpty()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SubHeading(texts.supportedGames(release.games.size), Modifier.weight(1f))
      FilterField(filter, { filter = it }, t.common.search, Modifier.width(180.dp))
    }
    // Click to show only those; again to show all
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      SourceFilter(texts.bundled, p.muted, source == true) { source = if (source == true) null else true }
      SourceFilter(texts.fromApworld, p.key, source == false) { source = if (source == false) null else false }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      release.games.filter { filter.isBlank() || it.game.contains(filter.trim(), ignoreCase = true) }
        .filter { source == null || (it.source == GameSource.BUNDLED) == source }
        .forEach { game ->
        Pill(game.game, if (game.source == GameSource.BUNDLED) p.muted else p.key)
      }
    }
  }
}

/** One of the supported games' sources as a toggle: [selected] shows only its games. */
@Composable
private fun SourceFilter(text: String, color: Color, selected: Boolean, onClick: () -> Unit) {
  Txt(
    text, size = 11.5.sp, weight = FontWeight.SemiBold, maxLines = 1,
    color = if (selected) LocalPalette.current.onBrand else color,
    modifier = Modifier.clip(CircleShape).background(if (selected) color else color.copy(alpha = 0.15f))
      .hand().clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 3.dp),
  )
}

/** An apworld file as a bubble: its name and games, coloured by where it comes from; [onRemove]: its ×. */
/** One file of the library; while removing, [picked] (null: it can't be picked) and a click picks or drops it. */
@Composable
private fun ApworldChip(file: ApworldFile, games: List<String>, picked: Boolean?, onClick: (() -> Unit)?) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val color = when {
    games.isEmpty() -> p.trap
    file.source == GameSource.MANUAL -> p.muted
    file.from != null -> p.warn
    else -> p.key
  }
  Row(
    Modifier.clip(CircleShape).background(color.copy(alpha = if (picked == true) 0.32f else 0.15f))
      .then(if (picked == true) Modifier.border(1.5.dp, color, CircleShape) else Modifier)
      .then(if (onClick != null) Modifier.hand().clickable(onClick = onClick) else Modifier)
      .then(if (picked == false) Modifier.border(1.dp, color.copy(alpha = 0.4f), CircleShape) else Modifier)
      .padding(horizontal = 10.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Txt(file.file, size = 12.5.sp, weight = FontWeight.SemiBold, color = color, maxLines = 1)
    val details = listOfNotNull(
      games.joinToString(", ").ifEmpty { t.apworlds.noGame },
      file.from?.let { t.apworlds.from(it) },
      t.fileSize(file.size),
    )
    Txt(details.joinToString(" · "), size = 11.5.sp, color = color.copy(alpha = 0.8f), maxLines = 1)
    if (picked == true) DrawnIcon(Icon.CHECK, color, 12.dp)
  }
}

@Composable
private fun SubHeading(text: String, modifier: Modifier = Modifier) {
  Txt(text, size = 12.5.sp, weight = FontWeight.SemiBold, color = LocalPalette.current.muted, modifier = modifier.padding(top = 4.dp))
}

/** Several picked files go up in one request, up to this much base64 together (the tracker's limit for one). */
private const val BATCH_BASE64 = 64 * 1024 * 1024 / 3 * 4
