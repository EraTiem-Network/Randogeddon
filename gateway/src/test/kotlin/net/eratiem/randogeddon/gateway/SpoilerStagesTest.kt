package net.eratiem.randogeddon.gateway

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SpoilerStagesTest {
  private fun spoiler(locations: List<String>, more: String = "") = """
    |Archipelago Version 0.6.8  -  Seed: 424242
    |
    |Filling Algorithm:               balanced
    |
    |Locations:
    |
    |${locations.joinToString("\n")}
    |
    |$more""".trimMargin()

  @Test
  fun `a later level belongs to the seed when it places everything the same`() {
    val placed = listOf("Crossroads (Bob-HK): Mantis Claw (Bob-HK)", "1 score (Bob-Yacht): Rancid_Egg (Alice-HK)")
    val seed = spoiler(placed)
    // Another order, and the playthrough and paths after it: the same placements
    val level3 = spoiler(placed.reversed(), "Playthrough:\n\n0: {\n  Start\n}\n\nPaths:\n\n  Mantis Claw: ...\n")
    assertEquals(placementsOf(seed), placementsOf(level3))
    assertEquals(2, placementsOf(level3).size)
    // One item elsewhere: another world
    assertNotEquals(placementsOf(seed), placementsOf(spoiler(listOf(placed[0], "1 score (Bob-Yacht): Dash (Alice-HK)"))))
    assertEquals(emptyList(), placementsOf("no spoiler at all"))
  }

  @Test
  fun `the next spoiler to complete is one without its playthrough, then one without its paths`() {
    val data = Files.createTempDirectory("data")
    val dirs = SessionDirs(data)
    val stages = SpoilerStages(dirs)
    for (id in listOf("brave-otter", "calm-heron", "quiet-fox", "sunny-owl", "wild-hare")) {
      data.resolve("sessions/$id").createDirectories().resolve("AP_$id.zip").createFile()
    }
    stages.save("brave-otter", SpoilerStages.State(1, "0.6.8", level = 2))
    stages.save("calm-heron", SpoilerStages.State(2, "0.6.8", level = 3))
    stages.save("quiet-fox", SpoilerStages.State(3, "0.6.8", level = 1))
    stages.save("sunny-owl", SpoilerStages.State(4, "0.6.8", level = 1, stopped = "the placements differ"))
    // wild-hare: from before 0.19.3, no state: complete
    assertEquals("quiet-fox", stages.next()?.first)
    stages.save("quiet-fox", stages.read("quiet-fox")!!.copy(level = 2))
    assertEquals("brave-otter", stages.next()?.first)
    stages.save("brave-otter", stages.read("brave-otter")!!.copy(level = 3))
    assertEquals("quiet-fox", stages.next()?.first)
    stages.save("quiet-fox", stages.read("quiet-fox")!!.copy(level = 3))
    assertNull(stages.next())
    assertNull(stages.read("wild-hare"))
    assertEquals(4L, stages.read("sunny-owl")?.seed)
  }

  @Test
  fun `a state is read again when its file changed, and is gone with it`() {
    val data = Files.createTempDirectory("data")
    val dirs = SessionDirs(data)
    val stages = SpoilerStages(dirs)
    data.resolve("sessions/brave-otter").createDirectories().resolve("AP_1.zip").createFile()
    stages.save("brave-otter", SpoilerStages.State(1, "0.6.8", level = 1))
    assertEquals(1, stages.read("brave-otter")?.level)
    // Written by another (a gateway before this one, or another user of the same files)
    SpoilerStages(dirs).save("brave-otter", SpoilerStages.State(1, "0.6.8", level = 2, stopped = "it took too long"))
    assertEquals("it took too long", stages.read("brave-otter")?.stopped)
    stages.save("brave-otter", SpoilerStages.State(1, "0.6.8", level = 3))
    assertEquals(3, stages.read("brave-otter")?.level)
    dirs.generationDir("brave-otter").resolve(SpoilerStages.FILE).deleteExisting()
    assertNull(stages.read("brave-otter"))
  }
}
