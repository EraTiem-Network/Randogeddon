package net.eratiem.randogeddon.gateway

import org.junit.Assume.assumeFalse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Archipelago's processes as a user of their own: the command they start with, and what the gateway gives that user of
 * the data directory (here the test's own group stands for theirs: the switch itself needs the image). Skipped on
 * Windows (no POSIX modes, no `/proc`).
 */
class ProcessUserTest {
  private fun linux() = assumeFalse("needs POSIX modes and /proc", System.getProperty("os.name").startsWith("Windows"))

  @Test
  fun `as its own user a process starts through setpriv with nothing left, bounded, its out-of-memory score raised`() {
    assertEquals(
      listOf(
        "setpriv", "--reuid=archipelago", "--regid=archipelago", "--clear-groups", "--inh-caps=-all", "--ambient-caps=-all",
        "--no-new-privs", "--", "prlimit", "--nproc=3072", "--fsize=4294967296", "--", "choom", "-n", "800", "--",
        "python", "serve.py", "--port", "38282",
      ),
      ProcessUser("archipelago").command(listOf("python", "serve.py", "--port", "38282"), OOM_GAME_SERVER),
    )
    // seed_info.py: its address space bounded too
    assertEquals(
      listOf("prlimit", "--nproc=3072", "--fsize=4294967296", "--as=1024", "--", "choom"),
      ProcessUser("archipelago").command(listOf("python"), OOM_FIRST, memory = 1024).drop(8).take(6),
    )
    assertEquals("/home/archipelago", ProcessUser("archipelago").home)
    // The image's start names it; without (the tests, a gateway started by hand) the processes run as the gateway
    assertEquals("archipelago", ProcessUser.from(" archipelago ")?.name)
    assertNull(ProcessUser.from(""))
  }

  @Test
  fun `as the gateway's user a process starts as it was given, its out-of-memory score raised by the gateway`() {
    linux()
    // It says its score once it is asked (the gateway raised it right after the start)
    val proc = startProcess(listOf("sh", "-c", "read _; cat /proc/${'$'}${'$'}/oom_score_adj"), null, input = true, user = null)
    proc.outputStream.bufferedWriter().apply { write("go"); newLine(); flush() }
    assertEquals(OOM_FIRST.toString(), proc.inputStream.bufferedReader().readText().trim())
    assertEquals(0, proc.waitFor())
  }

  @Test
  fun `Python reads no user site and writes no byte code, a seed's worlds are built with one hash seed`() {
    linux()
    fun environment(worlds: Boolean): List<String> {
      val proc = startProcess(listOf("sh", "-c", "env"), null, user = null, worlds = worlds)
      return proc.inputStream.bufferedReader().readLines().also { proc.waitFor() }
    }
    val plain = environment(worlds = false)
    assertTrue("PYTHONNOUSERSITE=1" in plain && "PYTHONDONTWRITEBYTECODE=1" in plain, plain.toString())
    // Not for a game server (its dictionaries hold what clients send)
    assertTrue(plain.none { it.startsWith("PYTHONHASHSEED=") }, plain.toString())
    assertTrue("PYTHONHASHSEED=$WORLDS_HASH_SEED" in environment(worlds = true))
  }

  @Test
  fun `of the data directory the processes reach their sessions and the uploaded seeds, nothing else`() {
    linux()
    val data = Files.createTempDirectory("data")
    val session = data.resolve("sessions/brave-otter").createDirectories()
    session.resolve("AP_1.zip").writeText("seed")
    session.resolve("AP_1.apsave").writeText("save")
    val players = session.resolve("generation/players").createDirectories()
    players.resolve("000-alice.yaml").writeText("name: Alice")
    data.resolve("seed-uploads").createDirectories().resolve("0f.zip").writeText("upload")
    val library = data.resolve("apworlds/0.6.8").createDirectories()
    library.resolve("hk.apworld").writeText("world")
    val minecraft = data.resolve("minecraft").createDirectories()
    // The test's own group stands for theirs
    val group = Files.readAttributes(data, PosixFileAttributes::class.java).group().name
    val user = ProcessUser("archipelago", group)

    user.prepare(data)
    fun mode(path: Path) = Integer.toOctalString(modeOf(path))
    // Only passed through, to sessions/
    assertEquals("710", mode(data))
    assertEquals("2710", mode(data.resolve("sessions")))
    // A session's directories: read and written, what is made in them theirs too (setgid); its files read and written
    for (dir in listOf(session, session.resolve("generation"), players)) assertEquals("2770", mode(dir), "$dir")
    for (file in listOf(session.resolve("AP_1.zip"), session.resolve("AP_1.apsave"), players.resolve("000-alice.yaml"))) {
      assertEquals("660", mode(file), "$file")
    }
    // The uploaded seeds: only read
    assertEquals("2750", mode(data.resolve("seed-uploads")))
    assertEquals("640", mode(data.resolve("seed-uploads/0f.zip")))
    // The APWorld library and the Minecraft servers' folder: the gateway's alone (their group is the processes' here)
    for (closed in listOf(data.resolve("apworlds"), minecraft)) assertEquals(0, modeOf(closed) and "077".toInt(8), mode(closed))
    assertTrue(Files.readAttributes(session, PosixFileAttributes::class.java).group().name == group)

    // Again (each start): nothing to change, nothing refused
    user.prepare(data)
    assertEquals("2770", mode(session))
    // Made in a session's directory after: the processes' group, by the setgid bit
    val kept = session.resolve("kept").createDirectories()
    assertEquals(group, Files.readAttributes(kept, PosixFileAttributes::class.java).group().name)
    data.toFile().deleteRecursively()
  }

  @Test
  fun `a file the processes only read stays so`() {
    linux()
    val dir = Files.createTempDirectory("custom_worlds")
    val world = dir.resolve("hk.apworld").also { it.writeText("world") }
    val group = Files.readAttributes(dir, PosixFileAttributes::class.java).group().name
    val user = ProcessUser("archipelago", group)
    assertTrue(user.readable(world))
    assertEquals("640", Integer.toOctalString(modeOf(world)))
    assertTrue(user.readable(dir))
    assertEquals("2750", Integer.toOctalString(modeOf(dir)))
    dir.toFile().deleteRecursively()
  }
}
