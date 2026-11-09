"""Web layer: JSON API, Server-Sent Events stream and the static dashboard."""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
from pathlib import Path

from aiohttp import web

from .ap_client import TrackerService, from_env
from .state import SessionState

STATIC = Path(__file__).parent / "static"
log = logging.getLogger("tracker.web")

state_key = web.AppKey("state", SessionState)
service_key = web.AppKey("service", TrackerService)


class SummaryCache:
    def __init__(self, state: SessionState, service: TrackerService):
        self.state, self.service = state, service
        self._version = -1
        self._json = ""

    def get(self) -> str:
        if self.state.version != self._version:
            self._version = self.state.version
            data = self.state.summary()
            data["title"] = os.environ.get("TRACKER_TITLE", "Archipelago")
            data["error"] = self.service.last_error
            self._json = json.dumps(data, separators=(",", ":"))
        return self._json


cache_key = web.AppKey("cache", SummaryCache)


async def index(request: web.Request) -> web.FileResponse:
    return web.FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})


async def api_state(request: web.Request) -> web.Response:
    return web.Response(text=request.app[cache_key].get(), content_type="application/json")


async def api_player(request: web.Request) -> web.Response:
    try:
        slot = int(request.match_info["slot"])
    except ValueError:
        raise web.HTTPBadRequest()
    detail = request.app[state_key].player_detail(slot)
    if detail is None:
        raise web.HTTPNotFound()
    return web.json_response(detail)


async def api_stream(request: web.Request) -> web.StreamResponse:
    """SSE: pushes a full summary whenever the state changes (polled, debounced)."""
    resp = web.StreamResponse(headers={
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",
    })
    await resp.prepare(request)
    state, cache = request.app[state_key], request.app[cache_key]
    sent_version = -1
    idle = 0.0
    try:
        while True:
            if state.version != sent_version:
                sent_version = state.version
                await resp.write(f"event: state\ndata: {cache.get()}\n\n".encode())
                idle = 0.0
            elif idle >= 15:
                await resp.write(b": keepalive\n\n")
                idle = 0.0
            await asyncio.sleep(0.75)
            idle += 0.75
    except (ConnectionResetError, asyncio.CancelledError):
        pass
    return resp


async def healthz(request: web.Request) -> web.Response:
    return web.json_response({"ok": True, "connected": request.app[state_key].connected})


async def _run_service(app: web.Application):
    task = asyncio.create_task(app[service_key].run())
    yield
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await task


def make_app(state: SessionState | None = None, service: TrackerService | None = None) -> web.Application:
    state = state or SessionState()
    service = service or from_env(state)
    app = web.Application()
    app[state_key] = state
    app[service_key] = service
    app[cache_key] = SummaryCache(state, service)
    app.router.add_get("/", index)
    app.router.add_get("/api/state", api_state)
    app.router.add_get("/api/player/{slot}", api_player)
    app.router.add_get("/api/stream", api_stream)
    app.router.add_get("/healthz", healthz)
    app.router.add_static("/static", STATIC)
    app.cleanup_ctx.append(_run_service)
    return app


def main() -> None:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    web.run_app(make_app(), host="0.0.0.0", port=int(os.environ.get("PORT", "8000")), access_log=None)


if __name__ == "__main__":
    main()
