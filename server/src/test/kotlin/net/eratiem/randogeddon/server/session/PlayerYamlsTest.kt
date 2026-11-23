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
        // Dots inside a name are fine
        UploadedYaml("fine.yaml", "name: A.B\ngame: APQuest"),
      )
    )
    assertEquals(
      listOf("up.yaml", "dot.yaml", "turned.yaml", "lines.yaml").map { it to Problem.YAML_NAME_CHARACTERS },
      result.problems.map { it.args["file"] to it.code },
    )
    assertEquals(listOf("A.B"), result.worlds.map { it.name })
  }

  @Test
  fun `a name Archipelago would change is refused, too long or with a placeholder`() {
    val result = PlayerYamls.inspect(
      listOf(
        // 17 characters: Archipelago cuts it to 16
        UploadedYaml("long.yaml", "name: Christopher-Celes\ngame: APQuest"),
        UploadedYaml("number.yaml", "name: Player{number}\ngame: APQuest"),
        UploadedYaml("upper.yaml", "name: P{PLAYER}\ngame: APQuest"),
        UploadedYaml("percent.yaml", "name: P%number%\ngame: APQuest"),
        UploadedYaml("brace.yaml", "name: \"A{\"\ngame: APQuest"),
        UploadedYaml("closing.yaml", "name: \"A}\"\ngame: APQuest"),
        UploadedYaml("empty.yaml", "name: \"A{}\"\ngame: APQuest"),
        UploadedYaml("double.yaml", "name: \"A{{B\"\ngame: APQuest"),
        UploadedYaml("format.yaml", "name: \"A{x!r}\"\ngame: APQuest"),
        UploadedYaml("attribute.yaml", "name: \"A{x.y}\"\ngame: APQuest"),
        UploadedYaml("player.yaml", "name: \"A%player%\"\ngame: APQuest"),
        UploadedYaml("percents.yaml", "name: \"100%%\"\ngame: APQuest"),
        // 16 characters (code points: an emoji is one) is kept as it is
        UploadedYaml("sixteen.yaml", "name: Dave-Timespinner\ngame: APQuest"),
        UploadedYaml("emoji.yaml", "name: \"Dave-Timespinne😀\"\ngame: APQuest"),
        // A lone % and the placeholders in another case are kept as they are by Archipelago
        UploadedYaml("lone.yaml", "name: \"100%\"\ngame: APQuest"),
        UploadedYaml("apart.yaml", "name: \"A%B%C\"\ngame: APQuest"),
        UploadedYaml("cased.yaml", "name: \"A%PLAYER%\"\ngame: APQuest"),
      )
    )
    assertEquals(
      listOf("long.yaml" to Problem.YAML_NAME_TOO_LONG) +
        listOf("number", "upper", "percent", "brace", "closing", "empty", "double", "format", "attribute", "player", "percents")
          .map { "$it.yaml" to Problem.YAML_NAME_PLACEHOLDER },
      result.problems.map { it.args["file"] to it.code },
    )
    assertEquals("16", result.problems.first().args["max"])
    assertEquals(
      listOf("Dave-Timespinner", "Dave-Timespinne😀", "100%", "A%B%C", "A%PLAYER%"), result.worlds.map { it.name },
    )
  }

  @Test
  fun `a slot named like the locks' slot is a problem only while the session uses locks`() {
    val files = listOf(
      UploadedYaml("locks.yaml", "name: locks\ngame: APQuest"),
      UploadedYaml("other.yaml", "name: Locksmith\ngame: APQuest"),
    )
    // Without locks it is a slot like any other
    assertEquals(emptyList(), PlayerYamls.inspect(files).problems)
    val result = PlayerYamls.inspect(files, locks = true)
    assertEquals(listOf("locks.yaml" to Problem.YAML_LOCKS_SLOT), result.problems.map { it.args["file"] to it.code })
    assertEquals("locks", result.problems.single().args["name"])
    assertTrue("the locks' slot's" in result.problems.single().message, result.problems.single().message)
    // A player YAML all the same: the locks may be switched off again
    assertEquals(emptyList(), result.invalid)
    assertEquals(listOf("locks", "Locksmith"), result.worlds.map { it.name })
    assertTrue(PlayerYamls.isLocksSlot("LOCKS"))
  }

  @Test
  fun `a YAML that could roll World Locks in any form is refused`() {
    val result = PlayerYamls.inspect(
      listOf(
        UploadedYaml("plain.yaml", "name: P1\ngame: World Locks"),
        // A weighted game, whatever the weight (0 too), and its case
        UploadedYaml("weighted.yaml", "name: P2\ngame:\n  World Locks: 1\n  A Link to the Past: 0\n"),
        UploadedYaml("zero.yaml", "name: P3\ngame:\n  APQuest: 1\n  World Locks: 0\n"),
        UploadedYaml("case.yaml", "name: P4\ngame: world locks"),
        UploadedYaml("list.yaml", "name: P5\ngame: [APQuest, World Locks]"),
        UploadedYaml("tagged.yaml", "name: P6\ngame: !!str World Locks"),
        // A trigger or a linked option can set the root's game
        UploadedYaml(
          "trigger.yaml",
          "name: P7\ngame: APQuest\ntriggers:\n  - option_name: game\n    option_result: APQuest\n    options:\n" +
            "      \"\":\n        game: World Locks\n",
        ),
        UploadedYaml(
          "linked.yaml",
          "name: P8\ngame: APQuest\nlinked_options:\n  - name: switch\n    percentage: 50\n    options:\n" +
            "      \"\":\n        game:\n          World Locks: 1\n",
        ),
        // Its own game's options may name it as a value: not a way to become it
        UploadedYaml("fine.yaml", "name: P9\ngame: APQuest\nAPQuest:\n  note: World Locks\n"),
      )
    )
    assertEquals(
      listOf("plain", "weighted", "zero", "case", "list", "tagged", "trigger", "linked").map { "$it.yaml" to Problem.YAML_WORLD_LOCKS },
      result.problems.map { it.args["file"] to it.code },
    )
    assertEquals(listOf("P9"), result.worlds.map { it.name })
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
