# Randogeddon

Our self-hosted [Archipelago](https://archipelago.gg) multiworld server (plain `MultiServer`, AP **0.6.8**)
plus a custom live dashboard. Everything runs in Docker behind an existing Traefik. The dashboard is at
`randogeddon.eratiem.net` and needs a login; the session's game server is at
`<session-id>.randogeddon.eratiem.net:443`, where every slot has its own generated password. The gateway runs the game
server; admins start, stop and command it from the admin panel. The Kotlin apps (tracker and gateway) run as native
executables (GraalVM Native Image, Linux x86_64).

What's done and what comes next (login, admin panel, multiple sessions, user management): [.docs/TODO.md](.docs/TODO.md).

Words used here and in the dashboard: a **player** is a person (with a login); a **slot** is one YAML, one game in
the multiworld, played by one or more players (Archipelago itself says "player" for a slot, e.g. in `/send <player>`);
the **game** is just its title, e.g. Hollow Knight.

```
              players' game clients                      browser
                       │ wss://<session-id>.randogeddon     │ https://randogeddon
                       │       .example.net:443             │         .example.net
                 ┌─────▼─────────────── Traefik ────────────▼─────┐
                 │                                                │
        ┌────────▼──────────────────┐                 ┌───────────▼──────────┐
        │  gateway (Ktor)           │  internal API   │  tracker (Ktor)      │
        │  checks each Connect's    │◄────────────────┤  login, API, SSE,    │
        │  slot password            │  :8001 (control)│  admin panel,        │
        │  ┌─────────────────────┐  │                 │  Wasm app            │
        │  │ MultiServer :38282  │◄─┼─────────────────┤  Tracker-tag clients │
        │  └─────────────────────┘  │                 └───┬──────────────────┘
        └──────┬──────────┬─────────┘                     │
   ./data (seed, .apsave, │room.json)             ┌───────▼──────────────┐
                          └──────────────────────►│  postgres            │
                            reads slot passwords  │  logins, slot pw     │
                                                  └──────────────────────┘
```

The gateway runs MultiServer as a child process; only the tracker reaches it directly (port 38282 and the internal
API on 8001 are not published). A tracker restart or update doesn't drop the players.

| Path | What it is |
|---|---|
| `archipelago/` | `serve.py` (MultiServer with the hint switches), `room_info.py`, `list_games.py`, `ap-generate`; the gateway image installs Archipelago next to them |
| `server/` | Tracker server: Kotlin + Ktor. Follows the room, serves the login, the API and the web app; host commands (`randogeddon logins`) |
| `gateway/` | The game host: runs MultiServer, relays each client connection to it and checks the slot password of every `Connect`; internal API for the admin panel |
| `database/` | PostgreSQL schema (Flyway migrations) and Exposed tables, shared by tracker and gateway |
| `webApp/` | The dashboard: Compose Multiplatform (Kotlin/Wasm), installable as a PWA |
| `shared/` | The API contract (Kotlin Multiplatform data classes) used by both |
| `apworlds/world_locks/` | Our own APWorld: slots that start locked until their key is found (part of the gateway image) |
| `session.yaml` | Who plays which slots, only for seeds deployed with the CLI (copy `session.example.yaml`; gitignored) |
| `demo/` | An example session (YAMLs, `session.yaml`, simulated players) — see *Demo* below |
| `kotlin.Dockerfile` | Builds the tracker image and the gateway image (native, plus the pinned Archipelago release) |
| `players/` | One YAML per slot (gitignored) |
| `custom_worlds/` | Extra `.apworld` files for games that aren't bundled, for every release (gitignored); or upload them in the admin panel |
| `output/` | Generated `AP_<seed>.zip` (gitignored, contains the spoiler) |
| `data/` | Server side: active seed, `.apsave` savegame, `room.json`, the uploaded APWorlds in `apworlds/<release>/` (gitignored) |
| `scripts/generate.sh` | Generate a seed locally |
| `scripts/deploy-seed.sh` | Push a seed to the server (or install it locally) and restart |

## One-time server setup

```sh
git clone <this repo> /srv/randogeddon && cd /srv/randogeddon
cp .env.example .env     # set SESSION_ID, POSTGRES_PASSWORD, INTERNAL_TOKEN, TRAEFIK_* …
docker compose build     # takes a few minutes (every game's requirements, the native builds)
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

Until a seed is deployed, the gateway waits and logs `No seed in /data yet`, and the dashboard shows "server
offline".

The gateway runs as uid 1000 and writes saves and `room.json` to `data/`; when moving from the old `archipelago`
container (which ran as root), hand the directory over once: `sudo chown -R 1000:1000 data`.

Make yourself an admin (after creating the logins, see *Session day*):

```sh
docker compose exec tracker randogeddon admins --grant <person>    # --revoke takes it back; `admins` lists them
```

## Session day

### In the admin panel

1. **Collect the YAMLs**, one per slot, each with a unique `name:` (e.g. `Alice-HK`, `Alice-Celeste`). Players export
   them from the game's options page on archipelago.gg or via the Launcher's *Generate Template Options*. A game that
   isn't bundled with Archipelago needs its `.apworld` (see *apworlds* below).
2. **Create the session** (admin panel, *Session*, only while there is none): *Add YAMLs…*, then for every slot who
   plays it (one or more players: a slot with several players is played together; new names get a login); the locks
   (tick the slots that start locked, or *Random*: a number of slots picked at random start open, all others start
   locked); the hint and release options; *Generate and start*. Every option has an ⓘ that says what it does. The
   gateway generates the seed with the chosen Archipelago release and starts the game server on it. A game the release
   has no APWorld for is flagged before generating; if Generate.py fails, the panel names the games that may be the
   cause (one the error mentions, or one whose APWorld comes from another release) above its output.
3. **Hand out the new logins**: the panel shows each new player's password **once** (and, with random locks, which
   slots start locked). Players who already had a login keep it.
4. **Players log in** at `https://randogeddon.eratiem.net`. Under *Your connection* they find the server
   (`<session-id>.randogeddon.eratiem.net:443`; clients try `wss://` first, so TLS through Traefik just works), their
   slot names and each slot's password. Phones can install the dashboard as an app (see below).

Patch files for ROM-based games are in the seed: `data/AP_<seed>.zip` on the server. **Deleting** the session (admin
panel) stops the game server and moves the seed and its save to `data/old/`; the logins stay. Only one running
session at a time: a new one can be created once the current one is finished (it stays a past session) or deleted.

### Finishing, past sessions and the spoiler log

- **Ending and pausing:** 2 minutes after every slot has reached its goal or was released, the session ends by
  itself. Meanwhile the dashboard shows a countdown; admins can *End now* or *Cancel* (it then doesn't start again
  until a slot is no longer done). An admin can also end it by hand: *Pause session* while not every check is done
  (e.g. when someone drops out), *End session* once it is at 100 %. Either way the game server stops (the gateway
  refuses to start that seed, also after a restart), and the dashboard keeps showing how far it got: the session is
  read-only from then on.
- **Reopening:** within 14 days of the finish (*Reopen*), unless the session was completed (100 %). A past session
  can be reopened too, once the current one is finished; the current one is archived then. Its history comes back
  with it.
- **Past sessions:** creating a new session over a finished one archives the finished one (`data/sessions/<seed>/`
  for its seed and save, `/state/sessions/<seed>.json` in the tracker's volume for what the dashboard showed). Its
  players see it under *Past sessions* on the dashboard, admins see all; it is kept until an admin deletes it in the
  admin panel, or automatically `SESSION_RETENTION_DAYS` after it finished (90 by default, `0`: never; the date shows
  in the lists). *Disable auto-deletion* (asked once more) keeps it until an admin deletes it; enabled again, the days count from
  then. Someone who is in no current session but in a past one can still log in and see those.
- **Spoiler log:** the seed's spoiler log (from its zip), with the playthrough sphere by sphere and the whole log
  searchable. Admins can open it at any time (behind a warning: it is a full spoiler); the players of a session see it
  once it is finished.

### APWorlds

The admin panel's *apworlds* lists, for every Archipelago release of the gateway, the games it can generate and serve,
and its APWorld library:

- **Upload APWorld…** adds a `.apworld` to that release's library (kept in `data/apworlds/<release>/`). The gateway
  checks that it is an APWorld (a zip with one world folder), loads the release's worlds again and says which game it
  brought, or why it didn't load (e.g. it needs a newer Archipelago). *Remove* takes it out again.
- **From other releases:** an APWorld uploaded for another release can be tried with this one (*Try with …*); it is
  copied and marked "untested here". If generating fails, such APWorlds are among the suspects.
- APWorlds put into the server's `custom_worlds/` by hand count for every release (they can't be removed in the
  panel).

The gateway puts exactly these files into each release's `custom_worlds/` before it lists the games or generates.

### With the CLI

1. **Collect YAMLs** in `players/`, one per slot, each with a unique `name:` (e.g. `Alice-HK`, `Alice-Celeste`). Players export them
   from the game's options page on archipelago.gg or via the Launcher's *Generate Template Options*.
   Only `.yaml`/`.yml` files may be in that folder, because `Generate.py` parses every file there.
   Put any non-bundled games' `.apworld` files in `custom_worlds/`, **both** locally and on the server.
   For locked slots add a World Locks YAML (see below), and write `session.yaml` (who plays which slots; the
   logins are made for its players). A seed deployed this way has no session in the database: it runs on the
   gateway's newest Archipelago release with the `AP_*` options of `.env`.
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
4. **Logins** for the players of `session.yaml` (on the server; existing logins are kept):
   ```sh
   docker compose exec tracker randogeddon logins
   ```
   This prints each new username and password **once**; only their hashes are stored. Hand them out, together with
   each slot's patch file from the zip (if its game uses one). `randogeddon logins --reset <player>` makes a new
   password (and ends that player's logins).
5. **Players log in** at `https://randogeddon.eratiem.net`. Under *Your connection* they find the server
   (`<session-id>.randogeddon.eratiem.net:443`; clients try `wss://` first, so TLS through Traefik just works), their
   slot names and each slot's password. Phones can install the dashboard as an app (see below).

### Admin panel: the game server and its console

Admins get a gear button in the dashboard's header. The admin panel shows the game server's state (running,
starting, stopped, crashed, waiting for a seed), lets them start, stop and restart it (stop and restart ask once more:
every player is disconnected), shows its log live, and has the server console:

```
/players              # who's connected
/send <slot> <item>   # give an item ("Mantis Claw" in quotes if it has spaces)
/hint <slot> <item>   # free hint
/release <slot>       # release a slot's items (e.g. someone has to drop out)
/collect <slot>
```

Archipelago's commands say `<player>` for a slot.

Up and down in the console bring back earlier commands. Every action is logged with the admin's name in the tracker's
log.

**Cheats** (admin panel, *Cheats*): give a slot an item, check one of its locations, release or collect for it, pick
the slot and the item or location from searchable lists. Free hints (`/hint`, `/hint_location`) only if the session
was created with *Cheat hints*. Every cheat is stored with the admin's name and shown in the live feed ("Cheat: Alice
gave Mantis_Claw to Alice-HK"). Items a player got with the client's `!getitem` (if the server allows it) are
marked as cheats there too. A crashed MultiServer is started again by the gateway after a pause (1 s, doubling up to 30 s); one stopped by an
admin stays stopped until started again.

Players can also use `!hint <item>`, `!remaining`, `!release`, `!collect` and `!countdown 10` from their
client, depending on the `AP_*_MODE` settings in `.env`. `AP_ADMIN_PASSWORD` enables `!admin` for
remote server commands.

### Progress and restarts

The server saves to `data/AP_<seed>.apsave` continuously, and `restart: unless-stopped` brings it
back after crashes or reboots. Stopping with `docker compose down` and starting with `up -d` keeps the
progress. **Deploying a new seed starts over.** The old save is kept in `data/old/` if you need it.

## Logins and passwords

Nobody sees anything of the session without logging in, and nobody joins the game without their own password.

- **Logins** are made when a session is created in the admin panel, or for the players of `session.yaml` with
  `randogeddon logins` (see *Session day*); the username is the player's name. Passwords are 32 random characters and stored only as Argon2id hashes. A login lasts 30 days
  (HttpOnly, Secure, SameSite=Strict cookie). Failed logins are slowed down and limited to 5 per minute per address.
  Someone with a login who isn't part of the current session is refused (admins always get in, to the admin panel).
- **Slot passwords:** every slot of a seed gets its own generated password when the tracker first sees the
  seed; they stay the same across restarts, a new seed gets new ones. The gateway compares the password of every
  client's `Connect` with the slot's, the same field clients already send the room password in, so no client needs
  changes. A wrong one gets `InvalidPassword`. There is no room password (`AP_PASSWORD` is gone).
- Slot passwords use only characters every client accepts and that don't break config files (printable ASCII without
  spaces, quotes or backslashes). Some games only have an in-game text field without paste, so the dashboard shows
  them in a monospaced font.
- `docker compose exec tracker randogeddon slots` prints the current seed's slot passwords for the host, e.g. for the
  Locks slot (`!hint World Key 2`) or slots nobody plays.

The data lives in the `postgres-data` volume (and in the Proxmox backup of the whole VM).

## Hints

A hint tells a player where one of their items is ("Alice-HK's *Mantis Claw* is at *Sky Meadow: Chest 4* in
Bob-RoR2"). There are two systems, each switched in `.env` (`AP_POINT_HINTS`, `AP_AUTO_HINTS`, applied on restart):

- **Point hints:** checking a location earns `AP_LOCATION_CHECK_POINTS` points (default 1); `!hint <item>` or
  `!hint_location <location>` costs `AP_HINT_COST` % of the slot's locations (default 10 %, `0` = free).
  Players can mark hints *priority*, *avoid* or *no priority* in their client; the dashboard shows that.
- **Automatic hints:** some games create hints by themselves, e.g. a shop that hints what it sells.

MultiServer has no switches for this, so the server runs through `archipelago/serve.py`, a thin wrapper: with
point hints off, `!hint`/`!hint_location` answer "Hints are turned off"; with automatic hints off, games can still
scout locations but no hints are created. The host can always give hints from the server console (`/hint`).
With both off, the dashboard hides hints entirely (unless the host handed some out). With point hints on, every
slot shows its points and how many hints they buy. A session from the admin panel brings its own hint options
instead of the ones in `.env`.

## Locked slots

Some slots can start locked and open only when their key is found in another slot. This is part of the generated
seed, not just of the dashboard: the `World Locks` APWorld (`apworlds/world_locks/`, installed in the gateway image)
adds one key item per locked slot and tells the generator that nothing in that slot is reachable without its key. So
every seed stays completable, chains included (a key inside a slot that is itself locked).

In the admin panel, tick *starts locked* per slot, or choose *Random* locks with the number of slots that start open
(at least one; the others are locked). For the CLI, add one extra YAML to `players/` (nobody plays this slot):

```yaml
name: Locks
game: World Locks
World Locks:
  locked_worlds: ["Alice-Celeste", "Bob-Yacht"]   # slot names
  early_keys: false                               # true: keys are reachable from the start, slots open early
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
- **Overview:** overall completion, slots at goal, game clients online, locked slots opened, hint count.
- **Players:** one card per player of the session (or of `session.yaml`) with their slots (a slot played together
  shows on each of its players' cards): game, progress, status
  (not started / playing / locked / goal / released / complete), online dot, last activity, and for locked slots
  the key: who found it, or where it was hinted. Without any players, every slot is its own card. Click a
  player's name for their overview (totals, slots, open hints, combined connection log, recent activity). Click a slot
  for its received items (filterable), remaining locations, play time and connection log.
- **Play time:** time a game client was connected to a slot, per slot and per player, kept across restarts.
- **Live feed:** item sends colour-coded by classification (purple = progression, blue = useful,
  red = trap), hints, goals, releases, chat and joins. You can filter by player and kind.
- **Hints:** every hint in the room, filterable by player, slot and state (not found, found, priority,
  avoid, no priority, unmarked).
- **Layout:** the live feed can be hidden (list button in the header) and placed right, left or below; each viewer's
  choice is remembered in their browser. On narrow screens it always sits below the players.

Known limitations:

- Each tracker connection makes the server post "X tracking … has joined" to the chat. That happens
  once per slot whenever the tracker (re)connects.
- "Online" comes from join/leave messages. After a tracker restart a slot counts as online again as soon as it
  does something (finds its own item, chats, changes status); such sessions are marked "seen playing" in the
  slot's connection log, since their real start is unknown.
- The event history keeps the last 500 events, stored in the `tracker-state` volume.

API: `POST /api/login` (JSON with `username` and `password`, sets the cookie), `POST /api/logout`; with a login:
`GET /api/me` (name, server, own slots and passwords), `GET /api/state` (summary JSON), `GET /api/player/<slot>`,
`GET /api/stream` (SSE), `GET /api/sessions` (past sessions), `GET /api/sessions/<seed>`, `GET /api/spoiler/<seed>`.
Without a login these answer 401. `GET /healthz` is open and says nothing about the session.

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
./scripts/demo.sh loop   # the same, again and again: 2 minutes after a round reaches 100 %, a new round starts
./scripts/demo.sh stop   # stops the simulated players; `docker compose down` stops everything

nohup ./scripts/demo.sh loop > output/demo/loop.log 2>&1 &   # the loop in the background
```

Three players with different numbers of slots: Alice three (two of them start locked), Bob two (one locked) and, with
Carol, Risk of Rain 2, which they play together (a shared slot: on both cards, both get its slot password). The
simulator (`demo/simulate.py`) checks locations quickly for half a minute and then evenly until the end, finds the key
of Alice's Celeste after ~25 s, of her DLCQuest after ~75 s and of Bob's Yacht Dice after ~3 minutes
(hinted before). Every slot reaches its goal within about `DEMO_MINUTES` (default 15); then the simulator ends, which
the loop takes as the end of a round. 2 minutes later the session finishes by itself (the countdown shows meanwhile);
another 2 minutes later the next round starts, and the finished one is listed under *Past sessions* (seeds deployed
with the CLI go to `data/old/`; they can be reopened from there too). The simulated players connect through the gateway with their slot passwords. It
replaces the seed in `data/` (the old one goes to `data/old/`); about one in three random seeds can't be generated
("unbeatable"), so it retries with another one.

The first round prints the logins of Alice, Bob and Carol (only when they are created; later rounds keep them, a new
password: `docker compose exec tracker randogeddon logins --reset Alice`). Alice is an admin. Open
http://localhost:8000 and log in.

The demo builds the native images, so it needs Docker for Linux containers; on Windows, run it in WSL (see
*Native build*).

## Local testing

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml   # publishes 38281 + 8000, no Traefik
export POSTGRES_PASSWORD=local INTERNAL_TOKEN=local                # or set them in .env
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
./gradlew :gateway:run                          # gateway on :38281, only relaying to a MultiServer on :38282
GAME_SERVER=true AP_HOME=<archipelago checkout> ./gradlew :gateway:run   # running MultiServer itself
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

A session keeps the Archipelago release it was generated with, and the gateway runs it only with that release: one
it doesn't have can't be started (the admin panel and the gateway's log say so). The gateway image has one release
(`AP_VERSION` in `.env`) under `/opt/archipelago/<version>`; the admin panel offers what it has.

To update: set `AP_VERSION` to the new release tag, `docker compose build`, and recreate the gateway once no session
of the old release is running (delete it first). Everyone's client and APWorld versions should be compatible with
the release. The new release starts with an empty APWorld library: the APWorlds of the old one show under *From
other releases* (*apworlds* in the admin panel) and can be tried with it, or upload versions made for the new release.
