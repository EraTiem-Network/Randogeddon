# Archipelago session server + live tracker

Self-hosted [Archipelago](https://archipelago.gg) multiworld server (plain `MultiServer`, AP **0.6.8**)
plus a custom live dashboard. Everything runs in Docker behind an existing Traefik.

```
              players' game clients                      browser
                       │ wss://ap.example.com:443           │ https://tracker.example.com
                 ┌─────▼─────────────── Traefik ────────────▼─────┐
                 │                                                │
        ┌────────▼────────┐  ws://archipelago:38281   ┌───────────▼──────────┐
        │  archipelago    │◄──────────────────────────┤  tracker (Ktor)      │
        │  MultiServer.py │   Tracker-tag clients     │  API, SSE, Wasm app  │
        └──────┬──────────┘                           └───────┬──────────────┘
          ./data (seed, .apsave, room.json)            volume tracker-state (event history)
```

| Path | What it is |
|---|---|
| `archipelago/` | Image built from the pinned upstream release; used for **both** generation and the server |
| `server/` | Tracker server: Kotlin + Ktor. Follows the room, serves the API and the web app |
| `webApp/` | The dashboard: Compose Multiplatform (Kotlin/Wasm), installable as a PWA |
| `shared/` | The API contract (Kotlin Multiplatform data classes) used by both |
| `apworlds/world_locks/` | Our own apworld: worlds that start locked until their key is found (packed by `scripts/build-apworlds.sh`) |
| `session.yaml` | Who plays which worlds, for the dashboard (copy `session.example.yaml`; gitignored) |
| `demo/` | An example session (YAMLs, `session.yaml`, simulated players) — see *Demo* below |
| `tracker.Dockerfile` | Builds the tracker image from the Gradle build |
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
docker compose build     # takes a few minutes (every world's requirements, the Gradle build)
```

Building the tracker image needs **BuildKit** (the `docker buildx` plugin, included in Docker's own packages;
on Arch: `pacman -S docker-buildx`) and your Gradle init script with the Nexus credentials. Compose passes the init
script into the build as a secret (default `~/.gradle/init.gradle.kts`, change with `GRADLE_INIT_SCRIPT` in `.env`);
it never ends up in the image. If the server has no init script, build on your PC and copy the image over:
`docker save archipelago-tracker:latest | ssh you@server docker load`.

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

1. **Collect YAMLs** in `players/`, one per world, each with a unique `name:` (e.g. `Alice-HK`, `Alice-Celeste`). Players export them
   from the game's options page on archipelago.gg or via the Launcher's *Generate Template Options*.
   Only `.yaml`/`.yml` files may be in that folder, because `Generate.py` parses every file there.
   Put any non-bundled games' `.apworld` files in `custom_worlds/`, **both** locally and on the server.
   For locked worlds add a World Locks YAML (see below), and write `session.yaml` (who plays which worlds).
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
   This copies the zip (and `session.yaml`, if there is one) into `data/`, moves any previous seed and save to
   `data/old/`, and recreates both containers. The server loads the zip directly. `session.yaml` can be edited on
   the server at any time; the dashboard picks up changes by itself.
4. **Hand out** each player's patch file from the zip (if their game uses one) and the connection info:
   - Server: `ap.example.com:443`. Clients try `wss://` first, so TLS through Traefik just works.
   - Slot name: the `name:` from their YAML. Password: `AP_PASSWORD`.
5. **Watch** at `https://tracker.example.com`. Phones can install it as an app (see below).

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

## Hints

A hint tells a player where one of their items is ("Alice's *Mantis Claw* is at *Sky Meadow: Chest 4* in Bob's
world"). There are two systems, each switched in `.env` (`AP_POINT_HINTS`, `AP_AUTO_HINTS`, applied on restart):

- **Point hints:** checking a location earns `AP_LOCATION_CHECK_POINTS` points (default 1); `!hint <item>` or
  `!hint_location <location>` costs `AP_HINT_COST` % of the player's world's locations (default 10 %, `0` = free).
  Players can mark hints *priority*, *avoid* or *no priority* in their client; the dashboard shows that.
- **Automatic hints:** some games create hints by themselves, e.g. a shop that hints what it sells.

MultiServer has no switches for this, so the server runs through `archipelago/serve.py`, a thin wrapper: with
point hints off, `!hint`/`!hint_location` answer "Hints are turned off"; with automatic hints off, games can still
scout locations but no hints are created. The host can always give hints from the server console (`/hint`).
With both off, the dashboard hides hints entirely (unless the host handed some out). With point hints on, every
world shows its points and how many hints they buy.

## Locked worlds

Some worlds can start locked and open only when their key is found in another world. This is part of the generated
seed, not just of the dashboard: the `World Locks` apworld (`apworlds/world_locks/`, packed into
`custom_worlds/world_locks.apworld` by `generate.sh`) adds one key item per locked world and tells the generator that
nothing in that world is reachable without its key. So every seed stays completable, chains included (a key inside a
world that is itself locked).

Add one extra YAML to `players/` (nobody plays this slot):

```yaml
name: Locks
game: World Locks
World Locks:
  locked_worlds: ["Alice-Celeste", "Bob-Yacht"]   # slot names
  early_keys: false                               # true: keys are reachable from the start, worlds open early
```

The game clients don't know about the lock, so it's on the honour system: don't start a locked game before the
dashboard says it's unlocked. Keys show up as "World Key 1, 2, …" in game clients (`!hint World Key 2` works from the
Locks slot), and as "Key to Alice-Celeste" on the dashboard, which also shows who found it or where it was hinted.

## The dashboard

The tracker connects to the room like any other Archipelago tracker client (tag `Tracker`). It
doesn't read server internals. It opens one connection per slot, because the server only tells each
slot about its own checked locations and received items. The slot names come from `data/room.json`,
which the server container writes on startup.

- **Overview:** overall completion, worlds at goal, game clients online, locked worlds opened, hint count.
- **People:** one card per person of `session.yaml` with their worlds: game, progress, status
  (not started / playing / locked / goal / released / complete), online dot, last activity, and for locked worlds
  the key: who found it, or where it was hinted. Without `session.yaml`, every world is its own card. Click a
  person's name for their overview (totals, worlds, open hints, combined connection log, recent activity). Click a world
  for its received items (filterable), remaining locations, play time and connection log.
- **Play time:** time a game client was connected to a world, per world and per person, kept across restarts.
- **Live feed:** item sends colour-coded by classification (purple = progression, blue = useful,
  red = trap), hints, goals, releases, chat and joins. You can filter by player and kind.
- **Hints:** every hint in the room, filterable by player (person), world and state (not found, found, priority,
  avoid, no priority, unmarked).
- **Layout:** the live feed can be hidden (list button in the header) and placed right, left or below; each viewer's
  choice is remembered in their browser. On narrow screens it always sits below the players.

Known limitations:

- Each tracker connection makes the server post "X tracking … has joined" to the chat. That happens
  once per slot whenever the tracker (re)connects.
- "Online" comes from join/leave messages. After a tracker restart a world counts as online again as soon as it
  does something (finds its own item, chats, changes status); such sessions are marked "seen playing" in the
  world's connection log, since their real start is unknown.
- The event history keeps the last 500 events, stored in the `tracker-state` volume.

API: `GET /api/state` (summary JSON), `GET /api/player/<slot>`, `GET /api/stream` (SSE), `GET /healthz`.

### Install on your phone

The dashboard is a web app (PWA), so no app store or Apple account is needed:

- **Android (Chrome):** open `https://tracker.example.com` → menu ⋮ → *Install app* (or *Add to Home screen*).
- **iPhone / iPad (Safari):** open the page → Share → *Add to Home Screen*. It opens full screen with its own icon.

It needs a browser with WebAssembly GC: iOS/Safari 18.2+, Chrome/Edge 119+, Firefox 120+. Older browsers get a
message asking for an update. The first load is about 4 MB (gzip); after that the browser has it cached.

## Demo

```sh
./scripts/demo.sh        # generates demo/players, deploys it locally, plays it with simulated players
./scripts/demo.sh stop   # stops the simulated players; `docker compose down` stops everything
```

Three people (Alice, Bob, Carol) with two games each; one of each person's worlds starts locked. The simulator
(`demo/simulate.py`) checks locations quickly for half a minute and then slowly, finds the first key after ~25 s and
the second after ~75 s; the third key is only hinted, so Carol's DLCQuest stays locked. It replaces the seed in
`data/` (the old one goes to `data/old/`). Open http://localhost:8000.

## Local testing

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml   # publishes 38281 + 8000, no Traefik
./scripts/generate.sh && ./scripts/deploy-seed.sh output/AP_*.zip
# game server on localhost:38281, dashboard on http://localhost:8000
```

For a test seed that needs no ROM or installed game, use a few YAMLs like
`name: Alice` / `game: APQuest` / `APQuest: {}`.

## Developing the tracker

Gradle 9.8.2 (wrapper), Kotlin 2.4, JDK 25 toolchain; dependencies come from the Nexus via your init script, like in
CoreSync. Run Gradle on JDK 25 (e.g. `JAVA_HOME=/usr/lib/jvm/java-25-temurin`).

```sh
./gradlew :server:test                          # session state tests
./gradlew :webApp:wasmJsBrowserDistribution     # build the web app → webApp/build/dist/wasmJs/productionExecutable
./gradlew :server:run                           # tracker on :8000 against localhost:38281
```

`:server:run` builds the web app first and uses `data/room.json` of this checkout. All settings are in
`server/src/main/resources/application.yaml`, each with the environment variable that overrides it. For UI work, `./gradlew :webApp:wasmJsBrowserDevelopmentRun` starts a dev server with
hot reload that proxies `/api` to the tracker on port 8000.

## Updating Archipelago

Set `AP_VERSION` in `.env` to a new release tag, then run `docker compose build` and redeploy.
Everyone's client and apworld versions should be compatible with the server version. Seeds are
generally playable on newer servers but not older ones, so generate and serve with the same image.
