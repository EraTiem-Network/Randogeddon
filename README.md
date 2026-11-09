# Archipelago session server + live tracker

Self-hosted [Archipelago](https://archipelago.gg) multiworld server (plain `MultiServer`, AP **0.6.8**)
plus a custom live dashboard. Everything runs in Docker behind an existing Traefik.

```
              players' game clients                      browser
                       │ wss://ap.example.com:443           │ https://tracker.example.com
                 ┌─────▼─────────────── Traefik ────────────▼─────┐
                 │                                                │
        ┌────────▼────────┐  ws://archipelago:38281   ┌───────────▼──────────┐
        │  archipelago    │◄──────────────────────────┤  tracker (aiohttp)   │
        │  MultiServer.py │   Tracker-tag clients     │  /api/state, SSE, UI │
        └──────┬──────────┘                           └───────┬──────────────┘
          ./data (seed, .apsave, room.json)            volume tracker-state (event history)
```

| Path | What it is |
|---|---|
| `archipelago/` | Image built from the pinned upstream release; used for **both** generation and the server |
| `tracker/` | The dashboard: Python + aiohttp, vanilla-JS UI, no build step |
| `players/` | One YAML per player (gitignored) |
| `custom_worlds/` | Extra `.apworld` files for games that aren't bundled (gitignored) |
| `output/` | Generated `AP_<seed>.zip` (gitignored, contains the spoiler) |
| `data/` | Server side: active seed, `.apsave` savegame, `room.json` (gitignored) |
| `scripts/generate.sh` | Generate a seed locally |
| `scripts/deploy-seed.sh` | Push a seed to the server (or install it locally) and restart |

## One-time server setup

```sh
git clone <this repo> /srv/archipelago && cd /srv/archipelago
cp .env.example .env     # set AP_HOST, TRACKER_HOST, TRAEFIK_*, AP_PASSWORD …
docker compose build     # takes a few minutes (installs every world's requirements)
```

`.env` sets `COMPOSE_FILE=docker-compose.yml:docker-compose.traefik.yml`, so plain `docker compose …`
always uses the Traefik labels. Check `TRAEFIK_NETWORK`, `TRAEFIK_ENTRYPOINT` and
`TRAEFIK_CERTRESOLVER` against your Traefik setup. If you configure Traefik via the file provider
instead of labels, the two routes are:

- `Host(ap.example.com)` → `http://archipelago:38281` (websocket; Traefik handles the upgrade, no extra config)
- `Host(tracker.example.com)` → `http://tracker:8000` (uses SSE; Traefik doesn't buffer it by default)

The dashboard shows hints, which are mild spoilers. To restrict it, the Traefik overlay has a
commented-out basic-auth middleware.

Until a seed is deployed, the server container waits and logs `No seed in /data yet`, and the
dashboard shows "server offline".

## Session day

1. **Collect YAMLs** in `players/`, one per player, each with a unique `name:`. Players export them
   from the game's options page on archipelago.gg or via the Launcher's *Generate Template Options*.
   Only `.yaml`/`.yml` files may be in that folder, because `Generate.py` parses every file there.
   Put any non-bundled games' `.apworld` files in `custom_worlds/`, **both** locally and on the server.
2. **Generate** locally:
   ```sh
   ./scripts/generate.sh                 # extra args go to Generate.py, e.g. --spoiler 3 --seed 42
   ```
   This creates `output/AP_<seed>.zip`, containing the multidata, the spoiler log and each player's
   patch file (for ROM-based games).
3. **Deploy:**
   ```sh
   ./scripts/deploy-seed.sh output/AP_<seed>.zip you@server:/srv/archipelago
   ```
   This copies the zip into `data/`, moves any previous seed and save to `data/old/`, and recreates
   both containers. The server loads the zip directly.
4. **Hand out** each player's patch file from the zip (if their game uses one) and the connection info:
   - Server: `ap.example.com:443`. Clients try `wss://` first, so TLS through Traefik just works.
   - Slot name: the `name:` from their YAML. Password: `AP_PASSWORD`.
5. **Watch** at `https://tracker.example.com`.

### Running commands on the server console

```sh
docker attach archipelago-archipelago-1     # detach with Ctrl-P Ctrl-Q (NOT Ctrl-C, that stops the server)
/players              # who's connected
/send <player> <item> # give an item
/hint <player> <item> # free hint
/release <player>     # release a player's items (e.g. someone has to drop out)
/collect <player>
/exit                 # stop the server (the container restarts it automatically)
```

Players can also use `!hint <item>`, `!remaining`, `!release`, `!collect` and `!countdown 10` from their
client, depending on the `AP_*_MODE` settings in `.env`. `AP_ADMIN_PASSWORD` enables `!admin` for
remote server commands.

### Progress and restarts

The server saves to `data/AP_<seed>.apsave` continuously, and `restart: unless-stopped` brings it
back after crashes or reboots. Stopping with `docker compose down` and starting with `up -d` keeps the
progress. **Deploying a new seed starts over.** The old save is kept in `data/old/` if you need it.

## The dashboard

The tracker connects to the room like any other Archipelago tracker client (tag `Tracker`). It
doesn't read server internals. It opens one connection per slot, because the server only tells each
slot about its own checked locations and received items. The slot names come from `data/room.json`,
which the server container writes on startup.

- **Overview:** overall completion, players at goal, game clients online, hint count.
- **Player cards:** progress bar, status badge (idle / playing / goal / released / complete), online dot,
  last activity, items and progression received, hint points. Click a card for the received-item list
  (filterable) and the remaining locations.
- **Live feed:** item sends colour-coded by classification (purple = progression, blue = useful,
  red = trap), hints, goals, releases, chat and joins. You can filter by player and kind.
- **Hints:** every hint in the room with found/open status, filterable by player.

Known limitations:

- Each tracker connection makes the server post "X tracking … has joined" to the chat. That happens
  once per slot whenever the tracker (re)connects.
- "Online" counts game clients that joined *after* the tracker connected. After a tracker restart,
  players who were already connected show as offline until they reconnect.
- The event history keeps the last 500 events, stored in the `tracker-state` volume.

API: `GET /api/state` (summary JSON), `GET /api/player/<slot>`, `GET /api/stream` (SSE), `GET /healthz`.

## Local testing

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml   # publishes 38281 + 8000, no Traefik
./scripts/generate.sh && ./scripts/deploy-seed.sh output/AP_*.zip
# game server on localhost:38281, dashboard on http://localhost:8000
```

For a test seed that needs no ROM or installed game, use a few YAMLs like
`name: Alice` / `game: APQuest` / `APQuest: {}`.

Tracker unit tests:

```sh
cd tracker && python -m venv .venv && .venv/bin/pip install -e '.[test]' && .venv/bin/pytest
```

## Updating Archipelago

Set `AP_VERSION` in `.env` to a new release tag, then run `docker compose build` and redeploy.
Everyone's client and apworld versions should be compatible with the server version. Seeds are
generally playable on newer servers but not older ones, so generate and serve with the same image.
