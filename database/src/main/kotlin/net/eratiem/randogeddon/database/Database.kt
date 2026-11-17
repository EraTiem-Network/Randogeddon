package net.eratiem.randogeddon.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.ClassProvider
import org.flywaydb.core.api.migration.JavaMigration
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration

/** Where PostgreSQL is; every value comes from the environment (`DB_URL`, `DB_USER`, `DB_PASSWORD`). */
data class DatabaseConfig(val url: String, val user: String, val password: String)

/**
 * The connection pool and Exposed's view of it. Queries run on the IO dispatcher, one transaction each.
 *
 * Only the tracker passes `migrate = true`; Flyway's lock in PostgreSQL makes concurrent starts safe anyway.
 */
class RandogeddonDatabase private constructor(private val dataSource: HikariDataSource) : AutoCloseable {
  private val exposed: Database = Database.connect(dataSource)

  suspend fun <T> query(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
    transaction(db = exposed) { block() }
  }

  override fun close() = dataSource.close()

  companion object {
    /**
     * [waitFor]: how long to wait for the login to work and the tables to be there, for who doesn't make them (the
     * gateway): on a new database the tracker first migrates and then makes the gateway's role, a moment after both
     * containers started. Without it, a login that doesn't work fails at once.
     */
    fun connect(config: DatabaseConfig, migrate: Boolean, poolSize: Int = 5, waitFor: Duration = Duration.ZERO): RandogeddonDatabase {
      val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.url
        username = config.user
        password = config.password
        // Flyway holds one connection for its lock while migrating on a second
        maximumPoolSize = if (migrate) maxOf(poolSize, 2) else poolSize
        poolName = "randogeddon"
        // Tried again every second for this long (the default: not at all)
        if (waitFor > Duration.ZERO) initializationFailTimeout = waitFor.inWholeMilliseconds
      })
      if (!migrate && waitFor > Duration.ZERO) awaitTables(dataSource, waitFor)
      if (migrate) {
        Flyway.configure()
          .dataSource(dataSource)
          // No classpath scanning, which a native image can't do: the migrations are listed in db/migration/index.txt
          .resourceProvider(MigrationIndex)
          .javaMigrationClassProvider(NoJavaMigrations)
          .load()
          .migrate()
      }
      return RandogeddonDatabase(dataSource)
    }

    /** Waits until the sessions' table can be read (the tracker has migrated, and this login may read it). */
    private fun awaitTables(dataSource: HikariDataSource, waitFor: Duration) {
      val until = System.nanoTime() + waitFor.inWholeNanoseconds
      while (true) {
        val failure = runCatching {
          dataSource.connection.use { c -> c.createStatement().use { it.execute("SELECT 1 FROM sessions LIMIT 1") } }
        }.exceptionOrNull() ?: return
        if (System.nanoTime() >= until) {
          dataSource.close()
          throw IllegalStateException("The database isn't ready after $waitFor: ${failure.message}", failure)
        }
        Thread.sleep(1000)
      }
    }
  }
}

private object NoJavaMigrations : ClassProvider<JavaMigration> {
  override fun getClasses(): Collection<Class<out JavaMigration>> = emptyList()
}
