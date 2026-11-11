# Changelog

Versions follow [Semantic Versioning](https://semver.org/).

## Unreleased

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
- `World Locks` apworld: worlds that start locked until their key is found elsewhere; the generator knows about the
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
