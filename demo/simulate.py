"""Plays the demo session (demo/players) against a running server, so the dashboard has something to show.

Fake game clients connect to every world that is open, check locations (quickly at first, then slowly, so the
dashboard keeps moving), chat a little, finish one world and find the keys of the locked worlds on a schedule:
the first two keys are found after a while, the third one late (it is hinted first). Every world reaches its goal
within about DEMO_MINUTES (default 15); then the script exits, which `scripts/demo.sh loop` takes as the end of a
round.

Locked worlds are only played once their key has been found, just like the real session.

Run inside the gateway image (it has Archipelago and aiohttp):
    docker run --rm --network randogeddon_default -v $PWD/demo:/demo:ro -v $PWD/output/demo:/slots:ro \
        --entrypoint python randogeddon-gateway:0.6.8 /demo/simulate.py ws://gateway:38281 /slots/slots.json
"""
import asyncio
import json
import os
import random
import sys

import aiohttp

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://localhost:38281"
# Slot name -> password, from `randogeddon slots --json`
PASSWORDS = json.load(open(sys.argv[2]))["slots"] if len(sys.argv) > 2 else {}
LOCK_SLOT = "Locks"
# When each key (sorted by key name) may be picked up, in seconds after start; None = only hinted. The last one is
# found late, so a world stays locked for a while (and its key is hinted first), but every world can finish.
KEY_SCHEDULE = [25, 75, 180]
LATE_KEY = 120           # keys due later than this are hinted at HINT_AFTER
HINT_AFTER = 40
# The whole demo takes about this long: every world spreads its remaining checks evenly up to the end
END = float(os.environ.get("DEMO_MINUTES", "15")) * 60
FAST_PHASE = 30          # seconds of quick checks at the start
FAST_SHARE = 0.3         # share of a world's locations checked in the fast phase
BREAKS = {"Bob-Timespinner": (100, 40)}  # world -> (leave after s, come back after s), for the connection log
CHAT = ["gl hf everyone!", "who has my dash?", "that was a long dungeon", "BK for a bit, brb", "nice send!",
        "this seed is spicy", "hint me senpai", "found something shiny"]

random.seed(4)
loop = asyncio.get_event_loop
start = 0.0
unlocked: set[str] = set()      # slot names whose key has been found
key_of: dict[str, str] = {}     # locked slot -> key item name
key_items: dict[int, str] = {}  # World Locks item id -> key name


def elapsed() -> float:
    return loop().time() - start


async def session(name: str, game: str, tags=("AP",)):
    """Connects one client. A background task keeps reading, which also answers the server's keep-alive pings."""
    http = aiohttp.ClientSession()
    ws = await http.ws_connect(URL, max_msg_size=0, compress=15, heartbeat=20)
    queue: asyncio.Queue = asyncio.Queue()

    async def reader():
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                for packet in json.loads(msg.data):
                    queue.put_nowait(packet)
        queue.put_nowait({"cmd": "_closed"})

    asyncio.create_task(reader())

    async def recv(cmd=None):
        while True:
            packet = await queue.get()
            if packet["cmd"] == "_closed":
                raise ConnectionError(f"{name}: connection closed")
            if packet["cmd"] == "ConnectionRefused":
                raise SystemExit(f"{name}: {packet}")
            if cmd is None or packet["cmd"] == cmd:
                return packet

    room = await recv("RoomInfo")
    await ws.send_json([{"cmd": "Connect", "password": PASSWORDS.get(name, ""), "game": game, "name": name,
                         "uuid": f"demo-{name}", "version": room["version"], "items_handling": 0b111, "tags": list(tags),
                         "slot_data": True}])
    connected = await recv("Connected")
    return http, ws, recv, connected


async def watch_keys():
    """The Locks slot: learns which key opens which world and sees keys arrive."""
    _, ws, recv, connected = await session(LOCK_SLOT, "", tags=("Tracker",))
    key_of.update({world: key for key, world in connected["slot_data"]["locks"].items()})
    names = {v: k for k, v in key_of.items()}
    await ws.send_json([{"cmd": "GetDataPackage", "games": ["World Locks"]}])
    while True:
        packet = await recv()
        if packet["cmd"] == "DataPackage":
            key_items.update({v: k for k, v in packet["data"]["games"]["World Locks"]["item_name_to_id"].items()
                              if k.startswith("World Key ")})
        elif packet["cmd"] == "ReceivedItems":
            for item in packet["items"]:
                key = key_items.get(item["item"])
                if key in names:
                    unlocked.add(names[key])


async def play(name: str, game: str, finisher: bool):
    while name in key_of and name not in unlocked:
        await asyncio.sleep(1)
    http, ws, recv, connected = await session(name, game)
    missing = list(connected["missing_locations"])
    random.shuffle(missing)
    await ws.send_json([{"cmd": "StatusUpdate", "status": 20}])

    # Which of my locations hold keys? Scouting doesn't reveal anything to the players, only to this script.
    await ws.send_json([{"cmd": "LocationScouts", "locations": missing, "create_as_hint": 0}])
    scouted = (await recv("LocationInfo"))["locations"]
    my_keys = {}
    for info in scouted:
        if info["player"] == lock_player and info["item"] in key_items:
            index = int(key_items[info["item"]].rsplit(" ", 1)[1]) - 1  # "World Key 3" -> 2
            my_keys[info["location"]] = KEY_SCHEDULE[index] if index < len(KEY_SCHEDULE) else None
    hinted = False

    total = len(connected["checked_locations"]) + len(missing)
    fast = int(total * FAST_SHARE)
    checked = 0
    took_break = False
    while missing:
        now = elapsed()
        if name in BREAKS and not took_break and now >= BREAKS[name][0]:
            took_break = True
            await ws.send_json([{"cmd": "Say", "text": "dinner, back in a bit"}])
            await ws.close()
            await http.close()
            await asyncio.sleep(BREAKS[name][1])
            http, ws, recv, connected = await session(name, game)
            await ws.send_json([{"cmd": "StatusUpdate", "status": 20}, {"cmd": "Say", "text": "back!"}])
            continue
        # A key location only once its time has come
        candidates = [loc for loc in missing if loc not in my_keys or (my_keys[loc] is not None and now >= my_keys[loc])]
        due = [loc for loc in candidates if loc in my_keys]
        if not hinted and now >= HINT_AFTER:
            hinted = True
            # A few ordinary hints too, like a shop or a hint statue would create (automatic hints)
            extra = [loc for loc in missing if loc not in my_keys][:2]
            if extra:
                await ws.send_json([{"cmd": "LocationScouts", "locations": extra, "create_as_hint": 2}])
            for loc, when in my_keys.items():
                if when is None or when > LATE_KEY:
                    await ws.send_json([{"cmd": "LocationScouts", "locations": [loc], "create_as_hint": 2}])
        if not candidates:
            await asyncio.sleep(2)
            continue
        location = due[0] if due else candidates[0]
        missing.remove(location)
        checked += 1
        await ws.send_json([{"cmd": "LocationChecks", "locations": [location]}])
        if random.random() < 0.04:
            await ws.send_json([{"cmd": "Say", "text": random.choice(CHAT)}])
        if now < FAST_PHASE and checked < fast:
            await asyncio.sleep(random.uniform(0.2, 0.6))
        elif missing:
            # Evenly up to this world's end (the finisher is done halfway), a little irregular
            end = END * (0.5 if finisher else 1.0)
            pause = max(0.3, (end - elapsed()) / len(missing))
            await asyncio.sleep(pause * random.uniform(0.6, 1.4))
    await ws.send_json([{"cmd": "StatusUpdate", "status": 30}])
    await ws.send_json([{"cmd": "Say", "text": "done! GG"}])
    print(f"{name} done after {elapsed():.0f} s", flush=True)
    # Stays connected (online on the dashboard) until every world is done


async def main():
    global start, lock_player
    start = loop().time()
    # Learn the slots from the Locks slot's Connected packet
    http, ws, recv, connected = await session(LOCK_SLOT, "", tags=("Tracker",))
    slots = {int(s): info for s, info in connected["slot_info"].items()}
    lock_player = next(s for s, info in slots.items() if info["game"] == "World Locks")
    await ws.close()
    await http.close()

    asyncio.create_task(watch_keys())
    while not key_items or not key_of:
        await asyncio.sleep(0.2)

    worlds = [(info["name"], info["game"]) for s, info in sorted(slots.items()) if info["game"] != "World Locks"]
    # The world that finishes during the demo: the smallest one that starts open
    finisher = "Team-RoR2"
    tasks = [asyncio.create_task(play(name, game, name == finisher)) for name, game in worlds]
    print(f"Playing {len(worlds)} worlds in about {END / 60:.0f} minutes; locked: {', '.join(key_of)}", flush=True)
    await asyncio.gather(*tasks)
    # Every world is at its goal: the session is at 100 %. The exit tells scripts/demo.sh loop to start a new round.
    print(f"All worlds done after {elapsed():.0f} s", flush=True)


lock_player = 0
asyncio.run(main())
