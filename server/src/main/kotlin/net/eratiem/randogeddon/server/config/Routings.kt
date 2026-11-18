package net.eratiem.randogeddon.server.config

import net.eratiem.randogeddon.server.admin.SessionLimit
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.preCompressed
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.server.account.AccountService
import net.eratiem.randogeddon.server.account.BridgeKeys
import net.eratiem.randogeddon.server.account.LoginAttempts
import net.eratiem.randogeddon.server.account.LoginService
import net.eratiem.randogeddon.server.account.PendingLogins
import net.eratiem.randogeddon.server.account.SingleSignOn
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.account.TwoFactor
import net.eratiem.randogeddon.server.admin.Cheats
import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.admin.SessionLifecycle
import net.eratiem.randogeddon.server.admin.SessionPeopleService
import net.eratiem.randogeddon.server.admin.ShareLinks
import net.eratiem.randogeddon.server.admin.SlotDownloads
import net.eratiem.randogeddon.server.admin.Support
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.session.SpoilerSearches
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.shared.SessionRole
import org.koin.ktor.ext.inject
import java.io.File

private val HASHED = Regex("[0-9a-f]{16,}\\.wasm")

fun Application.routings(trackerConfig: TrackerConfig) {
  val logins by inject<LoginService>()
  val slotPasswords by inject<SlotPasswords>()
  val gameServer by inject<GameServerClient>()
  val sessionAdmin by inject<SessionAdmin>()
  val sessions by inject<LiveSessions>()
  val list by inject<SessionList>()
  val archives by inject<Archives>()
  val spoilers by inject<Spoilers>()
  val spoilerSearches by inject<SpoilerSearches>()
  val lifecycle by inject<SessionLifecycle>()
  val cheats by inject<Cheats>()
  val accounts by inject<AccountService>()
  val twoFactor by inject<TwoFactor>()
  val pending by inject<PendingLogins>()
  val sso by inject<SingleSignOn>()
  val people by inject<SessionPeopleService>()
  val settings by inject<AppSettingsStore>()
  val support by inject<Support>()
  val shareLinks by inject<ShareLinks>()
  val downloads by inject<SlotDownloads>()
  val bridgeKeys by inject<BridgeKeys>()
  val attempts by inject<LoginAttempts>()
  val sessionLimit by inject<SessionLimit>()

  routing {
    loginRoutes(logins, twoFactor, pending, sso, attempts, trackerConfig.publicUrl, trackerConfig.cookieSecure)
    signupRoutes(accounts, trackerConfig.cookieSecure)
    // A session by its share link, without a login
    publicWatchRoutes(shareLinks, archives)
    // Randogeddon Bridge: its downloads, and with a key what the app needs
    bridgeRoutes(trackerConfig, bridgeKeys, logins, sessions, list, slotPasswords)

    // Everything about the sessions needs a login (login and logout above are outside this branch)
    route("/api") {
      install(RequireLogin) { this.logins = logins }
      meRoute(list, sessionLimit)
      accountRoutes(accounts, twoFactor, bridgeKeys, trackerConfig.cookieSecure)
      sessionRoutes(trackerConfig, logins, sessions, list, archives, slotPasswords, spoilers, downloads, spoilerSearches)
      newSessionRoutes(sessionAdmin, people)
      peopleRoutes(people, logins)
      supportRoutes(support, logins)
      watcherRoutes(people, shareLinks, logins)
      // A session's management: its Session-Managers, and Administrators
      route("/sessions/{id}/manage") {
        install(RequireSessionRole) {
          this.logins = logins
          roles = setOf(SessionRole.MANAGER)
        }
        manageRoutes(gameServer, sessionAdmin, lifecycle, cheats, sessions, list, logins)
      }
      adminRoutes(gameServer, sessionAdmin, settings, logins)
      adminUserRoutes(accounts, logins)
    }

    // Nothing about the session: open for the container's healthcheck
    get("/healthz") {
      call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    webApp(trackerConfig.webDir.toFile())
  }
}

/**
 * The web app from [dir]. The .wasm files are named after their content hash and never change; everything else is
 * revalidated (ETag/Last-Modified), so a new build is picked up on the next load. Files compressed when the image was
 * built (`.br`, `.gz` beside them, kotlin.Dockerfile) go out as they are, for a browser that takes them; the others
 * (a local build) are compressed per request by [Compression][io.ktor.server.plugins.compression.Compression].
 */
fun Route.webApp(dir: File) {
  staticFiles("/", dir, index = "index.html") {
    preCompressed(CompressedFileType.BROTLI, CompressedFileType.GZIP)
    cacheControl { file ->
      if (HASHED.matches(file.name)) listOf(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public))
      else listOf(CacheControl.NoCache(null))
    }
    contentType { file ->
      when (file.extension) {
        "wasm" -> ContentType("application", "wasm")
        "webmanifest" -> ContentType("application", "manifest+json")
        else -> null
      }
    }
  }
}
