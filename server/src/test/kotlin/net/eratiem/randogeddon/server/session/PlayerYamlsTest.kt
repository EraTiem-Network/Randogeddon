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
}
