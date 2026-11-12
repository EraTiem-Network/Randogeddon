package net.eratiem.randogeddon.server.config

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.http.ContentType
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.sse.SSE
import net.eratiem.randogeddon.database.LoginSessionStore
import net.eratiem.randogeddon.database.RandogeddonDatabase
import net.eratiem.randogeddon.database.CheatLogStore
import net.eratiem.randogeddon.database.SessionFinishStore
import net.eratiem.randogeddon.database.SessionSetupStore
import net.eratiem.randogeddon.database.SessionTimeStore
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.RandogeddonTracker
import net.eratiem.randogeddon.server.account.LoginService
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.ap.TrackerService
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.admin.SessionLifecycle
import net.eratiem.randogeddon.server.session.CurrentSession
import net.eratiem.randogeddon.shared.ApiJson
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.koin.plugin.module.dsl.withConfiguration
import kotlin.time.Duration.Companion.minutes

fun Application.installs(trackerConfig: TrackerConfig) {
  install(Koin) {
    slf4jLogger()
    modules(module {
      single { trackerConfig }
      // Migrates on start; the gateway only reads
      single { RandogeddonDatabase.connect(trackerConfig.database, migrate = true) }
      single { UserStore(get()) }
      single { LoginSessionStore(get()) }
      single { SlotPasswordStore(get()) }
      single { SessionTimeStore(get()) }
      single { SessionSetupStore(get()) }
      single { SessionFinishStore(get()) }
      single { CheatLogStore(get()) }
    })
    withConfiguration<RandogeddonTracker>()
  }

  install(ContentNegotiation) { json(ApiJson) }
  // Behind Traefik: the client's address (for the login rate limit) comes from X-Forwarded-For
  install(XForwardedHeaders) {
    // The last address is the one Traefik saw; earlier ones in the header come from the client and can be made up
    useLastProxy()
  }
  install(RateLimit) {
    register(LOGIN_RATE_LIMIT) {
      rateLimiter(limit = 5, refillPeriod = 1.minutes)
      requestKey { it.request.origin.remoteHost }
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

  // Follows the room for as long as the application runs
  val tracker by inject<TrackerService>()
  tracker.start(this)
  val database by inject<RandogeddonDatabase>()
  monitor.subscribe(ApplicationStopping) {
    tracker.close()
    database.close()
  }
  val slotPasswords by inject<SlotPasswords>()
  slotPasswords.watch(this)
  val logins by inject<LoginService>()
  logins.cleanUp(this)
  val currentSession by inject<CurrentSession>()
  currentSession.watch(this)
  // Finishes the session 2 minutes after every slot is done
  val lifecycle by inject<SessionLifecycle>()
  lifecycle.watch(this)
  // Deletes past sessions whose time has come (SESSION_RETENTION_DAYS)
  val sessionAdmin by inject<SessionAdmin>()
  sessionAdmin.cleanUp(this)
}

val LOGIN_RATE_LIMIT = RateLimitName("login")
