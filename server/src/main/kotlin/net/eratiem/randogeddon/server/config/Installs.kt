package net.eratiem.randogeddon.server.config

import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.sse.SSE
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.database.CheatLogStore
import net.eratiem.randogeddon.database.EmailChangeStore
import net.eratiem.randogeddon.database.GatewayRole
import net.eratiem.randogeddon.database.InvitationStore
import net.eratiem.randogeddon.database.LoginLinkStore
import net.eratiem.randogeddon.database.LoginSessionStore
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.SessionEventStore
import net.eratiem.randogeddon.database.SessionSnapshotStore
import net.eratiem.randogeddon.database.SessionFinishStore
import net.eratiem.randogeddon.database.SessionRoleStore
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.SessionStore
import net.eratiem.randogeddon.database.SessionTimeStore
import net.eratiem.randogeddon.database.ShareLinkStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.TwoFactorStore
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.RandogeddonTracker
import net.eratiem.randogeddon.server.account.AccountCleanup
import net.eratiem.randogeddon.server.account.LoginService
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.admin.SessionLifecycle
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.shared.ApiJson
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.koin.plugin.module.dsl.withConfiguration

fun Application.installs(trackerConfig: TrackerConfig) {
  install(Koin) {
    slf4jLogger()
    modules(module {
      single { trackerConfig }
      // Migrates on start; the gateway only reads
      single {
        RandogeddonDatabase.connect(trackerConfig.database, migrate = true).also { database ->
          // The gateway's own role, with exactly its rights (after the migrations: the tables are there)
          if (trackerConfig.gatewayDbPassword.isNotBlank()) {
            runBlocking { GatewayRole.ensure(database, trackerConfig.gatewayDbUser, trackerConfig.gatewayDbPassword) }
            log.info("The gateway's database role {} has its rights", trackerConfig.gatewayDbUser)
          } else {
            log.warn("No GATEWAY_DB_PASSWORD: the gateway uses the tracker's database login")
          }
        }
      }
      single { UserStore(get()) }
      single { LoginSessionStore(get()) }
      single { SlotPasswordStore(get()) }
      single { SessionTimeStore(get()) }
      single { SessionSetupStore(get()) }
      single { SessionFinishStore(get()) }
      single { SessionEventStore(get()) }
      single { SessionSnapshotStore(get()) }
      single { CheatLogStore(get()) }
      single { SessionStore(get()) }
      single { InvitationStore(get()) }
      single { EmailChangeStore(get()) }
      single { TwoFactorStore(get()) }
      single { SessionRoleStore(get()) }
      single { AppSettingsStore(get()) }
      single { ShareLinkStore(get()) }
      single { LoginLinkStore(get()) }
    })
    withConfiguration<RandogeddonTracker>()
  }

  install(ContentNegotiation) { json(ApiJson) }
  install(SecurityHeaders) { secure = trackerConfig.cookieSecure }
  install(RequestLimits)
  // Behind Traefik: how the client reached it comes from the X-Forwarded headers
  install(XForwardedHeaders) {
    // The last address is the one Traefik saw; earlier ones in the header come from the client and can be made up
    useLastProxy()
  }
  // The client's address (for the rate limits): from X-Forwarded-For only if Traefik sent it (TRUSTED_PROXIES)
  install(ClientAddresses) { trusted = trackerConfig.trustedProxies }
  install(RateLimit) {
    register(LOGIN_RATE_LIMIT) {
      rateLimiter(limit = 5, refillPeriod = 1.minutes)
      requestKey { it.clientKey }
    }
    register(WATCH_RATE_LIMIT) {
      rateLimiter(limit = 60, refillPeriod = 1.minutes)
      requestKey { it.clientKey }
    }
    register(SSO_RATE_LIMIT) {
      rateLimiter(limit = 30, refillPeriod = 1.minutes)
      requestKey { it.clientKey }
    }
  }
  install(SSE)
  // ETag/Last-Modified for the static files, so the browser revalidates the web app instead of downloading it again
  install(ConditionalHeaders)
  // The web app is ~11 MB uncompressed (mostly Compose's Skia engine); gzip brings it to roughly a third. The live
  // stream must not be compressed, or the events are held back in the compressor's buffer.
  install(Compression) {
    gzip {
      excludeContentType(ContentType.Text.EventStream)
    }
  }

  // Follows every session (each running one with a tracker connection of its own) for as long as the application runs
  val sessions by inject<LiveSessions>()
  sessions.watch(this)
  val database by inject<RandogeddonDatabase>()
  monitor.subscribe(ApplicationStopping) {
    sessions.close()
    database.close()
  }
  val slotPasswords by inject<SlotPasswords>()
  slotPasswords.watch(this)
  val logins by inject<LoginService>()
  logins.cleanUp(this)
  // Warns and deletes the accounts whose auto-deletion is due
  val accountCleanup by inject<AccountCleanup>()
  accountCleanup.watch(this)
  // Finishes a session 2 minutes after every slot is done
  val lifecycle by inject<SessionLifecycle>()
  lifecycle.watch(this)
  // Deletes past sessions whose time has come (SESSION_RETENTION_DAYS)
  val sessionAdmin by inject<SessionAdmin>()
  sessionAdmin.cleanUp(this)
}

val LOGIN_RATE_LIMIT = RateLimitName("login")

/** Share links, without a login: enough for a dashboard (the stream, a slot now and then), not for guessing tokens. */
val WATCH_RATE_LIMIT = RateLimitName("watch")

/** The login form's options and the logins through the provider: every page load asks, a login takes two. */
val SSO_RATE_LIMIT = RateLimitName("sso")
