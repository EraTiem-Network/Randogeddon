# Randogeddon To-Do List

The target of this project, phase by phase. Items within a phase are roughly in implementation order.
`Open:` marks decisions that are not made yet.

## Conventions

- **Addresses:** the app runs at `randogeddon.eratiem.net`; each session's game server at
  `<session-id>.randogeddon.eratiem.net:443` (TLS, `wss://`).
    - TLS only: current clients connect over `wss://` (Launcher-based clients retry with it, and
      Archipelago.MultiClient.Net, used by many modded games, tries it first since April 2023). Older or unmaintained
      mods may only speak `ws://`, so every game of a session is tested with its real client before session day; for
      those, the Randogeddon Bridge (Phase 12) gives them a `ws://localhost` address.
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
- [x] World Locks APWorld: worlds locked until their key is found, seeds stay completable
- [x] Hint switches (point hints, automatic hints), hint points per world, hint filters
- [x] Demo session with simulated players
- [x] Renamed to Randogeddon; dashboard at `randogeddon.eratiem.net`, the session's game server at
  `<session-id>.randogeddon.eratiem.net:443` (`BASE_DOMAIN` in `.env`)

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

## Phase 3: Admin Panel (done)

*The host runs the session from the browser instead of the shell.*

- [x] Users can be marked as admins; only admins see and reach the panel (checked on the server, not only hidden)
    - `randogeddon admins --grant <person>`; `/api/admin/*` answers 403 for everyone else.
- [x] Create a session: upload the player YAMLs, set players, locks and hint options, generate the seed
    - Every slot gets one or more players (several: they play it together); new names get a login, shown once.
      Since Phase 5 the players are accounts, new people are invited by e-mail.
    - The gateway generates the seed; the game server starts on it.
    - Words: a player is a person (a user with a login), a slot is one YAML, the game only its title.
- [x] Random locks: a number of slots, picked at random, start open; all others start locked
- [x] APWorlds: the gateway reports each release's games; admins upload APWorlds into a library per release, remove
  them, and try ones made for another release
    - A YAML whose game the release lacks is flagged before generating; a failed generation names the games that may
      be the cause (named in the error, or with an untested APWorld).
- [x] Every option in the admin panel explains itself (an info tip on hover or tap)
- [x] Control the session: start, stop and restart the server, see its log, run server commands
    - Stop and restart ask once more; the log is live; the console keeps a command history.
- [x] Delete a session: stop the server, remove the seed, save and slot passwords
    - The logins stay: they belong to people, who will play again (see Phase 5).
- [x] Only one session at a time (creating a second one is refused until the first is deleted)
- [x] Optimal path: the spoiler playthrough rendered as a route (sphere by sphere)
    - Hidden by default (it is a full spoiler). Admins can reveal it at any time; all players of the session see it
      once the session is finished.
- [x] Finish a session
    - Automatically 2 minutes after every slot has reached its goal or was released, with a countdown on the
      dashboard that admins can cancel; an admin can also finish it by hand earlier (e.g. someone dropped out), and
      reopen it within 14 days if it was a mistake (not once it was completed, 100 %).
    - Past sessions are deleted automatically after `SESSION_RETENTION_DAYS` (90 by default); an admin can disable
      that per session.
    - A finished session no longer blocks a new one: it becomes a past session, viewable from a list.
    - Finishing stops the game server, makes the session read-only history, reveals the optimal path to its players
      and starts the auto-deletion timer of its users (Phase 5).
    - Finished sessions are kept until an admin deletes them; their players and the admins can see them. Automatic
      deletion can come with the auto-deletion of users (Phase 5).
- [x] Cheats: give items (`/send`), checks (`/send_location`), release and collect, from the panel
    - Every cheat is logged with who did it, and shown in the live feed as a cheat.
- [x] Cheat hints, if enabled for the session: admins give free hints (`/hint`, `/hint_location`)
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
    - Saved with the session, together with the APWorlds it used (custom worlds, World Locks).
    - The tracker image carries several Archipelago releases side by side; a session always runs on its own, so an
      update never breaks a running session. Releases no session uses any more can be dropped.

## Phase 4: Multiple Sessions (done)

*Several groups play at the same time on the same server.*

- [x] More than one session at the same time, each with its own MultiServer, seed, save and tracker state
    - As many run at once as the gateway has ports for (`AP_PORTS`); a finished session doesn't take one.
- [x] The dashboard lists the sessions a user is part of; the session is chosen after login
    - The only running one opens by itself; the top card switches between them. Each view has a link (`#/<id>`,
      `#/admin/<id>`, …).
- [x] Users of a new session: pick from the users of the existing sessions, or add new ones (login generated as in
  Phase 2)
- [x] Admins see and control all sessions
- [x] Each session's server is reachable from outside
    - One host name per session, `<session-id>.randogeddon.eratiem.net`, on 443 (TLS). Traefik routes the WebSocket
      to the gateway, which relays it by the host name to that session's MultiServer.
    - One Traefik router for `*.randogeddon.eratiem.net` with a wildcard certificate (DNS challenge through ClouDNS's
      API; the HTTP challenge has no wildcard certificates). A new session needs no change in Traefik or DNS.
    - DNS: one wildcard record `*.randogeddon.eratiem.net` pointing to the server.
    - Not paths (`randogeddon.eratiem.net/<session-id>`): Launcher-based clients would accept them, but many modded
      game clients only take host:port.
    - The gateway (Phase 2) already sees each connection's host name, so it can route to the session's MultiServer
      itself instead of one Traefik router per session.
    - The session ID: the admin picks it when creating the session, prefilled with a free readable suggestion
      (`friday-chaos`); a DNS label that starts with a letter, 3 to 40 characters.
    - The gateway routes by the host name (the first label), or by the path without a domain
      (`ws://localhost:38281/<session-id>`, local use and the demo).

## Phase 5: Proper User Management (done)

*Accounts belong to people, not to a session.*

- [x] When creating a session: select existing users, or add new ones by e-mail address
- [x] New users get an invitation mail with a link to choose their username and password
    - The link is single-use and works for 7 days; sending it again (or to another address) replaces it.
    - No open sign-up: an account can only be created with the link from an invitation mail, for the address the
      admin entered.
    - Mail via SMTP on `mail.example.net`, sender `randogeddon@example.net`, credentials in `.env`. The certificate
      is checked against `MAIL_HOST`; `MAIL_ADDRESS` connects to an internal address instead (the VM behind the same
      host as the mail server).
    - People log in with their name or their e-mail address.
- [x] As long as the user hasn't signed up, an admin can change the e-mail address; the old invitation stops working
- [x] Automatic account deletion, chosen when signing up
    - Delete after a time span (2 or 4 weeks, 3 or 6 months, 1 year) or never.
    - The time counts from the end of the user's last session (when it was finished, Phase 3).
    - The user is told 14 days before the deletion happens (and at least 2 days before); a running session postpones it.
    - Deleting removes only the account (login, e-mail address, settings). Sessions stay viewable: they keep the user's
      UUID and the name the user had in that session, saved with the session.
- [x] Display names are unique among active accounts
    - Checked without regard to case ("Alice" and "alice" can't both exist), at sign-up and when renaming; a deleted
      account frees its name.
    - The UI shows only the name; the UUID appears only when hovering over it (e.g. to tell a deleted user's name in
      an old session from a new account with the same name).
- [x] Two-factor login (TOTP, any authenticator app), first for admins, since they can cheat and delete sessions
    - Set up with a QR code on first admin login, with one-time recovery codes; optional for everyone else.
    - The secrets are encrypted (`TOTP_KEY`); `randogeddon admins --reset-2fa` for a lost device.
- [x] User settings page: change username, password, e-mail address and the auto-deletion setting
    - Changing the e-mail address needs a confirmation mail to the new address.
    - Renaming doesn't change the names saved in old sessions.

## Phase 6: Roles: Administrators, Session-Managers, Support and Watchers (done)

*Sessions belong to whoever creates them; people who don't play can follow a session, or help in it.*

- [x] Administrators: the admins of before (`users.is_admin`), account-wide
    - They reach everything: every session, the APWorlds, the users, the settings. Two-factor login stays required for
      them only.
    - Players and Session-Managers can't give them a part in a session (they see every session anyway).
- [x] Every user can create sessions, and is the Session-Manager of the ones they create
    - How many running sessions one user may manage at once is the Administrators' setting (default 1, 0 to 20; 0:
      only Administrators create sessions). The game servers' number still applies to everyone.
    - A Session-Manager manages their session: state, finish, reopen, keep, delete, the game server, console and log,
      the cheats, the spoiler log while it runs, and its people.
    - Co-managers: Session-Managers (and Administrators) make others Session-Managers of the session. A session keeps
      at least one, unless an Administrator takes the last one away. Sessions from before have none until an
      Administrator adds one.
    - Every cheat is logged with the role it was done as ("Bob (Session-Manager) gave …").
- [x] Support: helps the players of a session, given per session by its Session-Managers
    - Sees the spoiler log while the session runs; gives free hints (also without the session's cheat hints), gives
      items and checks locations. No release or collect, game server or console.
    - Every action is logged with their name and shown in the live feed ("Erin (support) gave …").
- [x] Watchers: an account that follows a session without a slot
    - Sees the session's dashboard, live feed and hints like its players do, and finds the session under their
      sessions later. No slot password, not in the people or progress.
    - The spoiler log only once the session is finished, as for players.
    - Added by the session's Session-Managers and by its players: by name, or an invitation for an address that has
      no account. Players take away watchers, not support or Session-Managers.
- [x] Share links: anyone with the link follows the session without a login, read-only (e.g. for a stream)
    - Made and turned off by the session's players and Session-Managers; a new link replaces the old one. Shown once,
      stored only as SHA-256; rate-limited.
    - The players' summary only: no passwords, no spoiler, no accounts, no connection.
- [x] Who sees which accounts: only Administrators see the user list with e-mail addresses (*Users*). Everyone else
      picks people by name and invites by e-mail only an address that has no account.
- [x] Nobody gets a second part in a session (one role besides playing; a Session-Manager may also play)
- [x] The panel (*Manage*) is everyone's: the sessions they manage with their people (who watches, supports or
      co-manages, added or removed while it runs); *General* is the Administrators'

## Phase 7: Patch File Downloads (done)

*Players of ROM-based games get their patch files from the dashboard, not from whoever has the server.*

- [x] Each player downloads the files of their own slots
    - From the dashboard, under the slot in *Your connection* (e.g. `AP_<seed>_P3_Alice-SM.apsm`); slots without a
      file show nothing.
    - The files come from the session's seed (`data/sessions/<id>/AP_<seed>.zip`), read by the gateway and handed
      out by the tracker; the ROM itself is never part of it.
    - A file belongs to a slot by `P<n>` in its name (`AP_<seed>_P<n>_…`, or with `-` as Factorio's mod); the
      spoiler log's `Player <n>: <name>` lines name the slot.
- [x] Which files: everything Archipelago writes for the slot that doesn't help cheating
    - ROM patches, mods (Factorio's zip), other per-slot output.
    - Never the multidata, anything named spoiler, or readable text that could show the way (`.txt`, `.log`,
      `.html`, `.md`, `.csv`).
- [x] Who may download what
    - Players: their own slots (a slot played together: each of its players).
    - Session-Managers and Administrators: every slot of the session (e.g. for someone who can't log in), in the
      panel's *Players*; on the dashboard they see only their own, like everyone.
    - Support, watchers and share links: none. Every download is logged.
- [x] A short how-to with the download: patching with the Archipelago Launcher (*Open Patch*, the player's own ROM)
- [x] How long: as long as the session exists, finished ones too (until it is deleted, by hand or after
      `SESSION_RETENTION_DAYS`)

## Phase 8: The Live Server (done)

*Updating the live server is one command, from images in our own registry; it holds only what runs.*

- [x] Two new VMs for the build infrastructure (the host's services), Docker Compose with pinned
      tags (every stack updates the same way: bump the tag, pull, `up -d`), behind the host's Traefik
    - **1020 `registry`** (`<internal-ip>`): **Artifact Keeper** at `pkg.example.org`: the tracker and gateway images
      (Docker/OCI) and the Maven repositories for the Gradle dependencies, in place of the Nexus; its data on its own
      disk. (Zot and Reposilite were tried first: no real single sign-on for Reposilite, so one registry for both.)
    - **1010 `auth`** (`<internal-ip>`): **Authentik** at `auth.example.org`, apart from the registry.
    - Their compose files in the repo (`infra/`), secrets only in their `.env`.
- [x] Maven in Artifact Keeper: the Nexus's proxies (Maven Central, Gradle plugins, Google, JitPack, PaperMC,
      Sonatype, …), one virtual `public` mirroring them all, and hosted repositories of our own; reading needs a token
      too (`infra/registry/provision.py`)
- [x] Authentik: one login for the infrastructure instead of one per service
    - Artifact Keeper by OIDC (the group `infrastructure` are its admins); machines use service accounts' tokens
      (`<service-account>` reads Maven, `<service-account>` pushes, `<service-account>` on the live VM pulls).
- [x] Randogeddon's Administrators log in through Authentik
    - An Authentik group (`randogeddon-administrators`) decides who is one; the account is linked by its verified
      e-mail address on the first login. Authentik's MFA replaces the app's two-factor login for them.
    - No password login for Administrators; if Authentik is away, `randogeddon admins --login` prints a one-time
      link. Players keep the normal login.
- [x] Images through the registry: built locally (WSL) as now, pushed to Artifact Keeper
      (`pkg.example.org/eratiem-network/randogeddon/{tracker,gateway}`): a release as its version and `latest`, any
      other commit as the commit and `edge`; plain `linux/amd64` images. The live VM pulls them (a read-only token)
      instead of tarballs.
- [x] The live server holds only what runs
    - `/srv/randogeddon`: `docker-compose.yml`, `docker-compose.proxy.yml`, `.env`, `data/`, `custom_worlds/`; the
      volumes `postgres-data` and `tracker-state`; no source code.
    - Randogeddon starts from scratch there (only a test account existed); `.env` stays.
    - The compose files work there without the build parts (`build:`, the Gradle secret).
- [x] One update command (`scripts/deploy.sh`, run from WSL)
    - Builds and pushes the images, copies the compose files over SSH, dumps the database to `/srv/backups/`, pulls
      and restarts, checks `/healthz`, the dashboard and the migrations.
    - `--rollback`: back to the previous image tag (code only; a migration that ran needs the dump).
- [x] Backups on the VMs: each update's dump and a daily one, kept 14 days; a restore tested once
    - Randogeddon's (`randogeddon-backup.timer`), Authentik's and Artifact Keeper's (`infra/stack-dump.sh` and their
      timers), on top of the Proxmox backup: a point before each update, and the database alone without the rest of
      the VM. Restored once into a scratch database on the live VM: every table as live.
    - Old images go too: `scripts/deploy.sh` keeps the running and the previous ones on the server, in WSL only
      what it just pushed.
- [x] README runbook: what is on the server, updating, rolling back, restoring, the `.env` keys
- [x] Versions: `0.<phase>.<step>`, every release tagged in git (`v…`) and in the registry; the changelog by version
- [x] Administrators' accounts follow Authentik: no two-factor login of the app's own for them, deleted when
      Authentik deletes the person (its webhook)
- [x] `.docs/INFRA.md`: the machines, what they keep, updating each stack, the tokens and where they live

## Phase 9: Multi-Language (done)

*Everyone reads the app in their language.*

- [x] Every text of the web app comes from one place per language instead of the code
    - Typed Kotlin in `:shared` (`i18n/*Texts.kt`, both languages side by side): a missing text doesn't compile, a
      test finds a dropped value or a language that borrows another's words.
    - English stays the default; the first other language is German ("du"). Decided in Phase 13: English and German
      are it for now; another one can be added later (its texts per group, the compiler and `TextsTest` find what's
      missing).
- [x] A language applies to every page: dashboard, person and world views, the panel, settings, login
    - Dates, times and numbers are formatted for the language (`14.11.2026, 08:17`, `1.234,5`).
    - Names from Archipelago (games, items, locations, slots) and its option values stay as they are.
    - Messages from the server are codes with their values (`Problem`): the web app words them, and the server words
      them in the request's language too (the `ap-language` cookie, else `Accept-Language`) for the login page.
      Feed lines it builds come in their parts. The mails go out in the recipient's language.
- [x] The language is a user setting, stored with the account (*Your account*; automatic follows the browser)
    - Before login: the language last used in this browser, else the browser's; the login page has a switch.

## Phase 10: More Themes (done)

*Everyone picks how the dashboard looks for them.*

- [x] Several themes, each with a light and a dark variant
    - The colours of before are the default theme (Randogeddon); a theme is a pair of palettes (`:shared`'s
      `Themes.kt`), each with its own item and status colours.
    - Ocean, Forest, Ember, High contrast, and inspired by Nintendo's consoles (colours only): N64, GameCube, Wii,
      Wii U, Switch, Switch 2.
    - `ThemesTest`: every palette at least as readable as the default, its item and status colours apart.
- [x] A theme applies to every page: dashboard, person and world views, the panel, settings, login, the share link
- [x] Theme and mode are a user setting, stored with the account (*Your account*, *Appearance*)
    - Mode: light, dark, or automatic (follows the device's light/dark setting and switches with it); the top card's
      button cycles it.
    - Active for that user on every device they log in with; other users are not affected.
    - Before login: the theme last used in this browser (the login page takes its colours), else the default theme in
      automatic mode.

## Phase 11: The Administrators' Account Page (done)

*Administrators who log in through Authentik manage their account there, not in Randogeddon.*

- [x] With single sign-on, *Your account* for an Administrator has no panels for the name, the password, the e-mail
      address, two-factor login and the account's deletion: Authentik holds those
    - The server refuses those changes for them too (not only hidden in the web app): the account routes and the
      two-factor routes (set up, turn on and off, new recovery codes).
    - A two-factor login an Administrator set up before (0.8.3 let them turn it off) is removed when they log in
      through Authentik; it isn't asked anyway.
    - Without single sign-on (no `OIDC_ISSUER`) nothing changes: Administrators keep those panels.
- [x] Instead, the first panel: *Your Authentik account*, a link to Authentik's user settings
      (`auth.example.org/if/user/`), where they change their name, password, e-mail address and two-factor login
    - The name and e-mail address follow right away: Authentik's `model_updated` event for the user, sent by a
      notification rule like the deletion's (`infra/auth/setup.py`, the same webhook token), names the subject and
      the new values. The next login through Authentik catches up on anything missed, the Administrator status
      included.
- [x] The other settings stay: language and appearance

## Phase 12: Randogeddon Bridge (done)

*Games that can't do TLS connect to `localhost`; the bridge takes them to their session securely.*

- [x] A small app on the player's PC (Go, one binary per OS: Windows, Linux, macOS Intel and ARM; no CGO, all built
      in the Docker build), its GUI a page in the default browser served by the app on localhost, styled like
      Randogeddon; English and German
    - Top left "Randogeddon" with a link out to the website; top right the user (icon, name) and logout.
    - A table: a heading row, then one row per open connection: session, slot, address (`localhost:<port>`, copy),
      state, close; below them session and slot as dropdowns and *Open*.
    - Several connections at once, each on a random free local port (`127.0.0.1` only).
- [x] Login with a long key from the dashboard (*Your connection*: *Make a key*, shown once to copy), so Authentik
      Administrators can use it too and the app never sees a password; kept in the user's config directory until
      logout
    - A key is a login of its own kind (`bridge`, 30 days) that reaches only what the app needs (who, the sessions,
      a session's connection); no settings, no panel.
- [x] The relay works on the WebSocket level: the game connects to `ws://localhost:<port>`, the bridge to
      `wss://<id>.randogeddon.eratiem.net` (Traefik and the gateway route by host name), every message both ways
    - It fills the chosen slot's name and password into the game's `Connect`: nothing to type in the game.
    - States: waiting for the game, connected, error (the server's words); a new game connection tries again.
- [x] Download from the dashboard (*Your connection*, per OS), with how to start an unsigned app (SmartScreen,
      Gatekeeper)
- [x] Each tagged release publishes the bridge's builds to Artifact Keeper (`pkg.example.org`, a generic repository,
      with their SHA-256 sums); from 1.0.0 on also as a GitHub release (with the version's changelog)
- [x] No plain, unencrypted port for game clients: `wss://` stays the only way in

## Phase 13: Polish and Bug Fixes

*Everything tested by hand, and what comes up fixed.*

- [x] Every page, role and flow tested in a browser (and on a phone): logins (password, two-factor, Authentik, CLI
      link), invitations and sign-up, sessions from creation to deletion, cheats and support, watchers and share links,
      downloads, the mails, the bridge
    - English and German side by side are checked after Phase 14 (its themes and fonts change the pages once more)
- [x] A security scan with Fable 5.2: the server's routes and their checks (logins, roles, keys, share links), the
      gateway, the bridge, the infrastructure (Traefik, Authentik, Artifact Keeper, the VMs); what it finds is fixed
      or noted with why not
    - Scanned on 2026-11-16 at 0.13.14: 2 High, 9 Medium and 17 Low findings, all fixed or noted with why not
      (0.13.15 to 0.13.17); the findings are kept outside this repository
- [ ] A second security scan with Fable 5.2, where the first was thin: the code since then, the page's own script,
      what stays open after a right ends, the limits' ways around, logs and disks, the versions on the servers,
      Authentik's own settings, the host's mail and firewall, the repository
    - Scanned on 2026-11-16 at 0.13.17: 1 Medium and 12 Low findings, all fixed or noted with why not (0.13.18,
      0.13.19); the findings are kept outside this repository
- [ ] What comes up is collected here and fixed, phase by phase as needed
    - [x] Bug: an APWorld upload takes only one of the picked files instead of all (*APWorlds*, *Upload APWorld…*)
    - [x] Bug: choosing a theme doesn't explain itself enough (*Your account*, *Appearance*): the dropdown names the
          themes, but doesn't show what each looks like before it is picked
    - [x] Polish: switching the theme (or the mode) fades from the old colours to the new ones instead of jumping
    - [x] Polish: the mode button on the top card shows the mode it is in: a sun (light), a moon (dark), and for
          *Automatic* an icon of its own (e.g. the half-filled circle with an *A*), with the mode as its tooltip; today
          it is the same half-filled circle in every mode (`Icon.THEME`)
    - [x] Polish: an uploaded APWorld's file name is tidied: lower case, `-` instead of spaces, every other symbol
          removed
        - Archipelago imports an APWorld by its file name, which must be the world folder inside it: the file is
          named after that folder (`pokemon_emerald/` → `pokemon_emerald.apworld`, whatever it was called), which
          is lower case with `_` as a rule; a folder Python can't import (e.g. with a `-`) is refused
    - [x] Bug: an upload that would replace something says so and asks first (*Replace* / *Skip*): a file of the
          same name in the release's library, or another uploaded APWorld of the same game (by its manifest)
        - A game the release brings itself can't be replaced (Archipelago 0.6.8 loads its own world first and skips
          the APWorld): refused, with why
    - [x] Bug: while files are uploading, the release's list of uploaded APWorlds doesn't change; each file should
          show up as soon as it is added
    - [x] Polish: *Users* (Administrators) as pills (avatar, name; *invited* and *Administrator* marked on them)
          instead of one row each, in a box that scrolls once there are enough accounts
        - A click on a pill opens a floating panel with everything a row shows today: the address, the sessions,
          until when an invitation works, when the account deletes itself, and an invited one's *Send again* /
          *Other address*
    - [x] Polish: the panel shows the new-session form only after *New session* was clicked (or at
          `#/manage/new`); with no session running it no longer opens by itself (`Admin.kt`, the page falls back to
          `NEW_PAGE`), but says that none is running, next to the *New session* button
    - [x] Polish: *Your connection* wider, in two columns, so it isn't as tall (now 420 dp next to the tiles,
          `Dashboard.kt` `CONNECTION_WIDTH`, which get less room)
        - Left: the server, the slot (the dropdown), its name and password. Right: the slot's files, then the bridge.
          On a narrow screen one column, as now.
        - The slot's files get a small heading of their own (*Patch files*), always shown for a player: the
          picked slot's files to download, or that this slot has none (its game needs no patch).
    - [x] Polish: the bridge under *Your connection*
        - *Create key* instead of *Make a key* (German *Schlüssel erstellen*); the button and the key it makes come
          above the downloads
        - The downloads under a small heading of their own (e.g. *Downloads*, with the version), one row per system
          in this order, the architectures as buttons:
          `Windows  x86_64  ARM64` / `macOS  Intel  Apple Silicon` / `Linux  x86_64  ARM64`
        - One ⓘ only, next to *Randogeddon Bridge*: what it is and how it is set up, then how to start the unsigned
          app (now the downloads' own ⓘ, which goes)
        - Windows ARM64 is new: a sixth build (`windows/arm64`, `kotlin.Dockerfile`'s bridge stage); the downloads
          route and `publish-bridge.sh` take it as it is
    - [x] Polish: removing APWorlds by selecting them, instead of a × on each pill (on a long pill the × was
          squeezed away, and on a phone the question didn't fit its line)
        - Each release's library gets a *Remove…* button next to *Upload APWorld…*: it switches the uploaded pills
          to selectable (a click marks one; hand-placed ones can't be picked), with *Remove (n)* and *Cancel* below
          them, on their own line
        - *Remove (n)* asks once more, naming the files, then removes them, with a line for each, as the uploads do
    - [x] Polish: uploading and removing APWorlds faster. Each file makes the release list its games again
          (`list_games.py`, every world loaded: about 5 s), twice for one that is refused.
        - Archipelago's Python files compiled in the image (`python -m compileall` in `kotlin.Dockerfile`'s
          archipelago stage): the gateway can't write `__pycache__` there, so every listing compiled all 1,827 files
          again. Measured: 5 s → 2.2 s per listing; generating and starting game servers gain the same way.
        - Several files in one request, uploads and removals alike: one listing for all of them (and once more if
          one is refused and taken out again), instead of one per file
    - [x] Bug: after a click inside a user's floating panel (*Users*), it can't be closed any more (neither × nor a
          click outside)
    - [x] Polish: the floating panel becomes a user information panel, everything about the account in one place
        - Its fields, each with its label: name, e-mail address, ID (to copy), state (active, invited), group
          (Administrator or not), made on, the invitation (until when its link works), auto-deletion and when the
          account deletes itself; *n/a* for what an account doesn't have (an invited one's name, a deletion that
          isn't planned)
        - The sessions it plays in as pills again, a click opens that session
        - Possibly more from the server (new fields of `AdminUser`): how they log in (password, Authentik), whether
          two-factor login is on, the last login. To be decided when it is fixed.
        - An invited one's *Send again* / *Other address* stay in it
    - [x] Bug: selecting a finished session breaks the view: the session shows no content, and after it the panel
          and *Your account* are empty too (until a reload)
        - The cause: any session's dashboard on a wide screen crashed since 0.13.2 (the tiles' row measured *Your
          connection*, which decided its columns by its own width), and Compose draws nothing after a crash
    - [x] Polish: the state on the top card (the game server's: connected, stopped) only for a session that isn't
          finished; a finished one has no game server
    - [x] Bug: zooming is broken again: sometimes it zooms in, but it doesn't zoom out any more
        - The app doesn't block the browser's zoom (Ctrl and the wheel, Ctrl +/−/0 reach it). On a phone, the login
          form's 12.5 px fields made it zoom in by itself, and over the app's canvas a pinch can't zoom back out:
          16 px now. On a computer, the page noticed a zoom by a media query on the exact pixel density, which can
          miss one like 1.3636…: by the window's size now
    - [x] Polish: *APWorlds*, *Remove…*: a *Select all* button next to *Remove (n)* / *Cancel* (all uploaded files of
          that release; again: none)
    - [x] Polish: the bridge's line in *Your connection*: `Bridge ⓘ` on the left, *Create key* on the right, without
          the line of text between them
        - Its ⓘ in parts, each with a small heading (the tip takes headed parts already, `InfoTip`'s `options`):
          what it is, what it does, how to use it (download, create a key, paste it, open a relay, the address into
          the game), then starting it per system: Windows, macOS, Linux, each its own part
        - A new key shows like the slot's password: hidden (dots) with *Show* / *Hide* and *Copy*, plus a button
          that closes the key's line (it is shown only this once, as now)
        - The bridge gets a heading of its own (*Randogeddon Bridge*) and can be collapsed: it folds away to the left,
          *Your connection* becomes one column again (as narrow as before 0.13.2), and the tiles beside it get the
          room; a button on the heading (or on the panel's edge while folded) opens it again; remembered in the
          browser
    - [x] Polish: the patch files go back under the slot and its password (left column, they belong to the slot); the
          right column is the bridge's alone
    - [x] Bug: a Session-Manager can remove themselves as Session-Manager (*People*): refused, in the panel (no
          *Remove* on their own entry) and by the server; another Session-Manager or an Administrator can
    - [x] Bug: an APWorld that imports `tkinter` doesn't load (e.g. `minecraft_dig.apworld`, for its client window):
          `ImportError: libtk8.6.so: cannot open shared object file`
        - The image's Python (`python:3.12-slim`) has `tkinter`, but not the Tk library it needs: `libtk8.6` into the
          archipelago stage (`kotlin.Dockerfile`). Importing it needs no screen; only opening a window would, which a
          world doesn't do while loading.
        - Checked what else: every bundled world of 0.6.8 loads in the image, and `kivy` (Archipelago's client
          toolkit) imports; Tk was the only library missing. A missing one shows in the panel with Python's error.
    - [x] Polish: the live feed's place (*Right* / *Left* / *Below*) is only offered where it can be beside the players;
          on a narrow screen (a phone) it is always below, so the dropdown goes there (the choice stays for wider
          screens)
    - [x] Feature: hosted slots, for games that run as a server of their own (the players join that server with their
          games, not Archipelago)
        - In the panel's slot player field, *Hosted* can be entered as the slot's player: then the slot's players are
          removed and its field to add more goes (no one plays it from Archipelago's side)
        - *Hosted* is reserved: no account can be named so (sign-up, renaming, invitations; `UserNames`)
        - *Your connection*: the session's players can pick a hosted slot too, but see only its patch files (no
          name, no password); its Session-Managers see its whole connection (server, slot, password, files), to set
          the game's server up
        - The dashboard and the panel show the hosted slots on a card of their own, *Hosted* (its time and checks are
          the slots' own, as for any slot); locks and hints treat it like any other slot
    - [x] Polish: without a login (logged out, or the login ended), the settings kept in the browser go back to the
          defaults (theme and mode, language, the live feed's place and visibility, the bridge folded or not); a login
          sets the account's again (theme, mode and language are stored with it; the rest starts from the default)
    - [x] Bug: *Your account* shows the user's ID (UUID) only in the name panel, which Administrators with single sign-on
          (Authentik) don't get: they see it too, e.g. in the *Your Authentik account* panel (`Settings.kt`, `acc.id`)
    - [x] Polish: the panel's *General* (Administrators) gets a last panel after *Settings*: the running versions of
          the tracker and the gateway (and the gateway's Archipelago releases), so a deploy can be checked there
    - [x] Polish: *Hosted* is a checkbox on the right of a slot's row in the new-session form (next to *starts
          locked*), instead of typing *Hosted* into the player field; ticking it asks once more, then removes the
          slot's players and its player field (unticking brings the field back)
    - [x] Bug: loading the YAMLs prefills a slot's player; a slot starts without players, they are added by hand
    - [x] Bug: ending (finishing) a session cancels its invitations that aren't accepted yet (accounts invited for it
          that haven't signed up: their links stop working and the invited accounts go, unless another running
          session has them; who invited them isn't recorded, so an Administrator's invitation counts the same)
    - [x] Polish: adding a player to a slot is a searchable dropdown (type to filter the accounts, pick one; an e-mail
          address offers the invitation as before), instead of the text field with suggestion buttons below it
    - [x] Polish: the new-session form adds no slots for YAMLs whose game the release has no APWorld for; they are
          summed up in one message (the files and their games), and someone who isn't an Administrator is told to ask
          one to upload the APWorld
        - While the form is open, an APWorld uploaded meanwhile (by anyone, in another view or another browser) takes
          effect by itself: the release's games are looked at again and the waiting YAMLs become slots, without
          uploading them anew
    - [x] Polish: *Generate and start* is only active once every slot has at least one player or is hosted; until
          then the form names the slots still without one. The server refuses such a session too (a new problem,
          e.g. `SLOT_WITHOUT_PLAYER`).
    - [x] Polish: *Generate and start* shows how far the generation is, as a bar with the stage's name (e.g. *Filling
          the items… 60 %*), instead of only a text while it runs
        - Archipelago's `Generate.py` reports no percentage, but logs its fixed stages (creating the worlds, the
          access rules, `generate_early` / `create_items` / `set_rules` / `generate_basic`, filling the items, the
          spoiler, writing the output files, the zip): each gets a share of the bar
        - The gateway keeps the latest stage per session while `Generate.py` runs and answers it on a small status
          route; the panel asks about every second while it generates
        - Filling the items takes most of the time and has no progress of its own: the bar waits there, the stage's
          name says why
    - [x] Bug: generating with a locked A Link Between Worlds slot fails ("No more spots to place 1 items"): ALBW
          places its dungeon keys in `pre_fill`, inside its own world, with a state of its own items, and World Locks had
          locked the world before: World Locks 1.1.0 locks the worlds in `stage_pre_fill`, after every world's
          `pre_fill`
    - [x] Polish: no menu of Compose's own on a right click (it had only *Copy*)
    - [x] Polish: diagrams in the Markdown files are Mermaid (```` ```mermaid ```` blocks, which GitHub draws), not
          drawn with box characters: the README's overview of Traefik, the gateway, the tracker and Postgres first;
          new diagrams in `.md` files only so
    - [x] Polish: the generation's bar asks more often (about every 300 ms instead of every second) and has more steps
        - The fill counts itself: Fill.py logs `Current fill step (<step>) at <n>/<total> items placed.` (steps *Early
          Items*, *Priority*, *Progression*, *Accessibility Corrections*): the bar moves within *Filling the items*
          by that count. Only every 1000 items, though, so a session of a few hundred items still waits there.
        - *Balancing multiworld progression for N Players.* is its own stage after the fill (or *Progression
          balancing skipped.*)
        - The bar spans the form's whole width, left to right (now at most 480 dp)
    - [x] Bug: *Your account* doesn't show the selected session's state on the top card: it shows the session (its
          progress, its game server's state) as the panel does; without one, that none is chosen, as everywhere else
    - [x] Polish: Randogeddon Bridge follows a theme (or mode, language) changed on the dashboard by itself, without
          logging in again: it asks for its info (`/bridge/info`) again regularly, e.g. with its key check every minute
          or when its page gets the focus back
    - [x] Polish: a locked slot can't be connected until its key is found
        - *Your connection* and the bridge show it as locked (no password, no relay) until it is unlocked
        - Hosted slots too: a locked hosted slot's game server can't connect either (its Session-Managers see its
          connection as locked until then; its players see its patch files as before)
        - The gateway refuses a game client's `Connect` for a locked slot (`ConnectCheck`), so the lock no longer rests
          on the honour system alone; it needs to know when a slot is unlocked (the tracker sees the key arrive and
          tells it, or the gateway reads it from the game server's save)
        - Built strictly: nobody's game client connects a locked slot, Session-Managers' and support's neither (the
          tracker's own connections go to the game server directly, not through the gateway, so the dashboard keeps
          seeing it)
    - [x] Polish: the panel's *Players* and *People* said the same twice: one *People*
        - Everyone of the session as a pill with their part (no slots on the pill); a click opens their panel: their
          slots (game, how far, how it stands), how far they are in the session, played, last active
        - In the panel: another part for them (a player becomes Session-Manager or support besides their slots), or
          taking theirs (a playing one only plays then)
        - The last Session-Manager stays, for Administrators too: someone else becomes Session-Manager first (the
          panel says so; `KEEP_MANAGER` on the server)
        - What was left of *Players* is *Patch files* (every slot's files from the seed), after *People*
    - [x] Polish: Randogeddon Bridge for hosted slots: offered to the session's Session-Managers and Administrators
          (they have the password), still not to its players, never a locked one
    - [x] Polish: Randogeddon Bridge on the command line, for the computer a hosted game runs on: `login` (the key from
          standard input), `slots`, `relay -session -slot [-port]`, `logout`
    - [x] Polish: Randogeddon Bridge quits once its page or the browser is closed (unless back within 10 seconds)
    - [x] Polish: a login through Authentik ends with the Authentik session (its back-channel logout); an open
          dashboard notices within 30 seconds
    - [x] Polish: the cheat *Check a location* from the slot or to it (another world's location holding its item)
    - [x] Polish: the spoiler log's playthrough marks the steps already checked, reads *Item @ Check*, and has a
          search that filters its rows
    - [x] Bug: the page scrolls, but shows no scroll bar (drawn on a canvas): one on everything that scrolls
    - [x] Bug: a login ended at Authentik showed "not logged in" in a field instead of the login form
    - [x] Bug: the playthrough's *Fold all* / *Unfold all* didn't follow the list (the filtered spheres, folding by hand)
- [x] Open points from earlier phases looked at again: the only one was which languages follow (English and German
      stay, see Phase 9)

## Phase 14: More Themes

*More themes to pick from, grouped, and clear that the console-inspired ones are only inspired.*

- [ ] Console-inspired themes, each light and dark: NES, SNES, PlayStation (PS1), PS2, PS3, PS4, PS5, Xbox, Xbox 360,
      Xbox Series X|S
- [ ] More of Randogeddon's own themes (not inspired by anything)
- [ ] The themes grouped under *Your account*, *Appearance*: Randogeddon's own, Nintendo, PlayStation, Xbox (the theme
      cards under a small heading per group)
- [ ] A trademark notice under the theme cards (and in the README): the console themes are only inspired by those
      consoles' colours; the names are their owners' trademarks, and Randogeddon isn't affiliated with or endorsed by
      Nintendo, Sony or Microsoft
- [ ] A theme can bring its own font (e.g. a pixel font for NES/SNES); under *Appearance* a font choice beside the
      mode: *The theme's* (the default), *Randogeddon's original*, or the font of another theme; stored with the
      account like the theme and the mode
    - Fonts bundled with the app (free licences only, e.g. SIL OFL), loaded only when used; every font must still
      be readable at the dashboard's small sizes (a pixel font maybe only for headings)
- [ ] Each new theme passes `ThemesTest` (readable like the default, item and status colours apart)
- [ ] Afterwards every page checked in English and German side by side (moved here from Phase 13: the new themes and
      fonts change the pages once more)
