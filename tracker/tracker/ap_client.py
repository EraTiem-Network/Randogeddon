"""Archipelago network-protocol client that feeds SessionState.

One *primary* connection (Tracker tag) receives the room-wide information: RoomInfo, data
package, every PrintJSON event, and the data-storage keys for hints and client status of
all slots. It then starts one *secondary* connection per remaining slot (Tracker + NoText)
only to learn each slot's checked locations and received items, which the server only sends
to a slot's own connections.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
from typing import Any

import aiohttp

from .state import SLOT_PLAYER, SessionState

log = logging.getLogger("tracker.ap")

ITEMS_ALL = 0b111


class ConnectionRefused(Exception):
    def __init__(self, errors: list[str]):
        super().__init__(", ".join(errors))
        self.errors = errors


class APConnection:
    def __init__(self, session: aiohttp.ClientSession, url: str):
        self._http = session
        self.url = url
        self.ws: aiohttp.ClientWebSocketResponse | None = None

    async def __aenter__(self) -> "APConnection":
        self.ws = await self._http.ws_connect(self.url, heartbeat=30, compress=15, max_msg_size=0)
        return self

    async def __aexit__(self, *exc: Any) -> None:
        if self.ws is not None:
            await self.ws.close()

    async def send(self, *packets: dict) -> None:
        assert self.ws is not None
        await self.ws.send_str(json.dumps(list(packets)))

    async def packets(self):
        assert self.ws is not None
        async for msg in self.ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                for pkt in json.loads(msg.data):
                    yield pkt
            elif msg.type in (aiohttp.WSMsgType.ERROR, aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED):
                break
        raise ConnectionError("websocket closed")

    async def handshake(self, slot_name: str, password: str, tags: list[str]) -> tuple[dict, dict]:
        """Wait for RoomInfo, send Connect, return (room_info, connected)."""
        it = self.packets().__aiter__()
        room_info = await _expect(it, "RoomInfo")
        await self.send({
            "cmd": "Connect",
            "password": password,
            "game": "",
            "name": slot_name,
            "uuid": f"ap-dashboard-{slot_name}",
            "version": room_info.get("version", {"major": 0, "minor": 6, "build": 0, "class": "Version"}),
            "items_handling": ITEMS_ALL,
            "tags": tags,
            "slot_data": False,
        })
        connected = await _expect(it, "Connected")
        self._iter = it
        return room_info, connected

    def rest(self):
        """Iterator over packets after the handshake."""
        return self._iter


async def _expect(it, cmd: str) -> dict:
    async for pkt in it:
        if pkt.get("cmd") == cmd:
            return pkt
        if pkt.get("cmd") == "ConnectionRefused":
            raise ConnectionRefused(pkt.get("errors", []))
    raise ConnectionError(f"connection closed while waiting for {cmd}")


class TrackerService:
    def __init__(self, state: SessionState, url: str, password: str, room_json: str,
                 state_path: str, bootstrap_slot: str | None = None):
        self.state = state
        self.url = url
        self.password = password
        self.room_json = room_json
        self.state_path = state_path
        self.bootstrap_slot = bootstrap_slot
        self.last_error: str | None = None

    # -------------------------------------------------------------- helpers
    def _slot_names(self) -> list[str]:
        if self.bootstrap_slot:
            return [self.bootstrap_slot]
        try:
            with open(self.room_json, encoding="utf-8") as f:
                room = json.load(f)
        except (OSError, ValueError):
            return []
        return [s["name"] for s in room.get("slots", []) if s.get("type") == SLOT_PLAYER]

    # ------------------------------------------------------------ main loop
    async def run(self) -> None:
        self.state.load(self.state_path)
        saver = asyncio.create_task(self._autosave())
        backoff = 2
        try:
            async with aiohttp.ClientSession() as http:
                while True:
                    names = self._slot_names()
                    if not names:
                        self._error(f"no slots known yet (waiting for {self.room_json})")
                        await asyncio.sleep(10)
                        continue
                    try:
                        await self._primary(http, names[0])
                        backoff = 2
                    except ConnectionRefused as e:
                        self._error(f"server refused connection: {e}")
                    except (aiohttp.ClientError, ConnectionError, OSError, asyncio.TimeoutError) as e:
                        self._error(f"connection problem: {e!r}")
                    except Exception:
                        log.exception("unexpected tracker error")
                        self._error("unexpected error, see logs")
                    self.state.connected = False
                    self.state.on_connection_lost()
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, 5)
        finally:
            saver.cancel()
            self._save()

    def _error(self, msg: str) -> None:
        if msg != self.last_error:
            log.warning(msg)
        self.last_error = msg

    async def _primary(self, http: aiohttp.ClientSession, slot_name: str) -> None:
        state = self.state
        async with APConnection(http, self.url) as conn:
            room_info, connected = await conn.handshake(slot_name, self.password, ["Tracker"])
            if state.on_room_info(room_info):
                log.info("seed %s", state.seed_name)
            await conn.send({"cmd": "GetDataPackage", "games": sorted(set(room_info.get("games", [])) | {"Archipelago"})})
            my_slot = int(connected["slot"])
            team = int(connected["team"])
            state.on_connected(my_slot, connected)
            state.apply_saved()
            state.connected = True
            self.last_error = None
            log.info("connected as tracker for %s (%d players)", slot_name, len(state.players))

            keys = [f"_read_hints_{team}_{s}" for s in state.players] + \
                   [f"_read_client_status_{team}_{s}" for s in state.players]
            await conn.send({"cmd": "SetNotify", "keys": keys}, {"cmd": "Get", "keys": keys})

            others = {s: p.name for s, p in state.players.items() if s != my_slot}
            secondaries = [asyncio.create_task(self._secondary(http, s, name)) for s, name in others.items()]
            try:
                async for pkt in conn.rest():
                    self._dispatch(my_slot, pkt, primary=True)
            finally:
                for t in secondaries:
                    t.cancel()
                await asyncio.gather(*secondaries, return_exceptions=True)

    async def _secondary(self, http: aiohttp.ClientSession, slot: int, slot_name: str) -> None:
        backoff = 2
        while True:
            try:
                async with APConnection(http, self.url) as conn:
                    _, connected = await conn.handshake(slot_name, self.password, ["Tracker", "NoText"])
                    self.state.on_connected(slot, connected)
                    backoff = 2
                    async for pkt in conn.rest():
                        self._dispatch(slot, pkt, primary=False)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # keep trying; the primary decides when to give up
                log.info("slot %s connection: %r", slot_name, e)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 5)

    def _dispatch(self, slot: int, pkt: dict, primary: bool) -> None:
        cmd = pkt.get("cmd")
        s = self.state
        if cmd == "ReceivedItems":
            s.on_received_items(slot, pkt)
        elif cmd == "RoomUpdate":
            s.on_room_update(slot, pkt)
        elif cmd == "Connected":
            s.on_connected(slot, pkt)
        elif not primary:
            return
        elif cmd == "PrintJSON":
            s.on_print_json(pkt)
        elif cmd == "SetReply":
            s.on_set_reply(pkt["key"], pkt.get("value"))
        elif cmd == "Retrieved":
            for key, value in pkt.get("keys", {}).items():
                s.on_set_reply(key, value)
        elif cmd == "DataPackage":
            s.on_data_package(pkt)
        elif cmd == "RoomInfo":
            s.on_room_info(pkt)

    # ---------------------------------------------------------- persistence
    async def _autosave(self) -> None:
        saved_version = -1
        while True:
            await asyncio.sleep(15)
            if self.state.version != saved_version and self.state.seed_name:
                saved_version = self.state.version
                self._save()

    def _save(self) -> None:
        if not self.state.seed_name:
            return
        try:
            self.state.save(self.state_path)
        except OSError as e:
            log.warning("could not save state: %s", e)


def from_env(state: SessionState) -> TrackerService:
    return TrackerService(
        state=state,
        url=os.environ.get("AP_SERVER", "ws://localhost:38281"),
        password=os.environ.get("AP_PASSWORD", ""),
        room_json=os.environ.get("ROOM_JSON", "/data/room.json"),
        state_path=os.environ.get("STATE_PATH", "/state/tracker.json"),
        bootstrap_slot=os.environ.get("TRACKER_SLOT") or None,
    )
