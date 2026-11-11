package net.eratiem.randogeddon.database

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationIndexTest {
  /** A migration that isn't listed would silently never run in the native image. */
  @Test
  fun `index lists exactly the migration files`() {
    val dir = File(javaClass.classLoader.getResource(MIGRATION_DIR)!!.toURI())
    val onDisk = dir.list()!!.filter { it.endsWith(".sql") }.sorted()
    assertEquals(onDisk, MigrationIndex.files.sorted())
  }

  @Test
  fun `resources are found by Flyway's prefix and suffix`() {
    val versioned = MigrationIndex.getResources("V", arrayOf(".sql"))
    assertTrue(versioned.isNotEmpty())
    assertEquals("V1__accounts.sql", MigrationIndex.getResource("V1__accounts.sql")?.filename)
    assertTrue(MigrationIndex.getResources("R", arrayOf(".sql")).isEmpty())
    assertTrue(versioned.first().read().readText().contains("CREATE TABLE users"))
  }
}
