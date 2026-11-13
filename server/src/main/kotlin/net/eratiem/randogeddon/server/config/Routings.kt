package net.eratiem.randogeddon.server.config

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.eratiem.randogeddon.server.account.AccountService
import net.eratiem.randogeddon.server.account.LoginService
import net.eratiem.randogeddon.server.account.PendingLogins
import net.eratiem.randogeddon.server.account.TwoFactor
import net.eratiem.randogeddon.server.account.SlotPasswords
import net.eratiem.randogeddon.server.admin.Cheats
import net.eratiem.randogeddon.server.admin.GameServerClient
import net.eratiem.randogeddon.server.admin.SessionAdmin
import net.eratiem.randogeddon.server.admin.SessionLifecycle
import net.eratiem.randogeddon.server.admin.SessionPeopleService
import net.eratiem.randogeddon.server.admin.ShareLinks
import net.eratiem.randogeddon.server.admin.SlotDownloads
import net.eratiem.randogeddon.server.admin.Support
import net.eratiem.randogeddon.database.AppSettingsStore
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.server.session.Archives
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.SessionList
import net.eratiem.randogeddon.server.session.Spoilers
import org.koin.ktor.ext.inject

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
  val lifecycle by inject<SessionLifecycle>()
  val cheats by inject<Cheats>()
  val accounts by inject<AccountService>()
  val twoFactor by inject<TwoFactor>()
  val pending by inject<PendingLogins>()
  val people by inject<SessionPeopleService>()
  val settings by inject<AppSettingsStore>()
  val support by inject<Support>()
  val shareLinks by inject<ShareLinks>()
  val downloads by inject<SlotDownloads>()

  routing {
    loginRoutes(logins, twoFactor, pending, trackerConfig.cookieSecure)
    signupRoutes(accounts, trackerConfig.cookieSecure)
    // A session by its share link, without a login
    publicWatchRoutes(shareLinks, archives)

    // Everything about the sessions needs a login (login and logout above are outside this branch)
    route("/api") {
      install(RequireLogin) { this.logins = logins }
      meRoute(list)
      accountRoutes(accounts, twoFactor, trackerConfig.cookieSecure)
      sessionRoutes(trackerConfig, logins, sessions, list, archives, slotPasswords, spoilers, downloads)
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
        manageRoutes(gameServer, sessionAdmin, lifecycle, cheats, sessions, list)
      }
      adminRoutes(gameServer, sessionAdmin, settings)
      adminUserRoutes(accounts)
    }

    // Nothing about the session: open for the container's healthcheck
    get("/healthz") {
      call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // The web app. The .wasm files are named after their content hash and never change; everything else is
    // revalidated (ETag/Last-Modified), so a new build is picked up on the next load
    staticFiles("/", trackerConfig.webDir.toFile(), index = "index.html") {
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
}
