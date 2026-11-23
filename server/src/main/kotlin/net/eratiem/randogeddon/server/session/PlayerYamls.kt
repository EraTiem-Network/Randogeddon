package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.server.config.toApiError
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
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

  /**
   * The longest slot name Archipelago keeps as it is: Generate.py's `handle_name` cuts a name to 16 characters (code
   * points, after stripping). A longer one would be another name in the seed than the panel's and the locks'.
   */
  const val MAX_NAME = 16

  /**
   * What Archipelago 0.6.8's `handle_name` changes in a name: it replaces exactly `%number%`, `%player%` and `%%`
   * (as written), then runs the name through Python's `str.format`, which turns `{player}`, `{NUMBER}`, `{}`, `{{`,
   * `{x!r}` and the like into something else and fails the generation on a lone brace or `{x.y}`. So every brace is
   * refused, and `%` only in those three; a lone `%` (`100%`) stays as written.
   */
  private val NAME_PLACEHOLDERS = Regex("[{}]|%%|%player%|%number%")

  /** The World Locks slot's name in a seed made in the admin panel (gateway/.../Archipelago.kt, locksYaml). */
  const val LOCKS_SLOT = "Locks"

  /**
   * Whether [name] is [LOCKS_SLOT]'s to Archipelago: Generate.py takes each name once, compared in lower case (as the
   * duplicates in [inspect] are told).
   */
  fun isLocksSlot(name: String): Boolean = name.lowercase() == LOCKS_SLOT.lowercase()

  /** Whether [name] has something of Archipelago's name placeholders ([NAME_PLACEHOLDERS]). */
  internal fun hasPlaceholder(name: String): Boolean = NAME_PLACEHOLDERS.containsMatchIn(name)

  /**
   * [files]' slots and their problems. [locks]: the session uses locks, so a slot named like [LOCKS_SLOT] is a
   * problem too (not an invalid file: it is a player YAML, and the locks may still be switched off).
   */
  fun inspect(files: List<UploadedYaml>, locks: Boolean = false): InspectResult {
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
          // A name Archipelago would change is another name in the seed than the one the panel and the locks know
          name.codePointCount(0, name.length) > MAX_NAME ->
            refuse(file, Problem.YAML_NAME_TOO_LONG, "name" to name, "max" to MAX_NAME)
          hasPlaceholder(name) -> refuse(file, Problem.YAML_NAME_PLACEHOLDER, "name" to name)
          // The World Locks slot comes from "starts locked" in the panel, in whatever form a YAML could roll it
          couldBeWorldLocks(map) -> refuse(file, Problem.YAML_WORLD_LOCKS, "name" to name)
          game.isNullOrEmpty() -> refuse(file, Problem.YAML_NO_GAME, "name" to name)
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
    if (locks) worlds.filter { isLocksSlot(it.name) && it.fileName !in invalid }.forEach {
      problems += Refusal.of(Problem.YAML_LOCKS_SLOT, "file" to it.fileName, "name" to it.name).toApiError()
    }
    // A refused file's slots don't count
    return InspectResult(worlds.filter { it.fileName !in invalid }, problems, invalid.toList())
  }

  /**
   * Whether [document] could be a World Locks slot in any way Archipelago 0.6.8 rolls a YAML's `game` (Generate.py's
   * `get_choice`): the plain name, a key of a weighted map whatever its weight (0 too: refused all the same), an
   * element of a list. Or set it afterwards: a root `triggers` or `linked_options` entry can set any root option,
   * `game` too (they are rolled before `game` is), so one that names the game anywhere is refused as well.
   */
  private fun couldBeWorldLocks(document: YamlMap): Boolean =
    listOf("game", "triggers", "linked_options").any { names(document.get<YamlNode>(it), WORLD_LOCKS_GAME) }

  /** Whether [node] names [game] anywhere: a scalar, a map's key, a list's element, at any depth (case aside). */
  private fun names(node: YamlNode?, game: String): Boolean = when (node) {
    is YamlScalar -> node.content.trim().equals(game, ignoreCase = true)
    is YamlMap -> node.entries.any { (key, value) -> names(key, game) || names(value, game) }
    is YamlList -> node.items.any { names(it, game) }
    is YamlTaggedNode -> names(node.innerNode, game)
    else -> false
  }

  /** The games of [worlds] that aren't in [games] (a release's), each game of a weighted one on its own. */
  fun missingGames(worlds: List<YamlWorld>, games: Set<String>): List<String> =
    worlds.flatMap { it.game.split(" / ") }.distinct().filter { it !in games }
}
