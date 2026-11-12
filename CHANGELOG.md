# Changelog

Versions follow [Semantic Versioning](https://semver.org/).

## Unreleased

### Finishing, past sessions, spoiler log, cheats (Phase 3, part 4)
- A session ends by itself 2 minutes after every slot reached its goal or was released, with a countdown on the
  dashboard (admins: end now, or cancel). Admins can pause it by hand (not every check done) or end it (100 %). A finished session is read-only: the
  game server stops (the gateway refuses to start it, also after a restart) and the dashboard shows how it ended.
- Reopening within 14 days, unless the session was completed (100 %); also a past one, once the current session is
  finished. Past sessions are deleted automatically `SESSION_RETENTION_DAYS` after they finished (90 by default, `0`:
  never), unless an admin disabled that for one (enabled again, the days count from then); the lists show the date.
- Past sessions: a finished session no longer blocks a new one. Creating the next one archives it (seed and save in
  `data/sessions/<seed>/`, the dashboard's view in the tracker's volume); its players see it under *Past sessions*,
  admins see all and delete them. A player of past sessions only can still log in to see them.
- The spoiler log: the playthrough sphere by sphere and the whole log, searchable. Admins see it any time (behind a
  warning), the players once the session is finished.
- Cheats in the admin panel: give items, check locations, release, collect; free hints if the session was created
  with cheat hints. Every cheat is stored with the admin's name and shown in the live feed.
- Migration V5 (finish state, cheat log). The demo loop waits 4 minutes after a round: it finishes after 2.

### APWorlds, random locks, tooltips (Phase 3, part 3)
- The same words everywhere: a **player** is a person with a login, a **slot** is one YAML (one game in the
  multiworld, played by one or more players), the **game** is its title. The dashboard and the admin panel say
  "slot" where they said "world".
- Random locks: instead of ticking the locked slots, set how many slots start open; that many, picked at random,
  start open and all others start locked. The panel shows which ones it picked.
- APWorlds in the admin panel: every Archipelago release of the gateway lists its games; admins upload APWorlds into
  a release's library (`data/apworlds/<release>/`), remove them, and try one uploaded for another release. APWorlds in
  the server's `custom_worlds/` count for every release (compose mounts it at `/custom_worlds` now).
- A YAML whose game the release doesn't have is flagged before generating. When generating fails, the panel names
  the games that may be the cause (named in the error, or with an APWorld from another release) above the output.
- Every option of the session form, the server buttons and the console have an ⓘ that says what they do.

### Sessions in the admin panel (Phase 3, part 2)
- Create a session in the admin panel: upload the player YAMLs, set who plays which world (one or more people per
  world: a world can be played together; new names get a login, shown once), which worlds start locked, and the hint
  and release options. The gateway generates the seed and starts the game server on it. One session at a time.
- Delete it: the game server stops, the seed and its save go to `data/old/`, its slot passwords and setup are removed;
  the logins stay.
- A session keeps the Archipelago release it was generated with; the gateway runs and generates only releases it
  has (`/opt/archipelago/<version>`).
- The session's people come from the database; `session.yaml` is only used for seeds deployed with the CLI.
- Admins reach the admin panel even when they aren't part of the session.
- World Locks is part of the gateway image; `scripts/build-apworlds.sh` is gone (an old
  `custom_worlds/world_locks.apworld` is removed by the scripts).

### Admin panel (Phase 3, part 1)
- Admins: `randogeddon admins --grant <person>` (`--revoke`, or no option to list them). Only admins see the admin
  panel, and every `/api/admin` call is checked on the server (403 otherwise).
- The gateway is the game host now: its image has Archipelago (pinned release) and it runs MultiServer itself, so a
  tracker restart or update no longer drops the players. A crashed MultiServer starts again after a pause; one stopped
  by an admin stays stopped. The `archipelago` container is gone; seed generation uses the gateway image
  (`ap-generate`).
- Admin panel: the game server's state, start, stop and restart (with a confirmation), its live log, and the server
  console with a command history. The tracker controls the gateway over an internal API on its own port, with a shared
  `INTERNAL_TOKEN` (new in `.env`).
- `docker stop` lets MultiServer save before it ends (native exit handlers, 30 s grace period for the gateway).
- Demo: every world reaches its goal within about 15 minutes (`DEMO_MINUTES`); `./scripts/demo.sh loop` starts a new
  round 2 minutes after a round reaches 100 %, keeping the logins; Alice is an admin.
- Moving an existing server over: `sudo chown -R 1000:1000 data` (the gateway runs as uid 1000, the old container as
  root) and set `INTERNAL_TOKEN` in `.env`.

### Login (Phase 2)
- The dashboard needs a login: `docker compose exec tracker randogeddon logins` creates one for every person of
  `session.yaml` and prints the generated passwords once (`--reset <person>` for a new one). Passwords are hashed with
  Argon2id; logins last 30 days (HttpOnly, Secure, SameSite=Strict cookie); failed logins are slowed down and rate
  limited. Without a login, the API and the live stream answer 401; someone not in `session.yaml` gets 403.
- The login form is a plain HTML form, so password managers can save and fill it.
- Every slot of a seed gets its own generated password, shown to its player under *Your connection* on the dashboard
  (with the server address). `randogeddon slots` prints them for the host.
- New service `gateway` (Kotlin): game clients connect through it; it checks the password of every `Connect` against
  the slot's and answers `InvalidPassword` otherwise. MultiServer is no longer published. Replaces the room password
  (`AP_PASSWORD` is gone); the tracker connects internally without one.
- PostgreSQL (new service `postgres`, `POSTGRES_PASSWORD` in `.env`) for logins and slot passwords; schema with Flyway,
  access with Exposed (module `:database`).
- `demo/session.yaml` is in the repository now (the ignore rule for `session.yaml` only covers the root); the demo's
  players connect through the gateway with their slot passwords, and the demo prints the logins.

### Native build
- Tracker and gateway run as GraalVM native executables (Linux x86_64, `-march=x86-64-v3` for the server's i7-8700),
  built in `kotlin.Dockerfile` (replaces `tracker.Dockerfile`); the tracker starts in milliseconds instead of seconds.
- Reachability metadata recorded with the tracing agent; Flyway's migrations are listed in an index instead of scanned.

### Randogeddon
- The project is now called Randogeddon: images `randogeddon-server` / `randogeddon-tracker`, compose project
  `randogeddon`, Kotlin package `net.eratiem.randogeddon`, dashboard and PWA title.
- Addresses from the roadmap: the dashboard at `randogeddon.eratiem.net` (`BASE_DOMAIN`), the game server at
  `<session-id>.randogeddon.eratiem.net:443` (`SESSION_ID`). Replaces `AP_HOST` and `TRACKER_HOST`.
- Migrating a running server: `docker compose -p archipelago down` before the first deploy with the new name. The
  dashboard's history volume starts fresh under the new project.

### Tracker
- Rewritten in Kotlin: a Ktor/Netty server (`:server`) follows the room over the Archipelago network protocol and
  serves the API (`/api/state`, `/api/player/{slot}`, `/api/stream` as Server-Sent Events, `/healthz`). Same behaviour
  as the former Python tracker: one tracker connection per slot, event history kept across restarts, reconnect within
  5 seconds.
- The dashboard is a Compose Multiplatform web app (`:webApp`, Kotlin/Wasm) and installable as a PWA on Android
  and iOS. Player cards, live feed, hints, player details, light and dark theme.
- The API contract is shared between both in `:shared`.
- The tracker image is built from the Gradle build; the Nexus credentials are passed in as a BuildKit secret.

### Locked worlds and people
- `World Locks` APWorld: worlds that start locked until their key is found elsewhere; the generator knows about the
  locks, so seeds stay completable. Option `early_keys` to make the keys reachable from the start.
- `session.yaml` groups worlds by person; the dashboard shows each person with their worlds, their state and, for
  locked worlds, who found the key or where it was hinted. Key finds are highlighted in the live feed.
- New, more colourful dashboard design (gradient header, avatars, game chips, softer cards).
- `scripts/demo.sh`: an example session with simulated players.
- Play time per world and person, and a connection log per world (who was online when).
- Hint switches: `AP_POINT_HINTS` and `AP_AUTO_HINTS` (via `archipelago/serve.py`); the dashboard shows hint points per
  world and hides hints when both are off. Hint priority (priority / avoid / no priority) is shown.
- Darker dark theme.
- Person view: totals, worlds (opening a world shows it in place, with a back button), open hints, connection log
  and recent activity of one person.
- Hint filters: player, world, state. The live feed can be hidden and placed right (default), left or below, per viewer.

### Server
- Archipelago 0.6.8 in Docker (MultiServer), generation and deployment scripts, Traefik routes.
