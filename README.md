# Randogeddon

Our self-hosted [Archipelago](https://archipelago.gg) multiworld server (plain `MultiServer`, AP **0.6.8**)
plus a custom live dashboard. Everything runs in Docker behind an existing Traefik. The dashboard is at
`randogeddon.eratiem.net` and needs a login; the session's game server is at
`<session-id>.randogeddon.eratiem.net:443`, where every slot has its own generated password. The Kotlin apps (tracker
and gateway) run as native executables (GraalVM Native Image, Linux x86_64).

What's done and what comes next (login, admin panel, multiple sessions, user management): [.docs/TODO.md](.docs/TODO.md).

```
              players' game clients                      browser
                       │ wss://<session-id>.randogeddon     │ https://randogeddon
                       │       .example.net:443             │         .example.net
                 ┌─────▼─────────────── Traefik ────────────▼─────┐
                 │                                                │
        ┌────────▼────────┐                           ┌───────────▼──────────┐
        │  gateway        │  checks each Connect's    │  tracker (Ktor)      │
        │  (Ktor)         │  slot password            │  login, API, SSE,    │
        └────────┬────────┘                           │  Wasm app            │
                 │ ws                                 └───┬───────────┬──────┘
        ┌────────▼────────┐  ws://archipelago:38281       │           │
        │  archipelago    │◄──────────────────────────────┘           │
        │  MultiServer.py │   Tracker-tag clients (internal)          │
        └──────┬──────────┘                           ┌───────────────▼──────┐
          ./data (seed, .apsave, room.json)           │  postgres            │◄── gateway reads
                                                      │  logins, slot pw     │    the slot passwords
                                                      └──────────────────────┘
```

MultiServer is reachable only inside the compose network; game clients come in through the gateway.

| Path | What it is |
|---|---|
| `archipelago/` | Image built from the pinned upstream release; used for **both** generation and the server |
| `server/` | Tracker server: Kotlin + Ktor. Follows the room, serves the login, the API and the web app; host commands (`randogeddon logins`) |
| `gateway/` | The game clients' entry: relays each connection to MultiServer and checks the slot password of every `Connect` |
| `database/` | PostgreSQL schema (Flyway migrations) and Exposed tables, shared by tracker and gateway |
| `webApp/` | The dashboard: Compose Multiplatform (Kotlin/Wasm), installable as a PWA |
| `shared/` | The API contract (Kotlin Multiplatform data classes) used by both |
| `apworlds/world_locks/` | Our own apworld: worlds that start locked until their key is found (packed by `scripts/build-apworlds.sh`) |
| `session.yaml` | Who plays which worlds, for the dashboard (copy `session.example.yaml`; gitignored) |
| `demo/` | An example session (YAMLs, `session.yaml`, simulated players) — see *Demo* below |
| `kotlin.Dockerfile` | Builds the tracker and gateway images (native) from the Gradle build |
| `players/` | One YAML per player (gitignored) |
| `custom_worlds/` | Extra `.apworld` files for games that aren't bundled (gitignored) |
| `output/` | Generated `AP_<seed>.zip` (gitignored, contains the spoiler) |
| `data/` | Server side: active seed, `.apsave` savegame, `room.json` (gitignored) |
| `scripts/generate.sh` | Generate a seed locally |
| `scripts/deploy-seed.sh` | Push a seed to the server (or install it locally) and restart |

## One-time server setup

```sh
git clone <this repo> /srv/randogeddon && cd /srv/randogeddon
cp .env.example .env     # set SESSION_ID, POSTGRES_PASSWORD, TRAEFIK_* …
docker compose build     # takes a few minutes (every world's requirements, the native builds)
```

Building the tracker and gateway images needs **BuildKit** (the `docker buildx` plugin, included in Docker's own
packages; on Arch: `pacman -S docker-buildx`) and your Gradle init script with the Nexus credentials. Compose passes
the init script into the build as a secret (default `~/.gradle/init.gradle.kts`, change with `GRADLE_INIT_SCRIPT` in
`.env`); it never ends up in an image. Compose asks once to let the build read that file outside the build context
(`additional privileges requested`); answer yes, or set `BUILDX_BAKE_ENTITLEMENTS_FS=0` as the scripts do. The native
builds need about 8 GB of RAM (peak, tracker). If the server has no init script (or
not the memory), build on your PC and copy the images over:
`docker save randogeddon-tracker:latest randogeddon-gateway:latest | ssh you@server docker load`.

`.env` sets `COMPOSE_FILE=docker-compose.yml:docker-compose.traefik.yml`, so plain `docker compose …`
always uses the Traefik labels. Check `TRAEFIK_NETWORK`, `TRAEFIK_ENTRYPOINT` and
`TRAEFIK_CERTRESOLVER` against your Traefik setup.

DNS: a wildcard record `*.randogeddon.eratiem.net` (and `randogeddon.eratiem.net` itself) pointing at the server,
so a new session ID needs no DNS change. Certificates come per host name from the cert resolver (HTTP challenge).
If you configure Traefik via the file provider instead of labels, the two routes are:

- `Host(<SESSION_ID>.randogeddon.eratiem.net)` → `http://gateway:38281` (websocket; Traefik handles the upgrade,
  no extra config)
- `Host(randogeddon.eratiem.net)` → `http://tracker:8000` (uses SSE; Traefik doesn't buffer it by default)

Set a new `SESSION_ID` in `.env` for every session (a DNS label, e.g. `winter26`); it only changes the game server's
host name. More than one session at a time comes later, see [.docs/TODO.md](.docs/TODO.md) (Phase 4).

Until a seed is deployed, the server container waits and logs `No seed in /data yet`, and the
dashboard shows "server offline".

## Session day

1. **Collect YAMLs** in `players/`, one per world, each with a unique `name:` (e.g. `Alice-HK`, `Alice-Celeste`). Players export them
   from the game's options page on archipelago.gg or via the Launcher's *Generate Template Options*.
   Only `.yaml`/`.yml` files may be in that folder, because `Generate.py` parses every file there.
   Put any non-bundled games' `.apworld` files in `custom_worlds/`, **both** locally and on the server.
   For locked worlds add a World Locks YAML (see below), and write `session.yaml` (who plays which worlds; the
   logins are made for its people).
2. **Generate** locally:
   ```sh
   ./scripts/generate.sh                 # extra args go to Generate.py, e.g. --spoiler 3 --seed 42
   ```
   This creates `output/AP_<seed>.zip`, containing the multidata, the spoiler log and each player's
   patch file (for ROM-based games).
3. **Deploy:**
   ```sh
   ./scripts/deploy-seed.sh output/AP_<seed>.zip you@server:/srv/randogeddon
   ```
   This copies the zip (and `session.yaml`, if there is one) into `data/`, moves any previous seed and save to
   `data/old/`, and recreates the containers. The server loads the zip directly. `session.yaml` can be edited on
   the server at any time; the dashboard picks up changes by itself. The tracker creates the new seed's slot
   passwords within seconds.
4. **Logins** for the people of `session.yaml` (on the server; existing logins are kept):
   ```sh
   docker compose exec tracker randogeddon logins
   ```
   This prints each new username and password **once**; only their hashes are stored. Hand them out, together with
   each player's patch file from the zip (if their game uses one). `randogeddon logins --reset <person>` makes a new
   password (and ends that person's logins).
5. **Players log in** at `https://randogeddon.eratiem.net`. Under *Your connection* they find the server
   (`<session-id>.randogeddon.eratiem.net:443`; clients try `wss://` first, so TLS through Traefik just works), their
   slot names and each slot's password. Phones can install the dashboard as an app (see below).

### Running commands on the server console

```sh
docker compose attach archipelago     # detach with Ctrl-P Ctrl-Q (NOT Ctrl-C, that stops the server)
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

## Logins and passwords

Nobody sees anything of the session without logging in, and nobody joins the game without their own password.

- **Logins** are made for the people of `session.yaml` with `randogeddon logins` (see *Session day*); the username is
  the person's name. Passwords are 32 random characters and stored only as Argon2id hashes. A login lasts 30 days
  (HttpOnly, Secure, SameSite=Strict cookie). Failed logins are slowed down and limited to 5 per minute per address.
  Someone with a login who isn't a person of the current `session.yaml` is refused.
- **Slot passwords:** every slot (world) of a seed gets its own generated password when the tracker first sees the
  seed; they stay the same across restarts, a new seed gets new ones. The gateway compares the password of every
  client's `Connect` with the slot's, the same field clients already send the room password in, so no client needs
  changes. A wrong one gets `InvalidPassword`. There is no room password (`AP_PASSWORD` is gone).
- Slot passwords use only characters every client accepts and that don't break config files (printable ASCII without
  spaces, quotes or backslashes). Some games only have an in-game text field without paste, so the dashboard shows
  them in a monospaced font.
- `docker compose exec tracker randogeddon slots` prints the current seed's slot passwords for the host, e.g. for the
  Locks slot (`!hint World Key 2`) or worlds that aren't in `session.yaml`.

The data lives in the `postgres-data` volume (and in the Proxmox backup of the whole VM).

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

- **Your connection:** the server address, your slot names and their passwords (shown on request, copyable).
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

API: `POST /api/login` (JSON with `username` and `password`, sets the cookie), `POST /api/logout`; with a login:
`GET /api/me` (name, server, own slots and passwords), `GET /api/state` (summary JSON), `GET /api/player/<slot>`,
`GET /api/stream` (SSE). Without a login these answer 401. `GET /healthz` is open and says nothing about the session.

The login form is a plain HTML form in `index.html` rather than part of the Compose app, so password managers can
save and fill the generated passwords.

### Install on your phone

The dashboard is a web app (PWA), so no app store or Apple account is needed:

- **Android (Chrome):** open `https://randogeddon.eratiem.net` → menu ⋮ → *Install app* (or *Add to Home screen*).
- **iPhone / iPad (Safari):** open the page → Share → *Add to Home Screen*. It opens full screen with its own icon.

It needs a browser with WebAssembly GC: iOS/Safari 18.2+, Chrome/Edge 119+, Firefox 120+. Older browsers get a
message asking for an update. The first load is about 4 MB (gzip); after that the browser has it cached.

## Demo

```sh
./scripts/demo.sh        # generates demo/players, deploys it locally, plays it with simulated players
./scripts/demo.sh stop   # stops the simulated players; `docker compose down` stops everything
```

Three people with different numbers of games: Alice three (two of them start locked), Bob two (one locked), Carol
one. The simulator (`demo/simulate.py`) checks locations quickly for half a minute and then slowly, finds the key of
Alice's Celeste after ~25 s and of her DLCQuest after ~75 s; the third key is only hinted, so Bob's Yacht Dice stays
locked. The simulated players connect
through the gateway with their slot passwords. It replaces the seed in `data/` (the old one goes to `data/old/`).

At the end it prints the logins of Alice, Bob and Carol (only when they are created; later runs keep them, a new
password: `docker compose exec tracker randogeddon logins --reset Alice`). Open http://localhost:8000 and log in.

The demo builds the native images, so it needs Docker for Linux containers; on Windows, run it in WSL (see
*Native build*).

## Local testing

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml   # publishes 38281 + 8000, no Traefik
export POSTGRES_PASSWORD=local                                     # or set it in .env
./scripts/generate.sh && ./scripts/deploy-seed.sh output/AP_*.zip
docker compose exec tracker randogeddon logins
# game server (gateway) on localhost:38281, dashboard on http://localhost:8000
```

For a test seed that needs no ROM or installed game, use a few YAMLs like
`name: Alice` / `game: APQuest` / `APQuest: {}`.

## Developing the tracker and the gateway

Gradle 9.8.2 (wrapper), Kotlin 2.4, JDK 25 toolchain; dependencies come from the Nexus via your init script, like in
CoreSync. Run Gradle on JDK 25 (e.g. `JAVA_HOME=/usr/lib/jvm/java-25-temurin`).

```sh
./gradlew :server:test :gateway:test :database:test  # unit tests (also run in the Docker build)
./gradlew :database:integrationTest             # schema and stores against PostgreSQL (Testcontainers, Linux Docker)
./gradlew :webApp:wasmJsBrowserDistribution     # build the web app -> webApp/build/dist/wasmJs/productionExecutable
./gradlew :server:run                           # tracker on :8000 against localhost:38281, PostgreSQL on :5432
./gradlew :gateway:run                          # gateway on :38281 against MultiServer on localhost:38282
./gradlew :server:run --args="logins"           # a host command, against the same database
```

`:server:run` builds the web app first and uses `data/room.json` and `data/session.yaml` of this checkout. All
settings are in `server/src/main/resources/application.yaml` and `gateway/src/main/resources/application.yaml`, each
with the environment variable that overrides it (database: `DB_URL`, `DB_USER`, `DB_PASSWORD`). For UI work,
`./gradlew :webApp:wasmJsBrowserDevelopmentRun` starts a dev server with hot reload that proxies `/api` to the tracker
on port 8000.

The schema is in `database/src/main/resources/db/migration`; a new migration must also be listed in `index.txt` there
(a native image can't scan the classpath; a test checks the list).

### Native build

The images contain native executables (GraalVM CE 25), built in `kotlin.Dockerfile` with the setup in the root
`build.gradle.kts` (the same as CoreSync's server):

- `-march=x86-64-v3` by default: AVX2, FMA and BMI2, which the server's **Intel i7-8700** (Coffee Lake) has; it has
  no AVX-512, so `x86-64-v4` would not run. Build with `NATIVE_MARCH=native` (in `.env`) only on that machine itself.
- Optimisation follows the version: `-Ob` (fast build) for `-SNAPSHOT`, `-O3` for releases
  (`-PnativeOptimization=quick|release` overrides it).
- `--static-nolibc`: only glibc is linked dynamically. The build stage (Oracle Linux 9, glibc 2.34) is older than the
  runtime image (Debian 12, glibc 2.36); glibc is backwards compatible.

Native images can't be cross-compiled, so the build runs on Linux: in the Docker build, or directly in WSL Debian with
GraalVM 25 (`sdk install java 25-graal`) and the Nexus init script in `~/.gradle/init.gradle.kts`:
`./gradlew :server:nativeCompile :gateway:nativeCompile`. Docker on Windows may run only Windows containers; then use
the Docker of WSL (also for `:database:integrationTest`).

Reflection and resources that the libraries' shared metadata doesn't cover are in
`*/src/main/resources/META-INF/native-image/`. After adding a library, record what it needs with the tracing agent
and review the result:

```sh
./gradlew -Pagent=standard :server:run          # exercise login, stream, commands; stop it normally
./gradlew :server:metadataCopy                  # likewise :gateway:run and :gateway:metadataCopy
```

## Updating Archipelago

Set `AP_VERSION` in `.env` to a new release tag, then run `docker compose build` and redeploy.
Everyone's client and apworld versions should be compatible with the server version. Seeds are
generally playable on newer servers but not older ones, so generate and serve with the same image.
