package net.eratiem.randogeddon.database

import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.resource.LoadableResource
import java.io.Reader

internal const val MIGRATION_DIR = "db/migration"

/**
 * Flyway's migrations, read from the list in `db/migration/index.txt` (one file name per line) instead of scanning the
 * classpath, which doesn't work in a native image. A unit test checks that the list matches the directory.
 */
internal object MigrationIndex : ResourceProvider {
  val files: List<String> by lazy {
    resource("index.txt").bufferedReader().useLines { lines ->
      lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    }
  }

  private val resources: List<LoadableResource> by lazy { files.map(::Migration) }

  override fun getResource(name: String): LoadableResource? =
    resources.firstOrNull { it.filename == name || it.relativePath == name || it.absolutePath == name }

  override fun getResources(prefix: String, suffixes: Array<String>): Collection<LoadableResource> =
    resources.filter { r -> r.filename.startsWith(prefix) && suffixes.any { r.filename.endsWith(it) } }

  private fun resource(file: String) = MigrationIndex::class.java.classLoader.getResourceAsStream("$MIGRATION_DIR/$file")
    ?: error("Missing resource $MIGRATION_DIR/$file")

  private class Migration(private val file: String) : LoadableResource() {
    override fun read(): Reader = resource(file).reader(Charsets.UTF_8)
    override fun getAbsolutePath() = "$MIGRATION_DIR/$file"
    override fun getAbsolutePathOnDisk() = absolutePath
    override fun getFilename() = file
    override fun getRelativePath() = file
  }
}
