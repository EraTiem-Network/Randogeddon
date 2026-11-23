package net.eratiem.randogeddon.gateway

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

/** How deep a tree is deleted at most ([SharedFolder.delete]): Archipelago's output is a few levels; deeper is left. */
private const val MAX_DEPTH = 64

/** What the gateway writes into a shared folder: its own to write, the processes' group's to read. */
private const val WRITTEN_MODE = "rw-r-----"

/**
 * A folder Archipelago's user ([ProcessUser]) writes into too: a session's directory, a process's temporary one. That
 * user runs third-party APWorld code, which can put a symbolic link (or a FIFO, a directory) where the gateway expects
 * its file, and swap it between a look and a use; through a link the gateway, another user with other rights, would
 * read what only it may (its own environment in `/proc/self/environ`) into a file that user reads or hands out, or
 * write and delete where only the gateway may. So nothing here follows a link: each folder below [root] is opened one
 * name at a time without following one (`openat` with `O_NOFOLLOW`, Java's [SecureDirectoryStream], where the platform
 * has it; elsewhere each name is looked at without following it first), a file is read only when it is a regular one,
 * written only as a new one (`O_EXCL`, which never follows a link) that is then moved over the old one at once, and a
 * tree is deleted without entering a link. [root] itself is a directory that user can't replace (`sessions/<id>/`,
 * whose parent it can't write; a temporary directory, likewise). Names are relative to it, `/` between folders.
 */
internal class SharedFolder(val root: Path) {
  /** The regular file [name], at most [max] bytes; an [IOException] for anything else (a link, a FIFO, more, gone). */
  fun read(name: String, max: Long): ByteArray = ByteArrayOutputStream().also { copyTo(name, it, max) }.toByteArray()

  fun readText(name: String, max: Long): String = read(name, max).toString(Charsets.UTF_8)

  /** The regular file [name] into [out], at most [max] bytes (more is an [IOException], after [max] were written). */
  fun copyTo(name: String, out: OutputStream, max: Long) {
    val (folders, file) = split(name)
    inFolder(folders) { dir ->
      dir.readFile(file).use { channel ->
        val input = Channels.newInputStream(channel)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          val n = input.read(buffer)
          if (n < 0) break
          total += n
          if (total > max) throw IOException("$root/$name is larger than $max bytes")
          out.write(buffer, 0, n)
        }
      }
    }
  }

  /** [name]'s attributes if it is a regular file (not a link to one); `null`: it isn't, or it isn't there. */
  fun file(name: String): BasicFileAttributes? = runCatching {
    val (folders, file) = split(name)
    inFolder(folders) { it.attributes(file) }?.takeIf { it.isRegularFile }
  }.getOrNull()

  /** The regular files in folder [name] (`""`: [root]) with their attributes; none where it is no folder. */
  fun files(name: String = ""): Map<String, BasicFileAttributes> = runCatching {
    inFolder(parts(name)) { dir ->
      dir.names().mapNotNull { entry -> dir.attributes(entry)?.takeIf { it.isRegularFile }?.let { entry.toString() to it } }.toMap()
    }
  }.getOrDefault(emptyMap())

  /**
   * Writes [name] anew: as `<name>.new` (whatever had that name before goes, unfollowed), created new, then moved over
   * [name] at once (a link there is replaced, not followed). A reader has the file of before or this one.
   */
  fun write(name: String, content: (OutputStream) -> Unit) {
    val (folders, file) = split(name)
    inFolder(folders) { dir ->
      val fresh = Path.of("$file.new")
      deleteIn(dir, fresh, 0)
      Channels.newOutputStream(dir.createFile(fresh)).use(content)
      dir.move(fresh, file)
    }
  }

  fun write(name: String, bytes: ByteArray) = write(name) { it.write(bytes) }

  fun writeText(name: String, text: String) = write(name, text.toByteArray())

  /**
   * Folder [name] (each level of it) made where it isn't, and refused (an [IOException]) where it is no folder, or a
   * link to one. A level swapped for a link while it is made can lead the next one's `mkdir` elsewhere (an empty folder,
   * nothing more): what is read or written in it afterwards is opened without following the link, and fails.
   */
  fun folder(name: String) {
    val levels = parts(name)
    for (depth in 1..levels.size) {
      val path = levels.take(depth)
      val there = inFolder(path.dropLast(1)) { it.attributes(Path.of(path.last())) }
      when {
        there == null -> runCatching { Files.createDirectory(root.resolve(path.joinToString("/"))) }
          // Made by another meanwhile: it is looked at again below
          .onFailure { if (it !is FileAlreadyExistsException) throw it }
        !there.isDirectory -> throw IOException("$root/${path.joinToString("/")} is no folder")
      }
    }
    // Opened as it is now: a link fails here
    inFolder(levels) {}
  }

  /** Moves [from] to [to] (a rename, of whatever is there: not followed), both under [root]. */
  fun move(from: String, to: String) {
    val (fromFolders, fromName) = split(from)
    val (toFolders, toName) = split(to)
    require(fromFolders == toFolders) { "Only within one folder: $from, $to" }
    inFolder(fromFolders) { it.move(fromName, toName) }
  }

  /** Deletes [name] and, if it is a folder (not a link to one), everything in it; nothing behind a link. */
  fun delete(name: String) {
    val (folders, entry) = split(name)
    runCatching { inFolder(folders) { deleteIn(it, entry, 0) } }.onFailure { if (it !is NoSuchFileException) throw it }
  }

  private fun deleteIn(dir: Folder, name: Path, depth: Int) {
    val attributes = dir.attributes(name) ?: return
    if (!attributes.isDirectory) return dir.deleteFile(name)
    if (depth < MAX_DEPTH) {
      runCatching { dir.sub(name).use { sub -> sub.names().forEach { deleteIn(sub, it, depth + 1) } } }
    }
    try {
      dir.deleteDirectory(name)
    } catch (e: FileSystemException) {
      // Swapped for a file or a link meanwhile: that goes instead
      if (dir.attributes(name)?.isDirectory == false) dir.deleteFile(name) else throw e
    }
  }

  private fun parts(name: String): List<String> = name.split('/').filter { it.isNotEmpty() }.onEach {
    require(it != "." && it != "..") { "Not a name in a folder: $name" }
  }

  private fun split(name: String): Pair<List<String>, Path> {
    val parts = parts(name)
    require(parts.isNotEmpty()) { "No name: $name" }
    return parts.dropLast(1) to Path.of(parts.last())
  }

  /** [block] with folder [folders] below [root], each opened without following a link. */
  private fun <T> inFolder(folders: List<String>, block: (Folder) -> T): T {
    var dir = Folder.open(root)
    try {
      for (name in folders) {
        val sub = dir.sub(Path.of(name))
        dir.close()
        dir = sub
      }
      return block(dir)
    } finally {
      dir.close()
    }
  }

  /** An open folder: its entries looked at, read, written, moved and deleted by name, never through a link. */
  private interface Folder : AutoCloseable {
    /** `null`: nothing of that name. */
    fun attributes(name: Path): BasicFileAttributes?
    fun names(): List<Path>
    fun open(name: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): SeekableByteChannel
    fun sub(name: Path): Folder
    fun move(from: Path, to: Path)
    fun deleteFile(name: Path)
    fun deleteDirectory(name: Path)
    override fun close()

    /** The regular file [name]; anything else is an [IOException] (a FIFO is refused before it is opened). */
    fun readFile(name: Path): SeekableByteChannel {
      val attributes = attributes(name) ?: throw NoSuchFileException(name.toString())
      if (!attributes.isRegularFile) throw IOException("$name is no file (a link, a folder or another kind)")
      return open(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
    }

    /** [name] created new (never through a link: `O_EXCL`), the gateway's to write, the processes' to read. */
    fun createFile(name: Path): SeekableByteChannel {
      val mode: Array<FileAttribute<*>> =
        if (POSIX) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(WRITTEN_MODE))) else emptyArray()
      return open(name, setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS), *mode)
    }

    companion object {
      private val POSIX = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

      fun open(path: Path): Folder {
        val stream = Files.newDirectoryStream(path)
        if (stream is SecureDirectoryStream<Path>) return Secure(stream)
        stream.close()
        return Plain(path)
      }
    }
  }

  /** `openat` and its kin on an open folder (Linux): race-free, nothing followed. */
  private class Secure(private val stream: SecureDirectoryStream<Path>) : Folder {
    override fun attributes(name: Path): BasicFileAttributes? = try {
      stream.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
    } catch (_: NoSuchFileException) {
      null
    }

    override fun names(): List<Path> = stream.map { it.fileName }

    override fun open(name: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): SeekableByteChannel =
      stream.newByteChannel(name, options, *attributes)

    override fun sub(name: Path): Folder = Secure(stream.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS))

    override fun move(from: Path, to: Path) = stream.move(from, stream, to)

    override fun deleteFile(name: Path) = stream.deleteFile(name)

    override fun deleteDirectory(name: Path) = stream.deleteDirectory(name)

    override fun close() = stream.close()
  }

  /** Where there is no [SecureDirectoryStream] (Windows): each name looked at without following it, then used. */
  private class Plain(private val dir: Path) : Folder {
    override fun attributes(name: Path): BasicFileAttributes? = try {
      Files.readAttributes(dir.resolve(name), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
      null
    }

    override fun names(): List<Path> = Files.newDirectoryStream(dir).use { entries -> entries.map { it.fileName } }

    override fun open(name: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): SeekableByteChannel =
      Files.newByteChannel(dir.resolve(name), options, *attributes)

    override fun sub(name: Path): Folder {
      val attributes = attributes(name) ?: throw NoSuchFileException(dir.resolve(name).toString())
      if (!attributes.isDirectory) throw IOException("${dir.resolve(name)} is no folder (a link, a file or another kind)")
      return Plain(dir.resolve(name))
    }

    override fun move(from: Path, to: Path) {
      Files.move(dir.resolve(from), dir.resolve(to), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun deleteFile(name: Path) = Files.delete(dir.resolve(name))

    override fun deleteDirectory(name: Path) = Files.delete(dir.resolve(name))

    override fun close() {}
  }
}

/**
 * Deletes [path] and everything in it without following a link ([SharedFolder.delete]): a folder Archipelago's user
 * wrote into (a temporary one, a session's), whose parent it can't write. Nothing there: nothing to do.
 */
internal fun deleteTree(path: Path) {
  val parent = path.toAbsolutePath().parent ?: error("$path has no parent")
  if (!Files.isDirectory(parent)) return
  SharedFolder(parent).delete(path.fileName.toString())
}
