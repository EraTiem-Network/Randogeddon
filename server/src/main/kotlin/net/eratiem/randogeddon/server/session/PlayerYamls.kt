package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.server.config.toApiError
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import net.eratiem.randogeddon.server.state.WORLD_LOCKS_GAME
import net.eratiem.randogeddon.server.util.unsafeText
import net.eratiem.randogeddon.shared.InspectResult
import net.eratiem.randogeddon.shared.UploadedYaml
import net.eratiem.randogeddon.shared.YamlWorld

private val DOCUMENT_SEPARATOR = Regex("(?m)^---\\s*$")
private val YAML_FILE = Regex("(?i).+\\.ya?ml")

/**
 * What the admin panel needs to know of the uploaded player YAMLs before generating: each slot's name and game.
 * Generate.py checks everything else. A file can hold several slots (YAML documents separated by `---`); a weighted
 * game (`game: {A: 1, B: 1}`) is shown as "A / B". A file that isn't a player YAML, or has a document that isn't one, is
 * [InspectResult.invalid]: the panel refuses it.
 */
object PlayerYamls {
  /** The most files and slots one session takes (a generation is the gateway's heaviest work). */
  const val MAX_FILES = 100
  const val MAX_SLOTS = 250

  fun inspect(files: List<UploadedYaml>): InspectResult {
    // Too many aren't even read
    if (files.size > MAX_FILES) {
      return InspectResult(emptyList(), listOf(Refusal.of(Problem.TOO_MANY_YAMLS, "max" to MAX_FILES).toApiError()), emptyList())
    }
    val worlds = mutableListOf<YamlWorld>()
    val problems = mutableListOf<ApiError>()
    val invalid = mutableSetOf<String>()
    fun refuse(file: UploadedYaml, problem: Problem, vararg args: Pair<String, Any?>) {
      problems += Refusal.of(problem, "file" to file.fileName, *args).toApiError()
      invalid += file.fileName
    }
    for (file in files) {
      if (!YAML_FILE.matches(file.fileName)) {
        refuse(file, Problem.YAML_FILE)
        continue
      }
      val documents = file.content.split(DOCUMENT_SEPARATOR).filter { it.isNotBlank() }
      if (documents.isEmpty()) refuse(file, Problem.YAML_EMPTY)
      for (document in documents) {
        val map = runCatching { Yaml.default.parseToYamlNode(document) }.getOrNull() as? YamlMap
        if (map == null) {
          refuse(file, Problem.YAML_NOT_PLAYER)
          continue
        }
        val name = (map.get<YamlNode>("name") as? YamlScalar)?.content?.trim()
        val game = when (val node = map.get<YamlNode>("game")) {
          is YamlScalar -> node.content
          is YamlMap -> node.entries.keys.joinToString(" / ") { it.content }
          else -> null
        }
        when {
          name.isNullOrEmpty() -> refuse(file, Problem.YAML_NO_NAME)
          // A slot's name is a part of the panel's paths (`.` and `..` would move them), and the feed shows it as one line
          name == "." || name == ".." || unsafeText(name) -> refuse(file, Problem.YAML_NAME_CHARACTERS)
          game.isNullOrEmpty() -> refuse(file, Problem.YAML_NO_GAME, "name" to name)
          // The World Locks slot comes from "starts locked" in the panel
          game == WORLD_LOCKS_GAME -> refuse(file, Problem.YAML_WORLD_LOCKS, "name" to name)
          else -> worlds += YamlWorld(file.fileName, name, game)
        }
      }
    }
    if (worlds.size > MAX_SLOTS) problems += Refusal.of(Problem.TOO_MANY_SLOTS, "max" to MAX_SLOTS).toApiError()
    worlds.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.forEach { (_, same) ->
      problems += Refusal.of(
        Problem.YAML_DUPLICATE, "n" to same.size, "name" to same.first().name, "files" to same.joinToString { it.fileName },
      ).toApiError()
    }
    // A refused file's slots don't count
    return InspectResult(worlds.filter { it.fileName !in invalid }, problems, invalid.toList())
  }

  /** The games of [worlds] that aren't in [games] (a release's), each game of a weighted one on its own. */
  fun missingGames(worlds: List<YamlWorld>, games: Set<String>): List<String> =
    worlds.flatMap { it.game.split(" / ") }.distinct().filter { it !in games }
}
