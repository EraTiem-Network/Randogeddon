package net.eratiem.randogeddon.server

import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.database.DatabaseConfig
import net.eratiem.randogeddon.database.EmailChangeStore
import net.eratiem.randogeddon.database.InvitationStore
import net.eratiem.randogeddon.database.LoginLinkStore
import net.eratiem.randogeddon.database.LoginSessionStore
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.SessionEventStore
import net.eratiem.randogeddon.database.SessionFinishStore
import net.eratiem.randogeddon.database.SessionRoleStore
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.SessionSnapshotStore
import net.eratiem.randogeddon.database.SessionStore
import net.eratiem.randogeddon.database.SessionTimeStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.TwoFactorStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.account.AccountService
import net.eratiem.randogeddon.server.account.LoginService
import net.eratiem.randogeddon.server.account.Mail
import net.eratiem.randogeddon.server.account.MailLimits
import net.eratiem.randogeddon.server.account.Mailer
import net.eratiem.randogeddon.server.account.SingleSignOn
import net.eratiem.randogeddon.server.account.SlotRenewal
import net.eratiem.randogeddon.server.account.TwoFactor
import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.server.admin.GameServerLimit
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.admin.SessionLimit
import net.eratiem.randogeddon.server.config.OidcConfig
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.GenerateResult
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/**
 * The tracker on a real PostgreSQL (Testcontainers; Docker must be running), migrated as the tracker migrates it, with
 * its services on the real stores. Only what talks to the outside is faked: the mails ([mailer]) and the gateway
 * ([gateway]). One per test, as the database's own tests have it: a container each. [oidc]: single sign-on as
 * configured; [adminPasswordLogin]: `ADMIN_PASSWORD_LOGIN`.
 */
class TrackerHarness(oidc: OidcConfig? = null, adminPasswordLogin: Boolean = false) : AutoCloseable {
  private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
  private val database = DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password)
  val db = RandogeddonDatabase.connect(database, migrate = true)

  /** The tracker's state, the gateway's data and the web app: empty, gone with the harness. */
  private val dir = Files.createTempDirectory("randogeddon-tracker")

  val gateway = FakeGateway()
  val mailer = TestMailer()

  val config = TrackerConfig(
    port = 0,
    title = "Randogeddon",
    webDir = dir.resolve("web"),
    stateDir = dir.resolve("state"),
    dataDir = dir.resolve("data"),
    publicAddress = "{id}.randogeddon.eratiem.net:443",
    gatewayHost = "localhost",
    database = database,
    gameServerApi = gateway.url,
    internalToken = "internal-token",
    oidc = oidc,
    adminPasswordLogin = adminPasswordLogin,
  )

  val users = UserStore(db)
  val loginStore = LoginSessionStore(db)
  val twoFactorStore = TwoFactorStore(db)
  val invitations = InvitationStore(db)
  val emailChanges = EmailChangeStore(db)
  val setups = SessionSetupStore(db)
  val sessionStore = SessionStore(db)
  val finishes = SessionFinishStore(db)
  val roles = SessionRoleStore(db)
  val settings = AppSettingsStore(db)
  private val snapshots = SessionSnapshotStore(db)
  private val events = SessionEventStore(db)

  val gameServer = GameServerClient(config)
  private val archives = Archives(config, snapshots)
  val sessions =
    LiveSessions(config, sessionStore, SessionTimeStore(db), setups, finishes, gameServer, archives, events, snapshots)
  private val list = SessionList(config, sessions)
  val logins = LoginService(users, loginStore, list, config)
  val twoFactor = TwoFactor(twoFactorStore, config, loginStore)
  val mailLimits = MailLimits()
  val accounts = AccountService(
    users, invitations, emailChanges, twoFactor, logins, mailer, sessions, config, mailLimits, setups, NoRenewals,
  )
  val singleSignOn by lazy { SingleSignOn(config, users, LoginLinkStore(db), accounts, loginStore, NoRenewals) }
  val admin by lazy {
    SessionAdmin(
      config, gameServer, users, setups, SlotPasswordStore(db), sessionStore, finishes, sessions, list, archives,
      Spoilers(gameServer, sessions), roles, settings, SessionLimit(setups, users, settings),
      GameServerLimit(gameServer, settings), events,
    )
  }

  /** A new account that signed up as [name] (an Administrator if [administrator]), as the stores read it. */
  suspend fun user(name: String, administrator: Boolean = false, email: String? = null): User {
    val created = users.create(name, "hash", email = email)
    if (administrator) users.setAdmin(created.id, true)
    return users.findActive(created.id)!!
  }

  override fun close() {
    runCatching { sessions.close() }
    runCatching { gateway.close() }
    db.close()
    postgres.stop()
    dir.toFile().deleteRecursively()
  }
}

/** The slots of accounts kept out or deleted: nothing to renew here (that is the gateway's and the sessions' part). */
object NoRenewals : SlotRenewal {
  override suspend fun renewOnlyTheirs(account: Uuid) = Unit
}

/** The mails as they would go out ([sent]); while [failing], the mail server can't be reached. */
class TestMailer : Mailer {
  val sent = CopyOnWriteArrayList<Mail>()

  @Volatile
  var failing = false

  override suspend fun send(mail: Mail) {
    if (failing) throw IOException("the mail server can't be reached")
    sent += mail
  }
}

/** Holds the gateway's answers while closed, until it is opened; [reached] once one waits at it. Open at first. */
class Gate {
  @Volatile
  private var opened = CompletableDeferred(Unit)

  val reached = CompletableDeferred<Unit>()

  fun close() {
    opened = CompletableDeferred()
  }

  fun open() {
    opened.complete(Unit)
  }

  suspend fun pass() {
    val gate = opened
    if (!gate.isCompleted) reached.complete(Unit)
    gate.await()
  }
}

/**
 * The gateway's internal API as far as the tracker's creations use it, on a port of its own: no sessions on it, one
 * release, no games listed (the YAMLs aren't checked against them), a seed for each generation ([results], by session,
 * else `seed-<id>`), and game servers that start. [listing] and [generating] hold the release listing (a creation's
 * checks) and the generations. Anything else is a 404.
 */
class FakeGateway : AutoCloseable {
  val listing = Gate()
  val generating = Gate()
  val results = ConcurrentHashMap<String, GenerateResult>()

  private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
    routing {
      get("/internal/sessions") { call.respondText("""{"sessions":[]}""", ContentType.Application.Json) }
      get("/internal/versions") { call.respondText("""["0.6.8"]""", ContentType.Application.Json) }
      get("/internal/archipelago") {
        listing.pass()
        call.respondText("{}", ContentType.Application.Json)
      }
      post("/internal/sessions/{id}/generate") {
        val id = call.parameters["id"].orEmpty()
        generating.pass()
        val result = results[id] ?: GenerateResult(seedName = "seed-$id")
        call.respondText(ApiJson.encodeToString(GenerateResult.serializer(), result), ContentType.Application.Json)
      }
      post("/internal/sessions/{id}/server/start") {
        call.respondText("""{"status":"starting"}""", ContentType.Application.Json)
      }
    }
  }.start(wait = false)

  val url = "http://127.0.0.1:" + runBlocking { server.engine.resolvedConnectors().first().port }

  override fun close() = server.stop(100, 1_000)
}
