package net.eratiem.randogeddon.webapp

import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ThemeRequest
import net.eratiem.randogeddon.shared.ThemeMode
import net.eratiem.randogeddon.shared.Theme
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import net.eratiem.randogeddon.shared.LockInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.eratiem.randogeddon.shared.EventType
import net.eratiem.randogeddon.shared.FeedEvent
import net.eratiem.randogeddon.shared.HintView
import net.eratiem.randogeddon.shared.ItemFlags
import net.eratiem.randogeddon.shared.PersonSummary
import net.eratiem.randogeddon.shared.PlayerStatus
import net.eratiem.randogeddon.shared.PlayerSummary
import net.eratiem.randogeddon.shared.SessionEntry
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SessionStatus
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.i18n.DashboardTexts
import net.eratiem.randogeddon.shared.i18n.FeedTexts
import net.eratiem.randogeddon.shared.i18n.Texts
import net.eratiem.randogeddon.shared.i18n.decimal
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.shared.i18n.ago
import net.eratiem.randogeddon.shared.i18n.duration
import net.eratiem.randogeddon.shared.i18n.texts

private const val FEED_VISIBLE_KEY = "ap-feed-visible"
private const val FEED_POSITION_KEY = "ap-feed-position"

/** Per viewer, remembered in the browser: the live feed is visible and on the right unless changed. */
internal object FeedSettings {
  var visible: Boolean by mutableStateOf(Platform.loadSetting(FEED_VISIBLE_KEY) != "false")
    private set
  var position: FeedPosition by mutableStateOf(
    FeedPosition.entries.firstOrNull { it.name == Platform.loadSetting(FEED_POSITION_KEY) } ?: FeedPosition.RIGHT,
  )
    private set

  fun show(visible: Boolean) {
    this.visible = visible
    Platform.saveSetting(FEED_VISIBLE_KEY, visible.toString())
  }

  fun place(position: FeedPosition) {
    this.position = position
    Platform.saveSetting(FEED_POSITION_KEY, position.name)
  }

  /** Back to the defaults (without a login). */
  fun reset() {
    visible = true
    position = FeedPosition.RIGHT
    Platform.saveSetting(FEED_VISIBLE_KEY, null)
    Platform.saveSetting(FEED_POSITION_KEY, null)
  }
}
private val FEED_WIDTH = 440.dp

enum class FeedPosition { RIGHT, LEFT, BELOW }

private fun FeedPosition.label(t: Texts): String = when (this) {
  FeedPosition.RIGHT -> t.feed.placeRight
  FeedPosition.LEFT -> t.feed.placeLeft
  FeedPosition.BELOW -> t.feed.placeBelow
}
private val WIDE = 1100.dp
private val GAP = 16.dp
/** *Your connection* beside the tiles: with the bridge open beside the rest, or one column (folded, or not playing). */
private val CONNECTION_WIDE = 720.dp
private val CONNECTION_NARROW = 420.dp
/** The tiles' room beside *Your connection*: three of them (at least 160 dp each) in a row; with less, it goes above. */
private val STATS_BESIDE = 160.dp * 3 + 12.dp * 2
/** The side feed is never shorter than this, even next to a single small person card. */
private val MIN_SIDE_HEIGHT = 420.dp

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
  var now by remember { mutableLongStateOf(Platform.now()) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(30_000)
      now = Platform.now()
    }
  }
  LaunchedEffect(summary?.version) { now = Platform.now() }

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
    BoxWithConstraints(
      // The page colour first: the tint on top of it is translucent
      Modifier.fillMaxSize().background(p.background).background(
        Brush.verticalGradient(listOf(p.brandEnd.copy(alpha = if (p.dark) 0.06f else 0.07f), p.background, p.background))
      )
    ) {
      val wide = maxWidth >= WIDE
      val narrow = maxWidth < NARROW
      val contentWidth = minOf(maxWidth, 1432.dp) - 32.dp
      Column(
        Modifier.fillMaxSize().scrolling().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Text can be selected (and copied) anywhere on the page
        SelectionContainer {
          Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GAP)) {
            val s = summary
            val user = me ?: run {
              // Before the login is known: either the login form of index.html covers the page, or the server is away
              if (unreachable) Txt(
                t.frame.cantReachServer, color = LocalPalette.current.muted,
                modifier = Modifier.padding(top = 120.dp).align(Alignment.CenterHorizontally),
              )
              return@Column
            }
            val r = route
            val dashboard = (r as? Route.Dashboard)?.id
            // The hero's switch: the user's sessions, and the one shown (an admin may watch one they don't play in);
            // the admin panel and Your account keep the dashboard's session in it, a spoiler log its own
            val keepsSession = adminOpen || r == Route.Settings
            val shown = dashboard ?: (r as? Route.Spoiler)?.id ?: selected.takeIf { keepsSession }
            val switchable = sessions.filter { it.myRole != null || it.id == shown }
            Headline()
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
              onAdmin = { go(if (adminOpen) back(selected) else Route.Admin(selected?.takeIf { it in user.manages || user.administrator })) },
              adminOpen = adminOpen,
              stacked = narrow,
            )
            when (r) {
              is Route.Spoiler -> {
                SpoilerView(r.id, onBack = { go(if (r.fromAdmin) Route.Admin(r.id) else Route.Dashboard(r.id)) })
                return@Column
              }
              is Route.Admin -> {
                AdminView(
                  now, user.administrator, r.page, onPage = { go(Route.Admin(it)) }, onBack = { go(back(selected)) },
                  onSpoiler = { go(Route.Spoiler(it, fromAdmin = true)) },
                  onDashboard = { go(Route.Dashboard(it)) },
                )
                return@Column
              }
              Route.Settings -> {
                SettingsView(onBack = { go(back(selected)) }, onRenamed = { scope.launch { repository.reloadMe() } })
                return@Column
              }
              is Route.ConfirmEmail -> {
                ConfirmEmailView(r.token, onSettings = { go(Route.Settings) })
                return@Column
              }
              // [App] shows it
              is Route.Watch -> return@Column
              is Route.Invite -> {
                // Signing up happens logged out (index.html's form); this browser is someone's already
                Txt(t.frame.loggedInAs(user.name), color = p.muted, modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  SmallButton(t.common.logOut, { scope.launch { repository.logout() } })
                  SmallButton(t.common.allYourSessions, { go(Route.Home) })
                }
                return@Column
              }
              Route.Home -> {
                if (loadedSessions != null) {
                  SessionPicker(sessions.filter { it.myRole != null }, user.administrator, onOpen = { go(Route.Dashboard(it)) })
                }
                return@Column
              }
              is Route.Dashboard -> if (loadedSessions != null && sessions.none { it.id == r.id }) {
                Txt(t.frame.noSuchSession(r.id), color = p.muted, modifier = Modifier.padding(vertical = 8.dp))
                SmallButton(t.common.allYourSessions, { go(Route.Home) })
                return@Column
              }
            }
            if (s == null || selected == null) return@Column
            val entry = sessions.firstOrNull { it.id == selected }
            val mine = entry?.mySlots?.isNotEmpty() == true
            val role = entry?.myRole
            val error = s.error
            if (!s.connected && error != null && s.finishedAt == null) Banner(t.problems.trackerError(s.errorRefused, error))
            // A Session-Manager is timed out, suspended or gone: who to turn to instead
            if (s.managerUnavailable) Banner(t.dashboard.managerUnavailable)
            FinishBanner(
              s, user.administrator || role == SessionRole.MANAGER,
              onFinishNow = { scope.launch { Platform.request("POST", "api/sessions/$selected/manage/finish") } },
              onCancel = { scope.launch { Platform.request("POST", "api/sessions/$selected/manage/finish/cancel") } },
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
            // By the role in it: support helps the players, players add watchers
            val id = selected
            if (role == SessionRole.SUPPORT && id != null) key(id) { SupportSection(id, onSpoiler = { go(Route.Spoiler(id, fromAdmin = false)) }) }
            if (role == SessionRole.PLAYER && id != null) key(id) { WatchersSection(id) }
          }
        }
      }
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

/** "Randogeddon" above the top card, centred, in the brand's gradient. */
@Composable
internal fun Headline() {
  val p = LocalPalette.current
  Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
    val change = LocalFontChange.current
    BasicText(
      // Its letters change with the page's when the font does
      if (change != null) AnnotatedString("RANDOGEDDON").inChange(change, heading = true, 34.sp) else AnnotatedString("RANDOGEDDON"),
      style = TextStyle(
        brush = Brush.linearGradient(listOf(p.brandStart, p.brandEnd)),
        fontSize = 34.sp * LocalFonts.current.headingScale, fontWeight = FontWeight.Black, letterSpacing = 6.sp, textAlign = TextAlign.Center,
        fontFamily = LocalFonts.current.heading,
        shadow = Shadow(p.brandEnd.copy(alpha = if (p.dark) 0.55f else 0.3f), Offset(0f, 4f), blurRadius = 18f),
      ),
    )
  }
}

/**
 * The gradient card on top: the session (its ID, a switch to the user's others), seed, overall progress and the
 * connection state. [stacked] (a phone): the session, its info, the pill and buttons, and the bar each on their own
 * line.
 */
@Composable
private fun Hero(
  summary: Summary?,
  streamOpen: Boolean,
  /** For the switch in the heading: the sessions, the one shown ([shown]; `null`: none), and the sessions' page. */
  sessions: List<SessionEntry>,
  shown: String?,
  onSession: (String) -> Unit,
  onAllSessions: () -> Unit,
  onToggleTheme: () -> Unit,
  feedVisible: Boolean,
  /** `null`: no feed on this page, no button. */
  onToggleFeed: (() -> Unit)?,
  onLogout: () -> Unit,
  /** The user's account; [settingsOpen] while it is shown. */
  onSettings: () -> Unit,
  settingsOpen: Boolean,
  /** The panel: the sessions the user manages (Administrators: every session), a new session. */
  onAdmin: (() -> Unit)?,
  adminOpen: Boolean,
  stacked: Boolean,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  // Light: on the full gradient; dark: on a dark card with the gradient's glow
  val onHero = if (p.dark) p.text else p.onBrand
  val live = streamOpen && summary?.connected == true
  // Light: the full gradient. Dark: a dark card with a gradient glow, so the page stays dark
  val heroBackground = if (p.dark) {
    Brush.linearGradient(listOf(p.brandStart.copy(alpha = 0.30f), p.brandEnd.copy(alpha = 0.34f)))
  } else {
    Brush.linearGradient(listOf(p.brandStart, p.brandEnd))
  }
  Column(
    Modifier.fillMaxWidth().card(p, RoundedCornerShape(20.dp), color = if (p.dark) p.panel else Color.Transparent)
      .background(heroBackground)
      .padding(horizontal = 20.dp, vertical = 18.dp)
  ) {
    val heading: @Composable (Modifier) -> Unit = { modifier ->
      Column(modifier) {
        if (sessions.isNotEmpty()) {
          // The session's ID is the heading and its switch; "All sessions" goes to the sessions' page
          SearchableSelect(
            listOf(Triple<String?, String, String>(null, t.frame.allSessions, "")) +
              sessions.map { Triple<String?, String, String>(it.id, it.id, sessionLine(t, it)) },
            shown, { id -> if (id == null) onAllSessions() else onSession(id) },
            chip = false, heading = 24.sp, headingColor = onHero, placeholder = t.frame.pickASession,
          )
        } else {
          Txt(shown ?: t.frame.noSession, size = 24.sp, weight = FontWeight.Bold, color = onHero)
        }
        val meta = listOfNotNull(
          summary?.seedName?.let { t.frame.seed(it) },
          summary?.serverVersion?.let { t.frame.archipelago(it) },
          summary?.let { t.frame.hintMode(it.hintSettings.pointHints, it.hintSettings.costPercent, it.hintSettings.autoHints) },
        ).ifEmpty { listOf(if (shown == null) t.frame.pickOneOfYourSessions else t.frame.connecting) }
        // A phone: one line each
        (if (stacked) meta else listOf(meta.joinToString("  ·  "))).forEach {
          Txt(it, size = 12.5.sp, color = onHero.copy(alpha = 0.85f))
        }
      }
    }
    val controls: @Composable () -> Unit = {
      // The game server's state, as tall as the buttons next to it: only with a session that has one (not finished)
      if (summary != null && summary.finishedAt == null) Row(
        Modifier.height(34.dp).clip(CircleShape).background(onHero.copy(alpha = 0.2f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Dot(if (live) p.liveDot else p.offlineDot)
        Txt(
          when {
            !streamOpen -> t.frame.reconnecting
            summary.connected -> t.frame.live
            else -> t.frame.serverOffline
          }, size = 12.5.sp, color = onHero, weight = FontWeight.SemiBold, maxLines = 1
        )
      }
      // Shows / hides the live feed; brighter while it is shown
      if (onToggleFeed != null) IconButton(onToggleFeed, Icon.FEED, tint = onHero,
        background = onHero.copy(alpha = if (feedVisible) 0.32f else 0.12f))
      // The mode it is in: light, dark, automatic
      val modeIcon = when (Themes.mode) {
        ThemeMode.LIGHT -> Icon.SUN
        ThemeMode.DARK -> Icon.MOON
        ThemeMode.AUTO -> Icon.THEME
      }
      IconButton(onToggleTheme, modeIcon, tint = onHero, background = onHero.copy(alpha = 0.2f))
      // Brighter while the admin panel is open
      if (onAdmin != null) IconButton(onAdmin, Icon.ADMIN, tint = onHero,
        background = onHero.copy(alpha = if (adminOpen) 0.32f else 0.12f))
      // The user's account; brighter while it is open
      IconButton(onSettings, Icon.USER, tint = onHero,
        background = onHero.copy(alpha = if (settingsOpen) 0.32f else 0.12f))
      IconButton(onLogout, Icon.LOGOUT, tint = onHero, background = onHero.copy(alpha = 0.2f))
    }
    if (stacked) {
      heading(Modifier)
      Spacer(Modifier.height(12.dp))
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
    } else {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        heading(Modifier.weight(1f))
        controls()
      }
    }
    if (summary != null) {
      val totals = summary.totals
      Spacer(Modifier.height(16.dp))
      Row(verticalAlignment = Alignment.Bottom) {
        Txt("${t.decimal(totals.percent)}%", size = 34.sp, weight = FontWeight.Bold, color = onHero)
        Txt(
          "  " + t.frame.ofChecksDone(t.number(totals.total)), size = 13.sp, color = onHero.copy(alpha = 0.85f),
          modifier = Modifier.padding(bottom = 7.dp),
        )
      }
      Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(onHero.copy(alpha = 0.25f))) {
        if (totals.percent > 0) {
          Box(
            Modifier.fillMaxWidth((totals.percent / 100).toFloat()).height(10.dp).clip(CircleShape)
              .background(if (p.dark) p.brand else SolidColor(onHero))
          )
        }
      }
    }
  }
}

/** The account's theme and mode (the app shows them already); a refusal leaves them for this browser only. */
internal suspend fun saveTheme(theme: Theme, mode: ThemeMode) {
  call("PUT", "api/account/theme", ApiJson.encodeToString(ThemeRequest.serializer(), ThemeRequest(theme, mode))) { }
}

/** Where back from the admin panel goes: the dashboard's session, or the sessions' page without one. */
private fun back(selected: String?): Route = selected?.let { Route.Dashboard(it) } ?: Route.Home

/** A session in the hero's switch: how it stands, how far it got, the user's slots. */
private fun sessionLine(t: Texts, session: SessionEntry): String = listOfNotNull(
  t.sessions.status(session.status),
  "${t.decimal(session.percent)}%",
  session.mySlots.takeIf { it.isNotEmpty() }?.joinToString(", "),
).joinToString(" · ")

@Composable
private fun Banner(text: String) {
  val p = LocalPalette.current
  Txt(
    text, size = 13.sp,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.warn.copy(alpha = 0.15f))
      .padding(horizontal = 14.dp, vertical = 10.dp),
  )
}

/**
 * Lays [count] cells out in rows of as many columns of at least [minWidth] as fit into [width]. [balanced]: rows as
 * even as possible (3 + 3 instead of 5 + 1). [fillHeight]: the rows share the height the grid is given.
 */
@Composable
private fun Grid(
  count: Int,
  width: Dp,
  minWidth: Dp,
  modifier: Modifier = Modifier,
  gap: Dp = GAP,
  balanced: Boolean = false,
  fillHeight: Boolean = false,
  cell: @Composable (Int, Modifier) -> Unit,
) {
  val fitting = ((width + gap) / (minWidth + gap)).toInt().coerceIn(1, count.coerceAtLeast(1))
  val rowCount = (count + fitting - 1) / fitting
  val columns = if (balanced && rowCount > 0) (count + rowCount - 1) / rowCount else fitting
  Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
    (0 until count).chunked(columns).forEach { row ->
      // Cells of a row share the height of the tallest one
      Row(
        if (fillHeight) Modifier.weight(1f) else Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(gap),
      ) {
        row.forEach { cell(it, Modifier.weight(1f).fillMaxHeight()) }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

private data class Tile(val label: String, val value: String, val sub: String, val color: Color, val icon: Icon?)

@Composable
internal fun Stats(s: Summary, now: Long, width: Dp, modifier: Modifier = Modifier, fillHeight: Boolean = false) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val totals = s.totals
  val open = s.hints.count { !it.found }
  val tiles = buildList {
    val d = t.dashboard
    add(Tile(d.goals, "${totals.goals} / ${totals.players}", d.slotsFinished, p.ok, Icon.FLAG))
    add(Tile(d.online, "${totals.online}", d.clientsConnected, p.useful, null))
    if (totals.lockable > 0) {
      add(Tile(d.unlocked, "${totals.lockable - totals.locked} / ${totals.lockable}", d.slotsOpened, p.key, Icon.KEY))
    }
    if (showHints(s)) add(Tile(d.hints, "${s.hints.size}", d.notFoundYet(open), p.progression, null))
    add(Tile(d.playTime, t.duration(s.players.sumOf { playTime(it, now) }), d.allSlotsTogether, p.useful, null))
    // The game server's running time for this seed, without the times it was stopped
    val since = s.sessionRunningSince
    val session = s.sessionTimeMs + if (since != null) (now - since).coerceAtLeast(0) else 0
    add(Tile(d.session, t.duration(session), if (since != null) d.serverRunning else d.serverStopped, p.brandStart, null))
  }
  Section(
    t.dashboard.overview, modifier,
    subtitle = t.dashboard.overviewSubtitle(totals.players, t.number(totals.checked), t.number(totals.total)),
  ) {
    Grid(
      tiles.size, width, 160.dp, if (fillHeight) Modifier.weight(1f) else Modifier,
      gap = 12.dp, balanced = true, fillHeight = fillHeight,
    ) { i, cellModifier ->
      StatTile(tiles[i], cellModifier)
    }
  }
}

@Composable
private fun StatTile(tile: Tile, modifier: Modifier) {
  val p = LocalPalette.current
  Row(modifier.card(p).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(
      Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tile.color.copy(alpha = 0.15f)),
      contentAlignment = Alignment.Center,
    ) {
      if (tile.icon != null) DrawnIcon(tile.icon, tile.color, 16.dp) else Dot(tile.color, 10.dp)
    }
    Column {
      Txt(tile.label, size = 12.sp, color = p.muted, weight = FontWeight.Medium)
      Txt(tile.value, size = 22.sp, weight = FontWeight.Bold, maxLines = 1)
      Txt(tile.sub, size = 11.5.sp, color = p.muted, maxLines = 1)
    }
  }
}

private enum class PeopleSort { PLAYER, PROGRESS, ACTIVITY }

@Composable
internal fun People(s: Summary, now: Long, width: Dp, onOpen: (Int) -> Unit, onOpenPerson: (String) -> Unit) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  var sort by remember { mutableStateOf(PeopleSort.PLAYER) }
  val people = when (sort) {
    PeopleSort.PLAYER -> s.people.sortedBy { it.name.lowercase() }
    PeopleSort.PROGRESS -> s.people.sortedByDescending { it.percent }
    PeopleSort.ACTIVITY -> s.people.sortedByDescending { it.lastActivity ?: 0 }
  }
  val worlds = s.players.associateBy { it.slot }
  Section(
    d.players, subtitle = d.playersSubtitle(s.people.size, s.players.size),
    actions = {
      Select(
        PeopleSort.entries.map {
          it to when (it) {
            PeopleSort.PLAYER -> d.sortPlayer
            PeopleSort.PROGRESS -> d.sortProgress
            PeopleSort.ACTIVITY -> d.sortActivity
          }
        },
        sort, { sort = it },
      )
    },
  ) {
    if (people.isEmpty()) {
      Panel(Modifier.fillMaxWidth()) { Txt(d.waitingForServer, color = p.muted, modifier = Modifier.padding(16.dp)) }
    } else {
      Grid(people.size, width, 330.dp) { i, modifier ->
        PersonCard(people[i], people[i].slots.mapNotNull { worlds[it] }, now, s.hintSettings.pointHints, modifier, onOpen, onOpenPerson)
      }
    }
  }
}

/**
 * A person's name, with their account's ID as a tip (it tells a deleted account's name in an old session from a new
 * account of the same name); a deleted account is marked so.
 */
@Composable
internal fun PersonName(name: String, userId: String?, deleted: Boolean, size: TextUnit, color: Color? = null) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  val text = if (deleted) d.deleted(name) else name
  if (userId == null) {
    Txt(text, size = size, weight = FontWeight.Bold, color = color ?: p.text, maxLines = 1)
  } else {
    Tip(d.account(userId, deleted)) {
      Txt(text, size = size, weight = FontWeight.Bold, color = color ?: if (deleted) p.muted else p.text, maxLines = 1)
    }
  }
}

@Composable
private fun PersonCard(
  person: PersonSummary,
  worlds: List<PlayerSummary>,
  now: Long,
  hintsByPoints: Boolean,
  modifier: Modifier,
  onOpen: (Int) -> Unit,
  onOpenPerson: (String) -> Unit,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val locked = worlds.count { it.status == PlayerStatus.LOCKED }
  Column(modifier.card(p).padding(8.dp)) {
    // The header opens the person's overview; each world row opens that world
    HoverRow(onClick = { onOpenPerson(person.name) }, modifier = Modifier.fillMaxWidth()) {
     Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Box {
        Avatar(person.name)
        if (person.online) {
          Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(p.panel).padding(2.dp)) {
            Dot(p.ok, 9.dp)
          }
        }
      }
      Column(Modifier.weight(1f)) {
        PersonName(person.name, person.userId, person.deleted, size = 16.sp)
        val played = worlds.sumOf { playTime(it, now) }
        val parts = listOfNotNull(
          t.sessions.slots(worlds.size),
          locked.takeIf { it > 0 }?.let { t.dashboard.locked(it) },
          if (person.goals > 0) t.dashboard.done(person.goals) else null,
          if (played > 0) t.dashboard.played(t.duration(played)) else null,
        )
        Txt(parts.joinToString(" · "), size = 12.sp, color = p.muted, maxLines = 1)
      }
      Txt("${t.decimal(person.percent)}%", size = 18.sp, weight = FontWeight.Bold, brush = p.brand)
     }
    }
    ProgressBar((person.percent / 100).toFloat(), Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp), height = 6.dp)
    worlds.forEach { WorldRow(it, now, hintsByPoints, onOpen) }
  }
}

@Composable
internal fun statusColor(status: PlayerStatus): Color = LocalPalette.current.let { p ->
  when (status) {
    PlayerStatus.GOAL, PlayerStatus.COMPLETE -> p.ok
    PlayerStatus.RELEASED -> p.progression
    PlayerStatus.PLAYING -> p.useful
    PlayerStatus.LOCKED -> p.locked
    PlayerStatus.IDLE -> p.muted
  }
}

internal fun statusLabel(t: Texts, world: PlayerSummary): String = t.dashboard.status(world.status, started = world.checked > 0)

/**
 * For worlds that start locked, under the game icon, as wide and down to the end of the world's texts: a key once it
 * was found, a lock until then (so the status pill leaves out "locked"). What it
 * means (who found the key, where it was hinted, which key it waits for) shows on hover, or on tap on a phone.
 */
@Composable
private fun LockBadge(lock: LockInfo, now: Long, modifier: Modifier = Modifier) {
  val t = LocalTexts.current
  val p = LocalPalette.current
  val color = if (lock.unlocked) p.key else p.locked
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  var tapped by remember { mutableStateOf(false) }
  Box(
    modifier.width(34.dp).heightIn(min = 18.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.18f))
      .hoverable(interaction)
      // Its own click: a tap shows the text instead of opening the world
      .hand().clickable(interactionSource = interaction, indication = null) { tapped = !tapped },
    contentAlignment = Alignment.Center,
  ) {
    DrawnIcon(if (lock.unlocked) Icon.KEY else Icon.LOCK, color, 11.dp)
    if (hovered || tapped) {
      AppPopup(offset = IntOffset(0, with(LocalDensity.current) { 22.dp.roundToPx() }), onDismissRequest = { tapped = false }) {
        Txt(
          when {
            lock.unlocked -> t.dashboard.unlockedBy(lock.foundBy.orEmpty()) + (lock.foundAt?.let { " · ${t.ago(it, now)}" } ?: "")
            lock.hintedAt != null -> t.dashboard.keyHinted(
              lock.hintLocation?.let { t.dashboard.hintPlace(it, lock.hintSlot.orEmpty()) } ?: lock.hintedAt.orEmpty()
            )
            else -> t.dashboard.waitingForKey(lock.key)
          },
          size = 12.sp, maxLines = 3,
          modifier = Modifier.widthIn(max = 260.dp).card(p, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
}

/** The hints a world can buy with its points, for the bubble next to its status ("3 hints · 12 pts"). */
private fun hintBubble(t: Texts, world: PlayerSummary, hintsByPoints: Boolean): String? {
  val points = world.hintPoints ?: return null
  if (!hintsByPoints || world.status == PlayerStatus.LOCKED) return null
  val cost = world.hintCost
  return when {
    cost == null -> t.dashboard.points(points)
    cost == 0 -> t.dashboard.hintsFree
    else -> t.dashboard.hintsForPoints(points / cost, points)
  }
}

/** One world of a person: game, progress, status; a locked one says what opens it. */
@Composable
private fun WorldRow(world: PlayerSummary, now: Long, hintsByPoints: Boolean, onOpen: (Int) -> Unit) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  val locked = world.status == PlayerStatus.LOCKED
  HoverRow(onClick = { onOpen(world.slot) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
    // As tall as the texts, so the lock badge under the game icon ends with them
    Row(Modifier.height(IntrinsicSize.Min).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.dimmedIf(locked)) { GameChip(world.game, dimmed = locked) }
        world.lock?.let { LockBadge(it, now, Modifier.weight(1f)) }
      }
      Column(Modifier.weight(1f)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Txt(world.game, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.weight(1f).dimmedIf(locked))
          hintBubble(t, world, hintsByPoints)?.let { Pill(it, p.progression) }
          // "playing" in colour while a game client is connected, grey otherwise
          val playing = world.status == PlayerStatus.PLAYING || world.online && world.status == PlayerStatus.IDLE
          // Locked: the badge under the game icon says it
          if (!locked) Pill(
            if (playing) t.dashboard.playing else statusLabel(t, world),
            if (playing && !world.online) p.muted else statusColor(if (playing) PlayerStatus.PLAYING else world.status),
            icon = if (world.status == PlayerStatus.GOAL) Icon.FLAG else null,
          )
        }
        Row(Modifier.fillMaxWidth()) {
          Txt(t.dashboard.slot(shortSlotName(world.name, world.person)), size = 11.5.sp, color = p.muted, maxLines = 1, modifier = Modifier.weight(1f))
          if (!locked && !world.online) Txt(t.ago(world.lastActivity, now), size = 11.5.sp, maxLines = 1, color = p.muted)
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar((world.percent / 100).toFloat(), height = 5.dp, dimmed = locked)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
          Txt(t.dashboard.progress(world.checked, world.total, world.progressionReceived), size = 11.5.sp,
            color = p.muted, maxLines = 1, modifier = Modifier.weight(1f))
          val played = playTime(world, now)
          if (played > 0 || world.goalAt != null) {
            Txt(
              listOfNotNull(
                t.dashboard.played(t.duration(played)).takeIf { played > 0 },
                world.goalAt?.let { t.dashboard.doneAt(t.clock(it)) },
              ).joinToString(" · "),
              size = 11.5.sp, color = p.muted, maxLines = 1,
            )
          }
        }
      }
    }
  }
}

/** Hint states as players see them: Archipelago's HintStatus, plus "not found" for all open ones. */
private enum class HintState(val matches: (HintView) -> Boolean) {
  OPEN({ !it.found }),
  ALL({ true }),
  FOUND({ it.found }),
  PRIORITY({ !it.found && it.status == 30 }),
  AVOID({ !it.found && it.status == 20 }),
  NO_PRIORITY({ !it.found && it.status == 10 }),
  UNSPECIFIED({ !it.found && it.status == 0 }),
  ;

  fun label(d: DashboardTexts): String = when (this) {
    OPEN -> d.hintNotFound
    ALL -> d.hintAllStates
    FOUND -> d.hintFound
    PRIORITY -> d.hintPriority
    AVOID -> d.hintAvoid
    NO_PRIORITY -> d.hintNoPriority
    UNSPECIFIED -> d.hintUnmarked
  }
}

@Composable
internal fun Hints(s: Summary, compact: Boolean) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  var person by remember { mutableStateOf<String?>(null) }
  var world by remember { mutableStateOf<String?>(null) }
  var state by remember { mutableStateOf(HintState.OPEN) }
  val worldsOfPerson = person?.let { name ->
    val slots = s.people.firstOrNull { it.name == name }?.slots.orEmpty().toSet()
    s.players.filter { it.slot in slots }
  } ?: s.players
  // A hint concerns a world if the item is for it or in it (key hints are "for" the world they unlock)
  val shownWorlds = world?.let { setOf(it) } ?: worldsOfPerson.map { it.name }.toSet()
  val hints = s.hints.filter { h ->
    (person == null && world == null || h.receiverName in shownWorlds || h.finderName in shownWorlds) && state.matches(h)
  }
  Section(d.hints, Modifier.fillMaxWidth(), subtitle = d.hintsSubtitle(s.hints.count { !it.found }, s.hints.size), actions = {
    if (s.people.size > 1) {
      Select(listOf<Pair<String?, String>>(null to d.allPlayers) + s.people.map { it.name to it.name }, person, {
        person = it
        world = null
      })
    }
    Select(listOf<Pair<String?, String>>(null to d.allSlots) + worldsOfPerson.map { it.name to it.name }, world, { world = it })
    Select(HintState.entries.map { it to it.label(d) }, state, { state = it })
  }) {
    Column(Modifier.fillMaxWidth().card(p)) { HintList(s, hints, compact) }
  }
}

@Composable
private fun HintList(s: Summary, hints: List<HintView>, compact: Boolean) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  if (hints.isEmpty()) {
    Txt(if (s.hints.isEmpty()) d.noHintsYet else d.noHintsMatch, color = p.muted, modifier = Modifier.padding(16.dp))
    return
  }
  if (!compact) {
    HintRow(listOf(d.columnFor, d.columnItem, d.columnCheck, d.columnInSlot, "").map { { Txt(it, size = 12.sp, color = p.muted, weight = FontWeight.SemiBold) } })
    Divider()
  }
  Column(Modifier.heightIn(max = 600.dp).scrolling()) {
    hints.forEachIndexed { i, h ->
      if (i > 0) Divider()
      if (compact) CompactHint(h) else HintRow(listOf(
        { Txt(h.receiverName, size = 13.sp, weight = FontWeight.SemiBold) },
        { ItemText(h.itemName, h.itemFlags, key = h.itemName.startsWith("Key to ")) },
        {
          Column {
            Txt(h.locationName, size = 13.sp)
            if (h.entrance.isNotEmpty()) Txt(h.entrance, size = 12.sp, color = p.muted)
          }
        },
        { Txt(h.finderName, size = 13.sp, weight = FontWeight.SemiBold) },
        { HintStatus(h) },
      ))
    }
  }
}

@Composable
internal fun ItemText(name: String, flags: Int, key: Boolean) {
  val p = LocalPalette.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    if (key) DrawnIcon(Icon.KEY, p.key, 12.dp)
    Txt(name, size = 13.sp, weight = FontWeight.SemiBold, color = if (key) p.key else p.item(flags))
  }
}

private val HINT_WEIGHTS = listOf(1f, 1.5f, 2f, 1.2f, .7f)

@Composable
private fun HintRow(cells: List<@Composable () -> Unit>) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    cells.forEachIndexed { i, cell -> Box(Modifier.weight(HINT_WEIGHTS[i])) { cell() } }
  }
}

@Composable
private fun CompactHint(h: HintView) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Txt(buildAnnotatedString {
        appendTemplate(d.hintItem, mapOf(
          "receiver" to (h.receiverName to SpanStyle(fontWeight = FontWeight.SemiBold)),
          "item" to (h.itemName to SpanStyle(fontWeight = FontWeight.SemiBold, color = p.item(h.itemFlags))),
        ))
      }, size = 13.sp, modifier = Modifier.weight(1f))
      HintStatus(h)
    }
    Txt(fill(d.hintWhere, "location" to h.locationName, "finder" to h.finderName), size = 12.sp, color = p.muted)
  }
}

/** Archipelago's HintStatus: players can mark their hints as priority, avoid or no priority in their client. */
@Composable
internal fun HintStatus(h: HintView) {
  val p = LocalPalette.current
  val d = LocalTexts.current.dashboard
  when {
    h.found || h.status == 40 -> Pill(d.pillFound, p.ok)
    h.status == 30 -> Pill(d.pillPriority, p.brandStart)
    h.status == 20 -> Pill(d.pillAvoid, p.trap)
    h.status == 10 -> Pill(d.pillNoPriority, p.muted)
    else -> Pill(d.pillOpen, p.warn)
  }
}

private enum class FeedKind {
  ALL, PROGRESSION, ITEMS, KEYS, HINTS, OTHER;

  fun label(f: FeedTexts): String = when (this) {
    ALL -> f.allEvents
    PROGRESSION -> f.progression
    ITEMS -> f.items
    KEYS -> f.keysAndGoals
    HINTS -> f.hints
    OTHER -> f.connectionsAndChat
  }
}

/** The live feed; [onPosition] and [onHide] `null`: without the buttons to place or hide it (a past session). */
@Composable
internal fun Feed(
  s: Summary,
  modifier: Modifier,
  position: FeedPosition,
  /** Beside players and hints: the given height is filled and the list scrolls inside. */
  fillHeight: Boolean,
  onPosition: ((FeedPosition) -> Unit)?,
  onHide: (() -> Unit)?,
  /** `null`: the live feed's. */
  title: String? = null,
) {
  val p = LocalPalette.current
  val t = LocalTexts.current
  var who by remember { mutableStateOf<Int?>(null) }
  var kind by remember { mutableStateOf(FeedKind.ALL) }
  val events = s.events.asReversed().filter { e ->
    (who == null || who in listOfNotNull(e.slot, e.sender, e.receiver, e.finder)) &&
      when (kind) {
        FeedKind.ALL -> true
        FeedKind.PROGRESSION -> e.type == EventType.ITEM && e.flags and ItemFlags.PROGRESSION != 0
        FeedKind.ITEMS -> e.type == EventType.ITEM
        FeedKind.KEYS -> e.type in setOf(EventType.UNLOCK, EventType.GOAL, EventType.RELEASE, EventType.COLLECT)
        FeedKind.HINTS -> e.type == EventType.HINT
        FeedKind.OTHER -> e.type in setOf(EventType.CHAT, EventType.JOIN, EventType.PART, EventType.COUNTDOWN, EventType.CHEAT)
      }
  }
  // The position picker's height, so the close button next to it is as tall
  var pickerHeight by remember { mutableStateOf(26.dp) }
  val density = LocalDensity.current
  Section(title ?: t.feed.title, modifier, subtitle = t.feed.newestFirst, actions = {
    if (onPosition != null) {
      Select(
        FeedPosition.entries.map { it to it.label(t) }, position, onPosition,
        Modifier.onSizeChanged { pickerHeight = with(density) { it.height.toDp() } },
      )
    }
    if (onHide != null) IconButton(onHide, Icon.CLOSE, Modifier.size(pickerHeight))
  }) {
    Column(Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier).card(p)) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Select(listOf<Pair<Int?, String>>(null to t.feed.everyone) + s.players.map { it.slot to it.name }, who, { who = it })
        Select(FeedKind.entries.map { it to it.label(t.feed) }, kind, { kind = it })
      }
      Divider()
      if (events.isEmpty()) {
        Txt(t.feed.nothingYet, color = p.muted, modifier = Modifier.padding(16.dp))
      } else {
        Column(
          (if (fillHeight) Modifier.weight(1f) else Modifier.heightIn(max = if (position == FeedPosition.BELOW) 520.dp else 640.dp))
            .scrolling().padding(vertical = 4.dp)
        ) {
          events.forEach { FeedRow(it) }
        }
      }
    }
  }
}

@Composable
internal fun FeedRow(e: FeedEvent) {
  val p = LocalPalette.current
  val (color, icon) = when (e.type) {
    EventType.ITEM -> p.item(e.flags) to null
    EventType.UNLOCK -> p.key to Icon.KEY
    EventType.GOAL -> p.ok to Icon.FLAG
    EventType.HINT -> p.progression to null
    EventType.RELEASE, EventType.COLLECT -> p.progression to null
    EventType.CHEAT -> p.trap to null
    else -> p.muted to null
  }
  val highlight = e.type == EventType.UNLOCK || e.type == EventType.GOAL
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
      .background(if (highlight) color.copy(alpha = 0.1f) else Color.Transparent)
      .padding(horizontal = 8.dp, vertical = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(Modifier.padding(top = 3.dp).size(18.dp), contentAlignment = Alignment.Center) {
      if (icon != null) DrawnIcon(icon, color, 14.dp) else Dot(color, 8.dp)
    }
    Column(Modifier.weight(1f)) { FeedLine(e) }
    Txt(LocalTexts.current.clock(e.ts), size = 11.5.sp, color = p.muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
  }
}

@Composable
private fun FeedLine(e: FeedEvent) {
  val p = LocalPalette.current
  val f = LocalTexts.current.feed
  val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
  val system = SpanStyle(color = p.muted, fontStyle = FontStyle.Italic)
  val item = SpanStyle(fontWeight = FontWeight.SemiBold, color = p.item(e.flags))
  val player = mapOf("player" to (e.playerName.orEmpty() to bold))
  Txt(buildAnnotatedString {
    when (e.type) {
      EventType.ITEM -> appendTemplate(
        if (e.sender == e.receiver) f.foundOwn else f.sent,
        mapOf(
          "sender" to (e.senderName.orEmpty() to bold),
          "item" to (e.itemName.orEmpty() to item),
          "receiver" to (e.receiverName.orEmpty() to bold),
        ),
      )

      EventType.CHEAT -> {
        withStyle(SpanStyle(color = p.trap, fontWeight = FontWeight.SemiBold)) { append(f.cheat) }
        val action = e.cheatAction
        // Events from before 0.9.3 have only the English sentence
        append(
          if (action == null) e.message.orEmpty()
          else LocalTexts.current.cheats.feedLine(e.cheatBy.orEmpty(), e.cheatRole, action, e.cheatSlot.orEmpty(), e.cheatValue)
        )
      }

      EventType.UNLOCK -> appendTemplate(
        f.unlocked,
        mapOf(
          "sender" to (e.senderName.orEmpty() to bold),
          "slot" to ((e.playerName ?: f.aSlot) to SpanStyle(fontWeight = FontWeight.Bold, color = p.key)),
        ),
      )

      EventType.HINT -> {
        withStyle(system) { append(f.hint) }
        appendTemplate(
          f.hintLine,
          mapOf(
            "receiver" to (e.receiverName.orEmpty() to bold),
            "item" to (e.itemName.orEmpty() to item),
            "location" to (e.locationName.orEmpty() to null),
            "finder" to (e.finderName.orEmpty() to bold),
          ),
        )
        if (e.found) withStyle(SpanStyle(color = p.ok, fontWeight = FontWeight.SemiBold)) { append(f.found) }
      }

      EventType.GOAL -> withStyle(SpanStyle(color = p.ok, fontWeight = FontWeight.Bold)) {
        append(fill(f.goal, "player" to e.playerName.orEmpty()))
      }

      EventType.RELEASE -> appendTemplate(f.released, player, rest = system)
      EventType.COLLECT -> appendTemplate(f.collected, player, rest = system)
      EventType.JOIN -> appendTemplate(f.joined, player, rest = system)
      EventType.PART -> appendTemplate(f.left, player, rest = system)
      EventType.CHAT -> { withStyle(bold) { append(e.playerName ?: "") }; append(": ${e.message ?: ""}") }
      EventType.COUNTDOWN -> withStyle(SpanStyle(color = p.ok, fontWeight = FontWeight.Bold)) { append(e.message ?: "") }
    }
    // An item a client took with !getitem: Archipelago marks it as a cheat
    if (e.cheat && e.type != EventType.CHEAT) withStyle(SpanStyle(color = p.trap, fontWeight = FontWeight.SemiBold)) { append(f.cheatMark) }
  }, size = 13.5.sp)
  val location = e.locationName
  if ((e.type == EventType.ITEM || e.type == EventType.UNLOCK) && !location.isNullOrEmpty()) {
    Txt(location, size = 11.5.sp, color = p.muted)
  }
}

/** Connected time of [world] so far, including the running session. */
fun playTime(world: PlayerSummary, now: Long): Long =
  world.playTimeMs + (world.onlineSince?.let { (now - it).coerceAtLeast(0) } ?: 0)

/** Hints are shown when a hint system is in play, or when the host handed some out anyway (/hint). */
internal fun showHints(s: Summary): Boolean = s.hintSettings.enabled || s.hints.isNotEmpty()
