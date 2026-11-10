package net.eratiem.archipelago.server.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.eratiem.archipelago.shared.ApiJson
import net.eratiem.archipelago.shared.ConnectionSession
import net.eratiem.archipelago.shared.EventType
import net.eratiem.archipelago.shared.FeedEvent
import net.eratiem.archipelago.shared.HintSettings
import net.eratiem.archipelago.shared.HintView
import net.eratiem.archipelago.shared.ItemFlags
import net.eratiem.archipelago.shared.LockInfo
import net.eratiem.archipelago.shared.PersonSummary
import net.eratiem.archipelago.shared.PlayerDetail
import net.eratiem.archipelago.shared.PlayerStatus
import net.eratiem.archipelago.shared.PlayerSummary
import net.eratiem.archipelago.shared.ReceivedItemView
import net.eratiem.archipelago.shared.Summary
import net.eratiem.archipelago.shared.Totals
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.round

/** `NetUtils.ClientStatus` */
object ClientStatus {
  const val UNKNOWN = 0
  const val READY = 10
  const val GOAL = 30
}

/** `NetUtils.SlotType` */
object SlotType {
  const val PLAYER = 1
  const val GROUP = 2
}

/** The game of the World Locks apworld (apworlds/world_locks); its slot holds the keys of the locked worlds. */
const val WORLD_LOCKS_GAME = "World Locks"

/** Tags of clients that don't play: trackers (including this one), text clients and hint games. */
private val NON_GAME_TAGS = setOf("Tracker", "TextOnly", "HintGame")
const val MAX_EVENTS = 500
const val MAX_SESSIONS = 200

/** A session cut by a tracker restart continues if the world is seen again within this time. */
const val SESSION_MERGE_GAP_MS = 5 * 60_000L

/** One `NetworkItem`: the item, the location it was found at, the player it came from (or went to) and its flags. */
data class NetworkItem(val item: Long, val location: Long, val player: Int, val flags: Int)

class Player(val slot: Int, val name: String, val game: String) {
  var alias: String = name
  var checked: MutableSet<Long> = mutableSetOf()
  var missing: MutableSet<Long> = mutableSetOf()

  /** Received items; [NetworkItem.player] is the sender. */
  var items: MutableList<NetworkItem> = mutableListOf()
  var status: Int = ClientStatus.UNKNOWN

  /** Game clients connected to this slot, counted from Join/Part since the tracker connected. */
  var online: Int = 0
  var released: Boolean = false
  var lastActivity: Long? = null
  var hintPoints: Int? = null
  var sessions: MutableList<ConnectionSession> = mutableListOf()
  var firstCheckAt: Long? = null
  var goalAt: Long? = null

  val openSession: ConnectionSession? get() = sessions.lastOrNull()?.takeIf { it.end == null }

  /** Finished sessions only; the open one is added by whoever knows the current time. */
  val playTimeMs: Long get() = sessions.sumOf { s -> s.end?.let { it - s.start } ?: 0 }

  fun goOnline(now: Long, implicit: Boolean) {
    if (openSession != null) return
    val last = sessions.lastOrNull()
    val lastEnd = last?.end
    if (last != null && last.cut && lastEnd != null && now - lastEnd <= SESSION_MERGE_GAP_MS) {
      // Only the tracker was gone: the same session goes on
      sessions[sessions.lastIndex] = last.copy(end = null, cut = false)
      return
    }
    sessions += ConnectionSession(now, implicit = implicit)
    while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
  }

  /** [cut]: the tracker stops seeing the world (restart, lost server), the client itself may still be there. */
  fun goOffline(now: Long, cut: Boolean = false) {
    openSession?.let { sessions[sessions.lastIndex] = it.copy(end = now, cut = cut) }
  }

  val total: Int get() = (checked + missing).size
}

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

/** What survives a tracker restart: the feed and the last activity, for one seed. */
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
)

/**
 * The aggregated session, fed with the packets of the Archipelago network protocol.
 *
 * Pure logic apart from the JSON persistence, so it can be tested with recorded packets. Not thread-safe: the caller
 * serializes access (see [net.eratiem.archipelago.server.ap.TrackerService]).
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
  private var saved: SavedState? = null

  /** Key item name -> slot name of the world it unlocks, from the World Locks slot data. */
  private val locks = mutableMapOf<String, String>()

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
  }

  private fun touch() {
    version++
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
   * A data storage value from `Retrieved` or `SetReply`. [live]: a change that just happened (SetReply), not the
   * replay after connecting; a live status change of a world means a game client is connected to it.
   */
  fun onSetReply(key: String, value: JsonElement?, live: Boolean = false, now: Long = clock()) {
    when {
      key.startsWith("_read_client_status_") -> {
        val player = players[key.substringAfterLast('_').toInt()]
        val status = (value as? JsonPrimitive)?.intOrNull
        // Only the status: this is also the replay after every (re)connect, not an activity
        if (player != null && status != null) {
          if (live && status >= ClientStatus.READY && status != player.status) seenPlaying(player, now)
          player.status = status
        }
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
        if (type == "Release") players[slot]?.released = true
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
        // Part carries no tags; only count down what was counted up
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

  private fun event(
    ts: Long, type: EventType, slot: Int? = null, sender: Int? = null, receiver: Int? = null, finder: Int? = null,
    item: Long? = null, location: Long? = null, flags: Int = 0, found: Boolean = false, cheat: Boolean = false,
    message: String? = null,
  ) = FeedEvent(
    id = ++eventSeq, ts = ts, type = type, slot = slot, sender = sender, receiver = receiver, finder = finder,
    item = item, location = location, flags = flags, found = found, cheat = cheat, message = message,
  )

  // -------------------------------------------------------------------------------------------------------- names

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
        return LockInfo(key, unlocked = true, foundBy = playerName(received.player), foundAt = found?.ts)
      }
    }
    val hint = hints.values.firstOrNull { it.receivingPlayer in lockSlots() && itemName(it.item, it.receivingPlayer) == key }
    return LockInfo(
      key, unlocked = false,
      hintedAt = hint?.let { "${locationName(it.location, it.findingPlayer)} (${playerName(it.findingPlayer)}'s world)" },
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
      PlayerSummary(
        slot = p.slot,
        name = p.alias,
        game = p.game,
        checked = p.checked.size,
        total = total,
        percent = percent(p.checked.size, total),
        status = statusOf(p, lock),
        online = p.online > 0,
        lastActivity = p.lastActivity,
        itemsReceived = p.items.size,
        progressionReceived = p.items.count { it.flags and ItemFlags.PROGRESSION != 0 },
        hintPoints = p.hintPoints,
        // As MultiServer's Context.get_hint_cost
        hintCost = if (costPercent == 0) 0 else maxOf(1, (costPercent * 0.01 * total).toInt()),
        person = personOf[p.name],
        playTimeMs = p.playTimeMs,
        onlineSince = p.openSession?.start,
        firstCheckAt = p.firstCheckAt,
        goalAt = p.goalAt,
        lock = lock,
      )
    }
    val checked = list.sumOf { it.checked }
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
        goals = list.count { it.status == PlayerStatus.GOAL || it.status == PlayerStatus.RELEASED },
        online = list.count { it.online },
        locked = list.count { it.lock?.unlocked == false },
        lockable = list.count { it.lock != null },
      ),
      people = people(list, people),
      players = list,
      events = events.toList().takeLast(maxEvents).map(::render),
      hints = renderHints(),
    )
  }

  fun playerDetail(slot: Int): PlayerDetail? {
    val p = players[slot] ?: return null
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
      sessions = p.sessions.toList(),
      hintPoints = p.hintPoints,
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

  // -------------------------------------------------------------------------------------------------- persistence

  /** Written atomically (temporary file + move), so a crash never leaves a half-written file. */
  fun save(path: Path) {
    val data = SavedState(
      seedName, eventSeq, events.toList(),
      lastActivity = players.mapNotNull { (s, p) -> p.lastActivity?.let { s to it } }.toMap(),
      sessions = players.filterValues { it.sessions.isNotEmpty() }.mapValues { it.value.sessions.toList() },
      firstCheckAt = players.mapNotNull { (s, p) -> p.firstCheckAt?.let { s to it } }.toMap(),
      goalAt = players.mapNotNull { (s, p) -> p.goalAt?.let { s to it } }.toMap(),
      savedAt = clock(),
    )
    path.toAbsolutePath().parent.createDirectories()
    val tmp = Files.createTempFile(path.toAbsolutePath().parent, ".tracker-", ".json")
    tmp.writeText(ApiJson.encodeToString(SavedState.serializer(), data))
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  /** Reads what [save] wrote; it is only applied by [applySaved], once the seed is known to match. */
  fun load(path: Path) {
    saved = runCatching {
      if (path.exists()) ApiJson.decodeFromString(SavedState.serializer(), path.readText()) else null
    }.getOrNull()
  }

  fun applySaved() {
    val data = saved ?: return
    saved = null
    if (data.seedName != seedName) return
    // Events that arrived before the history was restored come after it
    val current = events.map { it.copy(id = it.id + data.eventSeq) }
    events.clear()
    events.addAll(data.events + current)
    while (events.size > MAX_EVENTS) events.removeFirst()
    eventSeq += data.eventSeq
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
      goals = list.count { it.status == PlayerStatus.GOAL || it.status == PlayerStatus.RELEASED },
      online = list.any { it.online },
      lastActivity = list.mapNotNull { it.lastActivity }.maxOrNull(),
      playTimeMs = list.sumOf { it.playTimeMs },
    )
  }
}

private fun statusOf(p: Player, lock: LockInfo?): PlayerStatus = when {
  p.status == ClientStatus.GOAL -> PlayerStatus.GOAL
  p.released -> PlayerStatus.RELEASED
  p.total > 0 && p.missing.isEmpty() -> PlayerStatus.COMPLETE
  lock?.unlocked == false -> PlayerStatus.LOCKED
  p.status >= ClientStatus.READY -> PlayerStatus.PLAYING
  else -> PlayerStatus.IDLE
}

private fun percent(part: Int, total: Int): Double = if (total == 0) 0.0 else round(1000.0 * part / total) / 10

private fun formatVersion(v: JsonObject): String = "${v.int("major") ?: 0}.${v.int("minor") ?: 0}.${v.int("build") ?: 0}"

/** `NetworkSlot` arrives as an object (`{"name", "game", "type", ...}`) or, from older servers, as a list. */
private fun slotFields(info: JsonElement): Triple<String, String, Int> = when (info) {
  is JsonObject -> Triple(info.string("name")!!, info.string("game")!!, info.int("type")!!)
  else -> info.jsonArray.let { Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content, it[2].jsonPrimitive.int) }
}

/** `NetworkItem` arrives as `{"item", "location", "player", "flags", "class"}` or as a list. */
internal fun networkItem(raw: JsonElement): NetworkItem = when (raw) {
  is JsonObject -> NetworkItem(raw.long("item")!!, raw.long("location")!!, raw.int("player")!!, raw.int("flags") ?: 0)
  else -> raw.jsonArray.let {
    NetworkItem(it[0].jsonPrimitive.long, it[1].jsonPrimitive.long, it[2].jsonPrimitive.int, it.getOrNull(3)?.jsonPrimitive?.int ?: 0)
  }
}

// Lenient accessors for the loosely typed Archipelago packets
internal fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)
internal fun JsonObject.array(key: String): JsonArray? = (this[key] as? JsonArray)
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.longs(key: String): List<Long> = array(key).orEmpty().map { it.jsonPrimitive.long }

private val JsonPrimitive.long: Long get() = longOrNull ?: content.toLong()
private val JsonPrimitive.int: Int get() = intOrNull ?: content.toInt()
