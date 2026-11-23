package net.eratiem.randogeddon.server.session

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.UploadedYaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerYamlsTest {
  @Test
  fun `names and games of plain, weighted and multi-document YAMLs`() {
    val result = PlayerYamls.inspect(
      listOf(
        UploadedYaml("alice.yaml", "name: Alice-HK\ngame: Hollow Knight\nHollow Knight:\n  start_location: king's_pass\n"),
        UploadedYaml("bob.yaml", "name: Bob\ngame:\n  Celeste 64: 1\n  DLCQuest: 1\n"),
        UploadedYaml("two.yaml", "name: Carol-A\ngame: APQuest\n---\nname: Carol-B\ngame: APQuest\n"),
      )
    )
    assertEquals(emptyList(), result.problems)
    assertEquals(emptyList(), result.invalid)
    assertEquals(
      listOf("Alice-HK" to "Hollow Knight", "Bob" to "Celeste 64 / DLCQuest", "Carol-A" to "APQuest", "Carol-B" to "APQuest"),
      result.worlds.map { it.name to it.game },
    )
  }

  @Test
  fun `a slot's name is no path step and one line without direction controls`() {
    val result = PlayerYamls.inspect(
      listOf(
        UploadedYaml("up.yaml", "name: '..'\ngame: APQuest"),
        UploadedYaml("dot.yaml", "name: '.'\ngame: APQuest"),
        UploadedYaml("turned.yaml", "name: \"Bob\\u202e\"\ngame: APQuest"),
        UploadedYaml("lines.yaml", "name: \"A\\nB\"\ngame: APQuest"),
        // Dots inside a name, and Archipelago's placeholders, are fine
        UploadedYaml("fine.yaml", "name: A.B{number}\ngame: APQuest"),
      )
    )
    assertEquals(
      listOf("up.yaml", "dot.yaml", "turned.yaml", "lines.yaml").map { it to Problem.YAML_NAME_CHARACTERS },
      result.problems.map { it.args["file"] to it.code },
    )
    assertEquals(listOf("A.B{number}"), result.worlds.map { it.name })
  }

  @Test
  fun `broken files, missing names, duplicates and World Locks YAMLs are problems`() {
    val result = PlayerYamls.inspect(
      listOf(
        UploadedYaml("broken.yaml", "name: [unclosed"),
        UploadedYaml("noname.yaml", "game: APQuest"),
        UploadedYaml("a.yaml", "name: Same\ngame: APQuest"),
        UploadedYaml("b.yaml", "name: same\ngame: APQuest"),
        UploadedYaml("locks.yaml", "name: Locks\ngame: World Locks"),
        UploadedYaml("empty.yaml", "  \n"),
        UploadedYaml("notes.txt", "name: Txt\ngame: APQuest"),
        // One good document doesn't save the file
        UploadedYaml("half.yaml", "name: Good\ngame: APQuest\n---\njust text"),
      )
    )
    val problems = result.problems.joinToString("\n") { it.message }
    val codes = result.problems.map { it.args["file"] to it.code }
    assertTrue("notes.txt" to Problem.YAML_FILE in codes, problems)
    assertTrue("notes.txt: not a .yaml or .yml file" in problems, problems)
    assertEquals(
      setOf("broken.yaml", "noname.yaml", "locks.yaml", "empty.yaml", "notes.txt", "half.yaml"), result.invalid.toSet(),
    )
    // Duplicate names are a problem, but the files themselves are player YAMLs
    assertEquals(listOf("Same", "same"), result.worlds.map { it.name })
    assertTrue("broken.yaml: not a player YAML" in problems, problems)
    assertTrue("noname.yaml: no name" in problems, problems)
    assertTrue("2 slots are called Same" in problems, problems)
    assertTrue("World Locks" in problems, problems)
    assertTrue("empty.yaml: empty" in problems, problems)
    assertTrue(result.problems.any { it.code == Problem.YAML_DUPLICATE && it.args["n"] == "2" }, problems)
  }

  @Test
  fun `a session takes only so many files and slots`() {
    fun yaml(n: Int) = UploadedYaml("p$n.yaml", "name: P$n\ngame: APQuest\n")
    // Too many files aren't even read
    val files = PlayerYamls.inspect((1..PlayerYamls.MAX_FILES + 1).map(::yaml))
    assertEquals(listOf(Problem.TOO_MANY_YAMLS), files.problems.map { it.code })
    assertEquals(emptyList(), files.worlds)
    assertEquals(emptyList(), PlayerYamls.inspect((1..PlayerYamls.MAX_FILES).map(::yaml)).problems)
    // Few files, many slots in them
    val documents = (1..PlayerYamls.MAX_SLOTS + 1).joinToString("---\n") { "name: S$it\ngame: APQuest\n" }
    val slots = PlayerYamls.inspect(listOf(UploadedYaml("all.yaml", documents)))
    assertEquals(listOf(Problem.TOO_MANY_SLOTS), slots.problems.map { it.code })
  }
}
