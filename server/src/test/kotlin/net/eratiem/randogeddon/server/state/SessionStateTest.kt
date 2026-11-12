package net.eratiem.randogeddon.server.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.EventType
import net.eratiem.randogeddon.shared.PlayerStatus
import net.eratiem.randogeddon.shared.Totals
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Recorded-shape packets of Archipelago 0.6.8 for two players (Alice, Bob) and an item link group. */
class SessionStateTest {
  private fun json(text: String): JsonObject = ApiJson.parseToJsonElement(text).jsonObject
  private fun element(text: String): JsonElement = ApiJson.parseToJsonElement(text)

  private val roomInfo = json(
    """{"cmd":"RoomInfo","seed_name":"1234","games":["APQuest","Archipelago"],
       "version":{"major":0,"minor":6,"build":8,"class":"Version"},
       "generator_version":{"major":0,"minor":6,"build":8,"class":"Version"},"hint_cost":10,"permissions":{}}"""
  )

  private fun connected(slot: Int, checked: List<Int>, missing: List<Int>) = json(
    """{"cmd":"Connected","team":0,"slot":$slot,
       "players":[{"team":0,"slot":1,"alias":"Alice","name":"Alice","class":"NetworkPlayer"},
                  {"team":0,"slot":2,"alias":"Bobby","name":"Bob","class":"NetworkPlayer"}],
       "slot_info":{"1":{"name":"Alice","game":"APQuest","type":1,"group_members":[],"class":"NetworkSlot"},
                    "2":{"name":"Bob","game":"APQuest","type":1,"group_members":[],"class":"NetworkSlot"},
                    "3":{"name":"Link","game":"APQuest","type":2,"group_members":[1,2],"class":"NetworkSlot"}},
       "checked_locations":$checked,"missing_locations":$missing,"hint_points":0}"""
  )

  private fun item(item: Int, location: Int, player: Int, flags: Int = 0) =
    """{"item":$item,"location":$location,"player":$player,"flags":$flags,"class":"NetworkItem"}"""

  private fun makeState(clock: () -> Long = { 0L }) = SessionState(clock).apply {
    assertTrue(onRoomInfo(roomInfo))
    onConnected(1, connected(1, listOf(10), listOf(11, 12, 13)))
    onConnected(2, connected(2, listOf(), listOf(20, 21)))
    onDataPackage(json(
      """{"data":{"games":{"APQuest":{
           "item_name_to_id":{"Sword":100,"Key":101},
           "location_name_to_id":{"Chest A":10,"Chest B":11,"Chest C":12,"Boss":13,"Pot":20,"Door":21}}}}}"""
    ))
  }

  @Test
  fun `the session is done once every slot reached its goal or was released`() {
    val s = makeState()
    assertFalse(SessionState().allDone()) // nothing known yet
    assertFalse(s.allDone())
    s.onPrintJson(json("""{"cmd":"PrintJSON","type":"Goal","slot":1}"""))
    assertFalse(s.allDone())
    s.onPrintJson(json("""{"cmd":"PrintJSON","type":"Release","slot":2}"""))
    assertTrue(s.allDone())
  }

  @Test
  fun `cheats from the panel are feed lines, and know the slot's items and open locations`() {
    val s = makeState()
    s.onCheat("Ada gave Sword to Alice", now = 7)
    val event = s.summary("t", null).events.last()
    assertEquals(EventType.CHEAT, event.type)
    assertEquals("Ada gave Sword to Alice", event.message)
    assertTrue(event.cheat)
    val names = s.cheatNames("Alice")!!
    assertEquals(listOf("Key", "Sword"), names.items)
    assertEquals(listOf("Boss", "Chest B", "Chest C"), names.locations)
    assertEquals(listOf("Alice", "Bob"), s.cheatSlots())
    assertEquals(null, s.cheatNames("Nobody"))
    // A slot with every location checked is no longer offered; with all of them, nothing is left
    s.players.getValue(1).let { it.checked += it.missing; it.missing.clear() }
    assertEquals(listOf("Bob"), s.cheatSlots())
    assertFalse(s.everyCheckDone())
    s.players.getValue(2).let { it.checked += it.missing; it.missing.clear() }
    assertEquals(emptyList(), s.cheatSlots())
    assertTrue(s.everyCheckDone())
  }

  @Test
  fun `players and groups`() {
    val s = makeState()
    assertEquals(setOf(1, 2), s.players.keys) // the group slot is not a player card
    assertEquals("Bobby", s.players[2]!!.alias)
    assertEquals("Link", s.playerName(3))
    val summary = s.summary("t", null)
    val alice = summary.players[0]
    assertEquals(Triple(1, 4, 25.0), Triple(alice.checked, alice.total, alice.percent))
    assertEquals(Totals(checked = 1, total = 6, percent = 16.7, players = 2, goals = 0, online = 0), summary.totals)
  }

  @Test
  fun `room update and item send update the progress`() {
    val s = makeState()
    s.onRoomUpdate(1, json("""{"cmd":"RoomUpdate","checked_locations":[11]}"""))
    assertEquals(setOf(10L, 11L), s.players[1]!!.checked)
    // An ItemSend from Bob updates Bob's progress before his RoomUpdate arrives
    s.onPrintJson(json("""{"cmd":"PrintJSON","type":"ItemSend","receiving":1,"item":${item(100, 20, 2, 1)}}"""), now = 50)
    val bob = s.players[2]!!
    assertTrue(20L in bob.checked && 20L !in bob.missing)
    assertEquals(50L, bob.lastActivity)
    val event = s.summary("t", null).events.last()
    assertEquals(listOf("Bobby", "Alice", "Sword", "Pot"),
      listOf(event.senderName, event.receiverName, event.itemName, event.locationName))
  }

  @Test
  fun `received items resync and append`() {
    val s = makeState()
    s.onReceivedItems(1, json("""{"index":0,"items":[${item(100, 20, 2, 1)}]}"""))
    s.onReceivedItems(1, json("""{"index":1,"items":[${item(101, 11, 1, 0)}]}"""))
    assertEquals(2, s.players[1]!!.items.size)
    s.onReceivedItems(1, json("""{"index":0,"items":[${item(100, 20, 2, 1)}]}""")) // reconnect resync
    assertEquals(1, s.players[1]!!.items.size)
    assertEquals(1, s.summary("t", null).players[0].progressionReceived)
  }

  @Test
  fun `join and part ignore trackers`() {
    val s = makeState()
    s.onPrintJson(json("""{"type":"Join","team":0,"slot":1,"tags":["Tracker"]}"""))
    assertEquals(0, s.players[1]!!.online)
    assertTrue(s.events.isEmpty())
    s.onPrintJson(json("""{"type":"Join","team":0,"slot":1,"tags":["AP"]}"""))
    assertEquals(1, s.players[1]!!.online)
    s.onPrintJson(json("""{"type":"Part","team":0,"slot":1}"""))
    s.onPrintJson(json("""{"type":"Part","team":0,"slot":1}""")) // a tracker leaving: no underflow
    assertEquals(0, s.players[1]!!.online)
    assertEquals(listOf(EventType.JOIN, EventType.PART), s.events.map { it.type })
  }

  @Test
  fun `goal wins over release`() {
    val s = makeState()
    s.onSetReply("_read_client_status_0_1", JsonPrimitive(ClientStatus.READY + 10))
    assertEquals(PlayerStatus.PLAYING, s.summary("t", null).players[0].status)
    s.onPrintJson(json("""{"type":"Goal","team":0,"slot":1}"""))
    s.onPrintJson(json("""{"type":"Release","team":0,"slot":2}"""))
    val players = s.summary("t", null).players
    assertEquals(listOf(PlayerStatus.GOAL, PlayerStatus.RELEASED), players.map { it.status })
    assertEquals(2, s.summary("t", null).totals.goals)
    s.onPrintJson(json("""{"type":"Release","team":0,"slot":1}""")) // auto-release on goal
    assertEquals(PlayerStatus.GOAL, s.summary("t", null).players[0].status)
  }

  @Test
  fun `hints are deduplicated across slots`() {
    val s = makeState()
    val hint = """{"receiving_player":1,"finding_player":2,"location":21,"item":101,"found":false,
                   "entrance":"","item_flags":1,"status":30,"class":"Hint"}"""
    s.onSetReply("_read_hints_0_1", element("[$hint]"))
    s.onSetReply("_read_hints_0_2", element("[$hint]"))
    assertEquals(1, s.renderHints().size)
    s.onSetReply("_read_hints_0_2", element("[${hint.replace("\"found\":false", "\"found\":true")}]"))
    val h = s.renderHints().single()
    assertTrue(h.found)
    assertEquals(listOf("Key", "Door", "Bobby"), listOf(h.itemName, h.locationName, h.finderName))
  }

  @Test
  fun `a new seed resets the state`() {
    val s = makeState()
    s.onPrintJson(json("""{"type":"Goal","team":0,"slot":1}"""))
    assertFalse(s.onRoomInfo(roomInfo))
    assertTrue(s.events.isNotEmpty())
    assertTrue(s.onRoomInfo(json(roomInfo.toString().replace("\"1234\"", "\"9999\""))))
    assertTrue(s.events.isEmpty() && s.players.isEmpty())
  }

  @Test
  fun `history survives a restart for the same seed only`() {
    val path = Files.createTempDirectory("tracker").resolve("tracker.json")
    val s = makeState()
    s.onPrintJson(json("""{"type":"Goal","team":0,"slot":1}"""), now = 100)
    s.save(path)

    val t = SessionState { 0L }
    t.load(path)
    t.onRoomInfo(roomInfo)
    t.onConnected(1, connected(1, listOf(10), listOf(11, 12, 13)))
    t.onPrintJson(json("""{"type":"Chat","team":0,"slot":1,"message":"hi"}"""), now = 200)
    t.applySaved()
    assertEquals(listOf(EventType.GOAL, EventType.CHAT), t.events.map { it.type })
    assertEquals(listOf(1L, 2L), t.events.map { it.id })
    assertEquals(100L, t.players[1]!!.lastActivity) // restored; chat is not "activity"

    val u = SessionState { 0L }
    u.load(path)
    u.onRoomInfo(json(roomInfo.toString().replace("\"1234\"", "\"other\"")))
    u.applySaved()
    assertTrue(u.events.isEmpty()) // history of another seed is ignored
  }

  /** Alice plays two worlds, one of them locked; slot 4 is the World Locks slot holding its key. */
  private fun lockedSession(): SessionState {
    val slotInfo = """{"1":{"name":"Alice-Quest","game":"APQuest","type":1},
                       "2":{"name":"Alice-Locked","game":"APQuest","type":1},
                       "3":{"name":"Bob","game":"APQuest","type":1},
                       "4":{"name":"Locks","game":"World Locks","type":1}}"""
    fun connected(slot: Int, checked: List<Int>, missing: List<Int>) = json(
      """{"cmd":"Connected","team":0,"slot":$slot,"players":[],"slot_info":$slotInfo,
         "checked_locations":$checked,"missing_locations":$missing}"""
    )
    return SessionState { 0L }.apply {
      onRoomInfo(roomInfo)
      onConnected(1, connected(1, listOf(), listOf(10, 11)))
      onConnected(2, connected(2, listOf(), listOf(10, 11)))
      onConnected(3, connected(3, listOf(), listOf(10, 11)))
      onConnected(4, connected(4, listOf(), listOf()))
      onDataPackage(json(
        """{"data":{"games":{"APQuest":{"item_name_to_id":{"Sword":100},"location_name_to_id":{"Chest A":10,"Chest B":11}},
             "World Locks":{"item_name_to_id":{"World Key 1":7400000},"location_name_to_id":{}}}}}"""
      ))
      onSetReply("_read_slot_data_4", element("""{"locks":{"World Key 1":"Alice-Locked"}}"""))
    }
  }

  @Test
  fun `locked worlds open when their key is found`() {
    val s = lockedSession()
    val people = mapOf("Alice" to listOf("Alice-Quest", "Alice-Locked"))
    var summary = s.summary("t", null, people)
    assertEquals(listOf("Alice-Quest", "Alice-Locked", "Bob"), summary.players.map { it.name }) // no Locks slot
    assertEquals(PlayerStatus.LOCKED, summary.players[1].status)
    assertEquals(1 to 1, summary.totals.locked to summary.totals.lockable)
    assertEquals(listOf("Alice", "Bob"), summary.people.map { it.name })
    assertEquals(listOf(1, 2), summary.people[0].slots)

    // A hint for the key says where it is
    s.onSetReply("_read_hints_0_4", element(
      """[{"receiving_player":4,"finding_player":3,"location":11,"item":7400000,"found":false,"item_flags":1}]"""
    ))
    assertEquals("Chest B (in Bob)", s.summary("t", null, people).players[1].lock!!.hintedAt)
    assertEquals("Key to Alice-Locked", s.renderHints().single().itemName)

    // Bob finds it
    s.onPrintJson(json("""{"type":"ItemSend","receiving":4,"item":${item(7400000, 11, 3, 1)}}"""), now = 70)
    s.onReceivedItems(4, json("""{"index":0,"items":[${item(7400000, 11, 3, 1)}]}"""))
    summary = s.summary("t", null, people)
    val lock = summary.players[1].lock!!
    assertTrue(lock.unlocked)
    assertEquals("Bob" to 70L, lock.foundBy to lock.foundAt)
    assertEquals(PlayerStatus.IDLE, summary.players[1].status)
    assertEquals(0, summary.totals.locked)
    val unlock = summary.events.single()
    assertEquals(EventType.UNLOCK, unlock.type)
    assertEquals(listOf("Alice-Locked", "Bob", "Key to Alice-Locked"), listOf(unlock.playerName, unlock.senderName, unlock.itemName))
    assertEquals(2, unlock.slot)
  }

  @Test
  fun `play time and connection log`() {
    var clock = 0L
    val s = makeState { clock }
    s.onPrintJson(json("""{"type":"Join","team":0,"slot":1,"tags":["AP"]}"""), now = 1_000)
    s.onPrintJson(json("""{"type":"Part","team":0,"slot":1}"""), now = 61_000)
    // The tracker restarted and missed the next Join, but sees Alice find her own item: implicitly online
    s.onPrintJson(json("""{"type":"ItemSend","receiving":1,"item":${item(100, 11, 1, 1)}}"""), now = 100_000)
    // Collect sends Bob's items on his behalf: not a sign that Bob plays
    s.onPrintJson(json("""{"type":"ItemSend","receiving":1,"item":${item(101, 20, 2, 1)}}"""), now = 100_000)
    clock = 160_000
    val summary = s.summary("t", null)
    val alice = summary.players[0]
    assertEquals(60_000L, alice.playTimeMs)
    assertEquals(100_000L, alice.onlineSince)
    assertEquals(100_000L, alice.firstCheckAt)
    assertFalse(summary.players[1].online)
    assertEquals(listOf(false, true), s.playerDetail(1)!!.sessions.map { it.implicit })

    // Saved while online: after a restart, the open session ends at the time of the save
    val path = Files.createTempDirectory("tracker").resolve("tracker.json")
    s.save(path)
    val t = SessionState { 0L }
    t.load(path)
    t.onRoomInfo(roomInfo)
    t.onConnected(1, connected(1, listOf(10), listOf(11, 12, 13)))
    t.applySaved()
    assertEquals(60_000L + 60_000L, t.summary("t", null).players[0].playTimeMs)
  }

  @Test
  fun `sessions split by a tracker restart are merged`() {
    var clock = 0L
    val s = makeState { clock }
    s.onPrintJson(json("""{"type":"Join","team":0,"slot":1,"tags":["AP"]}"""), now = 1_000)
    clock = 50_000
    val path = Files.createTempDirectory("tracker").resolve("tracker.json")
    s.save(path) // the tracker stops here

    clock = 90_000
    val t = SessionState { clock }
    t.load(path)
    t.onRoomInfo(roomInfo)
    t.onConnected(1, connected(1, listOf(10), listOf(11, 12, 13)))
    t.applySaved()
    // 40 s later Alice is seen playing: still the same session
    t.onPrintJson(json("""{"type":"ItemSend","receiving":1,"item":${item(100, 11, 1, 1)}}"""), now = 90_000)
    var sessions = t.playerDetail(1)!!.sessions
    assertEquals(1, sessions.size)
    assertEquals(1_000L to null, sessions[0].start to sessions[0].end)

    // The server goes away and Alice rejoins 10 minutes later: a new session
    t.onConnectionLost(now = 100_000)
    t.onPrintJson(json("""{"type":"Join","team":0,"slot":1,"tags":["AP"]}"""), now = 700_000)
    sessions = t.playerDetail(1)!!.sessions
    assertEquals(listOf(1_000L, 700_000L), sessions.map { it.start })
    // ...and a Join after "seen playing" continues that session instead of starting another
    t.onPrintJson(json("""{"type":"Part","team":0,"slot":1}"""), now = 800_000)
    t.onPrintJson(json("""{"type":"Chat","team":0,"slot":1,"message":"hi"}"""), now = 2_000_000)
    t.onPrintJson(json("""{"type":"Join","team":0,"slot":1,"tags":["AP"]}"""), now = 2_000_500)
    sessions = t.playerDetail(1)!!.sessions
    assertEquals(3, sessions.size)
    assertEquals(1, t.players[1]!!.online)
  }
}
