package net.eratiem.randogeddon.gateway

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("net.eratiem.randogeddon.gateway.ProcessUser")

/**
 * How readily the kernel ends one of Archipelago's processes when the gateway's container is out of memory
 * (`oom_score_adj`, added to its share of the memory in thousandths): a generation, a spoiler log's run, a logic and
 * the short scripts first ([OOM_FIRST]), a game server after them ([OOM_GAME_SERVER]: it is taken first only if it holds
 * a fifth of the container's memory more than the largest of those), the gateway (0) and every session with it last.
 */
const val OOM_FIRST = 1000
const val OOM_GAME_SERVER = 800

/** A file mode in octal, as `chmod` takes it (`"2770"`: the group's setgid bit, owner and group everything). */
private fun mode(octal: String) = octal.toInt(8)

/** The data directory itself: Archipelago's group only passes through it (to `sessions/`), nobody else gets in. */
private val PASS_ONLY = mode("0710")
/**
 * `sessions/`, the parent of the processes' temporary directories: passed through, not listed; what is made in it is
 * the group's.
 */
private val PASS_SHARED = mode("2710")
/** A directory the processes read and write (a session's, a temporary one); what is made in it is the group's. */
private val SHARED_DIR = mode("2770")
private val SHARED_FILE = mode("0660")
/** A directory and a file the processes only read (the uploaded seeds, `custom_worlds/`). */
private val READ_DIR = mode("2750")
private val READ_FILE = mode("0640")
/** Everything but the owner's and the group's bits (setgid, sticky, the others'). */
private val OTHERS = mode("7007")

/**
 * The user Archipelago's processes run as (MultiServer, Generate.py, the logic and spoiler runs, the small scripts: all
 * of them run APWorld code, which is third-party), [name], with a group of the same name ([groupName]) the gateway is a
 * member of. In the gateway's image its start (`gateway/docker-entrypoint.sh`) gives the gateway its own user with only
 * `CAP_SETUID`, `CAP_SETGID` and `CAP_KILL` and names this one (`AP_RUN_AS`); each process then starts through
 * `setpriv` as this user, every capability dropped ([command]). Another user can't read the gateway's environment (the
 * database login, the internal token), its memory or its open files (`/proc/<pid>/environ`, `mem`, `fd`: the kernel's
 * ptrace check), nor what the data directory keeps for the gateway alone ([prepare]); the gateway still ends and pauses
 * its processes (`CAP_KILL`). Without a name (the tests, a gateway started as any other user) they run as the gateway.
 */
class ProcessUser(val name: String, private val groupName: String = name) {
  /** Its home directory (`useradd --create-home`): where Archipelago keeps what it writes outside its own directory. */
  val home: String get() = "/home/$name"

  /**
   * [command] as this user: `setpriv` switches to it with no other group and no capability left to keep or pass on
   * (none inheritable, none ambient, no new ones through an executable: `no_new_privs`, as the whole container has
   * it), then `choom` raises its out-of-memory score to [oomScore] (raising needs no privilege; it stays through the
   * exec). Neither takes an option from [command]: it follows `--`.
   */
  fun command(command: List<String>, oomScore: Int): List<String> = listOf(
    "setpriv", "--reuid=$name", "--regid=$groupName", "--clear-groups", "--inh-caps=-all", "--ambient-caps=-all",
    "--no-new-privs", "--", "choom", "-n", oomScore.toString(), "--",
  ) + command

  private val group: GroupPrincipal by lazy {
    FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByGroupName(groupName)
  }

  /**
   * [path] in the processes' group with [mode] (setgid included), only where it isn't so already; `false`: it can't be
   * (not the gateway's own, or gone). A symbolic link is left as it is.
   */
  internal fun give(path: Path, mode: Int): Boolean = runCatching {
    if (path.isSymbolicLink()) return@runCatching
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
    if (view.readAttributes().group() != group) view.setGroup(group)
    if (modeOf(path) != mode) Files.setAttribute(path, "unix:mode", mode, LinkOption.NOFOLLOW_LINKS)
  }.isSuccess

  /** [path] closed to the processes: nobody else's bits, nor the group's where the group is theirs. */
  private fun close(path: Path): Boolean = runCatching {
    if (path.isSymbolicLink()) return@runCatching
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
    val current = modeOf(path)
    val kept = if (view.readAttributes().group() == group) mode("0700") else mode("0770")
    val closed = current and OTHERS.inv() and kept
    if (closed != current) Files.setAttribute(path, "unix:mode", closed, LinkOption.NOFOLLOW_LINKS)
  }.isSuccess

  /** A directory the processes read and write (a session's, a temporary one). */
  fun share(dir: Path): Boolean = give(dir, SHARED_DIR)

  /** A directory the processes only pass through, to what is shared in it (the temporary directories' parent). */
  fun passThrough(dir: Path): Boolean = give(dir, PASS_SHARED)

  /** A file (or a directory) the processes only read: an uploaded seed, an APWorld in `custom_worlds/`. */
  fun readable(path: Path): Boolean = give(path, if (path.isDirectory()) READ_DIR else READ_FILE)

  /**
   * The data directory as the processes may reach it, at every start of the gateway (the first after an update too,
   * over what a gateway before it made as its own): `sessions/<id>/` read and written (the seed, the save, the YAMLs of
   * its generation, its room), `seed-uploads/` read; nothing else of it (the APWorld library, `old/`, `minecraft/`),
   * which only the gateway (and the tracker, the same user) reaches. What can't be changed (another user's) is said once.
   */
  fun prepare(dataDir: Path) {
    var refused = 0
    var first: Path? = null
    fun count(path: Path, done: Boolean) {
      if (!done) {
        refused++
        if (first == null) first = path
      }
    }
    val sessions = dataDir.resolve("sessions")
    val uploads = dataDir.resolve("seed-uploads")
    runCatching { sessions.createDirectories(); uploads.createDirectories() }
    count(dataDir, give(dataDir, PASS_ONLY))
    for (entry in entries(dataDir)) {
      when (entry.fileName) {
        sessions.fileName -> {
          count(entry, give(entry, PASS_SHARED))
          // Each session's whole directory: what a gateway before this one made is its own group's, not the processes'
          for (dir in entries(entry).filter { it.isDirectory() }) {
            runCatching {
              Files.walk(dir).use { paths ->
                paths.forEach { path ->
                  val directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                  count(path, give(path, if (directory) SHARED_DIR else SHARED_FILE))
                }
              }
            }.onFailure { count(dir, false) }
          }
        }
        uploads.fileName -> {
          count(entry, give(entry, READ_DIR))
          entries(entry).forEach { count(it, give(it, READ_FILE)) }
        }
        else -> count(entry, close(entry))
      }
    }
    if (refused > 0) {
      logger.warn(
        "{} of the data directory's entries couldn't be given to Archipelago's user {} or closed to it (not the " +
          "gateway's own, e.g. {}): on the host they must belong to uid 1000, as the rest",
        refused, name, first,
      )
    }
  }

  private fun entries(dir: Path): List<Path> = runCatching { dir.listDirectoryEntries() }.getOrDefault(emptyList())

  companion object {
    /** The processes' own user from the setting (`AP_RUN_AS`, set by the image's start); `null` when it is empty. */
    fun from(setting: String): ProcessUser? = setting.trim().takeIf { it.isNotEmpty() }?.let { ProcessUser(it) }
  }
}

/** [path]'s mode bits (setgid, sticky and the permissions), not following a link. */
internal fun modeOf(path: Path): Int =
  (Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int) and mode("7777")

/**
 * The user Archipelago's processes run as, set once at the gateway's start ([ProcessUser.from]); `null`: the gateway's
 * own (the tests).
 */
@Volatile
var processUser: ProcessUser? = null
