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
  "bundled ones, plus apworlds. Apworlds uploaded here are kept per release; ones put into the server's " +
  "custom_worlds/ count for every release. An apworld uploaded for another release can be tried with this one, " +
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
  Section("apworlds", subtitle = "the games of each Archipelago release", actions = { InfoTip(APWORLDS_TIP) }) {
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
  var showGames by remember { mutableStateOf(false) }
  var filter by remember { mutableStateOf("") }
  val own = files.filter { it.release == release.version }
  // Uploaded for another release and not in this one's library yet
  val others = files.filter { f -> f.release != release.version && f.source == GameSource.UPLOADED && own.none { it.file == f.file } }
    .distinctBy { it.file }

  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Txt("Archipelago ${release.version}", size = 14.sp, weight = FontWeight.SemiBold)
    Txt("${release.games.size} games", size = 12.5.sp, color = p.muted, modifier = Modifier.weight(1f))
    if (enabled) SmallButton("Upload apworld…", onUpload)
  }
  release.error?.let { Txt(it, size = 12.5.sp, color = p.trap) }

  own.forEach { file ->
    val games = release.games.filter { it.apworld == file.file }.map { it.game }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f)) {
        Txt(file.file, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
        val origin = when {
          file.source == GameSource.MANUAL -> "in the server's custom_worlds/"
          file.from != null -> "from Archipelago ${file.from}, untested here"
          else -> "uploaded"
        }
        Txt(
          listOf(games.joinToString(", ").ifEmpty { "no game loaded" }, origin, size(file.size)).joinToString(" · "),
          size = 11.5.sp, color = if (games.isEmpty()) p.trap else p.muted, maxLines = 2,
        )
      }
      if (enabled && file.source == GameSource.UPLOADED) SmallButton("Remove", { onRemove(file) })
    }
  }
  release.failed.forEach { failed ->
    Txt("Didn't load: ${failed.name}: ${failed.reason}", size = 12.sp, color = p.trap, maxLines = 4)
  }
  if (others.isNotEmpty()) {
    Txt("From other releases", size = 12.5.sp, color = p.muted)
    others.forEach { file ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("${file.file} (Archipelago ${file.release})", size = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (enabled) SmallButton("Try with ${release.version}", { onTry(file) })
      }
    }
  }

  if (release.games.isNotEmpty()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SmallButton(if (showGames) "Hide the games" else "Show the games", { showGames = !showGames })
      if (showGames) FilterField(filter, { filter = it }, "Search…", Modifier.width(180.dp))
    }
    if (showGames) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        release.games.filter { filter.isBlank() || it.game.contains(filter.trim(), ignoreCase = true) }.forEach { game ->
          Pill(game.game, if (game.source == GameSource.BUNDLED) p.muted else p.accent)
        }
      }
    }
  }
}

private fun size(bytes: Long): String = when {
  bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
  else -> "${(bytes + 1023) / 1024} KB"
}
