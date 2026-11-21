package net.eratiem.randogeddon.server.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.AvailableAnswer
import net.eratiem.randogeddon.shared.EventType
import net.eratiem.randogeddon.shared.PlayerStatus
import net.eratiem.randogeddon.shared.Totals
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
  fun `the logic is asked about the slots whose items or checks changed, without the starting inventory`() {
    val s = makeState()
    s.onReceivedItems(1, json("""{"index":0,"items":[${item(100, 20, 2, 1)},${item(101, -2, 0, 1)}]}"""))
    val asked = s.availableAsks()
    assertEquals(listOf(1, 2), asked.map { it.slot })
    assertEquals(listOf(100L), asked.first { it.slot == 1 }.items)
    assertEquals(listOf(10L), asked.first { it.slot == 1 }.checked)
    s.onAvailable(asked, AvailableAnswer(mapOf(1 to 3, 2 to null)))
    assertEquals(3, s.summary("t", null).players.first { it.slot == 1 }.available)
    assertEquals(null, s.summary("t", null).players.first { it.slot == 2 }.available)
    // Nothing changed: nobody to ask
    assertEquals(emptyList(), s.availableAsks())
    // A check of slot 2: only it again; its count stays until answered
    s.onRoomUpdate(2, json("""{"checked_locations":[20]}"""))
    assertEquals(listOf(2), s.availableAsks().map { it.slot })
    assertEquals(3, s.playerDetail(1)!!.available)
    // Built again: everyone anew
    s.forgetAvailableAsks()
    assertEquals(listOf(1, 2), s.availableAsks().map { it.slot })
  }

  @Test
  fun `a slot counts its game clients, and a tracker or text client leaving isn't one of them`() {
    val s = makeState()
    fun connections() = s.summary("t", null).players.first { it.slot == 1 }.connections
    s.onPrintJson(json("""{"type":"Join","team":0,"slot":1,"tags":["AP"]}"""))
    s.onPrintJson(json("""{"type":"Join","team":0,"slot":1,"tags":["AP","DeathLink"]}"""))
    s.onPrintJson(json("""{"type":"Join","team":0,"slot":1,"tags":["TextOnly"]}"""))
    assertEquals(2, connections())
    // MultiServer's text for a client that doesn't play ends with its tags: not a game leaving
    s.onPrintJson(json("""{"type":"Part","team":0,"slot":1,"data":[{"text":"Alice (Team #1) has stopped viewing the game. Client(0.6.8), ['TextOnly']."}]}"""))
    s.onPrintJson(json("""{"type":"Part","team":0,"slot":1,"data":[{"text":"Alice (Team #1) has stopped tracking the game. Client(0.6.8), ['Tracker']."}]}"""))
    assertEquals(2, connections())
    s.onPrintJson(json("""{"type":"Part","team":0,"slot":1,"data":[{"text":"Alice (Team #1) has left the game. Client(0.6.8), ['AP', 'DeathLink']."}]}"""))
    assertEquals(1, connections())
    assertTrue(s.summary("t", null).players.first { it.slot == 1 }.online)
    s.onPrintJson(json("""{"type":"Part","team":0,"slot":1,"data":[{"text":"Alice (Team #1) has left the game. Client(0.6.8), ['AP']."}]}"""))
    assertEquals(0, connections())
  }

  @Test
  fun `a death or trap link becomes a feed event, with its slot if the source is one`() {
    val s = makeState()
    val name = s.players[1]!!.name
    s.onBounced(json("""{"cmd":"Bounced","tags":["DeathLink"],"data":{"time":1.5,"source":"$name","cause":"$name fell\u0007 into lava"}}"""))
    s.onBounced(json("""{"cmd":"Bounced","tags":["TrapLink"],"data":{"time":2,"source":"Somebody else","trap_name":"Ice Trap"}}"""))
    // Not a link the feed shows, and one without its data
    s.onBounced(json("""{"cmd":"Bounced","tags":["RingLink"],"data":{"time":3,"source":7,"amount":1}}"""))
    s.onBounced(json("""{"cmd":"Bounced","tags":["DeathLink"]}"""))
    s.onBounced(json("""{"cmd":"Bounced","games":["Hollow Knight"],"data":{"source":"x"}}"""))
    val links = s.events.filter { it.type == EventType.LINK }
    assertEquals(listOf("DeathLink", "TrapLink"), links.map { it.link })
    assertEquals(listOf(1, null), links.map { it.slot })
    assertEquals(listOf(name, "Somebody else"), links.map { it.linkSource })
    assertEquals(listOf("$name fell into lava", "Ice Trap"), links.map { it.message })
    assertEquals(name, s.render(links[0]).playerName)
  }

  @Test
  fun `the gateway's events are taken in their order, each once, with their own times`() {
    val s = makeState()
    val join = """{"cmd":"PrintJSON","type":"Join","team":0,"slot":1,"tags":["AP"]}"""
    s.onServerEvent(5, 1_000, "start", "")
    s.onServerEvent(6, 2_000, "packet", join)
    s.onServerEvent(7, 3_000, "packet", """{"cmd":"PrintJSON","type":"Release","team":0,"slot":2}""")
    s.onServerEvent(8, 4_000, "packet", """{"cmd":"Bounced","tags":["DeathLink"],"data":{"source":"x","cause":"fell"}}""")
    // Heard again (the same ID, or an older one): not taken twice
    s.onServerEvent(6, 9_000, "packet", join)
    assertEquals(8, s.serverCursor)
    assertEquals(listOf(EventType.JOIN to 2_000L, EventType.RELEASE to 3_000L, EventType.LINK to 4_000L), s.events.map { it.type to it.ts })
    assertEquals(1, s.players[1]!!.online)
    assertEquals(2_000L, s.playerDetail(1)!!.sessions.single().start)
    // The game server ends: nobody is connected, the session ended then; its next start changes nothing more
    s.onServerEvent(9, 60_000, "stop", "")
    assertEquals(0, s.players[1]!!.online)
    assertEquals(60_000L, s.playerDetail(1)!!.sessions.single().end)
    s.onServerEvent(10, 70_000, "start", "")
    assertEquals(60_000L, s.playerDetail(1)!!.sessions.single().end)
    // The tracker's own connection going away doesn't say who plays
    s.onServerEvent(11, 80_000, "packet", join)
    s.onTrackerDisconnected()
    assertEquals(1, s.players[1]!!.online)
  }

  @Test
  fun `a session comes back from room json and its events alone, before any connection, the same as heard live`() {
    fun join(slot: Int) = """{"cmd":"PrintJSON","type":"Join","team":0,"slot":$slot,"tags":["AP"]}"""
    fun part(slot: Int) = """{"cmd":"PrintJSON","type":"Part","team":0,"slot":$slot,"data":[{"text":"X (Team #1) has left the game. Client(0.6.8), ['AP']."}]}"""
    val events = listOf(
      "start" to "",
      "packet" to join(1), "packet" to join(1), "packet" to join(2),
      "packet" to """{"cmd":"PrintJSON","type":"ItemSend","receiving":2,"item":${item(100, 11, 1, 1)}}""",
      "packet" to """{"cmd":"PrintJSON","type":"Goal","team":0,"slot":2}""",
      "packet" to part(2), "packet" to part(1),
      "cheat" to """{"message":"Alice gave Sword to Bob","action":"item","by":"Alice","role":"administrator","slot":"Bob","value":"Sword"}""",
    )
    val room = net.eratiem.randogeddon.server.session.Room(
      "1234",
      listOf(
        net.eratiem.randogeddon.server.session.RoomSlot(1, "Alice", "APQuest", 1),
        net.eratiem.randogeddon.server.session.RoomSlot(2, "Bob", "APQuest", 1),
        net.eratiem.randogeddon.server.session.RoomSlot(3, "Link", "APQuest", 2),
      ),
    )
    val s = SessionState { 0L }
    s.prepare(room)
    events.forEachIndexed { i, (kind, payload) -> s.onServerEvent(i + 1L, (i + 1) * 1_000L, kind, payload) }
    assertEquals(listOf(1, 0), listOf(s.players[1]!!.online, s.players[2]!!.online))
    assertEquals(4_000L, s.playerDetail(2)!!.sessions.single().start)
    assertEquals(6_000L, s.players[2]!!.goalAt)
    assertEquals(5_000L, s.players[1]!!.firstCheckAt)
    assertEquals(
      listOf(EventType.JOIN, EventType.JOIN, EventType.JOIN, EventType.ITEM, EventType.GOAL, EventType.PART, EventType.PART, EventType.CHEAT),
      s.events.map { it.type },
    )
    assertEquals("Alice gave Sword to Bob" to 9_000L, s.events.last().let { it.message to it.ts })
    // The first connection finds the same seed: nothing it brought back is lost, and its own data is added
    assertFalse(s.onRoomInfo(roomInfo))
    s.onConnected(1, connected(1, listOf(10), listOf(11, 12, 13)))
    assertEquals(1, s.players[1]!!.online)
    assertEquals(8, s.events.size)
    assertEquals(4, s.summary("t", null).players[0].total)
    // A slot not in room.json, or room.json of another seed: nothing
    s.prepare(net.eratiem.randogeddon.server.session.Room("other", listOf(net.eratiem.randogeddon.server.session.RoomSlot(9, "X", "APQuest", 1))))
    assertEquals(null, s.players[9])
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
  fun `a slot's state is the first that applies, in their order`() {
    val s = makeState()
    fun status(slot: Int) = s.summary("t", null).players.first { it.slot == slot }.status
    fun print(text: String, at: Long) = s.onPrintJson(json(text), now = at)
    // Never connected, no check: not started; a check alone doesn't make it started either
    assertEquals(PlayerStatus.NOT_STARTED, status(2))
    // Connected: online; gone again with a check: idle; gone without one: not started
    print("""{"type":"Join","team":0,"slot":2,"tags":["AP"]}""", 1_000)
    assertEquals(PlayerStatus.ONLINE, status(2))
    print("""{"type":"Part","team":0,"slot":2}""", 2_000)
    assertEquals(PlayerStatus.NOT_STARTED, status(2))
    assertEquals(PlayerStatus.NOT_STARTED, status(1)) // checked 1 location, but never connected
    print("""{"type":"Join","team":0,"slot":1,"tags":["AP"]}""", 3_000)
    print("""{"type":"ItemSend","receiving":1,"item":${item(100, 11, 1, 1)}}""", 4_000)
    print("""{"type":"Part","team":0,"slot":1}""", 5_000)
    assertEquals(PlayerStatus.IDLE, status(1))
    assertEquals(4_000L, s.summary("t", null).players[0].lastCheckAt)
    // The client's status doesn't make it online any more
    s.onSetReply("_read_client_status_0_1", JsonPrimitive(ClientStatus.READY + 10))
    assertEquals(PlayerStatus.IDLE, status(1))
    // Its goal, locations left: goal
    print("""{"type":"Goal","team":0,"slot":1}""", 10_000)
    assertEquals(PlayerStatus.GOAL, status(1))
    // Released right at its goal (release mode auto) and so complete: complete, since then
    print("""{"type":"Release","team":0,"slot":1}""", 10_000)
    assertEquals(PlayerStatus.GOAL, status(1)) // the release's checks arrive after it
    for (location in listOf(12, 13)) print("""{"type":"ItemSend","receiving":2,"item":${item(100, location, 1, 0)}}""", 10_100)
    assertEquals(PlayerStatus.COMPLETE, status(1))
    assertEquals(10_100L, s.summary("t", null).players[0].completedAt)
    // Released before its goal: released, and that wins over complete
    print("""{"type":"Release","team":0,"slot":2}""", 20_000)
    s.onRoomUpdate(2, json("""{"cmd":"RoomUpdate","checked_locations":[20,21]}"""))
    assertEquals(PlayerStatus.RELEASED, status(2))
    assertEquals(20_000L, s.summary("t", null).players[1].releasedAt)
    assertEquals(2, s.summary("t", null).totals.goals)
  }

  @Test
  fun `a release by hand after the goal is released, the automatic one at it complete`() {
    val s = makeState()
    s.onPrintJson(json("""{"type":"Goal","team":0,"slot":1}"""), now = 1_000)
    s.onPrintJson(json("""{"type":"Release","team":0,"slot":1}"""), now = 1_000 + 60_000)
    s.onRoomUpdate(1, json("""{"cmd":"RoomUpdate","checked_locations":[11,12,13]}"""))
    assertEquals(PlayerStatus.RELEASED, s.summary("t", null).players[0].status)
    // A slot whose goal came from its status only (data storage, before the Goal message was heard)
    s.onSetReply("_read_client_status_0_2", JsonPrimitive(ClientStatus.GOAL))
    assertEquals(PlayerStatus.GOAL, s.summary("t", null).players[1].status)
    assertTrue(s.allDone())
  }

  @Test
  fun `items sent and links are counted for the tiles, the starting inventory isn't`() {
    val s = makeState()
    s.onReceivedItems(1, json("""{"index":0,"items":[${item(100, 20, 2, 1)},${item(101, 21, 2, 4)},${item(101, -2, 0, 1)}]}"""))
    s.onBounced(json("""{"cmd":"Bounced","tags":["DeathLink"],"data":{"source":"Alice"}}"""))
    s.onBounced(json("""{"cmd":"Bounced","tags":["DeathLink"],"data":{"source":"Bob"}}"""))
    s.onBounced(json("""{"cmd":"Bounced","tags":["TrapLink"],"data":{"source":"Bob","trap_name":"Ice Trap"}}"""))
    val totals = s.summary("t", null).totals
    assertEquals(listOf(2, 1, 1), listOf(totals.itemsSent, totals.progressionSent, totals.trapsSent))
    assertEquals(2 to 1, totals.deaths to totals.trapLinks)
  }

  @Test
  fun `a slot's progression checks and what it sent come from scouting its own locations, without totals for its players`() {
    val s = makeState()
    // Alice's world: two progression items for Bob, one for herself, a filler; Bob's: one progression item for Alice
    s.onLocationInfo(1, json("""{"cmd":"LocationInfo","locations":[${item(100, 10, 2, 1)},${item(101, 11, 2, 1)},${item(100, 12, 1, 1)},${item(102, 13, 1, 0)}]}"""))
    // Alice's own world: three progression locations (she checked one, which was for Bob: sent)
    fun own(slot: Int) = s.summary("t", null).players.first { it.slot == slot }
      .let { listOf(it.progressionChecks, it.progressionChecked, it.sent) }
    assertEquals(listOf(3, 1, 1), own(1))
    assertEquals(listOf(null, 0, null), own(2)) // Bob's world isn't scouted yet
    s.onLocationInfo(2, json("""{"cmd":"LocationInfo","locations":[${item(100, 20, 1, 1)},${item(103, 21, 2, 4)}]}"""))
    assertEquals(listOf(1, 0, 0), own(2))
    assertEquals(listOf(3, 1, 1), s.playerDetail(1)!!.let { listOf(it.progressionChecks, it.progressionChecked, it.sent) })
    // Received from a location counts, the starting inventory doesn't
    s.onReceivedItems(1, json("""{"index":0,"items":[${item(100, 12, 1, 1)},${item(100, -2, 0, 1)}]}"""))
    assertEquals(1, s.summary("t", null).players[0].progressionReceived)
    assertEquals(1, s.playerDetail(1)!!.progressionReceived)
    // A slot without locations is scouted at once
    val t = makeState()
    t.onNothingToScout(2)
    assertEquals(0, t.summary("t", null).players[1].progressionChecks)
  }

  @Test
  fun `an archive of before reads playing as online`() {
    assertEquals(PlayerStatus.ONLINE, ApiJson.decodeFromString(PlayerStatus.serializer(), "\"playing\""))
    assertEquals("\"online\"", ApiJson.encodeToString(PlayerStatus.serializer(), PlayerStatus.ONLINE))
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
    val s = makeState()
    s.onPrintJson(json("""{"type":"Goal","team":0,"slot":1}"""), now = 100)
    val saved = s.historyJson()

    val t = SessionState { 0L }
    t.loadSaved(saved)
    t.onRoomInfo(roomInfo)
    t.onConnected(1, connected(1, listOf(10), listOf(11, 12, 13)))
    t.onPrintJson(json("""{"type":"Chat","team":0,"slot":1,"message":"hi"}"""), now = 200)
    t.applySaved()
    assertEquals(listOf(EventType.GOAL, EventType.CHAT), t.events.map { it.type })
    assertEquals(listOf(1L, 2L), t.events.map { it.id })
    assertEquals(100L, t.players[1]!!.lastActivity) // restored; chat is not "activity"

    val u = SessionState { 0L }
    u.loadSaved(saved)
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
    // Open now, never connected: not started
    assertEquals(PlayerStatus.NOT_STARTED, summary.players[1].status)
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
    val saved = s.historyJson()
    val t = SessionState { 0L }
    t.loadSaved(saved)
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
    val saved = s.historyJson() // the tracker stops here

    clock = 90_000
    val t = SessionState { clock }
    t.loadSaved(saved)
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
