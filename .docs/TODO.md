# Archipelago To-Do List

The target of this project, phase by phase. Items within a phase are roughly in implementation order.
`Open:` marks decisions that are not made yet.

## Conventions

- **Addresses:** the app runs at `randomizer.example.net`; each session's game server at
  `<session-id>.randomizer.example.net:443`.
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

## Phase 2: User Login

*Nobody sees anything of the session without logging in.*

- [ ] Generate a login (username + random password) for every person of the session
    - People come from `session.yaml`; the username defaults to the person's name.
    - Passwords are stored only as hashes (Argon2id); the plain password is shown once.
    - The logins are printed once when they are generated; the host hands them to the players. Phase 5 replaces
      this with invitations.
- [ ] Login page; the dashboard is only accessible when logged in
    - Session cookie (HTTP-only, Secure, SameSite=Strict) with an expiry, logout.
    - Rate limiting and a short delay on failed logins.
- [ ] No data before login
    - `/api/*` and `/api/stream` answer 401 without a valid session.
    - The web app bundle and the login page contain no session data (no seed, names or worlds).
    - The service worker never caches API responses; logging out clears what the app has in memory.
- [ ] PostgreSQL, from this phase on (new `postgres` service in compose)
    - Users are identified by a UUID from the start; the name is the display name, unique among active accounts.
    - Schema with Flyway migrations, access through Exposed (versions CoreSync last used: Exposed 1.6.0,
      Flyway 13.11.1, PostgreSQL JDBC 42.7.14).
    - Connection settings and password in `.env`.
- [ ] Generated room password for every session
    - Replaces the manual `AP_PASSWORD`; a session never runs without a password.
    - Logged-in players see the server address, their slot names and their password on the dashboard, so the login
      also guards access to the game.
- [ ] Password per slot
    - Every slot (world) gets its own generated password; a player can only connect as their own worlds.
    - Checked by `archipelago/serve.py` against the password field of the client's `Connect` packet, the same field
      every client (modded games included) already sends the room password in, so no client needs changes.
    - The tracker's own connections authenticate with an internal secret that players never see.
    - Hooks into MultiServer's connection handling: re-check it on every Archipelago update.

## Phase 3: Admin Panel

*The host runs the session from the browser instead of the shell.*

- [ ] Users can be marked as admins; only admins see and reach the panel (checked on the server, not only hidden)
- [ ] Create a session: upload the player YAMLs, set people, locks and hint options, generate the seed
- [ ] Control the session: start, stop and restart the server, see its log, run server commands
- [ ] Delete a session: stop the server, remove the seed, save and logins
- [ ] Only one session at a time (creating a second one is refused until the first is deleted)
- [ ] Optimal path: the spoiler playthrough rendered as a route (sphere by sphere)
    - Hidden by default (it is a full spoiler) and only shown after an explicit "reveal" by an admin.
- [ ] Cheats: give items (`/send`), checks (`/send_location`), release and collect, from the panel
    - Every cheat is logged with who did it, and shown in the live feed as a cheat.
- [ ] Cheat hints, if enabled for the session: admins give free hints (`/hint`, `/hint_location`)
- [ ] The tracker runs the game servers itself
    - The tracker image gets Archipelago (pinned release, world requirements, `serve.py`, World Locks).
    - It starts one MultiServer process per session with the session's options, and stops and restarts it on request.
    - It reads the server log and sends console commands through the process's stdin.
    - Seed generation runs in the tracker too (for "Create a session"); `scripts/generate.sh` stays as a CLI tool.
    - The separate `archipelago` container is removed. The tracker needs no Docker access, it only controls processes.

## Phase 4: Multiple Sessions

*Several groups play at the same time on the same server.*

- [ ] More than one session at the same time, each with its own MultiServer, seed, save and tracker state
- [ ] The dashboard lists the sessions a user is part of; the session is chosen after login
- [ ] Users of a new session: pick from the users of the existing sessions, or add new ones (login generated as in
  Phase 2)
- [ ] Admins see and control all sessions
- [ ] Each session's server is reachable from outside
    - Preferred: one host name per session, `<session-id>.randomizer.example.net:443`. Traefik routes the WebSocket
      by host name to that session's port inside the container.
    - Needs a wildcard DNS entry `*.randomizer.example.net` and a wildcard certificate (Let's Encrypt only issues
      those with the DNS challenge, so Traefik's certificate resolver needs DNS-provider access).
    - Fallback, if host names don't work: one public port per session from a fixed range.
    - Not paths (`randomizer.example.net/<session-id>`): Launcher-based clients would accept them, but many modded
      game clients only take host:port.
    - Open: the port range (defined later).

## Phase 5: Proper User Management

*Accounts belong to people, not to a session.*

- [ ] When creating a session: select existing users, or add new ones by e-mail address
- [ ] New users get an invitation mail with a link to choose their username and password
    - The link is single-use and expires.
    - No open sign-up: an account can only be created with the link from an invitation mail, for the address the
      admin entered.
    - Mail via SMTP on `mail.example.net`, sender `randomizer@example.net`, credentials in `.env`. The host is
      configurable: if the TLS certificate is issued for `mail.example.org`, connect by that name.
- [ ] As long as the user hasn't signed up, an admin can change the e-mail address; the old invitation stops working
- [ ] Automatic account deletion, chosen when signing up
    - Delete after a time span (weeks, months or years, from a fixed list) or never.
    - The time counts from the end of the user's last session.
    - The user is told before the deletion happens.
    - Deleting removes only the account (login, e-mail address, settings). Sessions stay viewable: they keep the user's
      UUID and the name the user had in that session, saved with the session.
- [ ] Display names are unique among active accounts
    - Checked without regard to case ("Alice" and "alice" can't both exist), at sign-up and when renaming; a deleted
      account frees its name.
    - The UI shows only the name; the UUID appears only when hovering over it (e.g. to tell a deleted user's name in
      an old session from a new account with the same name).
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
