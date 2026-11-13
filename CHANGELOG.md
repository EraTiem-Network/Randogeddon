# Changelog

Versions follow [Semantic Versioning](https://semver.org/).

## Unreleased

### Patch file downloads in the web app (Phase 7, part 2)
- *Your connection*: the shown slot's files (name, size) with *Download*, and how to patch with the Archipelago
  Launcher on its ⓘ.
- The panel's *Players*: every slot's files, for Session-Managers and Administrators.

### Patch file downloads (Phase 7, part 1)
- The seed's files of a slot (ROM patches like `.apsm` or `.aplttp`, Factorio's mod zip, other per-slot output) at
  `GET /api/sessions/<id>/files` and `.../files/<name>` (an attachment): each player those of their own slots (a
  shared slot: each of its players), Session-Managers and Administrators every slot's, support and watchers none. As
  long as the session exists, finished ones too. Never the multidata, a spoiler or readable text (`.txt`, `.log`,
  `.html`, …). Every download is logged.
- The gateway reads them from the seed's zip (`/internal/sessions/<id>/files`); a file's slot comes from `P<n>` in its
  name, its slot's name and game from the spoiler log.

### Roles (Phase 6)
Sessions belong to whoever creates them now: every user can create one and is its Session-Manager; Administrators (the
admins of before) reach everything. Per session there are support (items, checks and free hints, logged) and watchers
(added by Session-Managers and players), and a share link shows it without a login. Updating: nothing to set
(migrations V9 and V10); existing sessions have no Session-Manager until an Administrator adds one, and every user may
create one running session at once (*Settings*). The parts:

### Administrators and Session-Managers (Phase 6, part 1)
- The admins are **Administrators** now: they reach everything (every session, the APWorlds, the users, the settings)
  and log in with two-factor login, as before.
- **Every user can create a session** and is its **Session-Manager**: they manage it (state, finish, reopen, keep,
  delete, game server, console, log, cheats, the spoiler log while it runs) and give others a part in it:
  Session-Managers, support or watchers (by account, or a new address invited). A session keeps one Session-Manager.
- How many running sessions one user may manage at once: the Administrators' setting (`/api/admin/settings`, default
  1).
- Routes: creating moves to `/api/sessions/new` and `POST /api/sessions`, a session's management to
  `/api/sessions/<id>/manage/…`, its people to `.../people`; `/api/admin` is the Administrators'.
- Watchers and support see the session; support and Session-Managers its spoiler log while it runs. Every cheat is
  logged with the role it was done as. Migration V9 (roles per session, settings).

### Support, watchers, share links (Phase 6, part 2)
- **Support** (`/api/sessions/<id>/support`): gives items, checks locations and free hints (even without the session's
  cheat hints); no release or collect. Logged and shown in the feed: "Bob (support) gave Mantis Claw to Alice-HK".
- **Watchers** are added by the session's players too (`/api/sessions/<id>/watchers`): by name, or an invitation for an
  address without an account. Players take away watchers, not support or Session-Managers.
- **Share links** (`/api/sessions/<id>/share`, players and Session-Managers): the session without a login, read-only,
  at `/api/watch/<token>/state`, `.../stream`, `.../player/<slot>` (rate-limited; no passwords, spoiler or accounts).
  A new link replaces the old one; stored only as SHA-256. Migration V10.

### The web app for every role (Phase 6, part 3)
- **The panel** (gear button, *Manage*, `#/manage`; `#/admin` still works) is everyone's: the sessions they manage
  (`GET /api/manage`; an Administrator's: every session), *New session* with their limit, and per session its
  **People** (Session-Managers, support, watchers by name or a new address; the share link). *General* (*Users*,
  *APWorlds*, *Settings*: sessions per user) is the Administrators'.
- **Dashboard by role:** support's *Support* section (items, checks, free hints, the spoiler log), players'
  *Watchers* (and the share link), a *Watching* pill for watchers. The session lists show every session one has a
  part in.
- **Share link page** `#/watch/<token>`: the dashboard without a login, read-only.
- New players are invited from the new-session form for an address without an account; `manage` and `watch` are
  reserved session IDs.

### Proper user management (Phase 5)
Accounts belong to people now: invited by e-mail, their own name and password, deleted by themselves when they choose,
two-factor login (required for admins). Updating: `MAIL_*` and `TOTP_KEY` in `.env`, the mailbox
`randogeddon@example.net`; existing logins stay (they add an e-mail address under *Your account*), admins set up
two-factor login at their next login. The parts:

### Two-factor login (Phase 5, part 3)
- TOTP (RFC 6238, any authenticator app): after the password, the code (or one of 10 recovery codes, each usable once;
  `autocomplete="one-time-code"`). A code works once; the step before and after count too.
- Admins need it: an admin without it sets it up right after the password (QR code, the key to type in, a first code),
  and the admin API refuses an admin without it. Everyone else can turn it on and off under *Your account*, and get new
  recovery codes there.
- The secrets are stored encrypted (AES-256-GCM) with `TOTP_KEY`, or a key file the tracker makes in its state volume.
  `randogeddon admins --reset-2fa <person>` turns it off for someone who lost both the device and the codes.
- Migration V8. The demo's admin logs in with it too (secret in `output/demo/admin.totp`).

### Sign-up, users, settings (Phase 5, part 2)
- The invitation's link opens the sign-up: name, password (twice) and the deletion period, as a plain form like the
  login, so password managers save the new account. A link that was used, has expired or was replaced says so.
- Admin panel, *Users*: every account (invited ones with until when their link works), invite by e-mail with a
  suggested name, send an invited one's link again or to another address. The new-session form invites an address
  typed in a slot's player field right there.
- *Your account* (the person button on the top card, `#/settings`): the name (unique; the running sessions show the new
  one), the password (a form of its own for the password manager; the other logins end), the e-mail address (a link
  to the new one confirms it, `#/email/<token>`), the deletion period with the date it would happen.
- Names show their account's ID as a tip, and a deleted account's name in an old session is marked so.
- The login field takes the name or the e-mail address. `settings`, `invite` and `email` can't be session IDs.

### Accounts of people (Phase 5, part 1)
- Accounts belong to people, not to a session. Admins invite people by e-mail (`/api/admin/users`); the link (single
  use, valid 7 days, a new one replaces the old) leads to the sign-up, where they choose their name, password and when
  their account deletes itself. Until then an admin can send the link again, or to another address.
- Logging in with the name or the e-mail address. An account without a session gets in too (and sees none).
- Automatic deletion: 2 or 4 weeks, 3 or 6 months, 1 year after the end of the user's last session, or never; a mail
  warns 14 days before. Deleting keeps the account's UUID in the sessions, with the name it had in each.
- Sessions keep the names their players had in them (a rename follows in running sessions only); who is part of a
  session goes by the account's UUID.
- Mails through SMTP (`MAIL_*` in `.env`; STARTTLS or TLS, `MAIL_ADDRESS` for an internal address); without a mail
  server they go to the log. The local overlay has Mailpit (http://localhost:8025).
- New sessions take their players by account (`WorldPlan.userIds`); new names no longer get a generated login.
  `randogeddon logins --create <name>` for local use and the demo, which makes its players that way.
- Migration V7: e-mail addresses, invitations, address changes, deletion settings, names per session.

### Reachable from outside (Phase 4, part 3)
- Every session's game server is reachable at `<id>.<BASE_DOMAIN>:443`: one Traefik router for `*.<BASE_DOMAIN>` to
  the gateway, which relays by the host name, with a wildcard certificate. `SESSION_ID` and the router for one
  session are gone. The certificate needs a cert resolver with the DNS challenge: `TRAEFIK_DNS_CERTRESOLVER` in
  `.env` (default `letsencrypt-dns`; ClouDNS in the README). Needs Traefik v3.
- A gateway restart no longer leaves a running session stopped when finished sessions came first: they took a port
  for a moment, before they were refused.
- A stop right after a start (of the gateway, or by an admin) no longer hangs the game server's controls.
- Game clients through Traefik reached nothing (404): the gateway picked its routes by the port the client asked for
  (443), not the one the connection came in on. A Host header naming the internal port no longer leads there either.
- `docker-compose.proxy.yml`: for a Traefik on another machine (file provider), e.g. on a Proxmox host with
  Randogeddon in a VM. Publishes 38281 and 8000 and keeps the public addresses.

### Picking sessions, links (Phase 4, part 2)
- Every view has a link of its own and survives a reload: `#/` the user's sessions, `#/<id>` a session's dashboard,
  `#/<id>/spoiler` its spoiler log, `#/admin`, `#/admin/<id>` and `#/admin/new` the admin panel. Back and forward
  move between them.
- After the login, the user's only running session opens; with several or none running, their sessions are listed as
  cards (running and finished, how far each got, their slots, who is online).
- The dashboard's top card has the session's ID as its heading, which switches between the user's sessions or goes
  back to the list; the list of other sessions below the dashboard is gone. "Randogeddon" is a headline above it.
- Admin panel: *Dashboard* opens a session's dashboard, also one the admin doesn't play in.

### Several sessions at once (Phase 4, part 1)
- Sessions have an ID (a DNS label, chosen when the session is created, a free suggestion like `brave-otter` prefilled):
  the game server's address `<id>.<domain>` and the session's name in the API. Migration V6 gives the sessions from
  before `s-<seed>`.
- The gateway runs one MultiServer per running session, each on a port of `AP_PORTS` (default `38300-38303`, four at
  once), and relays each game client by the host name it connected to, or by the path without a domain
  (`ws://localhost:38281/<id>`). Each session's files are in `data/sessions/<id>/`; the files of before are moved
  there on the first start. Deleting a running session moves them to `data/old/<id>-<time>/`, a finished one's are
  deleted.
- The tracker follows every running session with a connection of its own, keeps each one's history and archive in
  `/state/sessions/<id>/`, and finishes, reopens and deletes them one by one. A new session can be created while others
  run, as long as a game server is free; reopening needs a free one too.
- API per session: `/api/sessions/<id>/state`, `stream`, `player/<slot>`, `connection`, `archive`, `spoiler`; the
  admin panel's under `/api/admin/sessions/<id>/…`. A player sees the sessions they play in, admins see all.
- Dashboard: the user's running session, the others below it (finished ones as they ended). Admin panel: every
  session (pick one, its sections follow), a new session with its ID, the game server per session.
- The CLI deploy is gone (`deploy-seed.sh`, `generate.sh`, the `generate` service, `session.yaml`, the `AP_*` hint and
  release options in `.env`): sessions are created in the admin panel. `randogeddon admins --create <name>` makes the
  first admin; `randogeddon slots <id>` and `randogeddon sessions` replace the commands for the one session.
- The demo runs two sessions at once, created through the admin API (`demo/a`, `demo/b`, one with mixed locks).

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
- Uploads are checked before they are taken: a file that isn't a player YAML is refused with the reason, and so is
  an APWorld that doesn't load (a file it would have replaced stays).
- Locks: at least one slot starts open (the last open one can't be ticked), and a single slot can't be locked.
  *Mixed* locks: a three-way switch per slot (open, left to chance, locked); a slider sets how many slots start open,
  as it does for *Random* locks.
- Past sessions in the admin panel no longer list their players in the line: a click opens an overview with them.
- Cheats offer only slots with locations left to check, and their pickers drop a location once it is checked. With
  every check done (100 %) there is nothing to cheat, as without a session. The dashboard says "checks" where it
  said "locations".
- Dropdowns: a search field when they have more than 10 entries (not focused by itself on a phone, where its keyboard
  would cover the list); tapping a dropdown or an ⓘ again closes it. Tips on buttons show on a long press on a phone.
- Phones: the top card (one line per detail), the past sessions and the spoiler log stack their parts. The full
  spoiler log's search marks and counts the findings and jumps between them.
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
