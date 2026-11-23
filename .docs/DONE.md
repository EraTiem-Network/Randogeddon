# Randogeddon: Done

The finished phases as they were planned and done, with what was decided on the way, moved here from `TODO.md` unchanged when Phase 22 began. What each version brought: `CHANGELOG.md`.

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

## Phase 13: Polish and Bug Fixes (done)

*Everything tested by hand, and what comes up fixed.*

- [x] Every page, role and flow tested in a browser (and on a phone): logins (password, two-factor, Authentik, CLI
      link), invitations and sign-up, sessions from creation to deletion, cheats and support, watchers and share links,
      downloads, the mails, the bridge
    - English and German side by side are checked in Phase 16 (Phase 14's themes and fonts changed the pages once more)
- [x] A security scan with Fable 5.2: the server's routes and their checks (logins, roles, keys, share links), the
      gateway, the bridge, the infrastructure (Traefik, Authentik, Artifact Keeper, the VMs); what it finds is fixed
      or noted with why not
    - Scanned on 2026-11-16 at 0.13.14: 2 High, 9 Medium and 17 Low findings, all fixed or noted with why not
      (0.13.15 to 0.13.17); the findings are kept outside this repository
- [x] A second security scan with Fable 5.2, where the first was thin: the code since then, the page's own script,
      what stays open after a right ends, the limits' ways around, logs and disks, the versions on the servers,
      Authentik's own settings, the host's mail and firewall, the repository
    - Scanned on 2026-11-16 at 0.13.17: 1 Medium and 12 Low findings, all fixed or noted with why not (0.13.18,
      0.13.19); the findings are kept outside this repository
- [x] What comes up is collected here and fixed, phase by phase as needed
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

## Phase 14: More Themes (done)

*More themes to pick from, grouped, and clear that the console-inspired ones are only inspired.*

- [x] SteamOS as a theme too (asked for later), in a group of its own, named in the trademark notice (Valve)
- [x] Console-inspired themes, each light and dark: NES, SNES, PlayStation (PS1), PS2, PS3, PS4, PS5, Xbox, Xbox 360,
      Xbox Series X|S
- [x] More of Randogeddon's own themes (not inspired by anything): Midnight, Sakura, Terminal, Sepia
- [x] The themes grouped under *Your account*, *Appearance*: Randogeddon's own, Nintendo, PlayStation, Xbox (the theme
      cards under a small heading per group)
- [x] A trademark notice under the theme cards (and in the README): the console themes are only inspired by those
      consoles' colours; the names are their owners' trademarks, and Randogeddon isn't affiliated with or endorsed by
      Nintendo, Sony or Microsoft
- [x] A theme can bring its own font (e.g. a pixel font for NES/SNES); under *Appearance* a font choice beside the
      mode: *The theme's* (the default), *Randogeddon's original*, or the font of another theme; stored with the
      account like the theme and the mode
    - Fonts bundled with the app (free licences only, e.g. SIL OFL), loaded only when used; every font must still
      be readable at the dashboard's small sizes (a pixel font maybe only for headings)
    - Done in the web app (0.14.3): 25 fonts, one per theme but the default; display fonts in the headings, or
      everywhere if chosen. The login page follows nothing
    - [x] The bridge's page follows the font too (as it follows the theme): the bridge fetches the font's files
          from the dashboard and serves them on its own page (0.14.4)
- [x] Each new theme passes `ThemesTest` (readable like the default, item and status colours apart): all 26 themes
      do, light and dark; it also asks now that the accent is readable on the panel
- [x] Where the font shows is a choice for every font (asked for later): as the font is made, all text, or the
      headings only
- [x] A font change is animated (asked for later): the page's letters take the new font one by one, in no order
- The check of every page in English and German side by side is Phase 16 now

## Phase 15: More Polish (done)

*What the Administrators still miss for the accounts.*

- [x] An Administrator can time an account out (for a while), suspend it (until taken back) or delete it, under
      *Users*; never their own
    - Decided: time-outs of 1 hour, 1 day, 7 days or 30 days; the login form and a mail tell them, with a reason
      if one is given; never an Administrator's account (they are managed where they log in); their sessions go
      on, and a session whose Session-Manager is kept out tells its people to turn to an Administrator
- [x] *Sessions per user* moves from the settings into the user panel: the Administrator sets it per user. It counts
      the sessions that user created (not the ones they were made Session-Manager of)
    - Decided: a default (the setting so far, in the users' card) plus an account's own number in its panel

## Phase 16: English and German Side by Side (done)

*Every page reads right in both languages, with the themes and fonts there are now.*

- [x] Every page checked in English and German side by side (moved here from Phase 13, then from Phase 14: the new
      themes and fonts changed the pages once more), and what comes up fixed
    - [x] The texts read in both languages, about 150 changed; the font's animation only after a change by hand
          (0.16.1)
    - [x] The pages looked through (0.16.3): *No session* with a bubble to the panel's button, *Managed via
          Authentik*, choice buttons for language, mode and font mode, *SSO (Authentik)*, the self-created sessions'
          limit with arrows, more time-out steps, the title goes to the main page
    - [x] The font mode's first choice is *Font's default* (0.16.14)
- [x] A fresh install starts without errors: the gateway waits for its login and the tables (0.16.2)
- [x] The address from Authentik is taken over, and whether Authentik calls it verified is kept and shown (0.16.3,
      V22)
- [x] Support (asked for later)
    - [x] Support sees its sessions in the panel (0.16.4)
    - [x] Support helps in the panel only, and adds watchers and makes the share link there (0.16.5)
    - [x] The panel's *Support* section no longer flickers (0.16.5)
- [x] The panel is the *Management panel* (German *Verwaltung*) (0.16.5)
- [x] The spoiler log stays usable (asked for later, 0.16.4): steps checked from the checked locations, drawn
      icons, an × to empty a search, the full log 300 lines at a time, its search on the server (one per user at a
      time, half a second apart)
- [x] Names with rules (asked for later)
    - [x] User names only `[A-Za-z0-9_]`, names that pass for Randogeddon or its staff reserved (0.16.5)
    - [x] Unwanted words in user names and session IDs (0.16.5), from three newer lists, about 9,600 words of 17
          languages, ordinary words taken out against frequency lists (0.16.9)
    - [x] A session ID checked as it is typed (0.16.6)
- [x] A new session while it generates (asked for later)
    - [x] Only the ID, the stage and the bar show; a failed generation brings the form back (0.16.6)
    - [x] Four steps above the bar and more states (0.16.7)
    - [x] The ID shown is the one that was sent, not a new suggestion (0.16.12)
- [x] A locked slot's patch files aren't shown or handed out until its key (asked for later, 0.16.7)
- [x] Hints (asked for later)
    - [x] An item hint names one copy that is neither checked nor hinted; a hinted location isn't offered (0.16.8)
    - [x] An ⓘ explains the hints' states (0.16.8)
    - [x] A switch for which copy: *Random copy* or *Next in playthrough* (0.16.14)
- [x] APWorld uploads (asked for later)
    - [x] Each upload a pill that fills yellow, then green or red; *Clear*, and gone by itself 10 seconds after an
          upload without trouble (0.16.10)
    - [x] Why one was refused is in its pill's tip only (0.16.11); no *Uploading …* line (0.16.12)
- [x] Zoom (asked for later)
    - [x] A browser zoom no longer reloads the page and loses what is going on: Compose Multiplatform
          1.13.0-beta02 draws at the new density (0.16.13)
    - [x] Ctrl and the mouse wheel zoom the page instead of scrolling it (0.16.14)
    - [ ] Compose Multiplatform 1.13.0 once it is stable (the beta until then)
- [x] `RelayTest` waits for what it checks instead of a fixed moment (0.16.4)

## Phase 17: A Repository That Can Be Shown (done)

*What is critical leaves the repository and its history, into a new one.*

- [x] Critical docs, data and information (the servers' setup and addresses in `.docs/INFRA.md` and `infra/`, what
      the security scans found, anything else that tells too much) are removed from this repository and from its git
      history, and moved to a new repository: `infra` (the servers' network and setup, not on GitHub)
    - Decided: critical are the server docs and scripts, internal details (addresses, VM IDs, SSH hops, host paths,
      service accounts), the scans' findings, and every address but `randogeddon.eratiem.net` (with its subdomains)
      and the licence's contact; the history is rewritten, not squashed, and every commit and tag signed again
    - [x] Out of the tree (0.17.1): `.docs/INFRA.md`, `.docs/MAIL_MIGRATION.md`, `infra/` (Randogeddon's own backup
          units are in `scripts/` now), the README's server specifics, the scans' findings (a summary line each
          stays); the other addresses are placeholders (`example.org`, `<internal-ip>`, …). The registry and the
          Maven mirror are each machine's settings, not the repository's
    - [x] Out of the history: every commit cleaned the same way (672), every commit and tag (85) signed again;
          authors, dates and messages kept otherwise. The GitHub repository was made anew with it (no old commit
          stays reachable there): its pull requests start again at #1, the pre-releases were made again with their
          notes cleaned. The old history and the old pull requests are kept outside the repository

## Phase 18: Code Optimization (done)

*The code made leaner and faster where it pays, without changing what it does.*

- [x] The code looked through for what can be simpler, shared or dropped (repeated code, unused code, files that
      grew too big), and for what is slow or heavy (the web app's size and start, the native images, the database's
      queries, the work per update)
- [x] Restructured where it helps the optimization: modules, packages and files cut along what belongs together
      (e.g. a big file split by its parts, shared code moved to where both sides use it), so the code is easier to
      change and builds faster; not for its own sake
    - Decided: all four areas (page load, server resources, images and build, code structure), measured first;
      the code structure over every code file (repeated code merged where it makes it simpler, split, moved or
      merged where that helps), each step with its numbers before and after
- [x] The baseline and the audit (0.18.1): `scripts/measure.sh`, its numbers and the findings in
      `.docs/MEASUREMENTS.md`, with a verdict for every code file
- [x] Page load: the web files compressed once when built (Brotli and gzip), not per request (0.18.2): 3.4 MB
      instead of 4.5 MB with Brotli, the tracker's work per page load 10 ms instead of 207 ms
- [x] Server resources (0.18.3): one look per session for all its streams instead of each polling every 750 ms (the
      tracker at 0.8 % instead of 5.7 % CPU with 60 viewers); the options decoded once, the share link's summary
      serialized once; the panel's overview from the last reading (2.9 ms instead of 10.7 ms). The tracker's memory
      and the gateway's looked into: nothing kept, the 363 MiB were a running session
- [x] Images and build (0.18.4): the gateway image 725 MB instead of 1.2 GB (Archipelago built in a stage of its own,
      without its compilers); Gradle's build cache in the image build, so a change to the tracker builds in 149 s
      instead of 416 s. The tracker image stays (its 46 MB of bridge builds are downloads)
- [x] Code structure, by module: the web app in packages by area, its big files split, the request-and-decode
      helper used everywhere (53 places), the doubled components and the page frame shared; `Api.kt`,
      `Accounts.kt`, `Stores.kt`, `SessionState.kt`, `LoginRoutes.kt` split; `GeneralTexts.kt` renamed; the compiler's
      warnings fixed, `SingleSignOn.createLink` removed
    - [x] `shared` (0.18.5): `Api.kt` split by area (summary, connection, game server, sessions, spoiler log,
          cheats, APWorlds), `Accounts.kt` into the account, the login, the users' administration and a session's
          people; `GeneralTexts.kt` into `ApworldTexts.kt` and `UserAdminTexts.kt`, the session tips out of
          `SessionAdminTexts.kt`
    - [x] `database` (0.18.6): `Stores.kt` into the accounts' stores, the sessions' stores and the settings' store
    - [x] `server` (0.18.7): `SessionState.kt`'s model to `state/Model.kt` and its JSON accessors to `util/Json.kt`, the
          guards out of `LoginRoutes.kt` (`LoginGuards.kt`), the streams out of `PastRoutes.kt` (`Streams.kt`);
          `SingleSignOn.createLink` removed, the server's compiler warnings fixed
    - [x] the web app, its files (0.18.8): 26 files of one package as 45 in `app`, `ui`, `api`, `dashboard`, `manage`
          and `account`; `AdminSession.kt`, `Dashboard.kt`, `Components.kt`, `Settings.kt`, `Admin.kt` and `Watch.kt`
          split by part
    - [x] the web app, what repeats (0.18.11): one request helper for the 53 places that decode an answer themselves,
          the doubled components merged, the page frame and the clock shared, the interop opt-in for the module; the
          browser's own menu on a right click instead of the app's (asked for later)
    - [x] the audit's leftovers (0.18.14): `PersonDialog.kt` and `PlayerDialog.kt` with one dialog frame, the pills
          and panels of people of `AdminUsers.kt` and `People.kt` shared, the lines `server/` and `gateway/`
          `build.gradle.kts` repeat in one place, the two old `BasicTextField`s (the web app's last two warnings)
          moved to the state-based one
- [x] A game server that keeps crashing stops trying (asked for later, 0.18.9): it was started again every 30 s
      without end and kept its place (counted as in use); after 5 crashes in a row, each within a minute of its start,
      it stays down (*gave up*) with its last exit code, and *Start* tries again
- [x] No port pool (asked for later, 0.18.9): game clients come through the gateway's relay only, so each game server
      takes a free port of the gateway's container; how many run at once is a setting (*Game servers at once*, default
      8, empty: no limit), asked by the tracker and kept by the gateway too
- [x] Game servers that sleep (asked for later, 0.18.10): one without players for a while stops (a setting, default
      30 minutes; hosted slots don't count; Administrators can keep a session running), and starts again when a player
      connects, if there is room; at the gateway's start every session sleeps
- [x] A generation in the background, and cancelling it (asked for later, 0.18.12): creating a session waits for its
      whole generation in the page's request (up to `GENERATION_MINUTES`), so closing the page loses what comes after
      it (the session's setup, its start); the tracker runs it as a job of its own instead, the panel shows one that
      runs (after a reload too) and its end, and *Cancel* ends Generate.py on the gateway and frees the ID again
- [x] The generation's log under its bar (asked for later, 0.18.12): Generate.py's whole output, newest on top, a page
      at a time with a search; a failed one's to download, with the command that ran; the game server's log a page at a
      time with a search too
- [x] Tips that keep themselves open (asked for later, 0.18.12): one that came up under the pointer closed and opened
      again and again; a tip stays while the pointer is on it or its anchor. The APWorld pills' errors as wide as the
      window allows
- [x] The section as *Archipelago* (asked for later, 0.18.18): the supported versions as pills, the supported games
      one level higher with the custom games' chips among them, the upload, the removal and the search on their row
- [x] An APWorld's version in the library (asked for later, 0.18.16): each file as its game and its version (from its
      `archipelago.json`), its file name and size, and the oldest release it needs, in its tip
- [x] Tips that can be copied (asked for later, 0.18.15): a tip's text can be selected (and copied with Ctrl+C), and a
      long message (an APWorld's error on its pill) has a *Copy* button
- [x] An invited player's address seen only by Administrators (asked for later, 0.18.15): in a session an invited
      player was called by the part of their address before the `@` until they signed up, on the dashboard and in the
      panel; now the name suggested for them, or `Invited-` and their account's ID's start. A session's view in the
      panel listed every account with the invited ones' addresses to its Session-Managers too; now only to
      Administrators
- [x] Game servers only while they are needed (asked for later; noted here first, wrongly, as "the gateway on
      demand"): that is 0.18.10's sleep and start on connect. Generating doesn't use a game server (the gateway runs
      `Generate.py` itself), and the gateway with every session asleep needs some 35 MB. How it fits together, with two
      diagrams, in the README (0.18.13: *Sessions and their game servers*)
- Generation stays on one core (asked about): Archipelago's fill is single-threaded Python and has no option for more

## Phase 19: Generating, Keeping, Showing (done)

*A seed that the Launcher generates generates here too (asked for after 0.18.13: a session that the Launcher
generates didn't generate on the server, and an APWorld that loaded before was refused); then a session's state kept
in the database, and how the dashboard shows it. Renamed from "Generating Like the Launcher" with 0.19.15.*

- [x] Find out why first (closed with 0.19.7): the failed generation's log (its download, with the command, since 0.18.12) and the refused
      APWorld's message. What differs from a Launcher: no packages installed while it runs (and no compilers since
      0.18.4), an empty `host.yaml` (Archipelago's defaults, as an untouched Launcher's, but nothing that asks for a
      file: a ROM, EnemizerCLI), every uploaded APWorld loaded at once (also tools such as the Universal Tracker),
      release 0.6.8, and Linux instead of Windows: file names are case-sensitive here, so an APWorld that imports
      `.Items` for its `items.py` (or builds paths with `\`) works in the Launcher only
    - Found for Skyward Sword HD (after 0.19.4, from its log): the APWorld wants the extracted ROM on the generating
      machine; without it, it generates with fallbacks, and its fallback item pool has one *Progressive Loftwing*
      where the logic needs two for *Spiral Charge*, so four locations can't be reached (`FillError` with
      `accessibility: full`). A bug of the APWorld (one line: `"Progressive Loftwing": 2` in its fallback
      `POOL_ITEM_COUNTS`); until it is fixed, `start_inventory: {Progressive Loftwing: 2}` in the YAML generates here.
      Reproduced with the gateway's image, both ways tried
    - The refused APWorld: not a fault here. Its version had been changed by hand (a `-patched` at its end)
    - Found on the way (0.19.7): a playthrough Archipelago can't reduce left a session without its later spoiler
      levels; they run through `archipelago/spoiler_run.py` now, which falls back to the unreduced spheres
- [x] An APWorld's Python packages installed on its upload: not built, not needed (closed with 0.19.7; no refused
      APWorld wanted a package, and the Launcher installs none for a zipped APWorld either). As it was planned
      (decided: not while it runs): an Administrator's upload
      names the packages it wants, and after a confirmation the gateway installs them as ready-made wheels only
      (`--only-binary=:all:`, no build code run) into a folder of its own per release under `data/`, which
      `Generate.py` and MultiServer find; removing the APWorld removes what only it needed. Checked (0.19.1): Archipelago
      0.6.8 reads a `requirements.txt` only from a world folder under `worlds/`, never from a zipped APWorld, so the
      Launcher installs nothing for one either (such APWorlds bring their packages inside); needed only if a refused
      APWorld turns out to want a package
- [x] Back to the generation's bar (asked for, 0.19.1): *Generate and start* is at the form's end; the form shrinks
      to the bar, which left the page far below it. The page goes back up to it
- [x] It isn't stuck (asked for, 0.19.2): under the generation's bar how long it runs (each second) and what it does
      (*working* with Generate.py's share of a core, *waiting for its turn*, *no response* after a minute without
      CPU); the playthrough, not the fill, is named the longest part, and the bar's shares fit that
- [x] The spoiler log in stages, for every generation (decided, 0.19.3): the seed with a spoiler without playthrough
      (level 1) first, so the session starts; then in the background `--spoiler_only` with the same `--seed` and
      inputs at level 2 (the playthrough: the spoiler page, the hints and the cheats wait for it) and level 3 (the
      paths: the full log). Tried (0.19.2, the demo's 1,934 locations): the same seed name and every placement the
      same at all three levels. Each later spoiler replaces the earlier one only when its placements match the
      seed's (an APWorld with an unseeded random source would differ); race mode can't (it reseeds at random). The
      inputs kept with the session (its YAMLs, the seed number, the APWorlds it was generated with), so a restart of
      the gateway goes on. A pill with a working symbol on the session's top panel for everyone while it completes;
      its state and the run's log in the Management panel for Session-Managers, Administrators and Support
- [x] Generations at once (decided, 0.19.4): as many as the gateway has cores less one (the server has 4: three;
      `GENERATIONS_AT_ONCE`). A new session waits only for a level-2 run; a level-3 run is paused for it (`SIGSTOP`,
      `SIGCONT` once there is room again; ended and started over if pausing doesn't hold). The runs share the
      release's `custom_worlds/` as it is while they run (not a set of its own each: Archipelago reads it from its own
      directory); a change of the library waits for them. Memory: each needs its own (a big one gigabytes; a paused one
      keeps its), a killed one says "probably out of memory"
- [x] No *Spoiler log* button and no *Cheats* section before the spoiler log has its playthrough (asked for, 0.19.4)
- [x] Generator settings per session (decided: chosen settings, no raw `host.yaml`; 0.19.5): in the new session's
      form the plando modules the YAMLs may use, no progression balancing, and race mode, passed as `Generate.py`'s
      arguments (`--plando` with the known names only, `--skip_prog_balancing`, `--race --spoiler 0`); file and
      program paths never come from the form. A seed's settings are kept for its spoiler log's later levels; a race
      has no spoiler log and no later levels. The spoiler level isn't one of them (decided: every generation goes
      through all three, see above)
- ROMs for the worlds that need them: undecided, thought about later. No ROM is uploaded to the server (said after
      0.19.4; the earlier plan to keep them encrypted in the database is dropped)
- [x] A session from a seed generated elsewhere (asked for later; 0.19.8, Administrators only, its files for the
      players kept as they are: both decided): instead of the YAMLs, the `AP_<seed>.zip` that
      Archipelago's Launcher (or `Generate.py`) made, uploaded in the new session's form. Checked before anything of it
      runs, since a zip and the `.archipelago` in it come from outside: its size and file names (no paths out of it),
      only the files a seed has, the multidata read without running it (it is a pickle: only Archipelago's own
      classes allowed, as MultiServer does), its release against the gateway's, its games against the release's
      APWorlds; then its slots for the players and locks as with YAMLs (locks need the World Locks slot generated
      in, so a seed from elsewhere has none)
- [x] What the tracker misses while it isn't connected (asked for; 0.19.9, as proposed: the gateway listens and
      writes into the database, the tracker reads it there). As it was: it hears of joins, releases, goals and chat only
      from the messages that come while its connection to the game server is up. Seen: a slot released early wasn't
      in the live feed, and the player whose connection wakes a sleeping session isn't shown as online (the game
      server is up and takes the player in about two seconds before the tracker is back). Proposed, to be decided:
      the gateway, which starts every game server and is there whenever one runs, keeps the connection that listens
      and writes what happens into the database; the tracker and the dashboard read it from there (so nothing is lost
      over a tracker restart or a wake-up either). Smaller, and only narrowing the gap: the gateway tells the tracker
      the moment a game server is up and lets no player in before the tracker is connected
- [x] A session's state in the database (0.19.10, from the planning of the state panels): a snapshot of what its
      dashboard shows, kept when the tracker's connection ends (the dashboard isn't empty while the game server
      sleeps), the history replayed from the events instead of a file, finished sessions' archives in the database
- [x] The dashboard's states and tiles (0.19.11, planned with the user): a slot's states in a new order (released
      before or by hand after its goal > complete > goal > locked > online > idle > not started; the automatic
      release at the goal counts as complete; "playing" becomes "online"), the times beside them (since complete,
      released, goal; the last check), the Overview's tiles (Finished with its parts, Slots with online, Items, Links,
      the Session's age) and "x of y checks done" in the top card
- [x] The slot / player panels themselves (asked for; the cards stay, reworked step by step, each step its own PR so
      it can be taken back): 0.19.12 checks and progression as two bars; 0.19.13 progression checks as the second
      bar, received and sent as pills, the person header as pills; 0.19.14 nothing cut off, icons on the bars; 0.19.15
      received and sent without totals (the players aren't to learn how much is missing), slots and done in one pill,
      icons and pills in the Overview; 0.19.16 the Overview's pills as icons with a one-word tooltip, the game
      server's uptime said as such; then 0.19.17 to 0.19.19 as items of their own (below). Closed with the phase; the
      panels as floating windows are Phase 21
- [x] Available checks per slot and person (asked for after 0.19.15; 0.19.17): a pill with how many of a slot's
      locations can be checked now, by its world's logic with the items it has (as a Universal Tracker shows them),
      summed on the person card. The logic exists only in a generated multiworld (the seed's `.archipelago` has the
      placements, not the rules): a logic process generates the seed again from its kept inputs (as the spoiler levels
      do), but only up to the fill (the worlds, their rules, events and locked items; where the items lie comes from
      the seed), then answers. Measured on the server (10 slots, 2,868 locations, one core): 2.6 s and 195 MB up to the
      fill against 15.7 s for the whole generation; counting every slot 0.27 s. Decided with the user:
    - It runs while the session is played and stays twice as long as the game server's sleep setting; then it ends
      (no CRIU: tried, it works, but needs rights close to root; rebuilding is fast enough)
    - Its state in the top panel like the game server's (starting, running, stopped), with a note that the available
      checks aren't known again until it runs completely
    - The last counts kept in the database and shown while it doesn't run (as the last known ones)
    - A button to build it again (Session-Manager and up), and its log (pages, search; Administrators only)
    - The user's condition for more rights (a container of its own) doesn't apply: it needs none
    - Not for: seeds uploaded from elsewhere (no YAMLs), race mode, sessions generated before 0.19.3; a slot whose
      locations or events differ from the seed's gets no pill
- [x] Deaths and traps on the slots too (asked for after 0.19.17; 0.19.18, with icons on the players' pills): as the Overview's *Links* tile counts them for the
      session, each slot and person its own, by what it sent (the deaths it shared by DeathLink, the traps it sent by
      TrapLink), not by what it received
- [x] Cheatable locks (asked for after 0.19.18; 0.19.19): two cheats for a locked slot in the *Cheats* section.
      *Check its key's location*: the location that holds its key is checked as if its player found it (the key goes to
      the World Locks slot, the slot opens, the check counts for the world it is in); with the spoiler log's
      placements. *Unlock*: its key is given to the World Locks slot (`/send`), no location checked. Logged and in the
      live feed like every cheat, for whoever may cheat (Session-Managers, Administrators, support)

## Phase 20: Hosted Minecraft Dig (done)

Decided on 2026-11-19: Randogeddon hosts the Minecraft server a *Minecraft Dig* slot needs, and its players reach it
through Randogeddon Bridge only.

- Known (the mod's README and its versions file, APWorld 0.0.11): Minecraft 1.19.4, Forge 1.19.4-45.3.15, Java 17,
  the mod `aprandomizer-MC1.19.4-0.0.11.jar` (on the server only, never in the players' clients); one server per
  patch file (`.apmcdig`, into the server's `APData`), and several players can join the same slot's server; in the
  game a player connects it to Archipelago with `/connect <address> <port> <password>` and begins with `/start`;
  the players need Minecraft Java 1.19.4
- [x] Find out first: whether the mod connects to the session's MultiServer by itself when both run on the server
      (the `archipelago.json` the APWorld's client extracts for an auto-connect), so no player types `/connect` or
      sees a password; how much memory a server needs, and how much the live machine has to spare
    - Found (0.20.1, from the mod jar 0.0.11 and the APWorld's client on its `apworld_dig` branch): the mod connects
      by itself. When the server starts, it reads `APData/archipelago.json` ("Found server address in
      archipelago.json") and connects there with the slot name from the patch (`player_name`), trying again up to 5
      times when the connection drops; nobody types `/connect`. It sends no password then: only `/connect` takes
      one. Randogeddon's slot passwords are the gateway's (MultiServer itself has none), so the hosted server reaches
      the session's game server inside the compose network, on a way the gateway opens for that slot's container
      only (to decide in the container's step)
    - The server's files, as the APWorld's client writes them: the patch (a zip with `data.json`) as
      `APData/<name>.apmc`, and `APData/archipelago.json` (here: written by Randogeddon with the session's internal
      address). One world per seed: a world used for another seed is refused, so each slot gets a fresh one. Started
      as the client does: `java -Xmx<heap> @<forge's unix_args.txt> -nogui`, `eula.txt` set once (the setting);
      ready at the "Done (" line of `logs/latest.log`
    - Who may join: the mod has no whitelist of its own; Minecraft's own (`whitelist.json`, `white-list=true`) and
      `online-mode=true` (Mojang's account check) apply as on any server
    - Memory: neither the mod nor its client recommends a heap. Measured (0.20.1, WSL; Forge 1.19.4-45.3.15, Java 17,
      the mod 0.0.11, a generated seed's Minecraft Dig slot set up as the client does): the server is ready 12 s after
      its start (its world made), and holds 1.4 to 1.5 GB a while after, nobody on it, with `-Xmx` 3 G, 2 G or
      1.5 G alike. Players exploring load more of the world: `-Xmx2G` and a container limit of about 2.5 GB per
      server. The live machine: 8 GB, about 6.9 GB available next to the tracker, the gateway and the database, so
      one or two at a time (a setting, as the game servers at once)
    - Tried with it: the mod read the slot's patch ("Loaded APMC data with client_version: 16") and the address from
      `APData/archipelago.json` ("Found initial server address from archipelago.json") and tried to connect there
- [x] A container per Minecraft Dig slot, started by the gateway (not inside the gateway: a Java server with a
      third-party mod, with memory of its own), from an image that has Java, Forge and the mod; the slot's `.apmcdig`
      from the seed goes into it. The operator accepts Mojang's EULA once (a setting), or nothing is hosted
    - Decided with the user (after 0.20.1): a limit of 3 GB per container, `-Xmx2G` (the JVM needs room beyond its
      heap; a world full of dropped items needs more than the measured 1.5 GB). Its world kept on disk while it
      sleeps; a fresh one for each seed. No `archipelago.json` (the mod would connect without a password first)
    - Done (0.20.2): one container, the `minecraft` service, runs the servers as its processes instead of a container
      each (the gateway would need Docker's socket for that, which is root on the machine): the gateway's image with
      Java 17, Forge 1.19.4-45.3.15 and the mod (both checked by their SHA-256), `minecraft_host.py` as its program,
      as user 1000 without capabilities. The gateway tells it what to run (its API on 8002, the internal token): the
      slot's `data.json` from the seed's `.apmcdig` as `APData/<slot>.apmc`, `eula.txt`, `server.properties`
      (`online-mode=true`), a fresh world when the seed changes; at most `MINECRAFT_SERVERS` at once (1 by default);
      a server above `MINECRAFT_LIMIT_MB` (3072) is ended. Its world is in `data/minecraft/<key>`
    - Tried with it (WSL, the demo's group b with a Minecraft Dig slot): started through the gateway, ready and
      connected to the session's game server with its password, items received, 1.46 GB, stopped and saved
- [x] Started when needed, also for a hosted slot: it sleeps without players like a game server and starts when a
      bridge opens its tunnel; the bridge's page says "starting" meanwhile (a Forge server takes a minute or more)
    - Done (0.20.3): as decided below. Tried with a client speaking 1.19.4's protocol through the gateway (WSL, the
      demo): the server list's look answered while asleep (its pong too), a join held with a login plugin request
      every 10 s and started after 15 s, then the server's own encryption request. Whether a real Minecraft client
      keeps waiting that long is to be seen with one (the protocol says it does: any packet keeps it)
    - Decided with the user (after 0.20.1): it starts when a player joins, not when a client only looks at it. The
      gateway reads only the first packet of a Minecraft connection: a server list ping (a client in its menu) it
      answers itself ("asleep, join to wake it", "starting…") and starts nothing; a join (handshake and login start)
      starts the container, and the gateway holds the connection (the client says "Logging in…"), keeping it from
      timing out with a login plugin request every ~10 s whose answers it drops (to be tried with a real 1.19.4
      client), until the server is ready ("Done (" in its log). Then it sends the held packets on and only passes
      bytes from there: Mojang's account check and the encryption are the server's, end to end. A start that fails
      or takes too long ends the join with a message the client shows
    - [x] It sleeps when no player has been on it for as long as the game servers wait (the same setting) (0.20.2)
    - [x] It connects to Archipelago when its first player joins, not at its start: the gateway types
      `/connect <address> <port> "<password>"` into its console (the password in double quotes), through the gateway
      like any client (so the session's game server wakes for it); its connection ends when it sleeps (0.20.2: to
      a relay of the `minecraft` service on 127.0.0.1, which adds the session's path, as `/connect` takes no path)
- [x] The tunnel: the bridge opens a port on 127.0.0.1 for the slot, and passes the Minecraft connection on in a
      `wss://` connection to `<session-id>-dig.<domain>` (Traefik, 443), which the gateway passes on to the slot's
      container. Logged in with the player's key as for a relay, so only the slot's players reach the server; no
      Minecraft port is open to the internet. A relay of its own kind (binary, uncompressed, its own limits)
    - Done (0.20.3): on the session's own address instead of a host of its own, `…/minecraft/<slot>` (Traefik's
      router for the sessions needs nothing new), with the slot's password in `X-Randogeddon-Slot-Password` as the
      bridge has it for a relay (from the player's key), checked in constant time; a locked slot is refused. Each
      Minecraft connection its own WebSocket, binary messages; before the server takes over, a packet may have 4 KB
- [x] Who may join: Mojang's account check stays on; a whitelist from the session's players if the mod allows it (the
      server sees every connection coming from the gateway, so limits and bans by address mean nothing)
    - Decided with the user (0.20.4): no whitelist. Only the slot's players reach the server at all (the bridge
      with their key and the slot's password; no Minecraft port is public), and their Minecraft names aren't the
      slot names a whitelist could come from
- [x] Hosted here only when an Administrator says so (asked for on 2026-11-21, moved from Phase 21)
    - Done (0.20.5): a hosted Minecraft Dig slot's server is run here only with its switch (`session_hosted_slots.direct`,
      V26): on by Administrators only, when creating the session or in the panel; off by Session-Managers too (its
      server stops). The tunnel logs in with a key made from the slot's password, which the tracker gives the
      session's players and its Session-Managers (the players never get the password); the gateway checks it
      without anything stored
- [x] The management panel: the Minecraft server's state and log beside the game server's, start and stop by hand
    - Done (0.20.4): its own section, *Minecraft*, after *Logic* (a session without a Minecraft Dig slot doesn't
      show it): each slot's server, its state and who is on it, *Start* and *Stop* for Session-Managers and
      Administrators, its log for Administrators
    - Mojang's EULA: accepted by the operator, not in the panel (decided with the user): `MINECRAFT_EULA=true` in
      `.env` accepts it for every hosted server, false by default; the Administrators' setting of 0.20.2 is gone

## Phase 21: Floating Panels – Slot & Player (done)

*Asked for when Phase 19 closed: the slot and player panels as floating panels. What they float over, how they
open, move and close, and what they show is to come from the user.*

- [x] Floating panels for a slot and for a player
    - Done as the user meant it (0.21.8): the slot's and the player's panels as they are, lighter (their lists a page at
      a time, a slot's available checks first)
- [x] The players' panels use the empty space: not stretched to the row's width, laid out flexibly (as many side by
      side as fit), and sortable in some way (asked for on 2026-11-21)
    - Done (0.21.6): as many columns as fit (at least 330 dp each), each card into the shortest one so far, so they
      fill the space without rows' gaps; the columns don't widen for few cards; *Sort*'s order reads left to right
- [x] The hosted Minecraft servers in an image of their own (asked for on 2026-11-21, done in 0.21.6): Java 17, Forge
      with the mod and `minecraft_host.py` on Python's standard library, nothing of Archipelago; the gateway's image
      without Java and Forge
- [x] The site grows with the window: on 1440p or 2160p screens most of it is empty at the sides now (asked for on
      2026-11-21)
    - Done (0.21.5): no fixed width any more (1,400 dp before): the page takes the window less 2 % at each side; the
      feed grows with it (a quarter, 440 to 640 dp); the tiles and the players' cards fill as many columns as fit
      (2560 px: five cards side by side, 3840 px: six), the Management panel is as wide
- [x] Creating a session (asked for on 2026-11-21):
    - [x] A hosted game can still be given to certain players
    - [x] For every slot, a checkbox: open to all of the session's players
    - Done (0.21.2), decided with the user: *Open to all* means every player of the session (everyone named for any
      of its slots); a slot that isn't hosted is then played by each of them (its password, files, the bridge), on a
      card of its own, *Everyone*; a hosted one is theirs to join as before (its files, the tunnel; never its
      password). A hosted slot starts open; unticked, it goes to the players named for it. `session_open_slots`
      (V27), every slot hosted before copied in as open
    - [x] A hosted Minecraft Dig slot: an option, for Administrators only, to host its server directly (through the
      bridge's tunnel) (moved to Phase 20: 0.20.5)
- [x] A running session's slots edited (asked for on 2026-11-21):
    - [x] Who may use a slot changed; its hosted mode not (for now)
    - [x] Asked for on 2026-11-21, done in 0.21.7: one slot at a time (*Edit slot:* and a drop-down as the heading),
      its row as before with *Hosted* at the right; switched on asked once more, off twice (a directly hosted
      Minecraft server stops)
- [x] A hosted Minecraft server stops with its session's finish (or pause), and its idle time survives a gateway
      restart (asked for on 2026-11-21, done in 0.21.7)
- [x] The lock cheats reworked (asked for on 2026-11-21, done in 0.21.7): *Unlock* checks the key's location in the
      world that holds it (the lock says the server unlocked it); the other one hints that location
- [x] The slot's and the player's panels lighter, their lists a page at a time; *Your connection*'s fields as
      text fields that can't be changed, the server copied four ways, the bridge's *Create key* on its own row and
      its fold an icon (asked for on 2026-11-21, done in 0.21.8)
    - [x] A hosted Minecraft Dig slot's direct hosting switched on by Administrators only; switched off by
      Session-Managers too (moved to Phase 20: 0.20.5, in the panel's *Minecraft* section)
    - [x] Late invites, then
    - Done (0.21.3), decided with the user: the panel's *Slots* (Session-Managers, Administrators): each slot's
      players changed, *Open to all* switched, someone new invited by e-mail there. Whoever loses a slot loses its
      connection: a new password, which the gateway reads at once while it closes the slot's connections (the
      players connect again with the new one); a directly hosted Minecraft server connects again by itself. Not for
      a hosted slot whose server runs elsewhere (its players never had the password)
- [x] The Management panel's logs in tabs, one log panel instead of several (the game server's, the logic's, each
      Minecraft server's, …); nobody sees a tab of a log they may not read (asked for on 2026-11-21)
    - Done (0.21.1): *Logs* at the panel's bottom, a tab per log: the game server's (always, first), the spoiler log's
      run, the logic's and each directly hosted Minecraft server's (Administrators). A section hands its log over only
      while its viewer may read it, so no other tab exists; on the support page the spoiler log's run keeps its own
- [x] The README shorter and more compact (asked for on 2026-11-21; it has grown with every phase)
    - Done (0.21.3): down to the essential points (1,168 lines to 137, as the user asked): what it is, a session,
      the roles, the server, the bridge, development, each with a link to its details. The details as they were in
      `.docs/FEATURES.md`, `.docs/OPERATIONS.md` and `.docs/DEVELOPMENT.md`
- [x] Icons: *sent* gets the icon *released* has now; *released* a new one that fits better (unchained, an open door,
      …) (asked for on 2026-11-21)
    - [x] And a new icon for the available checks (asked for on 2026-11-21)
    - Done (0.21.4), picked by the user from candidates: *sent* the arrow up out of an open box, *released* a broken
      chain, the available checks a signpost

## Phase 22: Maps (done)

*Asked for on 2026-11-21: maps of the slots' games with markers for their checks. Done with 0.22.1 to 0.22.4; the
games' real map images moved to Later.*

- [x] Maps with check markers for the slots (0.22.4)
    - Decided with the user: generated for every game, no game images (maybe later), in the slot panel's *Map* tab:
      its regions from the logic, a location's place by its region, the ones without a useful region grouped by
      their names; checked, open and available, the name on hover, filters by state. Started with Luigi's
      Mansion and Pikmin
- [x] The README notes every tool and piece of software Randogeddon uses (asked for on 2026-11-21): *Built with*
- [x] `.docs/` down to the essentials, shorter, and restructured where that helps (asked for on 2026-11-21)
    - The finished phases moved unchanged into `DONE.md`; the rest rewritten as compact references (from about
      2,500 lines to 530); `MEASUREMENTS.md` only the latest numbers
- [x] *Your connection*: copy, show and hide as icons; the bridge's fold arrow left to collapse, right to expand
      (0.22.1)
- [x] Creating a session: its ID's field takes only what a subdomain allows, capitals as small letters (0.22.1)
- [x] After its generation, the panel goes to the new session's page, not back to *New session* (0.22.1)
- [x] *State and Control* on a phone: the actions wrap, the dashboard's and spoiler log's buttons below, nothing cut
      off (0.22.2)
- [x] The session ID's field: a space becomes `-` as it's typed, nothing before the first letter, a `-` at the end left
      off the ID (0.22.2)
- [x] *Your connection*: the server and the patch files' names on one line, cut in the middle, the whole on hover
      (0.22.2)
- [x] Randogeddon Bridge's page: the address copied the same ways as the server on the dashboard (0.22.2)
- [x] The Overview's *Slots* tile counts locked slots as not started (0.22.3)
- [x] A slot's panel in tabs: *Checks* (available, remaining), *Map*, *Transported items* (sent, received),
      *Connections* (0.22.3; the map 0.22.4)

## Phase 23: Full Code Review & Security Check (in progress)

*Asked for on 2026-11-21, started on 2026-11-22. What's open: [TODO.md](TODO.md).*

- [x] The map and the available checks failed on live for a session with a game whose regions aren't named with
      text (reported by the user on 2026-11-22: "the map does not work"): fixed, and a map that fails no longer
      takes the available checks with it (0.22.4.1)
- [x] `scripts/deploy.sh --switch`: the server switched to a version's images as published, nothing built (asked
      for on 2026-11-22, after 0.22.4 went live by a rebuild of what was in the registry already) (0.22.4.1)
- [x] 0.23.1: the tracker (logins, roles, the API, its limits, share links, bridge keys, mails, the CLI, the session
      state). Scanned: no high finding, 11 medium, 41 low (52 in all, some found by both scanners)
    - Decided by the user (2026-11-22), each a change of behaviour:
        - Whoever loses their last slot in a session loses the open-to-all slots' passwords too (renewed); timing
          out, suspending or deleting an account renews the passwords of the slots only they played
        - Forwarded headers are believed only from configured proxies (a new setting, `TRUSTED_PROXIES`; unset: as
          before)
        - No e-mail addresses in the tracker's log: accounts by ID or name
        - Support can no longer restart a stopped spoiler-log run (Session-Managers and Administrators can); the
          panel never offered it to support, only the route did
        - A Session-Manager's invitation whose mail fails leaves no half-made account: the retry works
        - The new-session routes (overview, ID check, YAML inspection) paced per user
        - Administrators only through Authentik ("never allow password logins"): an Administrator by password exists
          only with an opt-in setting, which the demo and local testing set; without Authentik and without it,
          nobody is an Administrator and `admins --create` refuses. Where it's on: granting the flag or resetting
          two-factor ends the account's other logins
    - The findings (F: the review's, O: the security scan's), all fixed and tested unless said below:
        - Security, medium:
            - [x] F1/O2: the 96 MB seed upload is read before the Administrator check (any login can exhaust memory)
            - [x] F2/O4: the mail limit counts by name: a rename resets it
            - [x] F3/O5: e-mail addresses can be probed for accounts without limit (invite, address change)
            - [x] F4/O3: a share link's streams aren't counted (no cap as logged-in ones have)
            - [x] O1: a login that never passed a second factor can become an Administrator's (see the decision above)
            - [x] F7: an open slot keeps its password when a player leaves the session; a kept-out account's too
        - Correctness, medium:
            - [x] F5: one malformed session event stops a session's tracker for good
            - [x] F6: a failed database read wipes a session's setup, finish state and time until the next read
        - Security, low:
            - [x] F8/O9: three Administrator-only checks ask the account, not the login (logic and Minecraft logs,
                  direct hosting)
            - [x] F9: a session stream keeps the Administrator flag from when it opened
            - [x] F10/O10: the gateway's error text (its internal address) reaches players and watchers
            - [x] F17: the provider's webhook resets two-factor of accounts no longer Administrators
            - [x] F18: OIDC hardening (the discovery's issuer compared, key reloads paced, visibility of the keys)
            - [x] F19: two-factor codes on the account routes aren't counted per account
            - [x] F20/O7: the current password can be guessed without limit on the password change
            - [x] O8: wrong passwords counted by what was typed (name and address count apart)
            - [x] F21/O12: a console line can carry line breaks (forged log lines, extra commands)
            - [x] F22/O6: `X-Forwarded-For` is believed from any peer (see the decision above)
            - [x] F23/O12: e-mail addresses in the log (see the decision above)
            - [x] F24/O11: the address pattern lets `<`, `>` and control characters through; a provider's address is
                  stored unchecked
            - [x] O13: support can restart the spoiler run (see the decision above)
            - [x] O14: the new-session routes are costly and unpaced (see the decision above)
        - Correctness, low:
            - [x] F11: two error wrappers miss each other's exceptions (500 instead of 404 on some routes)
            - [x] F12: two guards may answer the same call
            - [x] F13: another user's failed create removes a running creation's stage
            - [x] F14: two creates of the same ID at once can both pass the check
            - [x] F15: a sign-up that loses the name race burns its invitation
            - [x] F16: a Session-Manager's failed invitation blocks the retry (see the decision above)
            - [x] F25: smaller ones (a leaked socket on a failed TLS handshake, `@Volatile` on the tracker's last
                  error, the refresh skipped when a disconnect fails, no keepalive on the manage stream, the CLI
                  printing a refusal object, one account's failure ending the cleanup's pass, two managers removing
                  each other at once)
        - Optimisation:
            - [x] F26: an HTTP client per session that is never closed
            - [x] F27: seven database queries per session every 10 s, finished ones too; `room.json` read again and
                  again
            - [x] F28: the summary's hot path (`lockSlots`, the progression checks, `total`, `people()`)
            - [x] F29: the spoiler log parsed again per request
            - [x] F30: smaller ones (every account's activity loaded for one, Argon2 on the request thread, the
                  bridge's directory listed per request, the limits' map)
        - Duplication:
            - [x] F31: hex encoding and `SecureRandom` in several places
            - [x] F32: the session lookup, nine times
            - [x] F33: `GameServerClient`'s answer handling, sixteen times
            - [x] F34: route helpers (the user ID, `?since=`, the attachment header, the webhook's token check)
            - [x] F35: session predicates (still locked slots, "manages", the pick list of users)
            - [x] F36: inside single files (the cheats' carrying out, the feed's append, the OIDC key retry, the
                  login-check loop)
            - [x] F37: four hand-made expiring maps: one `ExpiringMap` (0.23.2.9)
            - [x] F38: misplaced KDoc
    - Not as the finding said, or only in part:
        - F6: the by-name match stays for seeds from before Phase 4 (a setup without options and members), since
          every session has a setup row; a session made in the panel is matched by its members only
        - F22: only the client address follows `TRUSTED_PROXIES` (see TODO, left from 0.23.1)
        - F27: finished sessions aren't skipped in the refresh: batched, they cost nothing more, and their roles
          stay current
        - F30: Argon2 stays where it is in the CLI (it has no request thread)
        - O1: as the user decided (the opt-in setting), not a second factor per login
- [x] The top panel's status pills say what they are: `<icon> Game server: <state>`, `<icon> Logic: <state>` (asked
      for on 2026-11-22) (0.23.1)
- [x] The check maps are cut off to the right: scrollable (reported by the user on 2026-11-22): a bar along the
      map's bottom, and the map dragged with the mouse (0.23.1.1)
- [x] The share links' streams per address set by Administrators like the other settings (asked for on
      2026-11-22) (0.23.1.1)
- [x] `GET /api/me` says `administrator` by whether this login may act as one, not by the account's flag (left from
      0.23.1; decided by the user on 2026-11-22: "WebApp and BE are ours") (0.23.1.1)
- [x] The map's lines (asked for on 2026-11-22): they don't cross boxes or each other, run straight with tight
      turns, and a box with several targets sends one line that branches off to them (0.23.1.1)
    - Done as a tree from the start; a box's other connections have no line and are marked on hover
- [x] 0.23.1 checked end to end (2026-11-22): its published images started and logged the demo's admin in before it
      went live; the demo ran on it with 0.23.1.1 (both sessions created and played, every slot's map, no errors)
    - Found by it: the demo's three logins met the login limit, which counts by address since 0.23.1 (by host
      name before: in Docker a container's own name); the demo's admin keeps its login now (0.23.1.1)
- [x] 0.23.2: the gateway and its tunnel, the findings fixed without the user (0.23.2; nine more wait for the
      user's decision, see TODO):
        - Security:
            - [x] G1 (high): MultiServer's output kept at any line length; any packet passed on before a `Connect`
            - [x] G2 (high, the size part) / S7: chat lines and link events kept whole (1 MiB each) in memory, the
                  log and the database
            - [x] G3/S3 (high): before its `Connect` a frame may hold tens of thousands of `GetDataPackage`
                  packets, each answered with megabytes; the wake waits outside the limit
            - [x] G13: taking someone off a slot can miss their connection
            - [x] G14/S10: the slot's password compared with `!=`
            - [x] G15: the slot locks fail open at the first read
            - [x] G16: a release's name becomes a path without being an installed release
            - [x] G17: parts of a seed zip and of an APWorld read whole into memory
            - [x] G25: names and console lines logged as they came
            - [x] S12: every anonymous `Connect` costs a file read (and a database query without passwords)
        - Correctness:
            - [x] G4: ending a logic while it is built blocks a build place and the APWorld library for 30 minutes
            - [x] G5: a killed game server, or one whose process can't start, leaves its state behind
            - [x] G6: deleting a session deletes the Minecraft worlds of other sessions whose ID starts the same
            - [x] G7: after one late answer of the logic, every later answer belongs to the question before
            - [x] G12: the game servers' `stop` events get lost when the gateway stops or kills one
            - [x] G19: sleep and wake look at the state before they take the lock
            - [x] G20: the tunnel's and the minecraft service's errors aren't caught where they are expected
            - [x] G21: a deleted session leaves its generation running and its logs to the next of the same ID
            - [x] G22: two generations for one session at once; a cancelled one that still waits
            - [x] G23: a cut-off seed upload answers 500
            - [x] G24 (the rest): a process without a time limit, what a kill leaves
            - [x] G26: the game servers end one after the other when the gateway stops
            - [x] G27: a client's own fault logged as "MultiServer not reachable"
        - Optimisation and duplication:
            - [x] G28: the relay decodes and encodes every frame twice
            - [x] G29: six places start a Python process, each its own way
            - [x] G30: the whole APWorld library copied at every first generation
            - [x] G31: an uploaded APWorld unpacked up to four times
            - [x] G32, G33: `stages.json` and `room.json` read again and again
            - [x] G34, G35: small things twice, smaller needless work
    - How: three fixers at once, each in its own files (the public side, the processes, the files and the internal
      API), then one that connected what they left for each other's files
    - Chosen by the fixers: 32 packets before a `Connect`; 2,000 characters a log line, 1,000 a kept text; a
      spoiler log of 64 MB and an APWorld of 1 GB unpacked at most
- [x] 0.23.2.1: the gateway's findings that waited for the user, built as decided on 2026-11-22 (asked one by one):
    - [x] S1/G8 (high): Archipelago's Python as a user of its own in the gateway's image, so APWorld code can't
          read the gateway's secrets
    - [x] S2/G10 (high): the minecraft service gets a token of its own (`MINECRAFT_TOKEN`) that only its own
          routes accept, and only `data/minecraft/` mounted
    - [x] G9/S6: a room password per game server that only the gateway and the tracker know (the user chose it
          over listening on localhost only)
    - [x] G11/S4/S5: at most 32 connections that haven't joined yet per address (an IPv6 network as one); a
          sleeping game server is woken only by a `Connect` with a slot's right password
    - [x] G18/S8: the gateway's database role may insert only the kinds of events it writes itself (a migration)
    - [x] S11: `!admin` removed: no server password is passed to a game server any more (`AP_ADMIN_PASSWORD`
          goes); remote commands only through the panel's console
    - [x] G24: `init: true` for the gateway's and the minecraft service's containers
    - [x] Game servers and generations marked for the kernel, so that one of them is ended when the gateway's
          container runs out of memory, not the gateway with every session
    - Decided to stay as they are (the user, 2026-11-22):
        - G2 (the rate part): no cap on how many chat lines and link events are kept (the size cut is enough)
        - S9: refusals keep Archipelago's answers (`InvalidSlot`, `InvalidPassword`): clients show them to players
    - Checked end to end in the demo (2026-11-22), with 0.23.2: the processes' users, capabilities and out-of-memory
      scores; the gateway's environment closed to Archipelago's user; the simulated players through the new rules
      before a `Connect`; the tracker with the room password; a direct connection to a game server refused; a
      sleeping session answering from what was kept and waking only for a right password (2 s)
    - Found by the image's build: a test took a killed process that nobody collected (a zombie, where no init runs)
      for a running one; it passed outside the image
- [x] The map sized up by a button: over the whole window, then to full screen (asked for on 2026-11-22)
      (0.23.2.2)
- [x] The live feed pinned (asked for on 2026-11-22): a pin on the feed keeps it on every view (Management panel,
      spoiler log, account), there as a floating window that can be moved and folded (the user's choice) (0.23.2.3)
- [x] Every copy says that it copied (asked for on 2026-11-22: users were confused without): the dashboard's copy
      buttons and menus, the panel's, the bridge's page (0.23.2.3)
- [x] The spoiler page and the hints without waiting for the playthrough (asked for on 2026-11-22, the user's
      frame): with level 1 the page shows the whole log (Archipelago's level 1 has no spheres). Once level 2
      finished: a tab for the playthrough as it was (tab 1 everything, tab 2 the playthrough, opened first); level
      3: another tab for the paths. The hint's *next* is offered only with a playthrough, otherwise only by chance;
      hints and cheats don't wait for level 2 any more. Levels 2 and 3 run in the background as before (0.23.2.4)
- [x] Lighter builds (asked for on 2026-11-22: the demo's builds were stopped for low memory, the IDE can't be
      closed while a session runs; the user chose): native-image's heap capped (6 GB), the demo's builds quick,
      and a demo on published images with only the web app built (`DEMO_IMAGES`) (0.23.2.5)
- [x] The map marks what is blocked (asked for on 2026-11-22): a region the slot's logic can't reach now (and the
      line into it) is shown as blocked, e.g. an area without checks that opens with an item still to come. Not
      by whom. For sure: from the logic run's own state (the regions it reaches with the items received), with the
      available checks (0.23.2.6)
- [x] The map more compact (asked for on 2026-11-22: "currently it's crazy spread out") (0.23.2.6)
- [x] The sent and received pills count all items (asked for on 2026-11-22: they showed progression only and
      didn't match the slot's lists) (0.23.2.6)
- [x] The pinned live feed moves on the dashboard too (asked for on 2026-11-22: "I cannot move it around in
      dashboard"): pinned, it is the floating window on every view; where it was moved to is remembered (0.23.2.7)
- [x] The spoiler page's full log and paths as views like the playthrough's (asked for on 2026-11-22) instead of
      plain text; the text keeps a tab of its own (0.23.2.8)
- [x] What the reviews of the tracker and the gateway and the steps of 2026-11-22 left (0.23.2.9; the user on
      2026-11-23: "let's continue fixing/checking every point in TODO"):
    - [x] The map's tree prefers the open ways (the user's choice): an open region hangs under an open box
    - [x] A copy says *Copied* only when the browser took it
    - [x] A Minecraft Dig patch is read as a stream with a limit, not whole
    - [x] Whether a Minecraft server's session is finished: asked once per session every 30 s, not every 3 s
          per server
    - [x] The tracker's tests against a database: a harness (`:server:integrationTest`) and ten tests for the
          mail limit's probes, the failed invitation, the provider's sync, the logins ended when an
          Administrator's rights change, a creation's reserved ID
    - [x] Seen in the demo: the hover text of a blocked box, the map in its small panel, the *received* and
          *sent* pills against the slot's lists (39 and 55, as listed), the pinned feed moved on the dashboard
          and in its place after a reload, the spoiler page's views and their search, a copy on `localhost`
          with the clipboard allowed
    - Decided by the user on 2026-11-23: the pinned feed stays the top panel's session's (also while the
      Management panel shows another); one user per session for Archipelago's processes: later (see TODO)
    - As decided on 2026-11-22: `v0.23.2` keeps its tag and pre-release without images (0.23.2.1 has everything
      in it)
    - Dropped by the user on 2026-11-22: a slot's lists and map with the checks in other worlds that hold its
      items; saying who blocks a region (the map only says that it is blocked)
- [x] 0.23.3: Randogeddon Bridge reviewed (0.23.3) (the key, the relays, the tunnel, its page, the downloads' signing). Scanned on
      2026-11-23: no high, 7 medium, 24 low (31 in all, some found by both; B: the review's, S: the security
      scan's)
    - Decided by the user on 2026-11-23: the key is tied to its server, and nothing goes out over plain http or
      ws but to localhost (B2, S3, B7, S6); sessions with only open-to-all or hosted slots are offered too (B3);
      the bridge stays while a game is connected and says so on its page (B10); the signing key's fingerprint in
      the web app and the docs (B12, S2: the cheap variant); `/bridge/info` carries the name, old bridges may
      break (B17); a session's title with its ID as a detail (B21); a release refuses unsigned builds (B11, S8)
    - [x] B1, S1 (medium): closing a relay, logging out or an ended key doesn't end the games connected through it
    - [x] B2, S3 (medium): the saved key is sent to whatever `-server` names, also over plain http; that
          server's 401 then wipes it
    - [x] B3 (medium): a player whose slots are only open-to-all or hosted ones isn't offered the session
    - [x] B4 (medium): any 401 counts as "the key is gone": an outage wipes the key and closes every relay
    - [x] B5 (medium): a refused tunnel shows "Connected", loses the gateway's reason, never fetches a renewed key
    - [x] S2, B12 (medium): the download check is circular (builds, checksums, signature and key from one place)
    - [x] B6: answers that arrive late are applied to a login they don't belong to
    - [x] B7, S6: a slot's password or the tunnel key goes wherever the connection answer says, also over ws
    - [x] B8, S4, S5: the page's token is a cookie for all of 127.0.0.1 and on the browser opener's command line
    - [x] B9: a second start empties the running bridge's log; the lock file stays after a failure
    - [x] B10: the bridge quits ten seconds after its tab is gone, also when the browser only discarded it
    - [x] B11, S8: a release can go out with unsigned builds; publishing never checks the signature
    - [x] B13: `check` throws the account's look away whenever `/bridge/info` fails once
    - [x] B14: `BridgeKeys.make` counts, then inserts: two requests at once pass the limit of ten
    - [x] B15: every refusal with `InvalidPassword` or `InvalidSlot` starts a dashboard call of its own
    - [x] B16: the relay parses every frame from the server in full to find two packet names
    - [x] B17: an idle bridge costs the tracker two requests and six database statements every 15 s
    - [x] B18: after one side ends, the close code for the other races with the context's cancel
    - [x] B19: the same things written twice in the bridge
    - [x] B20: what a build's file is called is decided in three places
    - [x] B21: smaller ones (the title that is the ID, bodies without a limit, the font cache, the log filled by
          refused origins, Ctrl+C waiting for a hanging check, two starts in the same instant)
    - [x] S7: redirects can carry the tunnel key or the bearer key along
    - [x] S9: no cap on a relay's or tunnel's connections, refetches without a bound
    - [x] S10: the Go build image pinned by tag, not digest; no vulnerability scan of the Go dependencies
    - Fixed by three Opus fixers (the bridge; the tracker, database, shared code and web app; the scripts and the
      build stage), each finding with its test where one could be written; the Go image's digest by the main
      session. Not as the finding said, or only in part: S5 (the code on the command line: once, five minutes);
      B3 needed nothing in the tracker; B16 without a benchmark; S10's scan in `audit.sh`, not in the image build
    - Tried: `gofmt`, `go vet`, `go test -race` in the bridge's build image; the tracker's tests and its tests
      on PostgreSQL (the key limit with twenty requests at once); the publish check with an unsigned build, a
      signed one, a key ID without its key, an `http://` registry, a wrong version
- [x] 0.23.4: the database reviewed (0.23.4). Scanned on 2026-11-23: no high, 9 medium, 26
      low (35 in all, some found by both; D: the review's, S: the security scan's). Live was checked for D1 / S1:
      both its programs have roles of their own, neither is a superuser
    - Decided by the user on 2026-11-23: roles of their own by default, a password derived where none is set
      (D1, S1, D24); the gateway's event inserts tightened by a migration (S2, D13); a connection limit on its
      role and timeouts on both programs' connections (S3, D5); a restart replays events from the snapshot's
      position, old events pruned (D4); foreign keys from events and snapshots to their session (D10, S10, D15);
      unique indexes for invitations and pending addresses (D11); the old recovery codes deleted (S6); login
      links pruned, a deleted account's name and preferences cleared (D20); UTC pinned in code, no change of the
      columns (D21); dumps written to a temporary name, the restore stopping at an error, no encryption (S7, S8,
      D22); a network of its own for the database (S9, D23)
    - [x] D1, S1, D24 (medium): without its own role's password the gateway is the database's superuser; the
          default role names differ between the service that makes a role and the one that logs in
    - [x] D2 (medium): `SessionSetupStore.create` takes over an existing session that has the same seed
    - [x] D3 (medium): `GatewayRole.ensure` on the tracker's own role takes the tracker's rights away
    - [x] D4 (medium): nothing prunes `session_events`, and every tracker start replays all of it
    - [x] D5, S3 (medium): no time limits in the pool; the gateway's role without a connection limit, able to
          hold locks the tracker waits on
    - [x] D6 (medium): every tracker start re-creates the policy and re-grants everything (a lock the gateway's
          inserts queue behind)
    - [x] S2, D13 (medium): the gateway's inserts into `session_events` are bounded only by their kind
    - [x] D7: events can be passed over for good (read by ID, IDs given before the commit)
    - [x] D8: `saveLive` can write a live snapshot over the archive
    - [x] D9: the session time's count reads, then writes
    - [x] D10, S10, D15: deleting a session is four transactions; leftovers show in the next session of that ID;
          three stores make a session row on their own
    - [x] D11: one invitation and one pending address per user is kept by code only
    - [x] D12: `EmailChangeStore.take` selects, then deletes (noted by the tracker's scan)
    - [x] D14: a gateway role no longer used keeps its login and its rights
    - [x] D16: check-then-insert where the database could decide
    - [x] D17, S4: the gateway role's password travels as SQL text at every start
    - [x] D18: the gateway can meet a schema older than its code
    - [x] D19: reads fetch password hashes and TOTP secrets they don't use, every 10 seconds
    - [x] D20: used login links and deleted accounts' rows are never removed
    - [x] D21: the times are `timestamp` without a zone, written in the JVM's zone
    - [x] D22, S8: a dump that failed looks like one that worked; the restore doesn't notice errors
    - [x] D23, S9: the database listens to every container of the project; the superuser may log in over the
          network
    - [x] D25: what the tests don't cover
    - [x] S5: SQL with its values in the tracker's log
    - [x] S6: recovery codes from before the keyed hash can be cracked from a dump
    - [x] S7: the dumps lie beside the key that unlocks their secrets (decided: no encryption)
    - The gateway's default password is derived from `INTERNAL_TOKEN`, not from `POSTGRES_PASSWORD` as first
      decided (the fixer's reason: a suffix can be taken off, so the gateway would hold the superuser's password;
      the user on 2026-11-23: keep it)
    - Fixed by two Opus fixers (the code of the database, the tracker and the gateway with the migration V29;
      the compose files and the dumps), the web app's part of the time zone by the main session. Not as the
      finding said, or only in part: S6 deletes the recovery codes of every account with two-factor login from
      before 2026-11-17 (the table can't tell the old form); D19 in part; D4 prunes with the session, not by age
    - Tried: all unit tests; 54 tests of the database module and 15 of the tracker on PostgreSQL (V29 on data in
      the old shape; the gateway's role and what it is refused; writers at once; a restore without rights); the
      demo on its database from before (schema 28, all the superuser's): both roles made, the tables given to
      the tracker's, V29 run, the gateway with ten connections at most, the minecraft service without a way to
      the database, the superuser refused over the network, a time zone kept by the native tracker; a dump
      taken as the script does and restored into a scratch database with the documented command
- [x] Dates and times in the user's time zone (asked for on 2026-11-23, with D21): the web app's views already
      show them in the browser's zone; the mails said UTC. Decided by the user: a time zone per account (the
      browser's, stored at login), used in the mails; an address without an account and the command line keep UTC (0.23.4)
- [x] 0.23.5: the web app reviewed (0.23.5). Scanned on 2026-11-23: 1 high, 15 medium, 28 low (44 in all, some found by both; W: the
      review's, S: the security scan's)
    - Decided by the user on 2026-11-23: the tracker checks a request's origin and the login cookie gets the
      `__Host-` prefix, everyone logged out once (S1); a running session's spoiler log asks before it shows,
      by a direct link and in the support's panel too (S3); the spoiler page opens on its first tab; a logout
      that didn't go through says so and stays (W3, S4); unsafe characters removed from chat lines and names so
      nobody can fake messages (W6, S7); complete by counts, the panel's session info with them (W4); with one
      session, straight to it and no session switch (W21); labels, Enter to submit, Tab consistent with a
      clearly visible focus, a text selection staying in its panel (W30); file pickers with a limit and a
      message, each kind of file remembering its folder (W11, S10); a sphere's steps a page at a time (W13); a
      person's panel on the share-link page (W25)
    - [x] W1 (high): after a tracker restart an open dashboard stays on its old state until reloaded
    - [x] W2 (medium): after a login that ended, logging in again leaves the dashboard empty
    - [x] W3, S4 (medium): logging out shows the login form even when the server never heard of it
    - [x] W4 (medium): "complete" is decided from a rounded percentage
    - [x] W5 (medium): a creation the tracker no longer knows keeps the form in "generating" for ever
    - [x] W6, S7 (medium): a chat line reaches the feed as it was sent (any length, line breaks, bidi controls)
    - [x] W7 (medium): the slot and person panels load every slot's whole detail again on every summary push
    - [x] W8 (medium): the map redraws everything, and lays out every label again, on each pointer move
    - [x] W9 (medium): the panel's sections poll every 3 s for ever
    - [x] W10 (medium): overlapping YAML checks: a late answer overwrites a newer one
    - [x] W11, S10 (medium): the file picker reads whatever is picked into memory and fails silently
    - [x] W12, S9 (medium): the service worker stores every GET it sees and never removes anything; under a path
          prefix it would keep API answers
    - [x] W13 (medium): the playthrough filters every step on each recomposition; "Unfold all" composes all
    - [x] S1 (medium): only the cookie's SameSite protects against forged cross-site requests
    - [x] S2 (medium): no npm lock file is committed; every image build resolves webpack's dependencies afresh
    - [x] S3 (medium): a direct link shows a running session's spoiler log without the panel's confirmation
    - [x] W14, S6: IDs and tokens from the URL's hash go into request paths as they are (`..` among them)
    - [x] W15: filters and a shown password survive a change of session
    - [x] W16: the map's hover uses the checks of when it was first drawn
    - [x] W17: the login forms have no `method`
    - [x] W18, S8: the content policy allows more than the page uses; the provider's link opened unchecked
    - [x] W19: failures that are ignored or shown wrongly
    - [x] W20: buttons that can be pressed twice while their request runs
    - [x] W21: "All sessions" does nothing for someone with exactly one running session
    - [x] W22: while the tracker is away each open dashboard asks four things every 2 s
    - [x] W23: what a logout leaves behind in the browser and the page
    - [x] W24: two of the five log pollers don't notice a log that starts again
    - [x] W25: on the share-link page a person's header looks clickable and isn't
    - [x] W26: where the app hides or shows by role, and what guards it in the tracker
    - [x] W27: the same patterns written several times
    - [x] W28: texts no code reads, dead code, small leftovers
    - [x] W29: files that grew too large to follow
    - [x] W30: keyboard and screen-reader basics
    - [x] W31: smaller things in hot paths
    - [x] W32: the web app has no tests
    - [x] W33: small leftovers in `index.html` and `page.js`
    - [x] S5: a login link or an invitation opened while logged in stays unused in the URL; a malformed `%`
          stops the login form
    - [x] S11: share and e-mail tokens stay in the URL (by design; noted)
    - Fixed in two waves by five Opus fixers (the UI helpers and the tracker's side first; then the app's core,
      the dashboard, the panel and account pages), three call sites and the focus ring's colour on the top panel
      by the main session. Not as the finding said, or only in part: S2 (see TODO); W29 for two files; W32
    - Tried: all unit tests, the tracker's 16 tests on PostgreSQL; in the demo: the dashboard, the Management
      panel, the new session's form, the map after its rewrite (blocked regions, the hover's marks, enlarged),
      the spoiler page asking and opening on *Everything*, Tab's ring through the panel, no complaint of the
      content policy in the browser's console
