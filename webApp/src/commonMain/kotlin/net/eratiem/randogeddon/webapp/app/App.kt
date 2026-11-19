package net.eratiem.randogeddon.webapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.Theme
import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.ThemeRequest
import net.eratiem.randogeddon.shared.i18n.ago
import net.eratiem.randogeddon.shared.i18n.duration
import net.eratiem.randogeddon.shared.i18n.texts
import net.eratiem.randogeddon.webapp.account.ConfirmEmailView
import net.eratiem.randogeddon.webapp.account.SettingsView
import net.eratiem.randogeddon.webapp.api.TrackerRepository
import net.eratiem.randogeddon.webapp.api.call
import net.eratiem.randogeddon.webapp.api.running
import net.eratiem.randogeddon.webapp.dashboard.Banner
import net.eratiem.randogeddon.webapp.dashboard.BridgeFold
import net.eratiem.randogeddon.webapp.dashboard.CONNECTION_NARROW
import net.eratiem.randogeddon.webapp.dashboard.CONNECTION_TWO_COLUMNS
import net.eratiem.randogeddon.webapp.dashboard.CONNECTION_WIDE
import net.eratiem.randogeddon.webapp.dashboard.ConnectionSection
import net.eratiem.randogeddon.webapp.dashboard.FEED_WIDTH
import net.eratiem.randogeddon.webapp.dashboard.Feed
import net.eratiem.randogeddon.webapp.dashboard.FeedPosition
import net.eratiem.randogeddon.webapp.dashboard.FeedSettings
import net.eratiem.randogeddon.webapp.dashboard.FinishBanner
import net.eratiem.randogeddon.webapp.dashboard.GAP
import net.eratiem.randogeddon.webapp.dashboard.Headline
import net.eratiem.randogeddon.webapp.dashboard.Hero
import net.eratiem.randogeddon.webapp.dashboard.Hints
import net.eratiem.randogeddon.webapp.dashboard.MIN_SIDE_HEIGHT
import net.eratiem.randogeddon.webapp.dashboard.People
import net.eratiem.randogeddon.webapp.dashboard.PersonDialog
import net.eratiem.randogeddon.webapp.dashboard.PlayerDialog
import net.eratiem.randogeddon.webapp.dashboard.STATS_BESIDE
import net.eratiem.randogeddon.webapp.dashboard.SessionPicker
import net.eratiem.randogeddon.webapp.dashboard.SpoilerView
import net.eratiem.randogeddon.webapp.dashboard.Stats
import net.eratiem.randogeddon.webapp.dashboard.WIDE
import net.eratiem.randogeddon.webapp.dashboard.WatchApp
import net.eratiem.randogeddon.webapp.dashboard.showHints
import net.eratiem.randogeddon.webapp.manage.AdminView
import net.eratiem.randogeddon.webapp.manage.WatchersSection
import net.eratiem.randogeddon.webapp.api.post
import net.eratiem.randogeddon.webapp.ui.PageFrame
import net.eratiem.randogeddon.webapp.ui.rememberNow
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.NARROW
import net.eratiem.randogeddon.webapp.ui.RandogeddonTheme
import net.eratiem.randogeddon.webapp.ui.SmallButton
import net.eratiem.randogeddon.webapp.ui.Themes
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.scrolling

/**
 * [main] and, to its left or right, [side] in [sideWidth], measured to exactly the height of [main] (at least
 * [MIN_SIDE_HEIGHT]). A Row can't do that: the feed scrolls, and scrolling content has no intrinsic height.
 */
@Composable
private fun SideBySide(sideFirst: Boolean, sideWidth: Dp, main: @Composable () -> Unit, side: @Composable () -> Unit) {
  Layout(contents = listOf(main, side)) { (mainMeasurables, sideMeasurables), constraints ->
    val sidePx = sideWidth.roundToPx()
    val gapPx = GAP.roundToPx()
    val mainWidth = (constraints.maxWidth - sidePx - gapPx).coerceAtLeast(0)
    val mainPlaceable = mainMeasurables.first().measure(Constraints(minWidth = mainWidth, maxWidth = mainWidth))
    val height = maxOf(mainPlaceable.height, MIN_SIDE_HEIGHT.roundToPx())
    val sidePlaceable = sideMeasurables.first().measure(Constraints.fixed(sidePx, height))
    layout(constraints.maxWidth, height) {
      if (sideFirst) {
        sidePlaceable.place(0, 0)
        mainPlaceable.place(sidePx + gapPx, 0)
      } else {
        mainPlaceable.place(0, 0)
        sidePlaceable.place(mainWidth + gapPx, 0)
      }
    }
  }
}

/**
 * The page: a share link's dashboard without a login ([WatchApp]), or the tracker for the logged-in user; in the
 * language that applies ([Languages]).
 */
@Composable
fun App() {
  var watching by remember { mutableStateOf((Route.parse(Platform.hash()) as? Route.Watch)?.token) }
  DisposableEffect(Unit) {
    val stop = Platform.onHashChange { watching = (Route.parse(it) as? Route.Watch)?.token }
    onDispose { stop() }
  }
  LaunchedEffect(Unit) {
    Platform.setPageLanguage(Languages.current.code)
    Themes.tellPage()
  }
  val token = watching
  CompositionLocalProvider(LocalTexts provides texts(Languages.current)) {
    if (token != null) WatchApp(token) else TrackerApp()
  }
}

@Composable
private fun TrackerApp() {
  val scope = rememberCoroutineScope()
  val repository = remember { TrackerRepository(scope) }
  DisposableEffect(repository) {
    repository.start()
    onDispose { repository.stop() }
  }
  val summary by repository.summary.collectAsState()
  val streamOpen by repository.streamOpen.collectAsState()
  // null until logged in; the login form of index.html covers the page meanwhile
  val me by repository.me.collectAsState()
  val unreachable by repository.unreachable.collectAsState()
  val loadedSessions by repository.sessions.collectAsState()
  val sessions = loadedSessions.orEmpty()
  val selected by repository.selected.collectAsState()
  val connection by repository.connection.collectAsState()
  val files by repository.files.collectAsState()


  // Relative times ("5m ago") and the session duration move on without new data
  val now = rememberNow(summary?.version)

  var detailSlot by remember { mutableStateOf<Int?>(null) }
  var detailPerson by remember { mutableStateOf<String?>(null) }
  // Where the page is, from the URL's hash: back, forward and links move it too
  var route by remember { mutableStateOf(Route.parse(Platform.hash())) }
  DisposableEffect(Unit) {
    val stop = Platform.onHashChange { route = Route.parse(it) }
    onDispose { stop() }
  }
  fun go(to: Route, replace: Boolean = false) {
    route = to
    Platform.setHash(to.hash, replace)
  }
  LaunchedEffect(me) {
    if (me == null) {
      detailSlot = null
      detailPerson = null
    }
  }
  // Logged in: the account's language (or, without a choice, the browser's)
  LaunchedEffect(me?.name, me?.language) { me?.let { Languages.apply(it.language) } }
  // Logged in: the account's theme; an account without one yet takes what this browser had (the old light/dark too)
  LaunchedEffect(me?.name, me?.theme, me?.themeMode) {
    val user = me ?: return@LaunchedEffect
    if (user.theme == null && user.themeMode == null) saveTheme(Themes.theme, Themes.mode)
    else Themes.apply(user.theme, user.themeMode)
  }
  // The account's font choice (nothing chosen: the theme's)
  LaunchedEffect(me?.name, me?.font, me?.fontScope) {
    val user = me ?: return@LaunchedEffect
    Themes.applyFont(user.font, user.fontScope)
  }
  // Another session: its own slots and people
  LaunchedEffect(selected) {
    detailSlot = null
    detailPerson = null
  }
  val adminOpen = route is Route.Admin || (route as? Route.Spoiler)?.fromAdmin == true
  // The admin panel may have created, finished or deleted one
  LaunchedEffect(adminOpen) { if (me != null) repository.refreshSessions() }
  // The route picks the dashboard's session; the sessions' page opens the user's only running one by itself. The
  // admin panel and a spoiler log leave the dashboard's session as it is (back returns to it)
  LaunchedEffect(route, loadedSessions, me) {
    val list = loadedSessions ?: return@LaunchedEffect
    if (me == null) return@LaunchedEffect
    when (val r = route) {
      Route.Home -> {
        val mine = list.filter { it.mySlots.isNotEmpty() && it.status.running }
        if (mine.size == 1) go(Route.Dashboard(mine.single().id), replace = true) else repository.select(null)
      }
      is Route.Dashboard -> repository.select(r.id.takeIf { id -> list.any { it.id == id } })
      else -> {}
    }
  }

  val feedVisible = FeedSettings.visible
  val feedPosition = FeedSettings.position
  fun setFeedVisible(visible: Boolean) = FeedSettings.show(visible)
  fun setFeedPosition(position: FeedPosition) = FeedSettings.place(position)

  RandogeddonTheme(Themes.palette) {
    val p = LocalPalette.current
    val t = LocalTexts.current
    PageFrame(GAP) { pageWidth, contentWidth ->
      val wide = pageWidth >= WIDE
      val narrow = pageWidth < NARROW
      val s = summary
      val user = me ?: run {
        // Before the login is known: either the login form of index.html covers the page, or the server is away
        if (unreachable) Txt(
          t.frame.cantReachServer, color = LocalPalette.current.muted,
          modifier = Modifier.padding(top = 120.dp).align(Alignment.CenterHorizontally),
        )
        return@PageFrame
      }
      val r = route
      val dashboard = (r as? Route.Dashboard)?.id
      // The hero's switch: the user's sessions, and the one shown (an admin may watch one they don't play in);
      // the admin panel and Your account keep the dashboard's session in it, a spoiler log its own
      val keepsSession = adminOpen || r == Route.Settings
      val shown = dashboard ?: (r as? Route.Spoiler)?.id ?: selected.takeIf { keepsSession }
      val switchable = sessions.filter { it.myRole != null || it.id == shown }
      Headline(onHome = { go(Route.Home) })
      // Without a session of their own: where a new one starts (for who may create one)
      val newSessionHint = r == Route.Home && loadedSessions != null && switchable.isEmpty() && user.createsSessions
      Hero(
        s.takeIf { dashboard != null || r is Route.Spoiler || keepsSession }, streamOpen,
        sessions = switchable, shown = shown,
        onSession = { go(Route.Dashboard(it)) },
        onAllSessions = { go(Route.Home) },
        // Light, dark, automatic: for the account (and this browser)
        onToggleTheme = {
          val next = Themes.mode.next
          Themes.apply(Themes.theme, next)
          scope.launch { saveTheme(Themes.theme, next) }
        },
        feedVisible = feedVisible,
        // Only where there is a feed: a session's dashboard, once the session is there
        onToggleFeed = { setFeedVisible(!feedVisible) }.takeIf { dashboard != null && s != null },
        onLogout = { scope.launch { repository.logout() } },
        onSettings = { go(if (r == Route.Settings) back(selected) else Route.Settings) },
        settingsOpen = r == Route.Settings,
        onAdmin = {
          // The session shown, if the panel has it: the user manages or supports it (an Administrator: any)
          val theirs = selected?.takeIf { id ->
            user.administrator || id in user.manages || sessions.any { it.id == id && it.myRole == SessionRole.SUPPORT }
          }
          go(if (adminOpen) back(selected) else Route.Admin(theirs))
        },
        adminOpen = adminOpen,
        newSessionHint = newSessionHint,
        stacked = narrow,
      )
      when (r) {
        is Route.Spoiler -> {
          SpoilerView(r.id, onBack = { go(if (r.fromAdmin) Route.Admin(r.id) else Route.Dashboard(r.id)) })
          return@PageFrame
        }
        is Route.Admin -> {
          AdminView(
            now, user.administrator, r.page, onPage = { go(Route.Admin(it)) }, onBack = { go(back(selected)) },
            onSpoiler = { go(Route.Spoiler(it, fromAdmin = true)) },
            onDashboard = { go(Route.Dashboard(it)) },
          )
          return@PageFrame
        }
        Route.Settings -> {
          SettingsView(onBack = { go(back(selected)) }, onRenamed = { scope.launch { repository.reloadMe() } })
          return@PageFrame
        }
        is Route.ConfirmEmail -> {
          ConfirmEmailView(r.token, onSettings = { go(Route.Settings) })
          return@PageFrame
        }
        // [App] shows it
        is Route.Watch -> return@PageFrame
        is Route.Invite -> {
          // Signing up happens logged out (index.html's form); this browser is someone's already
          Txt(t.frame.loggedInAs(user.name), color = p.muted, modifier = Modifier.padding(vertical = 8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(t.common.logOut, { scope.launch { repository.logout() } })
            SmallButton(t.common.allYourSessions, { go(Route.Home) })
          }
          return@PageFrame
        }
        Route.Home -> {
          if (loadedSessions != null) {
            SessionPicker(sessions.filter { it.myRole != null }, onOpen = { go(Route.Dashboard(it)) })
          }
          return@PageFrame
        }
        is Route.Dashboard -> if (loadedSessions != null && sessions.none { it.id == r.id }) {
          Txt(t.frame.noSuchSession(r.id), color = p.muted, modifier = Modifier.padding(vertical = 8.dp))
          SmallButton(t.common.allYourSessions, { go(Route.Home) })
          return@PageFrame
        }
      }
      if (s == null || selected == null) return@PageFrame
      val entry = sessions.firstOrNull { it.id == selected }
      val mine = entry?.mySlots?.isNotEmpty() == true
      val role = entry?.myRole
      val error = s.error
      if (s.serverAsleep && s.finishedAt == null) Banner(t.dashboard.serverAsleep)
      else if (!s.connected && error != null && s.finishedAt == null) Banner(t.problems.trackerError(s.errorRefused, error))
      // A Session-Manager is timed out, suspended or gone: who to turn to instead
      if (s.managerUnavailable) Banner(t.dashboard.managerUnavailable)
      FinishBanner(
        s, user.administrator || role == SessionRole.MANAGER,
        onFinishNow = { scope.launch { post("api/sessions/$selected/manage/finish") } },
        onCancel = { scope.launch { post("api/sessions/$selected/manage/finish/cancel") } },
        onSpoiler = { selected?.let { go(Route.Spoiler(it, fromAdmin = false)) } },
      )
      val connectionWidth = if (mine && BridgeFold.open) CONNECTION_WIDE else CONNECTION_NARROW
      if (wide && contentWidth - connectionWidth - GAP >= STATS_BESIDE) {
        // The tiles and the connection share a row and its height
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GAP)) {
          Stats(s, now, contentWidth - connectionWidth - GAP, Modifier.weight(1f).fillMaxHeight(), fillHeight = true)
          ConnectionSection(
            user.name, connection, selected.orEmpty(), files, mine, role, s,
            Modifier.width(connectionWidth).fillMaxHeight(), fillHeight = true,
            twoColumns = connectionWidth >= CONNECTION_TWO_COLUMNS,
          )
        }
      } else {
        ConnectionSection(
          user.name, connection, selected.orEmpty(), files, mine, role, s, Modifier.fillMaxWidth(),
          twoColumns = contentWidth >= CONNECTION_TWO_COLUMNS,
        )
        Stats(s, now, contentWidth)
      }
      val feed: @Composable (Modifier, Boolean) -> Unit = { modifier, fillHeight ->
        // Its place is only offered where it can be beside the players
        Feed(
          s, modifier, feedPosition, fillHeight, onPosition = if (wide) ::setFeedPosition else null,
          onHide = { setFeedVisible(false) },
        )
      }
      val side = wide && feedVisible && feedPosition != FeedPosition.BELOW
      if (side) {
        // The feed beside players and hints, exactly as tall as they are together; its list scrolls inside
        SideBySide(
          sideFirst = feedPosition == FeedPosition.LEFT,
          sideWidth = FEED_WIDTH,
          main = {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
              People(s, now, contentWidth - FEED_WIDTH - GAP, onOpen = { detailSlot = it }, onOpenPerson = { detailPerson = it })
              if (showHints(s)) Hints(s, compact = false)
            }
          },
          side = { feed(Modifier.fillMaxSize(), true) },
        )
      } else {
        // Narrow screens: left/right means right after the players, "below" at the very end
        People(s, now, contentWidth, onOpen = { detailSlot = it }, onOpenPerson = { detailPerson = it })
        if (feedVisible && feedPosition != FeedPosition.BELOW) feed(Modifier.fillMaxWidth(), false)
        if (showHints(s)) Hints(s, compact = contentWidth < 700.dp)
        if (feedVisible && feedPosition == FeedPosition.BELOW) feed(Modifier.fillMaxWidth(), false)
      }
      // Players add watchers (support helps in the panel)
      val id = selected
      if (role == SessionRole.PLAYER && id != null) key(id) { WatchersSection(id) }
    }
    detailPerson?.let { name ->
      summary?.let { s ->
        PersonDialog(name, s, now, repository, onClose = { detailPerson = null })
      }
    }
    detailSlot?.let { slot ->
      PlayerDialog(slot, summary?.version ?: 0, repository, onClose = { detailSlot = null })
    }
  }
}

/** The account's theme and mode (the app shows them already); a refusal leaves them for this browser only. */
internal suspend fun saveTheme(theme: Theme, mode: ThemeMode) {
  call("PUT", "api/account/theme", ApiJson.encodeToString(ThemeRequest.serializer(), ThemeRequest(theme, mode))) { }
}

/** Where back from the admin panel goes: the dashboard's session, or the sessions' page without one. */
private fun back(selected: String?): Route = selected?.let { Route.Dashboard(it) } ?: Route.Home
