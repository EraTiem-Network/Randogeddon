"""Aggregated session state, fed by Archipelago protocol packets.

Pure logic (no I/O besides optional JSON persistence) so it can be unit-tested with
recorded packets.
"""
from __future__ import annotations

import json
import os
import tempfile
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any

# NetUtils.ClientStatus
STATUS_UNKNOWN, STATUS_CONNECTED, STATUS_READY, STATUS_PLAYING, STATUS_GOAL = 0, 5, 10, 20, 30
# NetUtils.SlotType
SLOT_SPECTATOR, SLOT_PLAYER, SLOT_GROUP = 0, 1, 2
# BaseClasses.ItemClassification bits
FLAG_PROGRESSION, FLAG_USEFUL, FLAG_TRAP = 0b001, 0b010, 0b100

NON_GAME_TAGS = {"Tracker", "TextOnly", "HintGame"}
MAX_EVENTS = 500


def _item_tuple(raw: dict | list) -> tuple[int, int, int, int]:
    """NetworkItem arrives as {"item","location","player","flags","class"} (or a list)."""
    if isinstance(raw, dict):
        return int(raw["item"]), int(raw["location"]), int(raw["player"]), int(raw.get("flags", 0))
    item, location, player, *rest = raw
    return int(item), int(location), int(player), int(rest[0]) if rest else 0


@dataclass
class Player:
    slot: int
    name: str
    game: str
    alias: str = ""
    checked: set[int] = field(default_factory=set)
    missing: set[int] = field(default_factory=set)
    items: list[tuple[int, int, int, int]] = field(default_factory=list)  # item, location, sender, flags
    status: int = STATUS_UNKNOWN
    online: int = 0  # game clients currently connected (seen via Join/Part since we connected)
    released: bool = False
    last_activity: float | None = None
    hint_points: int | None = None

    @property
    def total(self) -> int:
        return len(self.checked | self.missing)


class SessionState:
    def __init__(self) -> None:
        self.reset(None)
        self.version = 0  # bumped on every change; used by the web layer to push updates
        self.connected = False
        self.started_at = time.time()

    # ------------------------------------------------------------------ basics
    def reset(self, seed_name: str | None) -> None:
        self.seed_name = seed_name
        self.server_version: str | None = None
        self.generator_version: str | None = None
        self.hint_cost: int | None = None
        self.permissions: dict[str, int] = {}
        self.players: dict[int, Player] = {}
        self.groups: dict[int, dict] = {}
        self.events: deque[dict] = deque(maxlen=MAX_EVENTS)
        self.hints: dict[tuple[int, int], dict] = {}  # (finding_player, location) -> hint
        self.items_by_name: dict[str, dict[int, str]] = {}
        self.locations_by_name: dict[str, dict[int, str]] = {}
        self.event_seq = 0

    def touch(self) -> None:
        self.version += 1

    # ----------------------------------------------------------------- packets
    def on_room_info(self, pkt: dict) -> bool:
        """Returns True if this is a different seed than before (state was reset)."""
        seed = pkt.get("seed_name")
        new_seed = seed != self.seed_name
        if new_seed:
            self.reset(seed)
        v = pkt.get("version") or {}
        gv = pkt.get("generator_version") or {}
        self.server_version = _fmt_version(v)
        self.generator_version = _fmt_version(gv)
        self.hint_cost = pkt.get("hint_cost")
        self.permissions = pkt.get("permissions", {})
        self.touch()
        return new_seed

    def on_connected(self, slot: int, pkt: dict) -> None:
        names = {int(p["slot"]): p for p in pkt.get("players", [])}
        for s, info in pkt.get("slot_info", {}).items():
            s = int(s)
            name, game, typ = _slot_fields(info)
            if typ == SLOT_GROUP:
                self.groups[s] = {"name": name, "game": game}
                continue
            if typ != SLOT_PLAYER:
                continue
            player = self.players.get(s)
            if player is None:
                player = self.players[s] = Player(slot=s, name=name, game=game)
            player.alias = names.get(s, {}).get("alias", name)
        player = self.players.get(slot)
        if player is not None:
            player.checked = set(pkt.get("checked_locations", []))
            player.missing = set(pkt.get("missing_locations", []))
            player.hint_points = pkt.get("hint_points")
        self.touch()

    def on_received_items(self, slot: int, pkt: dict) -> None:
        player = self.players.get(slot)
        if player is None:
            return
        index = pkt.get("index", 0)
        items = [_item_tuple(i) for i in pkt.get("items", [])]
        if index == 0:
            player.items = items  # full resync
        elif index == len(player.items):
            player.items.extend(items)
        elif index < len(player.items):
            # overlapping resend: replace the tail
            player.items = player.items[:index] + items
        else:
            # we missed something; the connection layer will resync on next connect
            player.items.extend(items)
        self.touch()

    def on_room_update(self, slot: int, pkt: dict) -> None:
        player = self.players.get(slot)
        if player is not None:
            new = set(pkt.get("checked_locations", []))
            if new:
                player.checked |= new
                player.missing -= new
            if "hint_points" in pkt:
                player.hint_points = pkt["hint_points"]
        if "players" in pkt:
            for p in pkt["players"]:
                pl = self.players.get(int(p["slot"]))
                if pl:
                    pl.alias = p.get("alias", pl.name)
        self.touch()

    def on_set_reply(self, key: str, value: Any) -> None:
        if key.startswith("_read_client_status_"):
            slot = int(key.rsplit("_", 1)[1])
            player = self.players.get(slot)
            if player is not None and value is not None:
                if value == STATUS_GOAL and player.status != STATUS_GOAL and player.last_activity is None:
                    player.last_activity = time.time()
                player.status = int(value)
        elif key.startswith("_read_hints_"):
            for h in value or []:
                hint = {
                    "receiving_player": int(h["receiving_player"]),
                    "finding_player": int(h["finding_player"]),
                    "location": int(h["location"]),
                    "item": int(h["item"]),
                    "found": bool(h["found"]),
                    "entrance": h.get("entrance", ""),
                    "item_flags": int(h.get("item_flags", 0)),
                    "status": int(h.get("status", 0)),
                }
                self.hints[(hint["finding_player"], hint["location"])] = hint
        self.touch()

    def on_data_package(self, pkt: dict) -> None:
        for game, data in pkt.get("data", {}).get("games", {}).items():
            self.items_by_name[game] = {int(v): k for k, v in data.get("item_name_to_id", {}).items()}
            self.locations_by_name[game] = {int(v): k for k, v in data.get("location_name_to_id", {}).items()}
        self.touch()

    def on_connection_lost(self) -> None:
        # We cannot know who stays connected across a server restart.
        for p in self.players.values():
            p.online = 0
        self.touch()

    def on_print_json(self, pkt: dict, now: float | None = None) -> None:
        now = time.time() if now is None else now
        typ = pkt.get("type")
        ev: dict[str, Any] | None = None

        if typ in ("ItemSend", "ItemCheat"):
            item, location, sender, flags = _item_tuple(pkt["item"])
            receiver = int(pkt["receiving"])
            ev = {"type": "item", "sender": sender, "receiver": receiver, "item": item,
                  "location": location, "flags": flags, "cheat": typ == "ItemCheat"}
            if typ == "ItemSend" and sender in self.players:
                self.players[sender].last_activity = now
                # keep progress fresh even before the RoomUpdate for that slot arrives
                self.players[sender].checked.add(location)
                self.players[sender].missing.discard(location)
        elif typ == "Hint":
            item, location, finder, flags = _item_tuple(pkt["item"])
            ev = {"type": "hint", "finder": finder, "receiver": int(pkt["receiving"]), "item": item,
                  "location": location, "flags": flags, "found": bool(pkt.get("found"))}
        elif typ == "Goal":
            slot = int(pkt["slot"])
            ev = {"type": "goal", "slot": slot}
            if slot in self.players:
                self.players[slot].status = STATUS_GOAL
                self.players[slot].last_activity = now
        elif typ in ("Release", "Collect"):
            slot = int(pkt["slot"])
            ev = {"type": typ.lower(), "slot": slot}
            if typ == "Release" and slot in self.players:
                self.players[slot].released = True
        elif typ in ("Join", "Part"):
            slot = int(pkt["slot"])
            tags = set(pkt.get("tags", []))
            player = self.players.get(slot)
            if typ == "Join":
                if tags & NON_GAME_TAGS:
                    return  # trackers / text clients (incl. ourselves) are noise
                if player:
                    player.online += 1
                    player.last_activity = now
                ev = {"type": "join", "slot": slot}
            else:
                # Part carries no tags; only count down if we counted a game client up.
                if not player or player.online <= 0:
                    return
                player.online -= 1
                ev = {"type": "part", "slot": slot}
        elif typ == "Chat":
            ev = {"type": "chat", "slot": int(pkt["slot"]), "message": pkt.get("message", "")}
        elif typ == "ServerChat":
            ev = {"type": "chat", "slot": 0, "message": pkt.get("message", "")}
        elif typ == "Countdown":
            if pkt.get("countdown") == 0:
                ev = {"type": "countdown", "message": "GO!"}
        else:
            return

        if ev is not None:
            self.event_seq += 1
            ev["id"] = self.event_seq
            ev["ts"] = now
            self.events.append(ev)
        self.touch()

    # ----------------------------------------------------------------- naming
    def player_name(self, slot: int) -> str:
        if slot == 0:
            return "Server"
        p = self.players.get(slot)
        if p:
            return p.alias or p.name
        g = self.groups.get(slot)
        return g["name"] if g else f"Player {slot}"

    def game_of(self, slot: int) -> str | None:
        if slot in self.players:
            return self.players[slot].game
        if slot in self.groups:
            return self.groups[slot]["game"]
        return None

    def item_name(self, item: int, owner_slot: int) -> str:
        game = self.game_of(owner_slot)
        for g in (game, "Archipelago"):
            name = self.items_by_name.get(g or "", {}).get(item)
            if name:
                return name
        return f"Item {item}"

    def location_name(self, location: int, owner_slot: int) -> str:
        game = self.game_of(owner_slot)
        for g in (game, "Archipelago"):
            name = self.locations_by_name.get(g or "", {}).get(location)
            if name:
                return name
        return f"Location {location}"

    # ---------------------------------------------------------------- output
    def summary(self, max_events: int = 200) -> dict:
        players = []
        for p in sorted(self.players.values(), key=lambda p: p.slot):
            total = p.total
            players.append({
                "slot": p.slot,
                "name": p.alias or p.name,
                "game": p.game,
                "checked": len(p.checked),
                "total": total,
                "percent": round(100 * len(p.checked) / total, 1) if total else 0.0,
                "status": _status_name(p),
                "online": p.online > 0,
                "last_activity": p.last_activity,
                "items_received": len(p.items),
                "progression_received": sum(1 for i in p.items if i[3] & FLAG_PROGRESSION),
                "hint_points": p.hint_points,
            })
        checked = sum(p["checked"] for p in players)
        total = sum(p["total"] for p in players)
        return {
            "connected": self.connected,
            "seed_name": self.seed_name,
            "server_version": self.server_version,
            "generator_version": self.generator_version,
            "hint_cost": self.hint_cost,
            "started_at": self.started_at,
            "version": self.version,
            "totals": {
                "checked": checked,
                "total": total,
                "percent": round(100 * checked / total, 1) if total else 0.0,
                "players": len(players),
                "goals": sum(1 for p in players if p["status"] in ("goal", "released")),
                "online": sum(1 for p in players if p["online"]),
            },
            "players": players,
            "events": [self.render_event(e) for e in list(self.events)[-max_events:]],
            "hints": self.render_hints(),
        }

    def player_detail(self, slot: int) -> dict | None:
        p = self.players.get(slot)
        if p is None:
            return None
        return {
            "slot": slot,
            "name": p.alias or p.name,
            "game": p.game,
            "items": [
                {"item": self.item_name(item, slot), "from": self.player_name(sender),
                 "location": self.location_name(loc, sender) if sender else "",
                 "flags": flags}
                for item, loc, sender, flags in p.items
            ],
            "missing": sorted(self.location_name(loc, slot) for loc in p.missing),
            "checked": len(p.checked),
            "total": p.total,
        }

    def render_event(self, e: dict) -> dict:
        out = dict(e)
        t = e["type"]
        if t == "item":
            out.update(sender_name=self.player_name(e["sender"]), receiver_name=self.player_name(e["receiver"]),
                       item_name=self.item_name(e["item"], e["receiver"]),
                       location_name=self.location_name(e["location"], e["sender"]))
        elif t == "hint":
            out.update(finder_name=self.player_name(e["finder"]), receiver_name=self.player_name(e["receiver"]),
                       item_name=self.item_name(e["item"], e["receiver"]),
                       location_name=self.location_name(e["location"], e["finder"]))
        elif "slot" in e:
            out["player_name"] = self.player_name(e["slot"])
        return out

    def render_hints(self) -> list[dict]:
        out = []
        for h in self.hints.values():
            out.append({
                **h,
                "finder_name": self.player_name(h["finding_player"]),
                "receiver_name": self.player_name(h["receiving_player"]),
                "item_name": self.item_name(h["item"], h["receiving_player"]),
                "location_name": self.location_name(h["location"], h["finding_player"]),
            })
        out.sort(key=lambda h: (h["found"], h["receiver_name"], h["item_name"]))
        return out

    # ------------------------------------------------------------ persistence
    def save(self, path: str) -> None:
        data = {
            "seed_name": self.seed_name,
            "event_seq": self.event_seq,
            "events": list(self.events),
            "last_activity": {str(s): p.last_activity for s, p in self.players.items()},
        }
        d = os.path.dirname(path) or "."
        os.makedirs(d, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=d, prefix=".tracker-", suffix=".json")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f)
        os.replace(tmp, path)

    def load(self, path: str) -> None:
        """Restore history saved by `save`. Only applied once the seed is known to match."""
        try:
            with open(path, encoding="utf-8") as f:
                self._saved = json.load(f)
        except (OSError, ValueError):
            self._saved = None

    def apply_saved(self) -> None:
        saved = getattr(self, "_saved", None)
        self._saved = None
        if not saved or saved.get("seed_name") != self.seed_name:
            return
        old_seq = int(saved.get("event_seq", 0))
        current = list(self.events)
        for e in current:  # renumber events that arrived before history was restored
            e["id"] += old_seq
        self.events.clear()
        self.events.extend(saved.get("events", []) + current)
        self.event_seq = old_seq + self.event_seq
        for s, ts in saved.get("last_activity", {}).items():
            p = self.players.get(int(s))
            if p and ts and (p.last_activity is None or ts > p.last_activity):
                p.last_activity = ts
        self.touch()


def _slot_fields(info: Any) -> tuple[str, str, int]:
    if isinstance(info, dict):
        return info["name"], info["game"], int(info["type"])
    name, game, typ, *_ = info
    return name, game, int(typ)


def _fmt_version(v: dict) -> str | None:
    if not v:
        return None
    return f"{v.get('major', 0)}.{v.get('minor', 0)}.{v.get('build', 0)}"


def _status_name(p: Player) -> str:
    if p.status == STATUS_GOAL:
        return "goal"
    if p.released:
        return "released"
    if p.total and not p.missing:
        return "complete"
    if p.status >= STATUS_READY:
        return "playing"
    return "idle"
