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
    fun connect(config: DatabaseConfig, migrate: Boolean, poolSize: Int = 5): RandogeddonDatabase {
      val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.url
        username = config.user
        password = config.password
        // Flyway holds one connection for its lock while migrating on a second
        maximumPoolSize = if (migrate) maxOf(poolSize, 2) else poolSize
        poolName = "randogeddon"
      })
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
  }
}

private object NoJavaMigrations : ClassProvider<JavaMigration> {
  override fun getClasses(): Collection<Class<out JavaMigration>> = emptyList()
}
