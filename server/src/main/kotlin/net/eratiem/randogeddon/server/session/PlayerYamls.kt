package net.eratiem.randogeddon.server.session

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import net.eratiem.randogeddon.server.state.WORLD_LOCKS_GAME
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
  fun inspect(files: List<UploadedYaml>): InspectResult {
    val worlds = mutableListOf<YamlWorld>()
    val problems = mutableListOf<String>()
    val invalid = mutableSetOf<String>()
    fun refuse(file: UploadedYaml, why: String) {
      problems += "${file.fileName}: $why"
      invalid += file.fileName
    }
    for (file in files) {
      if (!YAML_FILE.matches(file.fileName)) {
        refuse(file, "not a .yaml or .yml file")
        continue
      }
      val documents = file.content.split(DOCUMENT_SEPARATOR).filter { it.isNotBlank() }
      if (documents.isEmpty()) refuse(file, "empty")
      for (document in documents) {
        val map = runCatching { Yaml.default.parseToYamlNode(document) }.getOrNull() as? YamlMap
        if (map == null) {
          refuse(file, "not a player YAML")
          continue
        }
        val name = (map.get<YamlNode>("name") as? YamlScalar)?.content?.trim()
        val game = when (val node = map.get<YamlNode>("game")) {
          is YamlScalar -> node.content
          is YamlMap -> node.entries.keys.joinToString(" / ") { it.content }
          else -> null
        }
        when {
          name.isNullOrEmpty() -> refuse(file, "no name")
          game.isNullOrEmpty() -> refuse(file, "no game for $name")
          // The World Locks slot comes from "starts locked" in the panel
          game == WORLD_LOCKS_GAME -> refuse(file, "$name is a World Locks YAML; tick \"starts locked\" instead")
          else -> worlds += YamlWorld(file.fileName, name, game)
        }
      }
    }
    worlds.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.forEach { (_, same) ->
      problems += "${same.size} slots are called ${same.first().name} (${same.joinToString { it.fileName }}); names must be unique"
    }
    // A refused file's slots don't count
    return InspectResult(worlds.filter { it.fileName !in invalid }, problems, invalid.toList())
  }

  /** The games of [worlds] that aren't in [games] (a release's), each game of a weighted one on its own. */
  fun missingGames(worlds: List<YamlWorld>, games: Set<String>): List<String> =
    worlds.flatMap { it.game.split(" / ") }.distinct().filter { it !in games }
}
