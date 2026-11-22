package net.eratiem.randogeddon.server.state

import kotlin.math.round
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.eratiem.randogeddon.database.SessionEventStore
import net.eratiem.randogeddon.server.session.Room
import net.eratiem.randogeddon.server.util.array
import net.eratiem.randogeddon.server.util.bool
import net.eratiem.randogeddon.server.util.int
import net.eratiem.randogeddon.server.util.long
import net.eratiem.randogeddon.server.util.longs
import net.eratiem.randogeddon.server.util.obj
import net.eratiem.randogeddon.server.util.string
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AvailableAnswer
import net.eratiem.randogeddon.shared.AvailableSlot
import net.eratiem.randogeddon.shared.CheatAction
import net.eratiem.randogeddon.shared.CheatNames
import net.eratiem.randogeddon.shared.ConnectionSession
import net.eratiem.randogeddon.shared.EventType
import net.eratiem.randogeddon.shared.FeedEvent
import net.eratiem.randogeddon.shared.HintSettings
import net.eratiem.randogeddon.shared.HintView
import net.eratiem.randogeddon.shared.ItemFlags
import net.eratiem.randogeddon.shared.LockInfo
import net.eratiem.randogeddon.shared.MapRegionView
import net.eratiem.randogeddon.shared.MapView
import net.eratiem.randogeddon.shared.PersonSummary
import net.eratiem.randogeddon.shared.PlayerDetail
import net.eratiem.randogeddon.shared.PlayerStatus
import net.eratiem.randogeddon.shared.PlayerSummary
import net.eratiem.randogeddon.shared.ReceivedItemView
import net.eratiem.randogeddon.shared.SentItemView
import net.eratiem.randogeddon.shared.Summary
import net.eratiem.randogeddon.shared.Totals

/** Tags of clients that don't play: trackers (including this one), text clients and hint games. */
private val NON_GAME_TAGS = setOf("Tracker", "TextOnly", "HintGame")

/**
 * A `Part` message of a client that doesn't play: MultiServer's text for it ends with the client's tags
 * (`… has stopped tracking the game. Client(0.6.8), ['Tracker'].`). One without a text counts as a game's.
 */
private fun nonGameLeft(packet: JsonObject): Boolean {
  val text = (packet["data"] as? JsonArray).orEmpty()
    .joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }
  val tags = text.substringAfterLast("Client(", "")
  return NON_GAME_TAGS.any { "'$it'" in tags }
}
const val MAX_EVENTS = 500

/** The links between games the feed shows: the tracker listens for their `Bounced` packets by these tags. */
val LINK_TAGS = listOf("DeathLink", "TrapLink")

private data class Group(val name: String, val game: String)

private data class HintKey(val findingPlayer: Int, val location: Long)

private data class Hint(
  val receivingPlayer: Int,
  val findingPlayer: Int,
  val location: Long,
  val item: Long,
  val found: Boolean,
  val entrance: String,
  val itemFlags: Int,
  val status: Int,
)

/**
 * What the tracker kept of a seed in its history file until 0.19.10 (the feed, the last activity, the connection
 * logs): read once more as the baseline its events are replayed onto.
 */
@Serializable
private data class SavedState(
  val seedName: String?,
  val eventSeq: Long,
  val events: List<FeedEvent>,
  val lastActivity: Map<Int, Long>,
  val sessions: Map<Int, List<ConnectionSession>> = emptyMap(),
  val firstCheckAt: Map<Int, Long> = emptyMap(),
  val goalAt: Map<Int, Long> = emptyMap(),
  /** Open sessions end here at the latest: the tracker can't know how long they went on after it stopped. */
  val savedAt: Long? = null,
  /** Up to which of the gateway's events of the game server (by their IDs) this was heard. */
  val serverCursor: Long = 0,
)

/** A cheat's feed line as it is kept among the game server's events (written by the tracker, kind `cheat`). */
@Serializable
data class CheatLine(
  val message: String,
  val action: CheatAction? = null,
  val by: String? = null,
  val role: String? = null,
  val slot: String? = null,
  val value: String? = null,
)

/**
 * The aggregated session, fed with the packets of the Archipelago network protocol.
 *
 * Pure logic apart from the JSON persistence, so it can be tested with recorded packets. Not thread-safe: the caller
 * serializes access (see [net.eratiem.randogeddon.server.ap.TrackerService]).
 */
class SessionState(private val clock: () -> Long = System::currentTimeMillis) {
  /** Increases with every change; the web layer pushes a new summary when it changes. */
  var version: Long = 0
    private set
  var connected: Boolean = false
    set(value) {
      field = value
      touch()
    }
  val startedAt: Long = clock()

  var seedName: String? = null
    private set
  private var serverVersion: String? = null
  private var generatorVersion: String? = null
  private var hintCost: Int? = null
  private var checkPoints: Int = 1

  val players: MutableMap<Int, Player> = sortedMapOf()
  private val groups = mutableMapOf<Int, Group>()
  val events: ArrayDeque<FeedEvent> = ArrayDeque()
  private val hints = linkedMapOf<HintKey, Hint>()
  private val itemNames = mutableMapOf<String, Map<Long, String>>()
  private val locationNames = mutableMapOf<String, Map<Long, String>>()
  private var eventSeq = 0L

  /**
   * What lies at every location, from scouting each slot's own ones (`LocationScouts`): (the slot, its location) ->
   * (the slot it is meant for, its flags). Only counted, never shown; [scouted]: the slots done.
   */
  private val placements = mutableMapOf<Pair<Int, Long>, Pair<Int, Int>>()
  private val scouted = mutableSetOf<Int>()

  /** Deaths passed on by DeathLink and traps by TrapLink, over every event of the session. */
  private var deaths = 0
  private var trapLinks = 0

  /** Up to which of the gateway's events of the game server (their IDs in the database) this state has heard. */
  var serverCursor = 0L
    private set
  private var saved: SavedState? = null

  /** Key item name -> slot name of the world it unlocks, from the World Locks slot data. */
  private val locks = mutableMapOf<String, String>()
  /** The locked slots whose key's location the server checked (an *Unlock* cheat), by name: their locks say so. */
  private val unlockedByServer = mutableSetOf<String>()

  private fun reset(seed: String?) {
    seedName = seed
    serverVersion = null
    generatorVersion = null
    hintCost = null
    players.clear()
    groups.clear()
    events.clear()
    hints.clear()
    itemNames.clear()
    locationNames.clear()
    locks.clear()
    eventSeq = 0
    deaths = 0
    trapLinks = 0
    placements.clear()
    scouted.clear()
  }

  private fun touch() {
    version++
  }

  /** Forgets the session (it was deleted in the admin panel): the dashboard is empty until the next seed. */
  fun clear() {
    reset(null)
    connected = false
    touch()
  }

  /**
   * The seed and its slots from the game server's `room.json`, before any connection: the events are replayed onto
   * them, and the first connection's `RoomInfo` finds the same seed (and keeps what is here). Another seed than the
   * one known: nothing.
   */
  fun prepare(room: Room) {
    if (room.seedName == null || (seedName != null && seedName != room.seedName)) return
    seedName = room.seedName
    for (slot in room.slots) when (slot.type) {
      SlotType.GROUP -> groups.getOrPut(slot.slot) { Group(slot.name, slot.game) }
      SlotType.PLAYER -> players.getOrPut(slot.slot) { Player(slot.slot, slot.name, slot.game) }
    }
    touch()
  }

  // ------------------------------------------------------------------------------------------------------- packets

  /** @return whether this is another seed than before; the state was reset then. */
  fun onRoomInfo(packet: JsonObject): Boolean {
    val seed = packet.string("seed_name")
    val newSeed = seed != seedName
    if (newSeed) reset(seed)
    serverVersion = packet.obj("version")?.let(::formatVersion)
    generatorVersion = packet.obj("generator_version")?.let(::formatVersion)
    hintCost = packet.int("hint_cost")
    checkPoints = packet.int("location_check_points") ?: 1
    touch()
    return newSeed
  }

  fun onConnected(slot: Int, packet: JsonObject) {
    val aliases = packet.array("players").orEmpty().associate { it.jsonObject.int("slot")!! to it.jsonObject }
    packet.obj("slot_info")?.forEach { (key, value) ->
      val s = key.toInt()
      val (name, game, type) = slotFields(value)
      when (type) {
        SlotType.GROUP -> groups[s] = Group(name, game)
        SlotType.PLAYER -> {
          val player = players.getOrPut(s) { Player(s, name, game) }
          player.alias = aliases[s]?.string("alias") ?: name
        }
      }
    }
    players[slot]?.let { player ->
      player.checked = packet.longs("checked_locations").toMutableSet()
      player.missing = packet.longs("missing_locations").toMutableSet()
      player.hintPoints = packet.int("hint_points")
    }
    touch()
  }

  fun onReceivedItems(slot: Int, packet: JsonObject) {
    val player = players[slot] ?: return
    val index = packet.int("index") ?: 0
    val items = packet.array("items").orEmpty().map(::networkItem)
    when {
      index == 0 -> player.items = items.toMutableList() // full resync
      index <= player.items.size -> player.items = (player.items.take(index) + items).toMutableList()
      else -> player.items.addAll(items) // missed some; the next connect resyncs
    }
    touch()
  }

  /** What lies at [slot]'s own locations (`LocationInfo`, the answer to its scouting). */
  fun onLocationInfo(slot: Int, packet: JsonObject) {
    packet.array("locations").orEmpty().map(::networkItem).forEach { placements[slot to it.location] = it.player to it.flags }
    scouted += slot
    touch()
  }

  /** [slot] has no locations to scout. */
  fun onNothingToScout(slot: Int) {
    scouted += slot
    touch()
  }

  /** [p]'s own progression locations, the checked ones, and of those the ones meant for others; `null` until scouted. */
  private data class ProgressionChecks(val locations: Int, val checked: Int, val sent: Int)

  private fun progressionChecks(p: Player): ProgressionChecks? {
    if (p.slot !in scouted) return null
    val own = placements.filter { (key, value) -> key.first == p.slot && value.second and ItemFlags.PROGRESSION != 0 }
    val checked = own.filterKeys { it.second in p.checked }
    return ProgressionChecks(own.size, checked.size, checked.count { (_, value) -> value.first != p.slot })
  }

  /**
   * The slots to ask the session's logic about: those whose items or checks changed since it was last asked about
   * them, with what they received (without the starting inventory: the logic has it) and checked.
   */
  fun availableAsks(): List<AvailableSlot> {
    val lockSlots = lockSlots()
    return players.values.filter { it.slot !in lockSlots && it.game != WORLD_LOCKS_GAME }.mapNotNull { p ->
      val received = p.items.filter { it.location != STARTING_INVENTORY }.map { it.item }
      AvailableSlot(p.slot, received, p.checked.toList()).takeIf { p.availableAsked != received.size to p.checked.size }
    }
  }

  /** The slots whose map the logic wasn't asked for since it was built (a game it has none for isn't asked again). */
  fun mapsWanted(): List<Int> {
    val lockSlots = lockSlots()
    return players.values
      .filter { it.slot !in lockSlots && it.game != WORLD_LOCKS_GAME && it.map == null && !it.mapAsked }
      .map { it.slot }
  }

  /**
   * The logic's [answer] to [asked]: each slot's available checks (`null`: it has no answer for that slot), and the
   * maps of the slots in [maps].
   */
  fun onAvailable(asked: List<AvailableSlot>, answer: AvailableAnswer, maps: List<Int> = emptyList()) {
    asked.forEach { a ->
      val p = players[a.slot] ?: return@forEach
      p.available = answer.available[a.slot]
      answer.locations[a.slot]?.let { p.availableLocations = it.toSet() }
      p.availableAsked = a.items.size to a.checked.size
    }
    maps.forEach { slot -> players[slot]?.mapAsked = true }
    answer.maps.forEach { (slot, map) -> players[slot]?.map = map }
    touch()
  }

  /** The logic was built again: every slot is asked anew (its map too, kept until then). */
  fun forgetAvailableAsks() = players.values.forEach { it.availableAsked = null; it.mapAsked = false }

  /** Progression items [p] received from a location (not the starting inventory, not a cheat's). */
  private fun progressionReceived(p: Player): Int = p.items.count { it.location >= 0 && it.flags and ItemFlags.PROGRESSION != 0 }

  fun onRoomUpdate(slot: Int, packet: JsonObject) {
    players[slot]?.let { player ->
      val new = packet.longs("checked_locations")
      player.checked.addAll(new)
      player.missing.removeAll(new.toSet())
      if ("hint_points" in packet) player.hintPoints = packet.int("hint_points")
    }
    packet.array("players")?.forEach { p ->
      val o = p.jsonObject
      o.int("slot")?.let { players[it] }?.let { it.alias = o.string("alias") ?: it.name }
    }
    touch()
  }

  /**
   * A data storage value from `Retrieved` or `SetReply`. Who is connected comes from the joins and leaves the gateway
   * hears (every one since 0.19.9), not from a status change here.
   */
  fun onSetReply(key: String, value: JsonElement?) {
    when {
      key.startsWith("_read_client_status_") -> {
        val player = players[key.substringAfterLast('_').toInt()]
        val status = (value as? JsonPrimitive)?.intOrNull
        if (player != null && status != null) player.status = status
      }

      key.startsWith("_read_slot_data_") -> (value as? JsonObject)?.obj("locks")?.forEach { (item, world) ->
        locks[item] = (world as JsonPrimitive).content
      }

      key.startsWith("_read_hints_") -> (value as? JsonArray)?.forEach { element ->
        val h = element.jsonObject
        val hint = Hint(
          receivingPlayer = h.int("receiving_player")!!,
          findingPlayer = h.int("finding_player")!!,
          location = h.long("location")!!,
          item = h.long("item")!!,
          found = h.bool("found") ?: false,
          entrance = h.string("entrance") ?: "",
          itemFlags = h.int("item_flags") ?: 0,
          status = h.int("status") ?: 0,
        )
        // Every hint is sent to both players it concerns; one entry per location
        hints[HintKey(hint.findingPlayer, hint.location)] = hint
      }
    }
    touch()
  }

  fun onDataPackage(packet: JsonObject) {
    packet.obj("data")?.obj("games")?.forEach { (game, data) ->
      val o = data.jsonObject
      itemNames[game] = o.obj("item_name_to_id").orEmpty().entries.associate { (k, v) -> v.jsonPrimitive.long to k }
      locationNames[game] =
        o.obj("location_name_to_id").orEmpty().entries.associate { (k, v) -> v.jsonPrimitive.long to k }
    }
    touch()
  }

  /**
   * One of the session's events as they are kept ([id]: its place in their order, [at]: when, [kind] and its JSON
   * [payload]): a packet of the game server for the feed and the slots' presence, the game server's start or end
   * (after which nobody is connected), or a cheat's feed line. Replayed in their order, they bring back everything
   * of it; one already taken ([serverCursor]) isn't taken twice.
   */
  fun onServerEvent(id: Long, at: Long, kind: String, payload: String) {
    if (id <= serverCursor) return
    when (kind) {
      SessionEventStore.START, SessionEventStore.STOP -> players.values.forEach {
        // A new start: whoever was connected before isn't; an end: nobody is
        it.online = 0
        it.goOffline(at, cut = kind == SessionEventStore.START)
      }
      SessionEventStore.PACKET -> json(payload)?.let { packet ->
        when (packet.string("cmd")) {
          "PrintJSON" -> onPrintJson(packet, at)
          "Bounced" -> onBounced(packet, at)
        }
      }
      SessionEventStore.CHEAT -> runCatching { ApiJson.decodeFromString(CheatLine.serializer(), payload) }.getOrNull()?.let {
        onCheat(it.message, it.action, it.by, it.role, it.slot, it.value, at)
      }
    }
    serverCursor = id
    touch()
  }

  private fun json(text: String): JsonObject? =
    runCatching { ApiJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

  /** The tracker's own connection is gone; who plays is known from the gateway's events, not from it. */
  fun onTrackerDisconnected() {
    connected = false
  }

  /** Nobody is known to be online after the connection to the server was lost. */
  fun onConnectionLost(now: Long = clock()) {
    players.values.forEach {
      it.online = 0
      it.goOffline(now, cut = true)
    }
    connected = false
  }

  /** A world shows signs of life although no game client was seen joining (the tracker restarted meanwhile). */
  private fun seenPlaying(player: Player, now: Long) {
    if (player.online == 0) {
      player.online = 1
      player.goOnline(now, implicit = true)
    }
  }

  fun onPrintJson(packet: JsonObject, now: Long = clock()) {
    val event: FeedEvent? = when (val type = packet.string("type")) {
      "ItemSend", "ItemCheat" -> {
        val item = networkItem(packet["item"]!!)
        val receiver = packet.int("receiving")!!
        if (type == "ItemSend") {
          players[item.player]?.let { sender ->
            sender.lastActivity = now
            sender.lastCheckAt = now
            if (sender.firstCheckAt == null) sender.firstCheckAt = now
            // Only an own item proves the world is being played: collect and release send items for others
            if (item.player == receiver) seenPlaying(sender, now)
            // Progress is up to date even before the sender's RoomUpdate arrives
            sender.checked.add(item.location)
            sender.missing.remove(item.location)
          }
        }
        event(
          now, if (receiver in lockSlots()) EventType.UNLOCK else EventType.ITEM, sender = item.player,
          receiver = receiver, item = item.item, location = item.location, flags = item.flags,
          cheat = type == "ItemCheat",
        )
      }

      "Hint" -> {
        val item = networkItem(packet["item"]!!)
        event(
          now, EventType.HINT, finder = item.player, receiver = packet.int("receiving"), item = item.item,
          location = item.location, flags = item.flags, found = packet.bool("found") ?: false,
        )
      }

      "Goal" -> {
        val slot = packet.int("slot")!!
        players[slot]?.let {
          it.status = ClientStatus.GOAL
          it.lastActivity = now
          if (it.goalAt == null) it.goalAt = now
        }
        event(now, EventType.GOAL, slot = slot)
      }

      "Release", "Collect" -> {
        val slot = packet.int("slot")!!
        if (type == "Release") players[slot]?.let { if (it.releasedAt == null) it.releasedAt = now }
        event(now, if (type == "Release") EventType.RELEASE else EventType.COLLECT, slot = slot)
      }

      "Join" -> {
        val slot = packet.int("slot")!!
        val tags = packet.array("tags").orEmpty().map { it.jsonPrimitive.content }.toSet()
        if (tags.any { it in NON_GAME_TAGS }) return // trackers and text clients (including us) are noise
        players[slot]?.let {
          // Seen playing before this Join: that guessed client is this one, the session goes on
          if (it.openSession?.implicit == true && it.online == 1) it.online = 0
          if (it.online == 0) it.goOnline(now, implicit = false)
          it.online++
          it.lastActivity = now
        }
        event(now, EventType.JOIN, slot = slot)
      }

      "Part" -> {
        // Part carries no tags, but its text ends with the client's: a tracker or text client leaving (ours too) isn't
        // a game leaving. And only count down what was counted up
        if (nonGameLeft(packet)) return
        val slot = packet.int("slot")!!
        val player = players[slot]?.takeIf { it.online > 0 } ?: return
        player.online--
        if (player.online == 0) player.goOffline(now)
        event(now, EventType.PART, slot = slot)
      }

      "Chat" -> {
        packet.int("slot")?.let { players[it] }?.let { seenPlaying(it, now) }
        event(now, EventType.CHAT, slot = packet.int("slot"), message = packet.string("message") ?: "")
      }
      "ServerChat" -> event(now, EventType.CHAT, slot = 0, message = packet.string("message") ?: "")
      "Countdown" -> if (packet.int("countdown") == 0) event(now, EventType.COUNTDOWN, message = "GO!") else null
      else -> return
    }
    event?.let {
      events.addLast(it)
      while (events.size > MAX_EVENTS) events.removeFirst()
    }
    touch()
  }

  /**
   * A link between games (a `Bounced` packet with one of [LINK_TAGS]): a death or a trap passed on to every game
   * linked. Its texts are the sending client's own: cut, and without control characters.
   */
  fun onBounced(packet: JsonObject, now: Long = clock()) {
    val tags = packet.array("tags").orEmpty().mapNotNull { tag -> (tag as? JsonPrimitive)?.takeIf { it.isString }?.content }
    val link = LINK_TAGS.firstOrNull { it in tags } ?: return
    val data = packet["data"] as? JsonObject ?: return
    fun text(key: String, max: Int): String? = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
      ?.filterNot { it.isISOControl() }?.trim()?.take(max)?.ifEmpty { null }
    val source = text("source", 80)
    val slot = source?.let { name -> players.values.firstOrNull { it.alias == name || it.name == name }?.slot }
    val detail = if (link == "DeathLink") text("cause", 200) else text("trap_name", 80)
    if (link == "DeathLink") deaths++ else trapLinks++
    slot?.let { players[it] }?.let { if (link == "DeathLink") it.deaths++ else it.trapLinks++ }
    events.addLast(event(now, EventType.LINK, slot = slot, message = detail).copy(link = link, linkSource = source))
    while (events.size > MAX_EVENTS) events.removeFirst()
    touch()
  }

  /** An admin cheated from the admin panel: a feed line saying who and what ([message], e.g. "Alice gave …"). */
  fun onCheat(
    message: String,
    action: CheatAction? = null,
    by: String? = null,
    role: String? = null,
    slot: String? = null,
    value: String? = null,
    now: Long = clock(),
  ) {
    events.addLast(
      event(now, EventType.CHEAT, message = message, cheat = true).copy(
        cheatAction = action, cheatBy = by, cheatRole = role, cheatSlot = slot, cheatValue = value,
      )
    )
    while (events.size > MAX_EVENTS) events.removeFirst()
    if (slot != null && (action == CheatAction.UNLOCK || action == CheatAction.KEY_LOCATION)) unlockedByServer += slot
    touch()
  }

  private fun event(
    ts: Long, type: EventType, slot: Int? = null, sender: Int? = null, receiver: Int? = null, finder: Int? = null,
    item: Long? = null, location: Long? = null, flags: Int = 0, found: Boolean = false, cheat: Boolean = false,
    message: String? = null,
  ) = FeedEvent(
    id = ++eventSeq, ts = ts, type = type, slot = slot, sender = sender, receiver = receiver, finder = finder,
    item = item, location = location, flags = flags, found = found, cheat = cheat, message = message,
  )

  // -------------------------------------------------------------------------------------------------------- names

  /**
   * Every slot (the World Locks slot aside) has reached its goal or was released: the session can finish. `false`
   * while no slot is known yet.
   */
  fun allDone(): Boolean {
    val slots = players.values.filter { it.game != WORLD_LOCKS_GAME }
    return slots.isNotEmpty() && slots.all { it.status == ClientStatus.GOAL || it.goalAt != null || it.releasedAt != null }
  }

  /** The slot names an admin can cheat for (not the World Locks slot). */
  fun cheatSlots(): List<String> = players.values.filter { it.game != WORLD_LOCKS_GAME && !it.complete }.map { it.name }

  /** Every slot has checked all its locations: nothing is left to cheat. */
  fun everyCheckDone(): Boolean = players.values.filter { it.game != WORLD_LOCKS_GAME }.let { slots ->
    slots.isNotEmpty() && slots.all { it.complete }
  }


  /** For cheats: every item of the slot's game, and the slot's locations not checked yet; `null` for an unknown slot. */
  fun cheatNames(slotName: String): CheatNames? {
    val p = players.values.firstOrNull { it.name == slotName && it.game != WORLD_LOCKS_GAME } ?: return null
    return CheatNames(itemNames[p.game]?.values?.sorted().orEmpty(), p.missing.map { locationName(it, p.slot) }.sorted())
  }

  /** Each world's locations not checked yet, by its name (World Locks' slots left out). */
  fun openLocations(): Map<String, Set<String>> = players.values.filter { it.game != WORLD_LOCKS_GAME }
    .associate { p -> p.name to p.missing.mapTo(mutableSetOf()) { locationName(it, p.slot) } }

  /** Each world's hinted locations, by its name: where a hint says an item is, found or not. */
  fun hintedLocations(): Map<String, Set<String>> = hints.keys
    .groupBy({ players[it.findingPlayer]?.name }, { locationName(it.location, it.findingPlayer) })
    .mapNotNull { (world, locations) -> world?.let { it to locations.toSet() } }.toMap()

  /** Each world's checked locations, by its name (World Locks' slots left out). */
  fun checkedLocations(): Map<String, Set<String>> = players.values.filter { it.game != WORLD_LOCKS_GAME }
    .associate { p -> p.name to p.checked.mapTo(mutableSetOf()) { locationName(it, p.slot) } }

  /**
   * The slots still locked, for the lock cheats: each one's name, its key, and the World Locks slot that gets the key
   * (by their names).
   */
  fun lockedSlots(): List<Triple<String, String, String>> {
    val lockSlot = lockSlots().firstNotNullOfOrNull { players[it]?.name } ?: return emptyList()
    return players.values.filter { it.game != WORLD_LOCKS_GAME }.sortedBy { it.slot }.mapNotNull { p ->
      lockOf(p.slot)?.takeIf { !it.unlocked }?.let { Triple(p.name, it.key, lockSlot) }
    }
  }

  /** Slots of the World Locks game: they hold keys, they are not worlds anyone plays. */
  fun lockSlots(): Set<Int> = players.values.filter { it.game == WORLD_LOCKS_GAME }.mapTo(mutableSetOf()) { it.slot }

  /** Slot of the world that [keyName] unlocks. */
  private fun lockedSlot(keyName: String): Int? = locks[keyName]?.let { name -> players.values.firstOrNull { it.name == name }?.slot }

  /** The lock of [slot], if it starts locked: the key and whether, by whom and where it was found. */
  private fun lockOf(slot: Int): LockInfo? {
    val name = players[slot]?.name ?: return null
    val key = locks.entries.firstOrNull { it.value == name }?.key ?: return null
    for (lockSlot in lockSlots()) {
      val received = players[lockSlot]?.items?.firstOrNull { itemName(it.item, lockSlot) == key }
      if (received != null) {
        val found = events.lastOrNull { it.type == EventType.UNLOCK && it.item == received.item && it.receiver == lockSlot }
        return LockInfo(key, unlocked = true, foundBy = playerName(received.player), foundAt = found?.ts, byServer = name in unlockedByServer)
      }
    }
    val hint = hints.values.firstOrNull { it.receivingPlayer in lockSlots() && itemName(it.item, it.receivingPlayer) == key }
    val location = hint?.let { locationName(it.location, it.findingPlayer) }
    val slot = hint?.let { playerName(it.findingPlayer) }
    return LockInfo(
      key, unlocked = false, hintedAt = hint?.let { "$location (in $slot)" }, hintLocation = location, hintSlot = slot,
    )
  }

  /** Keys are shown as "Key to <world>" instead of the apworld's generic "World Key N". */
  fun displayItemName(item: Long, ownerSlot: Int): String {
    val name = itemName(item, ownerSlot)
    if (ownerSlot !in lockSlots()) return name
    return lockedSlot(name)?.let { "Key to ${playerName(it)}" } ?: name
  }

  fun playerName(slot: Int): String = when (slot) {
    0 -> "Server"
    else -> players[slot]?.alias ?: groups[slot]?.name ?: "Player $slot"
  }

  private fun gameOf(slot: Int): String? = players[slot]?.game ?: groups[slot]?.game

  /** Items and locations are named in the game of their owner, or in the generic "Archipelago" game. */
  private fun lookup(names: Map<String, Map<Long, String>>, id: Long, ownerSlot: Int): String? =
    listOfNotNull(gameOf(ownerSlot), "Archipelago").firstNotNullOfOrNull { names[it]?.get(id) }

  fun itemName(item: Long, ownerSlot: Int): String = lookup(itemNames, item, ownerSlot) ?: "Item $item"

  fun locationName(location: Long, ownerSlot: Int): String =
    lookup(locationNames, location, ownerSlot) ?: "Location $location"

  // ------------------------------------------------------------------------------------------------------- output

  fun summary(
    title: String,
    error: String?,
    people: Map<String, List<String>> = emptyMap(),
    pointHints: Boolean = true,
    autoHints: Boolean = true,
    maxEvents: Int = 200,
  ): Summary {
    val costPercent = hintCost ?: 0
    val lockSlots = lockSlots()
    val personOf = people.flatMap { (person, worlds) -> worlds.map { it to person } }.toMap()
    val list = players.values.filter { it.slot !in lockSlots }.map { p ->
      val total = p.total
      val lock = lockOf(p.slot)
      val checks = progressionChecks(p)
      PlayerSummary(
        slot = p.slot,
        name = p.alias,
        game = p.game,
        checked = p.checked.size,
        total = total,
        percent = percent(p.checked.size, total),
        status = statusOf(p, lock),
        online = p.online > 0,
        connections = p.online,
        lastActivity = p.lastActivity,
        itemsReceived = p.items.size,
        progressionReceived = progressionReceived(p),
        progressionChecks = checks?.locations,
        progressionChecked = checks?.checked ?: 0,
        sent = checks?.sent,
        available = p.available,
        deaths = p.deaths,
        trapLinks = p.trapLinks,
        hintPoints = p.hintPoints,
        // As MultiServer's Context.get_hint_cost
        hintCost = if (costPercent == 0) 0 else maxOf(1, (costPercent * 0.01 * total).toInt()),
        person = personOf[p.name],
        playTimeMs = p.playTimeMs,
        onlineSince = p.openSession?.start,
        firstCheckAt = p.firstCheckAt,
        goalAt = p.goalAt,
        releasedAt = p.releasedAt,
        lastCheckAt = p.lastCheckAt,
        completedAt = p.lastCheckAt?.takeIf { p.complete },
        lock = lock,
      )
    }
    val checked = list.sumOf { it.checked }
    // Found at a location, not the starting inventory (no location) or a cheat's
    val sent = players.values.filter { it.slot !in lockSlots }.flatMap { p -> p.items.filter { it.location >= 0 } }
    val total = list.sumOf { it.total }
    return Summary(
      title = title,
      connected = connected,
      error = error,
      seedName = seedName,
      serverVersion = serverVersion,
      generatorVersion = generatorVersion,
      hintCost = hintCost,
      hintSettings = HintSettings(pointHints, autoHints, costPercent, checkPoints),
      startedAt = startedAt,
      version = version,
      totals = Totals(
        checked = checked,
        total = total,
        percent = percent(checked, total),
        players = list.size,
        goals = list.count { it.status.done },
        online = list.count { it.online },
        locked = list.count { it.lock?.unlocked == false },
        lockable = list.count { it.lock != null },
        itemsSent = sent.size,
        progressionSent = sent.count { it.flags and ItemFlags.PROGRESSION != 0 },
        trapsSent = sent.count { it.flags and ItemFlags.TRAP != 0 },
        deaths = deaths,
        trapLinks = trapLinks,
      ),
      people = people(list, people),
      players = list,
      events = events.toList().takeLast(maxEvents).map(::render),
      hints = renderHints(),
    )
  }

  fun playerDetail(slot: Int): PlayerDetail? {
    val p = players[slot] ?: return null
    val checks = progressionChecks(p)
    return PlayerDetail(
      slot = slot,
      name = p.alias,
      game = p.game,
      items = p.items.map { (item, location, sender, flags) ->
        ReceivedItemView(
          item = displayItemName(item, slot),
          from = playerName(sender),
          location = if (sender != 0) locationName(location, sender) else "",
          flags = flags,
        )
      },
      missing = p.missing.map { locationName(it, slot) }.sorted(),
      checked = p.checked.size,
      total = p.total,
      progressionReceived = progressionReceived(p),
      progressionChecks = checks?.locations,
      progressionChecked = checks?.checked ?: 0,
      sent = checks?.sent,
      available = p.available,
      // Still remaining (a check since the last count is no longer available)
      availableLocations = (p.availableLocations intersect p.missing.toSet()).map { locationName(it, slot) }.sorted(),
      deaths = p.deaths,
      trapLinks = p.trapLinks,
      sessions = p.sessions.toList(),
      hintPoints = p.hintPoints,
      // Every slot's received items that came from this one's locations
      sentItems = players.values.flatMap { receiver ->
        receiver.items.filter { it.player == slot }.map { (item, location, _, flags) ->
          SentItemView(
            item = displayItemName(item, receiver.slot),
            to = playerName(receiver.slot),
            location = locationName(location, slot),
            flags = flags,
          )
        }
      }.sortedBy { it.location },
      map = p.map?.let { map ->
        MapView(map.regions.map { region -> MapRegionView(region.name, region.locations.map { locationName(it, slot) }) }, map.links)
      },
    )
  }

  fun render(e: FeedEvent): FeedEvent {
    val receiver = e.receiver ?: 0
    return when (e.type) {
      EventType.ITEM -> {
        val sender = e.sender ?: 0
        e.copy(
          senderName = playerName(sender), receiverName = playerName(receiver),
          itemName = displayItemName(e.item ?: 0, receiver), locationName = locationName(e.location ?: 0, sender),
        )
      }

      EventType.UNLOCK -> {
        val sender = e.sender ?: 0
        val world = lockedSlot(itemName(e.item ?: 0, receiver))
        e.copy(
          slot = world, playerName = world?.let(::playerName), senderName = playerName(sender),
          itemName = displayItemName(e.item ?: 0, receiver), locationName = locationName(e.location ?: 0, sender),
        )
      }

      EventType.HINT -> {
        val finder = e.finder ?: 0
        e.copy(
          finderName = playerName(finder), receiverName = playerName(receiver),
          itemName = displayItemName(e.item ?: 0, receiver), locationName = locationName(e.location ?: 0, finder),
        )
      }

      else -> e.slot?.let { e.copy(playerName = playerName(it)) } ?: e
    }
  }

  fun renderHints(): List<HintView> = hints.values.map { h ->
    HintView(
      receivingPlayer = h.receivingPlayer,
      findingPlayer = h.findingPlayer,
      location = h.location,
      item = h.item,
      found = h.found,
      entrance = h.entrance,
      itemFlags = h.itemFlags,
      status = h.status,
      finderName = playerName(h.findingPlayer),
      // A key is "for" the world it unlocks, not for the World Locks slot that technically receives it
      receiverName = if (h.receivingPlayer in lockSlots()) {
        lockedSlot(itemName(h.item, h.receivingPlayer))?.let(::playerName) ?: playerName(h.receivingPlayer)
      } else playerName(h.receivingPlayer),
      itemName = displayItemName(h.item, h.receivingPlayer),
      locationName = locationName(h.location, h.findingPlayer),
    )
  }.sortedWith(compareBy<HintView> { it.found }.thenBy { it.receiverName }.thenBy { it.itemName })

  // ------------------------------------------------------------------------------------------------------ baseline

  /**
   * What the tracker's history file of before held ([text], the baseline), as it wrote it every 15 s until 0.19.10;
   * a test makes one so. The events after its [serverCursor] are replayed onto it.
   */
  internal fun historyJson(): String = ApiJson.encodeToString(
    SavedState.serializer(),
    SavedState(
      seedName, eventSeq, events.toList(), serverCursor = serverCursor,
      lastActivity = players.mapNotNull { (s, p) -> p.lastActivity?.let { s to it } }.toMap(),
      sessions = players.filterValues { it.sessions.isNotEmpty() }.mapValues { it.value.sessions.toList() },
      firstCheckAt = players.mapNotNull { (s, p) -> p.firstCheckAt?.let { s to it } }.toMap(),
      goalAt = players.mapNotNull { (s, p) -> p.goalAt?.let { s to it } }.toMap(),
      savedAt = clock(),
    ),
  )

  /** Reads the baseline ([historyJson]); it is only applied by [applySaved], once the seed is known to match. */
  fun loadSaved(text: String?) {
    saved = text?.let { runCatching { ApiJson.decodeFromString(SavedState.serializer(), it) }.getOrNull() }
  }

  fun applySaved() {
    val data = saved ?: return
    // The seed isn't known yet (no room.json, no connection): it stays for when it is
    if (seedName == null) return
    saved = null
    if (data.seedName != seedName) return
    // Events that arrived before the history was restored come after it
    val current = events.map { it.copy(id = it.id + data.eventSeq) }
    events.clear()
    events.addAll(data.events + current)
    while (events.size > MAX_EVENTS) events.removeFirst()
    eventSeq += data.eventSeq
    serverCursor = maxOf(serverCursor, data.serverCursor)
    data.lastActivity.forEach { (s, ts) ->
      players[s]?.let { p -> if (p.lastActivity.let { it == null || ts > it }) p.lastActivity = ts }
    }
    data.sessions.forEach { (s, saved) ->
      val p = players[s] ?: return@forEach
      // Sessions still open at the last save were cut by the restart
      val closed = saved.map { if (it.end == null) it.copy(end = data.savedAt ?: it.start, cut = true) else it }
      p.sessions = (closed + p.sessions).takeLast(MAX_SESSIONS).toMutableList()
    }
    data.firstCheckAt.forEach { (s, ts) -> players[s]?.let { p -> p.firstCheckAt = minOf(ts, p.firstCheckAt ?: ts) } }
    data.goalAt.forEach { (s, ts) -> players[s]?.let { p -> p.goalAt = minOf(ts, p.goalAt ?: ts) } }
    // The history file had no release times: its feed has them (its last 500 events)
    data.events.filter { it.type == EventType.RELEASE }.forEach { e ->
      e.slot?.let { players[it] }?.let { p -> p.releasedAt = minOf(e.ts, p.releasedAt ?: e.ts) }
    }
    touch()
  }
}

/**
 * Groups the worlds by person: first the people of `session.yaml` in its order, then every world nobody claimed as a
 * person of its own.
 */
private fun people(worlds: List<PlayerSummary>, people: Map<String, List<String>>): List<PersonSummary> {
  val byName = worlds.associateBy { it.name }
  val claimed = mutableSetOf<Int>()
  val groups = people.map { (person, names) ->
    person to names.mapNotNull { byName[it] }.also { list -> list.forEach { claimed += it.slot } }
  } + worlds.filter { it.slot !in claimed }.map { it.name to listOf(it) }
  return groups.filter { it.second.isNotEmpty() }.map { (person, list) ->
    val checked = list.sumOf { it.checked }
    val total = list.sumOf { it.total }
    PersonSummary(
      name = person,
      slots = list.map { it.slot },
      checked = checked,
      total = total,
      percent = percent(checked, total),
      goals = list.count { it.status.done },
      online = list.any { it.online },
      lastActivity = list.mapNotNull { it.lastActivity }.maxOrNull(),
      playTimeMs = list.sumOf { it.playTimeMs },
    )
  }
}

/**
 * A release this soon after the slot's goal is the automatic one (release mode `auto`): MultiServer sends `Goal`, then
 * (`Collect` and) `Release` in the same moment (`on_goal_achieved`).
 */
private const val AUTO_RELEASE_MS = 5_000L

/** The first that applies; see [PlayerStatus]. */
internal fun statusOf(p: Player, lock: LockInfo?): PlayerStatus {
  val goal = p.goalAt ?: if (p.status == ClientStatus.GOAL) Long.MAX_VALUE else null
  val released = p.releasedAt
  val atGoal = released != null && goal != null && released - goal in 0..AUTO_RELEASE_MS
  return when {
    released != null && !atGoal -> PlayerStatus.RELEASED
    p.complete -> PlayerStatus.COMPLETE
    goal != null -> PlayerStatus.GOAL
    lock?.unlocked == false -> PlayerStatus.LOCKED
    p.online > 0 -> PlayerStatus.ONLINE
    p.sessions.isNotEmpty() && p.checked.isNotEmpty() -> PlayerStatus.IDLE
    else -> PlayerStatus.NOT_STARTED
  }
}

private fun percent(part: Int, total: Int): Double = if (total == 0) 0.0 else round(1000.0 * part / total) / 10

private fun formatVersion(v: JsonObject): String = "${v.int("major") ?: 0}.${v.int("minor") ?: 0}.${v.int("build") ?: 0}"

/** `NetworkSlot` arrives as an object (`{"name", "game", "type", ...}`) or, from older servers, as a list. */
private fun slotFields(info: JsonElement): Triple<String, String, Int> = when (info) {
  is JsonObject -> Triple(info.string("name")!!, info.string("game")!!, info.int("type")!!)
  else -> info.jsonArray.let { Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content, it[2].jsonPrimitive.int) }
}
