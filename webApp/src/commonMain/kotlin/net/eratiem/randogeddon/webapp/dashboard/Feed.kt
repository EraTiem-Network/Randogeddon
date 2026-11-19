package net.eratiem.randogeddon.webapp.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.eratiem.randogeddon.shared.EventType
import net.eratiem.randogeddon.shared.FeedEvent
import net.eratiem.randogeddon.shared.ItemFlags
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.i18n.FeedTexts
import net.eratiem.randogeddon.shared.i18n.Texts
import net.eratiem.randogeddon.shared.i18n.number
import net.eratiem.randogeddon.webapp.app.LocalTexts
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.appendTemplate
import net.eratiem.randogeddon.webapp.app.clock
import net.eratiem.randogeddon.webapp.app.fill
import net.eratiem.randogeddon.webapp.ui.Divider
import net.eratiem.randogeddon.webapp.ui.Dot
import net.eratiem.randogeddon.webapp.ui.DrawnIcon
import net.eratiem.randogeddon.webapp.ui.FilterField
import net.eratiem.randogeddon.webapp.ui.Icon
import net.eratiem.randogeddon.webapp.ui.IconButton
import net.eratiem.randogeddon.webapp.ui.LocalPalette
import net.eratiem.randogeddon.webapp.ui.Section
import net.eratiem.randogeddon.webapp.ui.Select
import net.eratiem.randogeddon.webapp.ui.Tip
import net.eratiem.randogeddon.webapp.ui.Txt
import net.eratiem.randogeddon.webapp.ui.card
import net.eratiem.randogeddon.webapp.ui.scrolling

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
internal val FEED_WIDTH = 440.dp

enum class FeedPosition { RIGHT, LEFT, BELOW }

internal fun FeedPosition.label(t: Texts): String = when (this) {
  FeedPosition.RIGHT -> t.feed.placeRight
  FeedPosition.LEFT -> t.feed.placeLeft
  FeedPosition.BELOW -> t.feed.placeBelow
}
internal val WIDE = 1100.dp
internal val GAP = 16.dp
/** *Your connection* beside the tiles: with the bridge open beside the rest, or one column (folded, or not playing). */
internal val CONNECTION_WIDE = 720.dp
internal val CONNECTION_NARROW = 420.dp
/** The tiles' room beside *Your connection*: three of them (at least 160 dp each) in a row; with less, it goes above. */
internal val STATS_BESIDE = 160.dp * 3 + 12.dp * 2
/** The side feed is never shorter than this, even next to a single small person card. */
internal val MIN_SIDE_HEIGHT = 420.dp

/** How many of the feed's events show at once; the arrows under them go to newer and older ones. */
private const val FEED_PAGE = 50

/** Whether [q] is in any of the event's names or texts. */
private fun FeedEvent.says(q: String): Boolean = listOfNotNull(
  playerName, senderName, receiverName, finderName, itemName, locationName, message, cheatBy, cheatSlot, cheatValue,
  link, linkSource,
).any { it.contains(q, ignoreCase = true) }

private enum class FeedKind {
  ALL, PROGRESSION, ITEMS, KEYS, HINTS, LINKS, OTHER;

  fun label(f: FeedTexts): String = when (this) {
    ALL -> f.allEvents
    PROGRESSION -> f.progression
    ITEMS -> f.items
    KEYS -> f.keysAndGoals
    HINTS -> f.hints
    LINKS -> f.links
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
  var query by remember { mutableStateOf("") }
  val q = query.trim()
  // The id of the event on top; null: the newest (new ones come in on top). On another page the events stay put
  var top by remember { mutableStateOf<Long?>(null) }
  val events = s.events.asReversed().filter { e ->
    (who == null || who in listOfNotNull(e.slot, e.sender, e.receiver, e.finder)) && (q.isEmpty() || e.says(q)) &&
      when (kind) {
        FeedKind.ALL -> true
        FeedKind.PROGRESSION -> e.type == EventType.ITEM && e.flags and ItemFlags.PROGRESSION != 0
        FeedKind.ITEMS -> e.type == EventType.ITEM
        FeedKind.KEYS -> e.type in setOf(EventType.UNLOCK, EventType.GOAL, EventType.RELEASE, EventType.COLLECT)
        FeedKind.HINTS -> e.type == EventType.HINT
        FeedKind.LINKS -> e.type == EventType.LINK
        FeedKind.OTHER -> e.type in setOf(EventType.CHAT, EventType.JOIN, EventType.PART, EventType.COUNTDOWN, EventType.CHEAT)
      }
  }
  val topIndex = top?.let { id -> events.indexOfFirst { it.id <= id }.coerceAtLeast(0) } ?: 0
  val page = events.drop(topIndex).take(FEED_PAGE)
  val scroll = rememberScrollState()
  // A page turned starts at its top
  LaunchedEffect(top) { scroll.scrollTo(0) }
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
      Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Select(listOf<Pair<Int?, String>>(null to t.feed.everyone) + s.players.map { it.slot to it.name }, who, { who = it; top = null })
          Select(FeedKind.entries.map { it to it.label(t.feed) }, kind, { kind = it; top = null })
        }
        FilterField(query, { query = it.take(100); top = null }, t.common.search, Modifier.fillMaxWidth(), clearable = true)
      }
      Divider()
      if (events.isEmpty()) {
        val all = who == null && kind == FeedKind.ALL && q.isEmpty()
        Txt(if (all) t.feed.nothingYet else t.feed.nothingFound, color = p.muted, modifier = Modifier.padding(16.dp))
      } else {
        Column(
          (if (fillHeight) Modifier.weight(1f) else Modifier.heightIn(max = if (position == FeedPosition.BELOW) 520.dp else 640.dp))
            .scrolling(scroll).padding(vertical = 4.dp)
        ) {
          page.forEach { key(it.id) { FeedRow(it) } }
        }
      }
      // Which events these are, counted from the newest, and the pages above (newer) and below (older)
      if (events.size > FEED_PAGE) {
        Divider()
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Txt(
            t.feed.eventsOf(t.number(topIndex + 1), t.number(topIndex + page.size), t.number(events.size)), size = 12.5.sp,
            color = p.muted, modifier = Modifier.weight(1f),
          )
          if (topIndex > 0) Tip(t.feed.newer) {
            IconButton(
              { top = (topIndex - FEED_PAGE).let { if (it <= 0) null else events[it].id } },
              Icon.CHEVRON, Modifier.size(30.dp).rotate(180f),
            )
          }
          if (topIndex + FEED_PAGE < events.size) Tip(t.feed.older) {
            IconButton({ top = events[topIndex + FEED_PAGE].id }, Icon.CHEVRON, Modifier.size(30.dp))
          }
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
    EventType.CHEAT, EventType.LINK -> p.trap to null
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

      EventType.LINK -> {
        withStyle(SpanStyle(color = p.trap, fontWeight = FontWeight.SemiBold)) { append("${e.link.orEmpty()}: ") }
        // Who it came from: their slot if their client names it, else what the client says
        val from = (e.playerName ?: e.linkSource ?: f.someone) to bold
        val trap = e.message
        when {
          e.link != "TrapLink" -> appendTemplate(f.died, mapOf("player" to from))
          trap.isNullOrEmpty() -> appendTemplate(f.trappedSome, mapOf("player" to from))
          else -> appendTemplate(f.trapped, mapOf("player" to from, "item" to (trap to SpanStyle(fontWeight = FontWeight.SemiBold, color = p.trap))))
        }
      }
    }
    // An item a client took with !getitem: Archipelago marks it as a cheat
    if (e.cheat && e.type != EventType.CHEAT) withStyle(SpanStyle(color = p.trap, fontWeight = FontWeight.SemiBold)) { append(f.cheatMark) }
  }, size = 13.5.sp)
  // A death's cause, in its game's words
  val cause = e.message
  if (e.type == EventType.LINK && e.link == "DeathLink" && !cause.isNullOrEmpty()) Txt(cause, size = 11.5.sp, color = p.muted)
  val location = e.locationName
  if ((e.type == EventType.ITEM || e.type == EventType.UNLOCK) && !location.isNullOrEmpty()) {
    Txt(location, size = 11.5.sp, color = p.muted)
  }
}
