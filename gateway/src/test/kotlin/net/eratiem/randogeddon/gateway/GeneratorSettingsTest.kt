package net.eratiem.randogeddon.gateway

import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.PlandoModule
import net.eratiem.randogeddon.shared.UploadedYaml
import org.junit.Assume.assumeFalse
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratorSettingsTest {
  private fun request(
    plando: Set<PlandoModule> = PlandoModule.DEFAULT, skipBalancing: Boolean = false, race: Boolean = false,
  ) = GenerateRequest(
    listOf(UploadedYaml("a.yaml", "name: A")), apVersion = "0.6.8", plando = plando, skipBalancing = skipBalancing, race = race,
  )

  @Test
  fun `the settings become Generate py's arguments, the plando modules always said`() {
    assertEquals(listOf("--plando", "bosses, connections, texts"), generatorArguments(request()))
    // None: said too, or Generate.py would take its own default
    assertEquals(listOf("--plando", ""), generatorArguments(request(plando = emptySet())))
    assertEquals(
      listOf("--plando", "bosses, items", "--skip_prog_balancing"),
      generatorArguments(request(plando = setOf(PlandoModule.ITEMS, PlandoModule.BOSSES), skipBalancing = true)),
    )
  }

  @Test
  fun `a seed is generated with its settings and keeps them for its spoiler's later levels, a race has none`() = runBlocking {
    assumeFalse("needs sh", System.getProperty("os.name").startsWith("Windows"))
    val root = Files.createTempDirectory("archipelago")
    val data = Files.createTempDirectory("data")
    val home = root.resolve("0.6.8").createDirectories()
    home.resolve("MultiServer.py").createFile()
    home.resolve("list_games.py").writeText("""echo '{"games":[{"game":"Hollow Knight","module":"worlds.hk"}]}' > "${'$'}1"""")
    // Says what it was called with
    home.resolve("Generate.py").writeText("echo \"args: ${'$'}*\"\ntouch \"${'$'}4/AP_${'$'}6.zip\"\n")
    val archipelago = Archipelago(root, "sh")
    val dirs = SessionDirs(data)
    val generator = Generator(archipelago, dirs, Apworlds(archipelago, data.resolve("apworlds"), null), atOnce = 1)
    fun called(id: String) = generator.log(id, 0, 100)!!.lines.map { it.text }.single { it.startsWith("args: ") }

    assertNotNull(generator.generate("brave-otter", request(plando = setOf(PlandoModule.ITEMS), skipBalancing = true)).seedName)
    assertTrue(called("brave-otter").endsWith("--spoiler 1 --plando items --skip_prog_balancing"), called("brave-otter"))
    val state = SpoilerStages(dirs).read("brave-otter")
    assertEquals(listOf("--plando", "items", "--skip_prog_balancing"), state?.arguments)
    assertEquals(1, generator.spoiler("brave-otter")?.level)

    // A race: unpredictable and without a spoiler log, so nothing is kept to complete one
    assertNotNull(generator.generate("quiet-fox", request(race = true)).seedName)
    assertTrue(called("quiet-fox").endsWith("--race --spoiler 0 --plando bosses, connections, texts"), called("quiet-fox"))
    assertNull(SpoilerStages(dirs).read("quiet-fox"))
    assertNull(generator.spoiler("quiet-fox"))
  }
}
