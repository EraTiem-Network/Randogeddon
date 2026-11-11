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

/**
 * What the admin panel needs to know of the uploaded player YAMLs before generating: each world's name and game.
 * Generate.py checks everything else. A file can hold several worlds (YAML documents separated by `---`); a weighted
 * game (`game: {A: 1, B: 1}`) is shown as "A / B".
 */
object PlayerYamls {
  fun inspect(files: List<UploadedYaml>): InspectResult {
    val worlds = mutableListOf<YamlWorld>()
    val problems = mutableListOf<String>()
    for (file in files) {
      val documents = file.content.split(DOCUMENT_SEPARATOR).filter { it.isNotBlank() }
      if (documents.isEmpty()) problems += "${file.fileName}: empty"
      for (document in documents) {
        val map = runCatching { Yaml.default.parseToYamlNode(document) }.getOrNull() as? YamlMap
        if (map == null) {
          problems += "${file.fileName}: not a player YAML"
          continue
        }
        val name = (map.get<YamlNode>("name") as? YamlScalar)?.content?.trim()
        val game = when (val node = map.get<YamlNode>("game")) {
          is YamlScalar -> node.content
          is YamlMap -> node.entries.keys.joinToString(" / ") { it.content }
          else -> null
        }
        when {
          name.isNullOrEmpty() -> problems += "${file.fileName}: no name"
          game.isNullOrEmpty() -> problems += "${file.fileName}: no game for $name"
          // The World Locks slot comes from "starts locked" in the panel
          game == WORLD_LOCKS_GAME -> problems += "${file.fileName}: $name is a World Locks YAML; tick \"starts locked\" instead"
          else -> worlds += YamlWorld(file.fileName, name, game)
        }
      }
    }
    worlds.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.forEach { (_, same) ->
      problems += "${same.size} worlds are called ${same.first().name} (${same.joinToString { it.fileName }}); names must be unique"
    }
    return InspectResult(worlds, problems)
  }
}
