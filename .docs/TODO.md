# Archipelago To-Do List

The target of this project, phase by phase. Items within a phase are roughly in implementation order.
`Open:` marks decisions that are not made yet.

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
    - Open: how the logins reach the players (printed by the deploy script, shown once on an admin page, ...).
- [ ] Login page; the dashboard is only accessible when logged in
    - Session cookie (HTTP-only, Secure, SameSite=Strict) with an expiry, logout.
    - Rate limiting and a short delay on failed logins.
- [ ] No data before login
    - `/api/*` and `/api/stream` answer 401 without a valid session.
    - The web app bundle and the login page contain no session data (no seed, names or worlds).
    - The service worker never caches API responses; logging out clears what the app has in memory.
- [ ] Logins survive tracker restarts (stored next to the tracker state)

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
- Open: how the panel controls MultiServer. Either the tracker starts it as a child process (simplest, needed for
  Phase 4 anyway) or it talks to Docker. This decides whether the `archipelago` container stays.

## Phase 4: Multiple Sessions

*Several groups play at the same time on the same server.*

- [ ] More than one session at the same time, each with its own MultiServer, seed, save and tracker state
- [ ] The dashboard lists the sessions a user is part of; the session is chosen after login
- [ ] Users of a new session: pick from the users of the existing sessions, or add new ones (login generated as in
  Phase 2)
- [ ] Admins see and control all sessions
- Open: how players reach each server. Either one port per session (needs a port range in Traefik or the firewall)
  or one host name per session (`<session>.ap.example.com`, wildcard certificate). AP clients connect to host:port.

## Phase 5: Proper User Management

*Accounts belong to people, not to a session.*

- [ ] When creating a session: select existing users, or add new ones by e-mail address
- [ ] New users get an invitation mail with a link to choose their username and password
    - The link is single-use and expires.
    - Open: mail delivery (SMTP server, sender address).
- [ ] As long as the user hasn't signed up, an admin can change the e-mail address; the old invitation stops working
- [ ] Automatic account deletion, chosen when signing up
    - Delete after a time span (weeks, months or years, from a fixed list) or never.
    - The time counts from the end of the user's last session.
    - The user is told before the deletion happens.
    - Open: what "deleted" removes (account only, or also the user's entries in old sessions' history).
- [ ] User settings page: change username, password, e-mail address and the auto-deletion setting
    - Changing the e-mail address needs a confirmation mail to the new address.
- Open: where users and sessions are stored. With accounts that live longer than a session, a database (SQLite or
  PostgreSQL) instead of files.
