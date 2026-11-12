package net.eratiem.randogeddon.webapp

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
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ApworldFile
import net.eratiem.randogeddon.shared.ApworldResult
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.ArchipelagoInfo
import net.eratiem.randogeddon.shared.GameSource
import net.eratiem.randogeddon.shared.ReleaseInfo
import net.eratiem.randogeddon.shared.UploadedYaml

private const val APWORLDS_TIP = "The games each Archipelago release of the gateway can generate and serve: the " +
  "bundled ones, plus APWorlds. APWorlds uploaded here are kept per release; ones put into the server's " +
  "custom_worlds/ count for every release. An APWorld uploaded for another release can be tried with this one, " +
  "it may or may not work."

/**
 * The admin panel's apworlds: per Archipelago release of the gateway its games, the apworlds of its library (upload,
 * remove), the ones that didn't load, and the apworlds of other releases to try with it.
 */
@Composable
fun ApworldsSection() {
  val p = LocalPalette.current
  val scope = rememberCoroutineScope()
  var info by remember { mutableStateOf<ArchipelagoInfo?>(null) }
  var problem by remember { mutableStateOf<String?>(null) }
  // What the last upload, try or removal did; or that one is going on
  var busy by remember { mutableStateOf<String?>(null) }
  var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

  fun load() = scope.launch {
    when (val answer = call("GET", "api/admin/apworlds", null) { ApiJson.decodeFromString(ArchipelagoInfo.serializer(), it) }) {
      is Answer.Ok -> { info = answer.value; problem = null }
      is Answer.Failed -> problem = answer.message
    }
  }
  LaunchedEffect(Unit) { load() }

  /** An upload or a try: the release lists its games again, which takes a moment. */
  fun change(label: String, path: String, body: String) {
    busy = label
    outcome = null
    scope.launch {
      outcome = when (val answer = call("POST", path, body) { ApiJson.decodeFromString(ApworldResult.serializer(), it) }) {
        is Answer.Ok -> answer.value.let { r ->
          if (r.error != null) false to "${r.file} didn't load: ${r.error}"
          else true to "${r.file} brings ${r.games.joinToString(", ")}"
        }
        is Answer.Failed -> false to answer.message
      }
      busy = null
      load()
    }
  }

  fun upload(release: String) {
    Platform.pickFiles(".apworld", binary = true) { json ->
      val files = runCatching { ApiJson.decodeFromString(ListSerializer(UploadedYaml.serializer()), json) }.getOrDefault(emptyList())
      // One at a time: each one lists the release again
      files.firstOrNull()?.let { file ->
        val body = ApiJson.encodeToString(ApworldUpload.serializer(), ApworldUpload(release, file.fileName, file.content))
        change("Uploading ${file.fileName}…", "api/admin/apworlds", body)
      }
    }
  }

  fun remove(file: ApworldFile) {
    busy = "Removing ${file.file}…"
    outcome = null
    scope.launch {
      outcome = when (val answer = call("DELETE", "api/admin/apworlds/${file.release}/${file.file}", null) { }) {
        is Answer.Ok -> true to "${file.file} removed from Archipelago ${file.release}"
        is Answer.Failed -> false to answer.message
      }
      busy = null
      load()
    }
  }

  val current = info
  Section("APWorlds", subtitle = "the games of each Archipelago release", actions = { InfoTip(APWORLDS_TIP) }) {
    Column(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      problem?.let { Txt(it, size = 12.5.sp, color = p.trap) }
      busy?.let { Txt("$it Archipelago lists its games again; that takes a moment.", size = 12.5.sp, color = p.muted) }
      outcome?.let { (ok, text) -> Txt(text, size = 12.5.sp, color = if (ok) p.ok else p.trap) }
      when {
        current == null -> Txt(if (problem == null) "Listing the games of each release…" else "…", size = 13.sp, color = p.muted)
        current.releases.isEmpty() -> Txt("The gateway reports no Archipelago release.", size = 13.sp, color = p.trap)
        else -> current.releases.forEachIndexed { i, release ->
          if (i > 0) Divider()
          ReleaseBlock(
            release, current.apworlds, enabled = busy == null,
            onUpload = { upload(release.version) },
            onRemove = ::remove,
            onTry = { file ->
              val body = ApiJson.encodeToString(ApworldTry.serializer(), ApworldTry(release.version, file.release, file.file))
              change("Trying ${file.file} with Archipelago ${release.version}…", "api/admin/apworlds/try", body)
            },
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
  onRemove: (ApworldFile) -> Unit,
  onTry: (ApworldFile) -> Unit,
) {
  val p = LocalPalette.current
  var filter by remember { mutableStateOf("") }
  // The supported games shown: true bundled, false from an apworld, null all
  var source by remember { mutableStateOf<Boolean?>(null) }
  // The file whose removal waits for a second click
  var removing by remember { mutableStateOf<ApworldFile?>(null) }
  val own = files.filter { it.release == release.version }
  // Uploaded for another release and not in this one's library yet
  val others = files.filter { f -> f.release != release.version && f.source == GameSource.UPLOADED && own.none { it.file == f.file } }
    .distinctBy { it.file }

  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Txt("Archipelago ${release.version}", size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    if (enabled) SmallButton("Upload APWorld…", onUpload)
  }
  release.error?.let { Txt(it, size = 12.5.sp, color = p.trap) }

  // The library: one bubble per file with the games it brings; uploaded ones can be removed (asked once more)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    SubHeading("Uploaded APWorlds")
    InfoTip(
      "Each file with the games it brings.",
      options = listOf(
        "gold" to "Loaded with this release.",
        "orange" to "Uploaded for another release and tried here: untested.",
        "red" to "No game loaded from it (see below why).",
        "grey" to "Put into the server's custom_worlds/ by hand; it can't be removed here.",
      ),
    )
  }
  if (own.isEmpty()) Txt("None yet: upload one for a game that isn't bundled.", size = 12.5.sp, color = p.muted)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    own.forEach { file ->
      val games = release.games.filter { it.apworld == file.file }.map { it.game }
      ApworldChip(
        file, games,
        onRemove = if (enabled && file.source == GameSource.UPLOADED) ({ removing = file }) else null,
      )
    }
  }
  removing?.takeIf { it.release == release.version }?.let { file ->
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Txt(
        "Remove ${file.file}? Its games can't be used for new sessions of Archipelago ${release.version} then.",
        size = 13.sp, modifier = Modifier.weight(1f),
      )
      Button("Yes, remove", danger = true) { removing = null; onRemove(file) }
      Button("Cancel") { removing = null }
    }
  }
  release.failed.forEach { failed ->
    Txt("Didn't load: ${failed.name}: ${failed.reason}", size = 12.sp, color = p.trap, maxLines = 4)
  }
  if (others.isNotEmpty()) {
    SubHeading("From other releases")
    others.forEach { file ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("${file.file} (Archipelago ${file.release})", size = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (enabled) SmallButton("Try with ${release.version}", { onTry(file) })
      }
    }
  }

  // Everything the release can generate; the games of apworlds in their own colour
  if (release.games.isNotEmpty()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SubHeading("Supported games (${release.games.size})", Modifier.weight(1f))
      FilterField(filter, { filter = it }, "Search…", Modifier.width(180.dp))
    }
    // Click to show only those; again to show all
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      SourceFilter("bundled", p.muted, source == true) { source = if (source == true) null else true }
      SourceFilter("from an APWorld", p.key, source == false) { source = if (source == false) null else false }
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
    color = if (selected) Color.White else color,
    modifier = Modifier.clip(CircleShape).background(if (selected) color else color.copy(alpha = 0.15f))
      .hand().clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 3.dp),
  )
}

/** An apworld file as a bubble: its name and games, coloured by where it comes from; [onRemove]: its ×. */
@Composable
private fun ApworldChip(file: ApworldFile, games: List<String>, onRemove: (() -> Unit)?) {
  val p = LocalPalette.current
  val color = when {
    games.isEmpty() -> p.trap
    file.source == GameSource.MANUAL -> p.muted
    file.from != null -> p.warn
    else -> p.key
  }
  Row(
    Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f))
      .padding(start = 10.dp, end = if (onRemove != null) 4.dp else 10.dp, top = 3.dp, bottom = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Txt(file.file, size = 12.5.sp, weight = FontWeight.SemiBold, color = color, maxLines = 1)
    val details = listOfNotNull(
      games.joinToString(", ").ifEmpty { "no game" },
      file.from?.let { "from $it" },
      size(file.size),
    )
    Txt(details.joinToString(" · "), size = 11.5.sp, color = color.copy(alpha = 0.8f), maxLines = 1)
    if (onRemove != null) {
      Box(Modifier.size(18.dp).clip(CircleShape).hand().clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
        DrawnIcon(Icon.CLOSE, color, 10.dp)
      }
    }
  }
}

@Composable
private fun SubHeading(text: String, modifier: Modifier = Modifier) {
  Txt(text, size = 12.5.sp, weight = FontWeight.SemiBold, color = LocalPalette.current.muted, modifier = modifier.padding(top = 4.dp))
}

private fun size(bytes: Long): String = when {
  bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
  else -> "${(bytes + 1023) / 1024} KB"
}
