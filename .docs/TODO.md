# Randogeddon To-Do List

The target of this project, phase by phase. Items within a phase are roughly in implementation order.
`Open:` marks decisions that are not made yet.

## Conventions

- **Addresses:** the app runs at `randogeddon.eratiem.net`; each session's game server at
  `<session-id>.randogeddon.eratiem.net:443` (TLS, `wss://`).
    - Plain `ws://<session-id>.randogeddon.eratiem.net:2401` as the fallback for game clients that can't do TLS: one
      port for all sessions, routed by host name like 443, but unencrypted (passwords included).
    - Current clients connect over TLS: Launcher-based clients retry with `wss://`, and Archipelago.MultiClient.Net
      (used by many modded games) tries `wss://` first since April 2023. Older or unmaintained mods may only speak
      `ws://`, so every game of a session is tested with its real client before session day.
- **Backups:** of the whole VM/container through Proxmox Backup Server (database, saves, seeds, state).
- **Generated passwords** (logins, room and slot passwords, ...): at least 32 characters, with lower- and upper-case
  letters, digits and symbols, from a cryptographically secure random source.
    - Passwords players type into game clients use only symbols every client accepts and that don't break config
      files: printable ASCII, no spaces, quotes or backslashes. Some games only offer an in-game text field without
      copy and paste.

## Phase 1: Basic Tracker (done)

*One session, run by hand: generate, deploy, watch.*

- [x] Archipelago 0.6.8 server in Docker (MultiServer), seed generation and deployment scripts, Traefik routes
- [x] Kotlin tracker (Ktor) following the room over the AP protocol, one Tracker connection per slot
- [x] Compose Multiplatform web dashboard (Kotlin/Wasm), installable as a PWA on Android and iOS
- [x] People and their worlds (`session.yaml`), play time, connection log, person and world views
- [x] World Locks apworld: worlds locked until their key is found, seeds stay completable
- [x] Hint switches (point hints, automatic hints), hint points per world, hint filters
- [x] Demo session with simulated players
- [x] Renamed to Randogeddon; dashboard at `randogeddon.eratiem.net`, the session's game server at
  `<session-id>.randogeddon.eratiem.net:443` (`BASE_DOMAIN`, `SESSION_ID` in `.env`)

## Phase 2: User Login (done)

*Nobody sees anything of the session without logging in.*

- [x] Generate a login (username + random password) for every person of the session
    - People come from `session.yaml`; the username defaults to the person's name.
    - Passwords are stored only as hashes (Argon2id); the plain password is shown once.
    - The logins are printed once when they are generated (`randogeddon logins` in the tracker container); the host
      hands them to the players. Phase 5 replaces this with invitations.
- [x] Login page; the dashboard is only accessible when logged in
    - Session cookie (HTTP-only, Secure, SameSite=Strict) with an expiry (30 days), logout.
    - Rate limiting (5 per minute per address) and a short delay on failed logins.
    - A plain HTML form in `index.html`, not part of the Compose canvas, so password managers can save and fill it.
- [x] No data before login
    - `/api/*` and `/api/stream` answer 401 without a valid session (403 for a user who isn't in the session).
    - The web app bundle and the login page contain no session data (no seed, names or worlds).
    - The service worker never caches API responses; logging out clears what the app has in memory.
- [x] PostgreSQL, from this phase on (new `postgres` service in compose)
    - Users are identified by a UUID from the start; the name is the display name, unique among active accounts.
    - Schema with Flyway migrations, access through Exposed (module `:database`; Exposed 1.6.1, Flyway 13.12.1,
      PostgreSQL JDBC 42.7.14).
    - Connection settings and password in `.env`.
- [x] ~~Generated room password for every session~~ Superseded by the slot passwords: a client's `Connect` carries
  only one password, so there is no room password any more and a session never runs open.
    - Logged-in players see the server address, their slot names and their passwords on the dashboard, so the login
      also guards access to the game.
- [x] Password per slot
    - Every slot (world) gets its own generated password; a player can only connect as their own worlds.
    - Checked by the **gateway** (`:gateway`, Kotlin) in front of MultiServer against the password field of the
      client's `Connect` packet, the same field every client (modded games included) already sends the room password
      in, so no client needs changes. MultiServer itself is reachable only inside the compose network.
    - The tracker connects to MultiServer internally and needs no password.
    - The gateway follows the Archipelago network protocol, not MultiServer's code: check it when the protocol
      changes (an Archipelago update with a new or changed `Connect`).
- [x] Tracker and gateway as native executables (GraalVM, Linux x86_64, `-march=x86-64-v3` for the i7-8700)

## Phase 3: Admin Panel

*The host runs the session from the browser instead of the shell.*

- [x] Users can be marked as admins; only admins see and reach the panel (checked on the server, not only hidden)
    - `randogeddon admins --grant <person>`; `/api/admin/*` answers 403 for everyone else.
- [x] Create a session: upload the player YAMLs, set people, locks and hint options, generate the seed
    - Every world gets one or more people (several: they play it together); new names get a login, shown once.
    - The gateway generates the seed; the game server starts on it.
- [x] Control the session: start, stop and restart the server, see its log, run server commands
    - Stop and restart ask once more; the log is live; the console keeps a command history.
- [x] Delete a session: stop the server, remove the seed, save and slot passwords
    - The logins stay: they belong to people, who will play again (see Phase 5).
- [x] Only one session at a time (creating a second one is refused until the first is deleted)
- [ ] Optimal path: the spoiler playthrough rendered as a route (sphere by sphere)
    - Hidden by default (it is a full spoiler). Admins can reveal it at any time; all players of the session see it
      once the session is finished.
- [ ] Finish a session
    - Automatically when every world has reached its goal or was released; an admin can also finish it by hand
      earlier (e.g. someone dropped out), and reopen it within a few days if it was a mistake.
    - Finishing stops the game server, makes the session read-only history, reveals the optimal path to its players
      and starts the auto-deletion timer of its users (Phase 5).
    - Finished sessions are kept until an admin deletes them; their players and the admins can see them. Automatic
      deletion can come with the auto-deletion of users (Phase 5).
- [ ] Cheats: give items (`/send`), checks (`/send_location`), release and collect, from the panel
    - Every cheat is logged with who did it, and shown in the live feed as a cheat.
- [ ] Cheat hints, if enabled for the session: admins give free hints (`/hint`, `/hint_location`)
- Two-factor login moved to Phase 5 (proper user management).
- [x] The **gateway** runs the game servers itself (not the tracker: so a tracker restart or update never drops
  the players; the gateway already sits in front of MultiServer)
    - The gateway image gets Archipelago (pinned release, world requirements, `serve.py`, World Locks).
    - It starts the MultiServer process with the session's options, and stops and restarts it on request (one session
      for now; one process per session comes with Phase 4).
    - It reads the server log and sends console commands through the process's stdin.
    - The tracker controls it over an internal API (own port, never published, shared token).
    - The separate `archipelago` container is removed; seed generation uses the gateway image (`ap-generate`),
      `scripts/generate.sh` stays as a CLI tool. Generating from the panel comes with "Create a session".
- [x] A session keeps the Archipelago version it was generated with
    - The gateway runs and generates only the releases it has; one release in the image for now, a second one is
      added next to it when an update comes.
    - Saved with the session, together with the apworlds it used (custom worlds, World Locks).
    - The tracker image carries several Archipelago releases side by side; a session always runs on its own, so an
      update never breaks a running session. Releases no session uses any more can be dropped.

## Phase 4: Multiple Sessions

*Several groups play at the same time on the same server.*

- [ ] More than one session at the same time, each with its own MultiServer, seed, save and tracker state
- [ ] The dashboard lists the sessions a user is part of; the session is chosen after login
- [ ] Users of a new session: pick from the users of the existing sessions, or add new ones (login generated as in
  Phase 2)
- [ ] Admins see and control all sessions
- [ ] Each session's server is reachable from outside
    - One host name per session, `<session-id>.randogeddon.eratiem.net`, on 443 (TLS) and 2401 (plain fallback, see
      Conventions). Traefik routes the WebSocket by host name to that session's port inside the container.
    - Certificates through Traefik's HTTP challenge, one per session host name (Let's Encrypt has no wildcard
      certificates with the HTTP challenge). For that, the tracker writes a Traefik router per session into a watched
      file (file provider) and removes it when the session is deleted; the first connection to a new session waits
      a few seconds for its certificate.
    - DNS: one wildcard record `*.randogeddon.eratiem.net` pointing to the server; no API access for Traefik needed.
    - Not paths (`randogeddon.eratiem.net/<session-id>`): Launcher-based clients would accept them, but many modded
      game clients only take host:port.
    - The gateway (Phase 2) already sees each connection's host name, so it can route to the session's MultiServer
      itself instead of one Traefik router per session.
    - Open: the session ID format (random like `k7f3q9` or readable like `friday-chaos`), decided in this phase.

## Phase 5: Proper User Management

*Accounts belong to people, not to a session.*

- [ ] When creating a session: select existing users, or add new ones by e-mail address
- [ ] New users get an invitation mail with a link to choose their username and password
    - The link is single-use and expires.
    - No open sign-up: an account can only be created with the link from an invitation mail, for the address the
      admin entered.
    - Mail via SMTP on `mail.example.net`, sender `randogeddon@example.net`, credentials in `.env`. The host is
      configurable: if the TLS certificate is issued for `mail.example.org`, connect by that name.
- [ ] As long as the user hasn't signed up, an admin can change the e-mail address; the old invitation stops working
- [ ] Automatic account deletion, chosen when signing up
    - Delete after a time span (weeks, months or years, from a fixed list) or never.
    - The time counts from the end of the user's last session (when it was finished, Phase 3).
    - The user is told before the deletion happens.
    - Deleting removes only the account (login, e-mail address, settings). Sessions stay viewable: they keep the user's
      UUID and the name the user had in that session, saved with the session.
- [ ] Display names are unique among active accounts
    - Checked without regard to case ("Alice" and "alice" can't both exist), at sign-up and when renaming; a deleted
      account frees its name.
    - The UI shows only the name; the UUID appears only when hovering over it (e.g. to tell a deleted user's name in
      an old session from a new account with the same name).
- [ ] Two-factor login (TOTP, any authenticator app), first for admins, since they can cheat and delete sessions
    - Set up with a QR code on first admin login, with one-time recovery codes; optional for everyone else.
- [ ] User settings page: change username, password, e-mail address and the auto-deletion setting
    - Changing the e-mail address needs a confirmation mail to the new address.
    - Renaming doesn't change the names saved in old sessions.

## Phase 6: More Themes

*Everyone picks how the dashboard looks for them.*

- [ ] Several themes, each with a light and a dark variant
    - The current colours (`webApp/…/Theme.kt`) become the default theme; a theme is a pair of palettes.
- [ ] A theme applies to every page: dashboard, person and world views, admin panel, settings, login
- [ ] Theme and mode are a user setting, stored with the account (settings page from Phase 5)
    - Mode: light, dark, or automatic (follows the device's light/dark setting and switches with it).
    - Active for that user on every device they log in with; other users are not affected.
    - Before login the user isn't known: the login page uses the default theme in automatic mode, or the theme last
      used in this browser.
