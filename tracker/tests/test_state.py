from tracker.state import STATUS_GOAL, STATUS_PLAYING, SessionState

ROOM_INFO = {"cmd": "RoomInfo", "seed_name": "1234", "games": ["APQuest", "Archipelago"],
             "version": {"major": 0, "minor": 6, "build": 8, "class": "Version"},
             "generator_version": {"major": 0, "minor": 6, "build": 8, "class": "Version"},
             "hint_cost": 10, "permissions": {}}

SLOT_INFO = {
    "1": {"name": "Alice", "game": "APQuest", "type": 1, "group_members": [], "class": "NetworkSlot"},
    "2": {"name": "Bob", "game": "APQuest", "type": 1, "group_members": [], "class": "NetworkSlot"},
    "3": {"name": "Link", "game": "APQuest", "type": 2, "group_members": [1, 2], "class": "NetworkSlot"},
}
PLAYERS = [{"team": 0, "slot": 1, "alias": "Alice", "name": "Alice", "class": "NetworkPlayer"},
           {"team": 0, "slot": 2, "alias": "Bobby", "name": "Bob", "class": "NetworkPlayer"}]


def item(i, loc, player, flags=0):
    return {"item": i, "location": loc, "player": player, "flags": flags, "class": "NetworkItem"}


def connected(slot, checked, missing):
    return {"cmd": "Connected", "team": 0, "slot": slot, "players": PLAYERS, "slot_info": SLOT_INFO,
            "checked_locations": checked, "missing_locations": missing, "hint_points": 0}


def make_state():
    s = SessionState()
    assert s.on_room_info(ROOM_INFO) is True
    s.on_connected(1, connected(1, [10], [11, 12, 13]))
    s.on_connected(2, connected(2, [], [20, 21]))
    s.on_data_package({"data": {"games": {"APQuest": {
        "item_name_to_id": {"Sword": 100, "Key": 101},
        "location_name_to_id": {"Chest A": 10, "Chest B": 11, "Chest C": 12, "Boss": 13, "Pot": 20, "Door": 21},
    }}}})
    return s


def test_players_and_groups():
    s = make_state()
    assert set(s.players) == {1, 2}           # group slot 3 is not a player card
    assert s.players[2].alias == "Bobby"
    assert s.player_name(3) == "Link"
    summary = s.summary()
    alice = summary["players"][0]
    assert (alice["checked"], alice["total"], alice["percent"]) == (1, 4, 25.0)
    assert summary["totals"] == {"checked": 1, "total": 6, "percent": 16.7, "players": 2, "goals": 0, "online": 0}


def test_room_update_and_itemsend_progress():
    s = make_state()
    s.on_room_update(1, {"cmd": "RoomUpdate", "checked_locations": [11]})
    assert s.players[1].checked == {10, 11}
    # ItemSend from Bob updates Bob's progress immediately (before his RoomUpdate arrives)
    s.on_print_json({"cmd": "PrintJSON", "type": "ItemSend", "receiving": 1, "item": item(100, 20, 2, 1)}, now=50)
    assert 20 in s.players[2].checked and 20 not in s.players[2].missing
    assert s.players[2].last_activity == 50
    ev = s.summary()["events"][-1]
    assert ev["sender_name"] == "Bobby" and ev["receiver_name"] == "Alice"
    assert ev["item_name"] == "Sword" and ev["location_name"] == "Pot"


def test_received_items_resync_and_append():
    s = make_state()
    s.on_received_items(1, {"index": 0, "items": [item(100, 20, 2, 1)]})
    s.on_received_items(1, {"index": 1, "items": [item(101, 11, 1, 0)]})
    assert len(s.players[1].items) == 2
    s.on_received_items(1, {"index": 0, "items": [item(100, 20, 2, 1)]})  # reconnect resync
    assert len(s.players[1].items) == 1
    assert s.summary()["players"][0]["progression_received"] == 1


def test_join_part_ignores_trackers():
    s = make_state()
    s.on_print_json({"type": "Join", "team": 0, "slot": 1, "tags": ["Tracker"]})
    assert s.players[1].online == 0 and not s.events
    s.on_print_json({"type": "Join", "team": 0, "slot": 1, "tags": ["AP"]})
    assert s.players[1].online == 1
    s.on_print_json({"type": "Part", "team": 0, "slot": 1})
    s.on_print_json({"type": "Part", "team": 0, "slot": 1})  # tracker leaving: no underflow
    assert s.players[1].online == 0
    assert [e["type"] for e in s.events] == ["join", "part"]


def test_status_goal_and_release():
    s = make_state()
    s.on_set_reply("_read_client_status_0_1", STATUS_PLAYING)
    assert s.summary()["players"][0]["status"] == "playing"
    s.on_print_json({"type": "Goal", "team": 0, "slot": 1})
    s.on_print_json({"type": "Release", "team": 0, "slot": 2})
    players = s.summary()["players"]
    assert players[0]["status"] == "goal" and players[1]["status"] == "released"
    assert s.summary()["totals"]["goals"] == 2
    assert s.players[1].status == STATUS_GOAL
    s.on_print_json({"type": "Release", "team": 0, "slot": 1})  # auto-release on goal
    assert s.summary()["players"][0]["status"] == "goal"


def test_hints_dedupe_across_slots():
    s = make_state()
    hint = {"receiving_player": 1, "finding_player": 2, "location": 21, "item": 101, "found": False,
            "entrance": "", "item_flags": 1, "status": 30, "class": "Hint"}
    s.on_set_reply("_read_hints_0_1", [hint])
    s.on_set_reply("_read_hints_0_2", [hint])
    assert len(s.render_hints()) == 1
    s.on_set_reply("_read_hints_0_2", [{**hint, "found": True}])
    h = s.render_hints()[0]
    assert h["found"] and h["item_name"] == "Key" and h["location_name"] == "Door" and h["finder_name"] == "Bobby"


def test_new_seed_resets():
    s = make_state()
    s.on_print_json({"type": "Goal", "team": 0, "slot": 1})
    assert s.on_room_info(ROOM_INFO) is False
    assert s.events
    assert s.on_room_info({**ROOM_INFO, "seed_name": "9999"}) is True
    assert not s.events and not s.players


def test_persistence_roundtrip(tmp_path):
    s = make_state()
    s.on_print_json({"type": "Goal", "team": 0, "slot": 1}, now=100)
    path = str(tmp_path / "tracker.json")
    s.save(path)

    t = SessionState()
    t.load(path)
    t.on_room_info(ROOM_INFO)
    t.on_connected(1, connected(1, [10], [11, 12, 13]))
    t.on_print_json({"type": "Chat", "team": 0, "slot": 1, "message": "hi"}, now=200)
    t.apply_saved()
    assert [e["type"] for e in t.events] == ["goal", "chat"]
    assert [e["id"] for e in t.events] == [1, 2]
    assert t.players[1].last_activity == 100  # restored from disk (chat is not "activity")

    u = SessionState()
    u.load(path)
    u.on_room_info({**ROOM_INFO, "seed_name": "other"})
    u.apply_saved()
    assert not u.events  # history from a different seed is ignored
