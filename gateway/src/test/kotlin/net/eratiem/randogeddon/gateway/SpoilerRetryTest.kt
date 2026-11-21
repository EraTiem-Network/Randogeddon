package net.eratiem.randogeddon.gateway

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpoilerRetryTest {
  @Test
  fun `a stopped spoiler log is tried again from its level, one that goes on or is complete isn't`() {
    val root = Files.createTempDirectory("archipelago")
    val data = Files.createTempDirectory("data")
    root.resolve("0.6.8").createDirectories().resolve("MultiServer.py").createFile()
    val archipelago = Archipelago(root, "sh")
    val dirs = SessionDirs(data)
    val stages = SpoilerStages(dirs)
    val generator = Generator(archipelago, dirs, Apworlds(archipelago, data.resolve("apworlds"), null), atOnce = 1)
    for (id in listOf("brave-otter", "calm-heron", "quiet-fox")) {
      data.resolve("sessions/$id").createDirectories().resolve("AP_$id.zip").createFile()
    }
    stages.save("brave-otter", SpoilerStages.State(1, "0.6.8", level = 1, stopped = "Generate.py ended with exit code 1 (see its log)"))
    stages.save("calm-heron", SpoilerStages.State(2, "0.6.8", level = 3))
    stages.save("quiet-fox", SpoilerStages.State(3, "0.6.8", level = 2))
    // Stopped: not among the ones to complete
    assertEquals("quiet-fox", stages.next()?.first)
    assertEquals("Generate.py ended with exit code 1 (see its log)", generator.spoiler("brave-otter")?.stopped)

    assertTrue(generator.retrySpoiler("brave-otter"))
    assertNull(generator.spoiler("brave-otter")?.stopped)
    assertEquals(1, stages.read("brave-otter")?.level)
    assertEquals("brave-otter", stages.next()?.first)
    // Nothing to try again: it goes on, it is complete, there is none
    assertFalse(generator.retrySpoiler("brave-otter"))
    assertFalse(generator.retrySpoiler("quiet-fox"))
    assertFalse(generator.retrySpoiler("calm-heron"))
    assertFalse(generator.retrySpoiler("no-such-one"))
  }
}
