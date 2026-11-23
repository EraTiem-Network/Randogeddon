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
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.SQLException
import java.util.TimeZone
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

  /**
   * [block] in one transaction. Exposed runs a transaction that failed with an [SQLException] again, up to three
   * times in all (its default, kept: a connection lost in between is tried anew; a refused row is refused each time,
   * and a statement that ran into its time limit waits each time). What still fails is thrown as a plain
   * [SQLException] with PostgreSQL's state and words only: Exposed's own names the statements with their values (an
   * address, a password's hash), which don't belong in a log.
   */
  suspend fun <T> query(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
    try {
      transaction(db = exposed) { block() }
    } catch (e: SQLException) {
      throw withoutStatements(e)
    }
  }

  /** [block] on a connection of the pool, in one transaction: plain JDBC, for what the tables don't say (the roles). */
  internal suspend fun <T> jdbc(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
    dataSource.connection.use { c ->
      c.autoCommit = false
      try {
        block(c).also { c.commit() }
      } catch (e: Throwable) {
        runCatching { c.rollback() }
        throw if (e is SQLException) withoutStatements(e) else e
      }
    }
  }

  /**
   * Whether this login is a superuser of the database. Neither program should be one (each has a role of its own);
   * they say so at their start.
   */
  suspend fun superuser(): Boolean = jdbc { c ->
    c.createStatement().use { s ->
      s.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user").use { it.next() && it.getBoolean(1) }
    }
  }

  override fun close() = dataSource.close()

  /**
   * Waits until the tables the gateway reads can be read as this code knows them (every column of [Tables]): the
   * tracker has migrated, and this login may read them. A schema older than the code keeps it waiting like a missing
   * table does.
   */
  private fun awaitTables(waitFor: Duration) {
    val until = System.nanoTime() + waitFor.inWholeNanoseconds
    while (true) {
      val failure = runCatching {
        transaction(db = exposed) {
          for (table in listOf(SessionsTable, SessionHostedSlotsTable, SlotPasswordsTable, AppSettingsTable)) {
            table.selectAll().limit(0).toList()
          }
        }
      }.exceptionOrNull() ?: return
      if (System.nanoTime() >= until) {
        dataSource.close()
        val reason = (failure as? SQLException)?.let { withoutStatements(it).message } ?: failure.message
        throw IllegalStateException("The database isn't ready after $waitFor: $reason")
      }
      Thread.sleep(1000)
    }
  }

  companion object {
    /** How long one statement of the programs may take; a migration (its own connections) has no limit. */
    const val STATEMENT_TIMEOUT = "20s"

    /** How long a statement waits for a lock before it gives up (a dump, a migration, a held row). */
    const val LOCK_TIMEOUT = "5s"

    /**
     * [waitFor]: how long to wait for the login to work and the tables to be there, for who doesn't make them (the
     * gateway): on a new database the tracker first migrates and then makes the gateway's role, a moment after both
     * containers started. Without it, a login that doesn't work fails at once.
     *
     * The times are kept without a zone, as UTC ([Tables]); Exposed writes and reads them in the process's zone, so
     * that is pinned to UTC here, whatever the container was started with (`TZ`).
     */
    fun connect(config: DatabaseConfig, migrate: Boolean, poolSize: Int = 5, waitFor: Duration = Duration.ZERO): RandogeddonDatabase {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
      if (migrate) migrate(config)
      val dataSource = HikariDataSource(pool(config, poolSize, "randogeddon", waitFor).apply {
        // A query takes milliseconds: one that waits for a lock, or for a database that stopped answering, ends and
        // gives its connection back instead of holding it for good
        connectionInitSql = "SET statement_timeout = '$STATEMENT_TIMEOUT'; SET lock_timeout = '$LOCK_TIMEOUT'; " +
          "SET idle_in_transaction_session_timeout = '60s'"
        // Longer than the statement's limit: the server ends a statement first, this only a silent connection
        addDataSourceProperty("socketTimeout", "60")
      })
      val database = RandogeddonDatabase(dataSource)
      if (!migrate && waitFor > Duration.ZERO) database.awaitTables(waitFor)
      return database
    }

    /**
     * Migrates on connections of their own, without the programs' time limits (a migration may take longer than a
     * query, and waits for the locks it needs); closed again when it is done.
     */
    private fun migrate(config: DatabaseConfig) {
      // Flyway holds one connection for its lock while migrating on a second
      HikariDataSource(pool(config, 2, "randogeddon-migrations", Duration.ZERO)).use { migrations ->
        Flyway.configure()
          .dataSource(migrations)
          // No classpath scanning, which a native image can't do: the migrations are listed in db/migration/index.txt
          .resourceProvider(MigrationIndex)
          .javaMigrationClassProvider(NoJavaMigrations)
          .load()
          .migrate()
      }
    }

    private fun pool(config: DatabaseConfig, size: Int, name: String, waitFor: Duration) = HikariConfig().apply {
      jdbcUrl = config.url
      username = config.user
      password = config.password
      maximumPoolSize = size
      poolName = name
      // A free connection within this, or the query fails (Hikari's default: 30 s)
      connectionTimeout = 10_000
      addDataSourceProperty("connectTimeout", "10")
      // PostgreSQL's details of a refused row name its values (an address): not in an exception's message
      addDataSourceProperty("logServerErrorDetail", "false")
      // Tried again every second for this long (the default: not at all)
      if (waitFor > Duration.ZERO) initializationFailTimeout = waitFor.inWholeMilliseconds
    }
  }
}

/**
 * [e] as a plain [SQLException]: the state and the words of PostgreSQL's (the driver's, without the details that name
 * a row's values), without Exposed's, which names the statements with their values, and without a batch's, which does
 * too.
 */
internal fun withoutStatements(e: SQLException): SQLException {
  val chain = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().toList()
  val driver = chain.last().let { (it as? BatchUpdateException)?.nextException ?: it }
  val own = driver !is BatchUpdateException && driver::class.simpleName != "ExposedSQLException"
  val state = driver.sqlState?.takeIf(String::isNotEmpty) ?: chain.firstNotNullOfOrNull { it.sqlState?.takeIf(String::isNotEmpty) }
  return SQLException(if (own) driver.message else "The database refused a statement", state, driver.errorCode)
}

private object NoJavaMigrations : ClassProvider<JavaMigration> {
  override fun getClasses(): Collection<Class<out JavaMigration>> = emptyList()
}
