package net.eratiem.randogeddon.webapp

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import net.eratiem.randogeddon.shared.SessionStatus
import net.eratiem.randogeddon.shared.Summary

private const val THEME_KEY = "ap-theme"
private const val FEED_VISIBLE_KEY = "ap-feed-visible"
private const val FEED_POSITION_KEY = "ap-feed-position"
private val FEED_WIDTH = 440.dp

enum class FeedPosition(val label: String) { RIGHT("Right"), LEFT("Left"), BELOW("Below") }
private val WIDE = 1100.dp
private val GAP = 16.dp
private val CONNECTION_WIDTH = 420.dp
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

@Composable
fun App() {
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

  var themeSetting by remember { mutableStateOf(Platform.loadSetting(THEME_KEY)) }
  val dark = themeSetting?.let { it == "dark" } ?: Platform.systemDarkMode()

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

  // Per viewer, remembered in the browser: the live feed is visible and on the right unless changed
  var feedVisible by remember { mutableStateOf(Platform.loadSetting(FEED_VISIBLE_KEY) != "false") }
  var feedPosition by remember {
    mutableStateOf(FeedPosition.entries.firstOrNull { it.name == Platform.loadSetting(FEED_POSITION_KEY) } ?: FeedPosition.RIGHT)
  }
  fun setFeedVisible(visible: Boolean) {
    feedVisible = visible
    Platform.saveSetting(FEED_VISIBLE_KEY, visible.toString())
  }
  fun setFeedPosition(position: FeedPosition) {
    feedPosition = position
    Platform.saveSetting(FEED_POSITION_KEY, position.name)
  }

  RandogeddonTheme(dark) {
    val p = LocalPalette.current
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Text can be selected (and copied) anywhere on the page
        SelectionContainer {
          Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GAP)) {
            val s = summary
            val user = me ?: run {
              // Before the login is known: either the login form of index.html covers the page, or the server is away
              if (unreachable) Txt(
                "Can't reach the server – trying again…", color = LocalPalette.current.muted,
                modifier = Modifier.padding(top = 120.dp).align(Alignment.CenterHorizontally),
              )
              return@Column
            }
            val r = route
            val dashboard = (r as? Route.Dashboard)?.id
            // The hero's switch: the user's sessions, and the one shown (an admin may watch one they don't play in);
            // the admin panel keeps the dashboard's session in it, a spoiler log its own
            val shown = dashboard ?: (r as? Route.Spoiler)?.id ?: selected.takeIf { adminOpen }
            val switchable = sessions.filter { it.mySlots.isNotEmpty() || it.id == shown }
            Headline()
            Hero(
              s.takeIf { dashboard != null || r is Route.Spoiler || adminOpen }, streamOpen,
              sessions = switchable, shown = shown,
              onSession = { go(Route.Dashboard(it)) },
              onAllSessions = { go(Route.Home) },
              onToggleTheme = {
                themeSetting = if (dark) "light" else "dark"
                Platform.saveSetting(THEME_KEY, themeSetting)
              },
              feedVisible = feedVisible,
              onToggleFeed = { setFeedVisible(!feedVisible) },
              onLogout = { scope.launch { repository.logout() } },
              onSettings = { go(if (r == Route.Settings) back(selected) else Route.Settings) },
              settingsOpen = r == Route.Settings,
              onAdmin = if (user.admin) ({ go(if (adminOpen) back(selected) else Route.Admin(selected)) }) else null,
              adminOpen = adminOpen,
              stacked = narrow,
            )
            when (r) {
              is Route.Spoiler -> {
                SpoilerView(r.id, onBack = { go(if (r.fromAdmin) Route.Admin(r.id) else Route.Dashboard(r.id)) })
                return@Column
              }
              is Route.Admin -> {
                if (user.admin) AdminView(
                  now, r.page, onPage = { go(Route.Admin(it)) }, onBack = { go(back(selected)) },
                  onSpoiler = { go(Route.Spoiler(it, fromAdmin = true)) },
                  onDashboard = { go(Route.Dashboard(it)) },
                ) else Txt("The admin panel is for admins.", color = p.muted)
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
              is Route.Invite -> {
                // Signing up happens logged out (index.html's form); this browser is someone's already
                Txt(
                  "You're logged in as ${user.name}. To sign up with this invitation, log out and open its link again.",
                  color = p.muted, modifier = Modifier.padding(vertical = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  SmallButton("Log out", { scope.launch { repository.logout() } })
                  SmallButton("All your sessions", { go(Route.Home) })
                }
                return@Column
              }
              Route.Home -> {
                if (loadedSessions != null) {
                  SessionPicker(sessions.filter { it.mySlots.isNotEmpty() }, user.admin, onOpen = { go(Route.Dashboard(it)) })
                }
                return@Column
              }
              is Route.Dashboard -> if (loadedSessions != null && sessions.none { it.id == r.id }) {
                Txt("There is no session ${r.id}, or you aren't part of it.", color = p.muted, modifier = Modifier.padding(vertical = 8.dp))
                SmallButton("All your sessions", { go(Route.Home) })
                return@Column
              }
            }
            if (s == null || selected == null) return@Column
            val mine = sessions.firstOrNull { it.id == selected }?.mySlots?.isNotEmpty() == true
            val error = s.error
            if (!s.connected && error != null && s.finishedAt == null) Banner(error)
            FinishBanner(
              s, user.admin,
              onFinishNow = { scope.launch { Platform.request("POST", "api/admin/sessions/$selected/finish") } },
              onCancel = { scope.launch { Platform.request("POST", "api/admin/sessions/$selected/finish/cancel") } },
              onSpoiler = { selected?.let { go(Route.Spoiler(it, fromAdmin = false)) } },
            )
            if (wide) {
              // The tiles and the connection share a row and its height
              Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Stats(s, now, contentWidth - CONNECTION_WIDTH - GAP, Modifier.weight(1f).fillMaxHeight(), fillHeight = true)
                ConnectionSection(user.name, connection, mine, s, Modifier.width(CONNECTION_WIDTH).fillMaxHeight(), fillHeight = true)
              }
            } else {
              ConnectionSection(user.name, connection, mine, s, Modifier.fillMaxWidth())
              Stats(s, now, contentWidth)
            }
            val feed: @Composable (Modifier, Boolean) -> Unit = { modifier, fillHeight ->
              Feed(s, modifier, feedPosition, fillHeight, onPosition = ::setFeedPosition, onHide = { setFeedVisible(false) })
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
private fun Headline() {
  val p = LocalPalette.current
  Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
    BasicText(
      "RANDOGEDDON",
      style = TextStyle(
        brush = Brush.linearGradient(listOf(p.brandStart, p.brandEnd)),
        fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, textAlign = TextAlign.Center,
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
  onToggleFeed: () -> Unit,
  onLogout: () -> Unit,
  /** The user's account; [settingsOpen] while it is shown. */
  onSettings: () -> Unit,
  settingsOpen: Boolean,
  /** Only for admins. */
  onAdmin: (() -> Unit)?,
  adminOpen: Boolean,
  stacked: Boolean,
) {
  val p = LocalPalette.current
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
            listOf(Triple<String?, String, String>(null, "All sessions", "")) +
              sessions.map { Triple<String?, String, String>(it.id, it.id, sessionLine(it)) },
            shown, { id -> if (id == null) onAllSessions() else onSession(id) },
            chip = false, heading = 24.sp, headingColor = Color.White, placeholder = "Pick a session",
          )
        } else {
          Txt(shown ?: "No session", size = 24.sp, weight = FontWeight.Bold, color = Color.White)
        }
        val meta = listOfNotNull(
          summary?.seedName?.let { "Seed $it" },
          summary?.serverVersion?.let { "AP $it" },
          summary?.let { hintModeLabel(it) },
        ).ifEmpty { listOf(if (shown == null) "Pick one of your sessions" else "Connecting to the tracker…") }
        // A phone: one line each
        (if (stacked) meta else listOf(meta.joinToString("  ·  "))).forEach {
          Txt(it, size = 12.5.sp, color = Color.White.copy(alpha = 0.85f))
        }
      }
    }
    val controls: @Composable () -> Unit = {
      // As tall as the buttons next to it
      Row(
        Modifier.height(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Dot(if (live) Color(0xFF6CF0A9) else Color(0xFFFFB4A8))
        Txt(
          when {
            !streamOpen -> "reconnecting…"
            summary?.finishedAt != null -> "finished"
            summary?.connected == true -> "live"
            else -> "server offline"
          }, size = 12.5.sp, color = Color.White, weight = FontWeight.SemiBold, maxLines = 1
        )
      }
      // Shows / hides the live feed; brighter while it is shown
      IconButton(onToggleFeed, Icon.FEED, tint = Color.White,
        background = Color.White.copy(alpha = if (feedVisible) 0.32f else 0.12f))
      IconButton(onToggleTheme, Icon.THEME, tint = Color.White, background = Color.White.copy(alpha = 0.2f))
      // Brighter while the admin panel is open
      if (onAdmin != null) IconButton(onAdmin, Icon.ADMIN, tint = Color.White,
        background = Color.White.copy(alpha = if (adminOpen) 0.32f else 0.12f))
      // The user's account; brighter while it is open
      IconButton(onSettings, Icon.USER, tint = Color.White,
        background = Color.White.copy(alpha = if (settingsOpen) 0.32f else 0.12f))
      IconButton(onLogout, Icon.LOGOUT, tint = Color.White, background = Color.White.copy(alpha = 0.2f))
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
      val t = summary.totals
      Spacer(Modifier.height(16.dp))
      Row(verticalAlignment = Alignment.Bottom) {
        Txt("${oneDecimal(t.percent)}%", size = 34.sp, weight = FontWeight.Bold, color = Color.White)
        Txt(
          "  of ${grouped(t.total)} checks done", size = 13.sp, color = Color.White.copy(alpha = 0.85f),
          modifier = Modifier.padding(bottom = 7.dp),
        )
      }
      Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
        if (t.percent > 0) {
          Box(
            Modifier.fillMaxWidth((t.percent / 100).toFloat()).height(10.dp).clip(CircleShape)
              .background(if (p.dark) p.brand else SolidColor(Color.White))
          )
        }
      }
    }
  }
}

/** Where back from the admin panel goes: the dashboard's session, or the sessions' page without one. */
private fun back(selected: String?): Route = selected?.let { Route.Dashboard(it) } ?: Route.Home

/** A session in the hero's switch: how it stands, how far it got, the user's slots. */
private fun sessionLine(session: SessionEntry): String = listOfNotNull(
  when (session.status) {
    SessionStatus.LIVE -> "live"
    SessionStatus.FINISHING -> "ending"
    SessionStatus.PAUSED -> "paused"
    SessionStatus.ENDED -> "ended"
  },
  "${oneDecimal(session.percent)}%",
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
  val t = s.totals
  val open = s.hints.count { !it.found }
  val tiles = buildList {
    add(Tile("Goals", "${t.goals} / ${t.players}", "slots finished", p.ok, Icon.FLAG))
    add(Tile("Online", "${t.online}", "clients connected", p.useful, null))
    if (t.lockable > 0) {
      add(Tile("Unlocked", "${t.lockable - t.locked} / ${t.lockable}", "slots opened", p.key, Icon.KEY))
    }
    if (showHints(s)) add(Tile("Hints", "${s.hints.size}", "$open not found yet", p.progression, null))
    add(Tile("Play time", duration(s.players.sumOf { playTime(it, now) }), "all slots together", p.useful, null))
    // The game server's running time for this seed, without the times it was stopped
    val since = s.sessionRunningSince
    val session = s.sessionTimeMs + if (since != null) (now - since).coerceAtLeast(0) else 0
    add(Tile("Session", duration(session), if (since != null) "game server running" else "game server stopped", p.brandStart, null))
  }
  Section(
    "Overview", modifier,
    subtitle = "${t.players} slots · ${grouped(t.checked)} of ${grouped(t.total)} checks",
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

private enum class PeopleSort(val label: String) { PLAYER("Sort: player"), PROGRESS("Sort: progress"), ACTIVITY("Sort: last activity") }

@Composable
internal fun People(s: Summary, now: Long, width: Dp, onOpen: (Int) -> Unit, onOpenPerson: (String) -> Unit) {
  val p = LocalPalette.current
  var sort by remember { mutableStateOf(PeopleSort.PLAYER) }
  val people = when (sort) {
    PeopleSort.PLAYER -> s.people.sortedBy { it.name.lowercase() }
    PeopleSort.PROGRESS -> s.people.sortedByDescending { it.percent }
    PeopleSort.ACTIVITY -> s.people.sortedByDescending { it.lastActivity ?: 0 }
  }
  val worlds = s.players.associateBy { it.slot }
  Section(
    "Players", subtitle = "${s.people.size} ${if (s.people.size == 1) "player" else "players"} · ${s.players.size} slots",
    actions = { Select(PeopleSort.entries.map { it to it.label }, sort, { sort = it }) },
  ) {
    if (people.isEmpty()) {
      Panel(Modifier.fillMaxWidth()) { Txt("Waiting for the server…", color = p.muted, modifier = Modifier.padding(16.dp)) }
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
  val text = if (deleted) "$name (deleted)" else name
  if (userId == null) {
    Txt(text, size = size, weight = FontWeight.Bold, color = color ?: p.text, maxLines = 1)
  } else {
    Tip("Account $userId${if (deleted) " (deleted)" else ""}") {
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
          "${worlds.size} ${if (worlds.size == 1) "slot" else "slots"}",
          locked.takeIf { it > 0 }?.let { "$it locked" },
          if (person.goals > 0) "${person.goals} done" else null,
          if (played > 0) "${duration(played)} played" else null,
        )
        Txt(parts.joinToString(" · "), size = 12.sp, color = p.muted, maxLines = 1)
      }
      Txt("${oneDecimal(person.percent)}%", size = 18.sp, weight = FontWeight.Bold, brush = p.brand)
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

internal fun statusLabel(world: PlayerSummary): String = when (world.status) {
  PlayerStatus.IDLE -> if (world.checked == 0) "not started" else "idle"
  else -> world.status.name.lowercase()
}

/**
 * For worlds that start locked, under the game icon, as wide and down to the end of the world's texts: a key once it
 * was found, a lock until then (so the status pill leaves out "locked"). What it
 * means (who found the key, where it was hinted, which key it waits for) shows on hover, or on tap on a phone.
 */
@Composable
private fun LockBadge(lock: LockInfo, now: Long, modifier: Modifier = Modifier) {
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
            lock.unlocked -> "Unlocked by ${lock.foundBy}" + (lock.foundAt?.let { " · ${ago(it, now)}" } ?: "")
            lock.hintedAt != null -> "Locked · key hinted: ${lock.hintedAt}"
            else -> "Locked · waiting for ${lock.key}"
          },
          size = 12.sp, maxLines = 3,
          modifier = Modifier.widthIn(max = 260.dp).card(p, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
}

/** The hints a world can buy with its points, for the bubble next to its status ("3 hints · 12 pts"). */
private fun hintBubble(world: PlayerSummary, hintsByPoints: Boolean): String? {
  val points = world.hintPoints ?: return null
  if (!hintsByPoints || world.status == PlayerStatus.LOCKED) return null
  val cost = world.hintCost
  return when {
    cost == null -> "$points pts"
    cost == 0 -> "hints free"
    else -> "${points / cost} ${if (points / cost == 1) "hint" else "hints"} · $points pts"
  }
}

/** One world of a person: game, progress, status; a locked one says what opens it. */
@Composable
private fun WorldRow(world: PlayerSummary, now: Long, hintsByPoints: Boolean, onOpen: (Int) -> Unit) {
  val p = LocalPalette.current
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
          hintBubble(world, hintsByPoints)?.let { Pill(it, p.progression) }
          // "playing" in colour while a game client is connected, grey otherwise
          val playing = world.status == PlayerStatus.PLAYING || world.online && world.status == PlayerStatus.IDLE
          // Locked: the badge under the game icon says it
          if (!locked) Pill(
            if (playing) "playing" else statusLabel(world),
            if (playing && !world.online) p.muted else statusColor(if (playing) PlayerStatus.PLAYING else world.status),
            icon = if (world.status == PlayerStatus.GOAL) Icon.FLAG else null,
          )
        }
        Row(Modifier.fillMaxWidth()) {
          Txt("Slot: ${shortSlotName(world.name, world.person)}", size = 11.5.sp, color = p.muted, maxLines = 1, modifier = Modifier.weight(1f))
          if (!locked && !world.online) Txt(ago(world.lastActivity, now), size = 11.5.sp, maxLines = 1, color = p.muted)
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar((world.percent / 100).toFloat(), height = 5.dp, dimmed = locked)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
          Txt("${world.checked} / ${world.total} · ${world.progressionReceived} progression", size = 11.5.sp,
            color = p.muted, maxLines = 1, modifier = Modifier.weight(1f))
          val played = playTime(world, now)
          if (played > 0 || world.goalAt != null) {
            Txt(
              listOfNotNull(
                "${duration(played)} played".takeIf { played > 0 },
                world.goalAt?.let { "done ${Platform.clock(it)}" },
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
private enum class HintState(val label: String, val matches: (HintView) -> Boolean) {
  OPEN("Not found", { !it.found }),
  ALL("All states", { true }),
  FOUND("Found", { it.found }),
  PRIORITY("Priority", { !it.found && it.status == 30 }),
  AVOID("Avoid", { !it.found && it.status == 20 }),
  NO_PRIORITY("No priority", { !it.found && it.status == 10 }),
  UNSPECIFIED("Unmarked", { !it.found && it.status == 0 }),
}

@Composable
internal fun Hints(s: Summary, compact: Boolean) {
  val p = LocalPalette.current
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
  Section("Hints", Modifier.fillMaxWidth(), subtitle = "${s.hints.count { !it.found }} open · ${s.hints.size} total", actions = {
    if (s.people.size > 1) {
      Select(listOf<Pair<String?, String>>(null to "All players") + s.people.map { it.name to it.name }, person, {
        person = it
        world = null
      })
    }
    Select(listOf<Pair<String?, String>>(null to "All slots") + worldsOfPerson.map { it.name to it.name }, world, { world = it })
    Select(HintState.entries.map { it to it.label }, state, { state = it })
  }) {
    Column(Modifier.fillMaxWidth().card(p)) { HintList(s, hints, compact) }
  }
}

@Composable
private fun HintList(s: Summary, hints: List<HintView>, compact: Boolean) {
  val p = LocalPalette.current
  if (hints.isEmpty()) {
    Txt(if (s.hints.isEmpty()) "No hints yet." else "No hints match the filter.", color = p.muted, modifier = Modifier.padding(16.dp))
    return
  }
  if (!compact) {
    HintRow(listOf("For", "Item", "Check", "In slot", "").map { { Txt(it, size = 12.sp, color = p.muted, weight = FontWeight.SemiBold) } })
    Divider()
  }
  Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
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
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Txt(buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(h.receiverName) }
        append("'s ")
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.item(h.itemFlags))) { append(h.itemName) }
      }, size = 13.sp, modifier = Modifier.weight(1f))
      HintStatus(h)
    }
    Txt("${h.locationName} · in ${h.finderName}", size = 12.sp, color = p.muted)
  }
}

/** Archipelago's HintStatus: players can mark their hints as priority, avoid or no priority in their client. */
@Composable
internal fun HintStatus(h: HintView) {
  val p = LocalPalette.current
  when {
    h.found || h.status == 40 -> Pill("found", p.ok)
    h.status == 30 -> Pill("priority", p.brandStart)
    h.status == 20 -> Pill("avoid", p.trap)
    h.status == 10 -> Pill("no priority", p.muted)
    else -> Pill("open", p.warn)
  }
}

private enum class FeedKind(val label: String) {
  ALL("All events"), PROGRESSION("Progression"), ITEMS("Items"), KEYS("Keys & goals"), HINTS("Hints"), OTHER("Connections & chat")
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
  title: String = "Live feed",
) {
  val p = LocalPalette.current
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
  Section(title, modifier, subtitle = "newest first", actions = {
    if (onPosition != null) {
      Select(
        FeedPosition.entries.map { it to it.label }, position, onPosition,
        Modifier.onSizeChanged { pickerHeight = with(density) { it.height.toDp() } },
      )
    }
    if (onHide != null) IconButton(onHide, Icon.CLOSE, Modifier.size(pickerHeight))
  }) {
    Column(Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier).card(p)) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Select(listOf<Pair<Int?, String>>(null to "Everyone") + s.players.map { it.slot to it.name }, who, { who = it })
        Select(FeedKind.entries.map { it to it.label }, kind, { kind = it })
      }
      Divider()
      if (events.isEmpty()) {
        Txt("Nothing has happened yet.", color = p.muted, modifier = Modifier.padding(16.dp))
      } else {
        Column(
          (if (fillHeight) Modifier.weight(1f) else Modifier.heightIn(max = if (position == FeedPosition.BELOW) 520.dp else 640.dp))
            .verticalScroll(rememberScrollState()).padding(vertical = 4.dp)
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
    Txt(Platform.clock(e.ts), size = 11.5.sp, color = p.muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
  }
}

@Composable
private fun FeedLine(e: FeedEvent) {
  val p = LocalPalette.current
  val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
  val system = SpanStyle(color = p.muted, fontStyle = FontStyle.Italic)
  val item = SpanStyle(fontWeight = FontWeight.SemiBold, color = p.item(e.flags))
  Txt(buildAnnotatedString {
    when (e.type) {
      EventType.ITEM -> {
        withStyle(bold) { append(e.senderName ?: "") }
        if (e.sender == e.receiver) {
          append(" found their ")
          withStyle(item) { append(e.itemName ?: "") }
        } else {
          append(" sent ")
          withStyle(item) { append(e.itemName ?: "") }
          append(" to ")
          withStyle(bold) { append(e.receiverName ?: "") }
        }
      }

      EventType.CHEAT -> {
        withStyle(SpanStyle(color = p.trap, fontWeight = FontWeight.SemiBold)) { append("Cheat: ") }
        append(e.message ?: "")
      }

      EventType.UNLOCK -> {
        withStyle(bold) { append(e.senderName ?: "") }
        append(" found the key: ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = p.key)) { append(e.playerName ?: "a slot") }
        append(" is unlocked!")
      }

      EventType.HINT -> {
        withStyle(system) { append("Hint: ") }
        withStyle(bold) { append(e.receiverName ?: "") }
        append("'s ")
        withStyle(item) { append(e.itemName ?: "") }
        append(" is at ${e.locationName} in ")
        withStyle(bold) { append(e.finderName ?: "") }
        if (e.found) withStyle(SpanStyle(color = p.ok, fontWeight = FontWeight.SemiBold)) { append(" (found)") }
      }

      EventType.GOAL -> withStyle(SpanStyle(color = p.ok, fontWeight = FontWeight.Bold)) {
        append("${e.playerName} completed their goal!")
      }

      EventType.RELEASE -> { withStyle(bold) { append(e.playerName ?: "") }; withStyle(system) { append(" released their remaining items") } }
      EventType.COLLECT -> { withStyle(bold) { append(e.playerName ?: "") }; withStyle(system) { append(" collected their items") } }
      EventType.JOIN -> { withStyle(bold) { append(e.playerName ?: "") }; withStyle(system) { append(" joined") } }
      EventType.PART -> { withStyle(bold) { append(e.playerName ?: "") }; withStyle(system) { append(" left") } }
      EventType.CHAT -> { withStyle(bold) { append(e.playerName ?: "") }; append(": ${e.message ?: ""}") }
      EventType.COUNTDOWN -> withStyle(SpanStyle(color = p.ok, fontWeight = FontWeight.Bold)) { append(e.message ?: "") }
    }
    // An item a client took with !getitem: Archipelago marks it as a cheat
    if (e.cheat && e.type != EventType.CHEAT) withStyle(SpanStyle(color = p.trap, fontWeight = FontWeight.SemiBold)) { append(" · cheat") }
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

private fun hintModeLabel(s: Summary): String {
  val h = s.hintSettings
  val points = when {
    !h.pointHints -> null
    h.costPercent == 0 -> "free hints"
    else -> "hints ${h.costPercent}%"
  }
  return when {
    points != null && h.autoHints -> "$points + auto hints"
    points != null -> points
    h.autoHints -> "auto hints only"
    else -> "no hints"
  }
}
