# Changelog

Versions follow [Semantic Versioning](https://semver.org/). While in development: `0.<phase>.<step>`, the phase as in
`.docs/TODO.md` and the step its PR, in the order they were merged; a follow-up to a step gets a fourth number
(`0.8.2.1`), which the next step drops again. A PR's branch builds `-SNAPSHOT`, its last commit before the merge makes
it the release, and the merge is tagged `v<version>`. Each release also publishes Randogeddon Bridge's builds to
Artifact Keeper (`pkg.example.org`, `randogeddon-bridge/<version>/`) and is a GitHub release with its part of this file
as the notes: before 1.0.0 a pre-release without files, from 1.0.0 on with the bridge's builds.

## Phase 23: Full Code Review & Security Check
The whole code reviewed and its security checked, part by part in the order of risk: two scanners per part (a full
review and an independent security scan, both read-only), the fixes made from their findings. A fix keeps what users
see, the API and the schema, unless the user decided otherwise for a finding.

### 0.23.2.4: The spoiler log and the cheats from the seed on
- The spoiler page, the hints and the cheats no longer wait for the spoiler log's playthrough (its second level,
  worked out in the background after the seed): they are there as soon as the session is.
- The spoiler page has tabs: *Everything* (the whole log), *Playthrough* (sphere by sphere, as before) once the log
  has one, and *Paths* once it has those (its third level). With a playthrough the page opens on it.
- An item hint's *Next in playthrough* is offered only once the log has its playthrough; until then a hint names a
  copy by chance (the tracker refuses *next* before, as it refused every hint).

### 0.23.2.3: The live feed pinned, and every copy says that it copied
- The live feed has a pin: pinned, it is kept on every view. Where a view has no place for it (the Management panel,
  a spoiler log, Your account) it is a window over the page: at the bottom right until it is moved by its head,
  folded to its head and unfolded again, gone with its pin. Remembered in the browser, as the feed's place is.
- Every copy says that it copied: an icon button shows a check mark (its tip *Copied*), a button with its word reads
  *Copied*, for a moment. In the dashboard (connection, the server's copy menu, patch files' keys), the panel
  (share links, account IDs), the recovery codes, error messages, and the bridge's page.

### 0.23.2.2: The map over the window, and in full screen
- The map has a button that takes it a step larger: from its panel over the whole window, then with the page in full
  screen; a second one takes it a step back. Larger than its panel it takes all the room, under a line with the
  slot's name. Esc leaves full screen (the browser's own way): the map stays over the window then.

### 0.23.2.1: The gateway's decisions
What the gateway's review left for the user to decide, built as decided (2026-11-22).

- **Archipelago's Python runs as a user of its own** (uid 1100; the gateway stays uid 1000). APWorld code is
  third-party: it ran as the gateway's own user and could read the gateway's secrets (the database login, the internal
  token) from its environment and memory. The gateway's container now starts as root holding only `SETUID`, `SETGID`
  and `KILL` (`cap_add`; everything else stays dropped, `no-new-privileges` stays), becomes uid 1000 at once and
  starts every Archipelago process as uid 1100 with no capability at all. At its start the gateway gives that user
  the session folders (group 1100, group-writable) and closes everything else in `data/` to it. What stays: all
  sessions share that one user (a world's code can still read and change another session's files).
- **The minecraft service has a token of its own**, `MINECRAFT_TOKEN`, and no longer the internal one (the gateway
  calls it, never the other way round), and it mounts only `data/minecraft/`. Without the setting hosted Minecraft
  is off, and the panel says why; `scripts/deploy.sh` sets one on the server if it has none. What the service is
  sent is checked before it becomes a path.
- **Each game server has a room password** only the gateway and the tracker know (new at every start, given on its
  console, never on its command line, masked in every log). The gateway puts it in place of a slot's password it
  checked; a connection to a game server's port past the gateway is refused.
- **A sleeping game server is woken only by a `Connect` with a slot's right password.** A client needs the server's
  `RoomInfo` (and often its data package) before it sends one, so the gateway keeps both while a game server runs
  (`sessions/<id>/kept/`, per seed) and answers from them while it sleeps. A wrong password, or a connection that
  never sends one, wakes nothing. A session whose game server hasn't run since this version wakes as before, once.
- **At most 32 connections that haven't joined yet per address** (an IPv6 network as one), of the 1,024 in all. The
  address is the forwarded one, believed from `TRUSTED_PROXIES` as the tracker does (the setting is the gateway's
  too now).
- **The gateway's database role adds only its own kinds of events** (`packet`, `start`, `stop`): migration V28
  (row-level security on `session_events`, no data changed). A cheat's line can only come from the tracker.
- **`!admin` is gone:** no server password is given to a game server (`AP_ADMIN_PASSWORD` is ignored, with a
  warning); remote commands go through the panel's console, which no longer passes on a line that would set a
  password.
- **`init: true`** for the gateway's and the minecraft service's containers (what a process leaves behind is reaped).
- **Out of memory, the kernel ends a generation or a logic build first, then a game server, and the gateway last**
  (`oom_score_adj` 1000, 800, 0): a killed one fails or is started again as before, instead of every session ending
  with the gateway.

**Updating:** nothing has to be set by hand. The deploy copies the new `docker-compose.yml` (without its `cap_add` the
new gateway refuses to start and says so) and sets `MINECRAFT_TOKEN` if `.env` has none; the tracker runs V28; the
gateway sets the modes in `data/` at its first start. **Rolling back** to 0.23.2 or earlier: `chown -R 1000:1000 data/`
on the server first (files made since belong to uid 1100), and hosted Minecraft stays off (the old images expect the
internal token there).

### 0.23.2: The gateway reviewed
47 findings (5 high, 16 medium, 26 low), listed in `.docs/DONE.md`; nine of them wait for the user's decision
(`.docs/TODO.md`): they change the deployment, the schema or what someone sees.

Security:
- Before it joins, a game client is passed on only what a client sends before it joins (`Connect`,
  `GetDataPackage`), 32 packets in all, each a proper packet: one frame could carry tens of thousands of requests
  that the game server answered with megabytes each, without a password, until the gateway ran out of memory (and
  with it every session). A sleeping game server's wake counts inside the same limits.
- A process's output is kept in lines of 2,000 characters at most, cut as they are read; chat lines and link events
  are kept at 1,000 characters a text: a player (or anyone, through the game server's log) could fill the gateway's
  memory, its log and the database with lines of a megabyte.
- The slot's password is compared in constant time; the slot locks stay closed while they can't be read; taking
  someone off a slot closes a connection that is just being made, too.
- A release's name reaches a path only if that release is installed.
- A seed's zip and an APWorld are read in a stream with limits (a seed's spoiler log 64 MB, an APWorld 1 GB
  unpacked); an APWorld is unpacked once and its manifest read by one reader.
- Names and console lines go into the logs as one line each.

Fixed:
- Ending a logic while it is built frees its place and the APWorld library at once (they were blocked for up to 30
  minutes, and every new generation with them).
- Deleting a session deletes only its own Minecraft servers and worlds, not those of a session whose ID starts the
  same (`abc` and `abc-p…`); it ends its running generation and takes its logs with it.
- A game server that had to be killed ends like any other (its events, its session time, its logic); one that can't
  be started fails after five tries instead of staying at *starting*. Ending a process ends what it started.
- After a late answer of the logic, each question gets its own answer again.
- One session generates once at a time; a generation cancelled while it waits for its place ends at once.
- The game servers' stops are written when the gateway stops, and for every session at its start (players left
  *connected* by a killed gateway show as offline); the game servers end at the same time, not one after another.
- A seed upload that broke off is refused as no seed's zip (it answered a server error).

Faster, less of it:
- `room.json` and `stages.json` are read when they change, not at every `Connect` and tick; the APWorld library is
  copied only where it changed, and swapped in whole; after its `Connect` a client's frames pass as they came.
- One way to start, limit, read and end a Python process (six had their own); the processes' temporary files in one
  directory, emptied at the gateway's start.

### 0.23.1.1: The map's lines, scrolled sideways, the share links' streams as a setting
- The map is a tree from its start: a box's line leaves its right edge, runs down the middle of the gap beside it
  and branches off to the boxes first reached from it, which stand below each other in the next column. The lines
  stay in the gaps: none crosses a box or another line, and they turn at right angles. What a box connects to
  besides (a way back, a second way in) has no line: hovering the box marks those boxes and names them.
- A map wider than its panel was cut off at the right with nothing to scroll it by with a mouse (the wheel goes up
  and down). The map has a view of its own now (600 high at most) that scrolls both ways: bars along its bottom and
  its right edge (dragged by their thumbs, a click beside one jumps there), and the map dragged itself with the
  mouse; a touch scrolls it as before.
- *Share-link streams per address* is a setting beside the others (*Users*; default 20, empty: no limit): how many
  live dashboards by share links one address may hold at once. 0.23.1 fixed it at 20, too few where many watch from
  one network.
- The dashboard shows a login as an Administrator's only if it may act as one (`GET /api/me` goes by the login, as
  every route does): an Administrator's account logged in another way (by password beside single sign-on, without
  `ADMIN_PASSWORD_LOGIN`) saw the panel's Administrator parts and got refused by each of them.
- The demo's admin keeps its login between its steps (`output/demo/admin.cookies`). Since 0.23.1 the login limit
  counts by address, not by host name: in Docker each step's container had a name of its own, so the demo's three
  logins never met the limit of 5 a minute; now they would. Behind a proxy the limit always went by the client's
  address.

### 0.23.1: The tracker reviewed
52 findings (none high, 11 medium, 41 low), listed in `.docs/DONE.md`.

Security:
- A seed upload (96 MB) is refused before its body is read unless from an Administrator: any login could fill the
  tracker's memory with it.
- The mail limit counts by account, not by name (a rename gave a fresh one), and a refused invitation or address
  change counts too: addresses could be probed for accounts without limit.
- A share link's live streams are counted per address (20, an IPv6 network as one), as an account's are.
- Whoever is left without a slot in a session loses its open-to-all slots too: they get new passwords and their
  connections close (the other players reconnect with the new one). Timing out, suspending or deleting an account
  does the same for the slots only that account plays.
- Administrators only through single sign-on: without it nobody acts as one, unless `ADMIN_PASSWORD_LOGIN=true`
  (local testing and the demo; the local overlay sets it). Where it is on, granting the flag, resetting two-factor
  or an Administrator turning it on ends the account's other logins: a login that never passed a second factor
  could make itself an Administrator's before. **Updating:** a server without single sign-on has no Administrators
  until the setting is set; with single sign-on (the live server) nothing changes.
- `TRUSTED_PROXIES`: the forwarded client address is believed only from these peers (unset: from every peer, as
  before); the rate limits and stream counts use it.
- Wrong two-factor codes on the account's routes and wrong current passwords on the password change count per
  account (429 after 10 in 10 minutes), and a login's wrong passwords count once for the account, by name or address.
- The Administrator-only logs (logic, Minecraft) and direct hosting ask whether this login may act as one, not the
  account's flag; a session's stream follows a revoked flag.
- The gateway's error text (it can name internal hosts) goes only to a session's managers and Administrators.
- A console line with a line break or another control character is refused (it could forge log lines).
- Support can no longer restart a stopped spoiler-log run (the panel never offered it; the route did).
- The new-session routes are paced per user (a loop gets 429; the form never meets it).
- An e-mail address may not contain `<`, `>`, `,`, `;`, `"` or control characters; a provider's address is checked
  like any other; the mail client refuses such an address as well.
- The tracker's log names accounts by ID or name, never by e-mail address.
- Single sign-on: the provider's discovery document must name the configured issuer; its keys are reloaded at most
  once a minute; its webhook leaves accounts alone that are no longer Administrators (it reset their two-factor).

Fixed:
- A session event that can't be read (a malformed packet) is passed over and logged: it stopped that session's
  tracker for good.
- A database read that fails during the sessions' refresh keeps the last known roles, finish state and time: it
  wiped them until the next read (everyone locked out for seconds, a finished session connecting again).
- Routes that answered 500 for an unknown session or account answer 404; a call refused by two guards is answered
  once.
- Two creations of the same session ID at once: the second is refused at once (both could pass the check), and a
  refused one no longer takes the running one's progress away. Two Session-Managers taking each other's part at
  once can't leave the session without one; the sessions are read again after a slot change also when closing its
  connections failed.
- A sign-up whose name is taken at the last moment keeps its invitation; a Session-Manager's invitation whose mail
  fails leaves no half-made account, so it can be sent again.
- The manage stream sends keepalives (an idle proxy cut it); the mail client closes its socket on a failed TLS
  handshake; one account's failure no longer ends the cleanup's pass.

Faster, less of it:
- One connection client for all sessions' trackers (one per session was never closed); the sessions' refresh reads
  setups, finishes and times in 8 queries, not 7 per session; `room.json` and the spoiler log's slots and placements
  are read once, not per request; the summary's lock slots, progression checks and people are kept, not rebuilt.
- One error wrapper for every route (`apiCall`), one stream loop, one pace, the routes' small helpers; the provider's
  rule and the keys' retry in one place each; one session lookup (it was written nine times), the gateway client's
  answers through four helpers (sixteen copies), the cheats carried out in one place, one hex encoding and one
  random source; the finish countdown asks the database only when it is armed.

Also:
- The top panel's status pills say what they are: *Game server: live* (asleep, offline, reconnecting…), *Logic:
  running* (building, stopped, failed).

## Phase 22: Maps
The slots' games' maps with markers for their checks, the README's tools, `.docs/` down to the essentials, and what
came up on the way.

The map in a slot's panel, generated from the logic for every game (its regions as boxes, its locations as dots:
checked, open, available now; a big region grouped by its locations' names), and the panel itself in tabs (checks,
map, transported items, connections); `.docs/` rewritten as compact references (the finished phases in `DONE.md`)
and the README's *Built with*; *Your connection* with icons and one-line fields, the bridge's page copying an
address four ways; a session's ID kept to what a subdomain allows as it is typed, *Plando* and *Generation* apart;
the panel going to a new session after its generation; *State and Control* on a phone; locked slots counted as not
started. Updating: no migration; the logic's maps need the gateway and the tracker of 0.22.4 (an older gateway
gives none: the map tab says so).

### 0.22.4.1: The map whatever a region is called
- A slot's map no longer fails on a game whose regions aren't named with text (a tuple, a number) or share a name:
  the regions are told apart by themselves, their names only shown. On such a game the logic's whole answer failed
  with 0.22.4, so the session's available checks stopped too, not only its map.
- A map that can't be made is left out and said in the logic's log: the available checks are answered either way.
- A slot whose logic fails when asked (a world's rules throwing) gets no answer and is said once in the logic's log,
  with where it failed: the other slots are answered either way. Before, one such slot failed the whole answer.
- `scripts/deploy.sh --switch`: the server switched to a commit's images as they are in the registry (published
  before with `--publish`), nothing built: the files, the dump, the switch and the checks as a deploy does them.

### 0.22.4: The map
- A slot's panel has a *Map* tab: its game's map, generated for every game from the session's logic, no game
  images. Its regions are boxes in columns from its start, linked as its logic connects them; each location is a dot
  in its box: checked, open, or available now (green; a box with one has a green edge). On hover a location's name,
  and once checked what it held and for whom (nothing before: no spoiler); a box's name and how much of it is
  checked. Filters: all, open, available now, with how many there are.
- A region with many locations is split by their names into boxes of their own, linked to it: by the part before
  `: ` or `-` (`Lake Desolation (Lower): Chicken chest`, `Grub-Crossroads_Acid`), then by a first or last word
  they share (`Unbreakable_Heart`, `Dirtmouth_Stag`). A game whose regions say little still gets a map: Hollow
  Knight's one region of 203 becomes 19 groups and its 51 unique locations.
- The panel is as wide as the window on the *Map* tab; a column taller than the panel wraps into narrower ones.
- The logic (`logic_run.py`) answers a slot's map with its available checks: its regions with their locations, its
  start first, and which connect (a region without locations that leads nowhere else is left out). The tracker asks
  once per slot and build and keeps it with the slot, so the map shows while the logic sleeps. Like the available
  checks, not for seeds from elsewhere, races and sessions from before 0.19.3.

### 0.22.3: The slot panel in tabs, locked slots as not started
- A slot's panel: under its progress and its time, tabs. *Checks*: what its logic reaches now on the left, the rest
  of its remaining checks on the right. *Transported items*: what it sent on the left (what its checked locations
  held, and to whom), what it received on the right. *Connections*: its connection log. One filter above both
  columns of a tab.
- The Overview's *Slots* tile counts a locked slot as not started (it can't connect before its key turns up): with
  locks, its *not started* no longer left them out, and *Slots* adds up again.

### 0.22.2: A session's control on a phone, the ID as typed, one-line fields
- *State and Control* on a narrow screen: its actions (pause or end, delete, *Keep running without players*) wrap
  where they need to, and the dashboard's and the spoiler log's buttons go on a line of their own below them; they
  were cut off at the right. On a wide screen the line stays as it was, those buttons on the right.
- The session ID's field turns a space into `-` as it is typed. A space typed at the end was trimmed away before it
  could become one (`friday chaos` came out as `fridaychaos`); only a pasted ID kept its hyphens.
- The ID's field takes nothing before its first letter (`2024 run`: `run`), and a hyphen at its end is left off the
  ID (it stays in the field while the next word comes): neither is said as a mistake any more. Under the field only
  what the server knows: taken, reserved or unwanted, too short.
- *Your connection*: the server, the slot, a shown password or key and the patch files' names stay on one line, cut
  in the middle where they don't fit (the port and the file's ending still show), the whole of it on hover. The panel
  is that much shorter, and with it the Overview's tiles beside it, which take its height.
- Randogeddon Bridge's page: a connection's address is copied the same ways as the server on the dashboard: as
  shown, its host, its port, or as a `ws://` address (not for a Minecraft tunnel, which takes host and port).

### 0.22.1: Icons for the connection, the session ID as typed
- *Your connection*: copy, show and hide are icons (two sheets; an eye, struck through while shown), their words as
  tips; the server's copy icon opens its menu. The bridge's fold arrow points left to collapse and right to expand.
- Creating a session: its ID's field takes only what a subdomain allows: capitals become small letters, a space, `_`
  or `.` a hyphen, anything else isn't taken, no two hyphens in a row, at most 40 characters. A letter first and no
  hyphen last are still said under it.
- Creating a session: *Plando* (was *YAMLs may plan*) in a row of its own, its ⓘ saying shortly what a ticked kind
  does; *No progression balancing* and *Race mode*, which have nothing to do with it, below as *Generation*.
- Once a new session is generated, the panel goes to its page. It went there before its list had the session, took
  it for gone, and fell back to another running session or to none, *New session* highlighted.

## Phase 21: Floating Panels – Slot & Player
The slot and player panels as floating panels, and what came up with them: the players' panels using the space, the
site growing with the window, a session's slots and their players, the Management panel's logs, the README.

The Management panel's logs in tabs (each only for whoever may read it); slots open to all of a session's players, a
hosted slot for certain players, and a running session's slots edited one at a time (players, open to all, hosted;
someone who loses a slot loses its connection: a new password), with late invites; the README down to its essentials,
its details in `.docs/`; new icons for sent, released and the available checks; the page as wide as the window and
the players' cards filling it without gaps; the hosted Minecraft servers in an image of their own, stopping with
their session and keeping their idle time past a deploy; the lock cheats reworked (*Unlock* takes the key from its
world, the lock says the server did; or a hint for it); the slot's and player's panels a page at a time, a slot's
available checks first; *Your connection*'s fields reworked. Updating: the database's V27 comes with its migration;
the deploy builds and pushes the third image (`minecraft`) and sets `MINECRAFT_IMAGE`.

### 0.21.8: Lighter panels, the connection's fields
- The slot's and the player's panels show their lists a page at a time (received items and remaining locations 50,
  hints and connections 20, recent activity 25) with arrows to the others: a slot with thousands of locations opened
  so slowly the browser lagged. The remaining locations get a filter of their own; the connection log and the recent
  activity are no longer cut off.
- A slot's remaining locations list the ones its logic reaches now first, marked (green, the signpost): the logic
  says which they are now, not only how many (the last known while it doesn't run).
- *Your connection*: server, slot and password are text fields that can't be changed, their buttons inside; the
  server is copied as shown, as its host, its port or a `wss://` address. The bridge's *Create key* has a row of its
  own, its fold is a chevron.
- Updating: nothing to do.

### 0.21.7: Editing a slot
- The panel's slots are edited one at a time: *Edit slot:* with a drop-down as the heading, then the slot's row as
  before (its players, *Open to all*) with *Hosted* at the right.
- *Hosted* now switches while the session runs: on (its players then join its game's server and the slot gets a new
  password) asked once more, off asked twice; a directly hosted Minecraft server stops when its slot is no longer
  hosted. Who held the slot's password (or its tunnel key) and no longer does decides whether it gets a new one.
- A hosted Minecraft server stops when its session is finished or paused, as its game server does, and doesn't
  start again until the session is reopened. Until now it ran on until nobody had been on it for the idle time.
- The idle time of a hosted Minecraft server is kept by the minecraft service (its start, its players' joins and
  leaves): a gateway restart (a deploy) no longer starts it over.
- The lock cheats reworked: *Unlock* checks the location that holds the slot's key, so the key comes from the world
  it is in (as *Check its key's location* did), and the slot's lock then says it was unlocked by the server. The
  other one is now *Hint its key's location*: a free hint for that location, nothing checked. Without the spoiler
  log's placements *Unlock* gives the key to the World Locks slot, as before.
- Updating: nothing to do.

### 0.21.6: The players' panels fill the space
- The players' cards lie in as many columns as fit, each card into the column that is shortest so far: no gaps under
  a short card any more, and a few cards stay as narrow as a column instead of being stretched. *Sort*'s order reads
  left to right.
- The hosted Minecraft servers have an image of their own, `minecraft` (Java 17, Forge with the mod,
  `minecraft_host.py`), instead of the gateway's: the gateway no longer carries Java and Forge, and the servers, which
  run third-party mod code, nothing of Archipelago. `scripts/deploy.sh` builds and pushes it, tagged like the tracker.
- Updating: nothing to do (the deploy sets `MINECRAFT_IMAGE`). A rollback to a version before 0.21.6 finds no
  `minecraft` image under that tag: tag 0.21.6's for it on the server first (`.docs/OPERATIONS.md`).

### 0.21.5: The whole window
- The page grows with the window instead of stopping at 1,400 dp: it takes the width less 2 % at each side. On a
  1440p screen five players' cards sit side by side, on 2160p six; the Overview's tiles fill their row, and the feed
  grows to a quarter of the width (440 to 640 dp). Normal windows look as before.
- Updating: nothing to do.

### 0.21.4: New icons
- *Sent* has the arrow up out of an open box that *released* had; *released* is a broken chain, its links pulled
  apart; the available checks are a signpost. Picked by the user from candidates.
- Updating: nothing to do.

### 0.21.3: A running session's slots, a shorter README
- The Management panel's *Slots* (Session-Managers and Administrators): who plays each slot is changed while the
  session runs (players added or taken off, *Open to all* switched), and someone new is invited by e-mail right
  there. A hosted slot stays hosted.
- Whoever loses a slot loses its connection: it gets a new password (asked once more), the gateway reads it at once
  and closes the slot's connections, and its players connect again with the new one (the dashboard and the bridge
  have it). A directly hosted Minecraft server connects again by itself. A hosted slot whose server runs elsewhere
  keeps its password: its players never had it.
- The README is down to the essential points (137 lines); the details are in `.docs/FEATURES.md`,
  `.docs/OPERATIONS.md` and `.docs/DEVELOPMENT.md`.
- Updating: nothing to do.

### 0.21.2: Slots open to all, hosted slots for certain players
- Creating a session, every slot has *Open to all*: every player of the session (everyone named for any of its slots)
  plays it, with its password, its files and the bridge; the dashboard shows it on a card of its own, *Everyone*.
- A hosted slot starts open to all, as before (every player gets its files and, hosted here, the bridge's tunnel).
  Unticked, it goes only to the players named for it; they join its game's server and never get its password.
- Database: V27 (`session_open_slots`); every slot hosted so far is open to all, so nothing changes for a running
  session.
- TODO, Phase 21: new icons for *sent*, *released* and the available checks.
- Updating: nothing to do.

### 0.21.1: The logs in tabs
- The Management panel's *Logs*, at its bottom, shows every log of the session as a tab: the game server's (first),
  the spoiler log's run, and for Administrators the logic's and each directly hosted Minecraft server's. The sections
  no longer show a log of their own; nobody gets a tab of a log they may not read.
- TODO: Phase 21 begun; Phase 22: the README notes every tool and piece of software Randogeddon uses.
- Updating: nothing to do.

## Phase 20: Hosted Minecraft Dig
The Minecraft server a *Minecraft Dig* slot needs, hosted by Randogeddon and reached through Randogeddon Bridge.

A new service, `minecraft`, runs the servers (Forge 1.19.4 and the mod, up to 3 GB each, `MINECRAFT_SERVERS` at once,
their worlds kept while they sleep), for a hosted Minecraft Dig slot an Administrator switches on. The bridge reaches
one by a tunnel, with a key made from the slot's password that the session's players get; no Minecraft port is
public. The gateway answers the server list while a server sleeps and starts it only for a join, which it holds
until the server is ready; then the server's own account check and encryption take over. A server connects to
Archipelago with the slot's password once a player is on it, and sleeps like the game servers. The panel's
*Minecraft* section shows each one with *Start*, *Stop*, the switch and its log. Mojang's EULA is the operator's
(`MINECRAFT_EULA=true`). Updating: the database's V26 comes with its migration; to host servers, `MINECRAFT_EULA=true`
in `.env` and the bridge 0.20.5 or newer for the players. Not yet tried with a real Minecraft client.

### 0.20.6: Phase 20 done
- TODO: Phase 20 done; Phase 22 (*Maps*: the slots' games' maps with markers for their checks) planned.

### 0.20.1: Found out first
- TODO: what the mod and the APWorld's client do, read from the mod (0.0.11) and the client. The mod connects by
  itself to the address in `APData/archipelago.json`, with the slot name from the patch but without a password, so
  the hosted server will reach the session's game server inside the compose network. The server's files are the
  patch's `data.json` in base64 as `APData/<name>.apmc` and that `archipelago.json`; each seed needs a fresh world.
- Measured in WSL with a generated seed: ready 12 s after its start; 1.4 to 1.5 GB a while after, nobody on it, with
  any heap from 1.5 to 3 GB.
- Decided with the user: 3 GB per server (`-Xmx2G`); it starts when a player joins through the bridge (a client
  only looking at the server list wakes nothing), the gateway holding the connection until the server is ready and
  then only passing bytes (the account check and the encryption stay the server's); it sleeps like the game
  servers; it connects to Archipelago when its first player joins, by `/connect` with the password in its console.

### 0.20.5: Hosted here only when an Administrator says so
- Randogeddon runs a Minecraft Dig slot's server only when the slot is hosted and an Administrator switches it on:
  when creating the session (*its server here (through the bridge)* beside *Hosted*) or later in the panel's
  *Minecraft* section (*Host here*). Session-Managers can switch it off (*Don't host here*: its server stops, its
  world stays). Any other Minecraft Dig slot runs its own server, and the bridge relays it like any game.
- The bridge's tunnel logs in with the slot's tunnel key instead of its password: made from the password, given to
  the session's players and its Session-Managers, so the players reach the server without being able to connect to
  Archipelago as the slot. The bridge offers such a slot to its players too.
- Database: V26 (`session_hosted_slots.direct`); every slot hosted so far stays as it was, not hosted here.
- Updating: the tunnel needs this bridge (0.20.5) or newer. `MINECRAFT_EULA=true` in `.env` now hosts only the
  switched-on slots.

### 0.20.4: The Minecraft servers in the panel
- The Management panel has a *Minecraft* section for a session with a Minecraft Dig slot: each slot's server (asleep,
  starting, running with who is on it, or ended on its own), *Start* and *Stop* for Session-Managers and
  Administrators, its log for Administrators.
- Mojang's EULA is the operator's: `MINECRAFT_EULA=true` in `.env` accepts it once for every hosted server (false by
  default: nothing runs). The Administrators' setting `minecraft_eula` of 0.20.2 is gone.
- No whitelist (decided with the user): only the slot's players reach its server, through the bridge.
- Updating: to host Minecraft Dig servers, `MINECRAFT_EULA=true` in `.env`.

### 0.20.3: The tunnel
- Randogeddon Bridge opens a tunnel for a Minecraft Dig slot instead of a relay: `localhost:<port>` for Minecraft
  1.19.4's *Direct Connection*, each Minecraft connection passed on over `wss://<session>.<domain>/minecraft/<slot>`
  with the slot's password. Its page says how to join.
- The gateway answers a look at the server list itself while the server sleeps ("asleep, join to wake it") and starts
  nothing for it; a join starts the server and is held until it is ready, the client kept waiting; then every byte
  passes, and Mojang's account check and the encryption are the server's. A wrong password or a locked slot is
  refused; a start that fails or takes more than 4 minutes ends the join with a message the client shows.
- Fixed: a deleted session's Minecraft server kept its relay open, so the next server given its number couldn't open
  its own (and would have reached the deleted session). Since 0.20.2; nothing was hosted live.
- Updating: nothing to do. The bridge's tunnel needs this bridge (0.20.3) or newer.

### 0.20.2: The Minecraft servers
- A new service, `minecraft`, runs the hosted Minecraft servers (Java 17, Forge 1.19.4-45.3.15 and the mod 0.0.11 in
  the gateway's image, both checked by their SHA-256), one process per Minecraft Dig slot, as user 1000 without
  capabilities, up to 3.5 GB in all (`MINECRAFT_MEMORY`); a server above 3 GB is ended, `MINECRAFT_SERVERS` run at
  once (1 by default). Each one's world is kept in `data/minecraft/` while it sleeps and made anew for a new seed.
- The gateway tells it what to run: a slot's server from the seed's patch, started and stopped (the internal API, for
  now; the bridge's tunnel and the panel come next). Nothing runs until an Administrator accepts Mojang's EULA (the
  setting `minecraft_eula`, its switch to come).
- Once its first player is on it, the gateway connects the server to Archipelago with `/connect` and the slot's
  password, through the gateway like any client; it sleeps when nobody has been on it for as long as the game
  servers wait.
- The tracker's and the gateway's native images are built one after the other (`scripts/deploy.sh`,
  `scripts/demo.sh`): each takes several GB while it compiles.
- Updating: nothing to do; the new service starts with the others.

## Phase 19: Generating, Keeping, Showing
It began with generating like the Launcher (a seed that the Launcher generates is to generate here too: first why one
didn't, then what the Launcher has that the gateway hasn't), went on to keeping a session's state (nothing lost while
the tracker isn't connected, nothing of it only in files) and ended with how the dashboard shows it (the slots'
states, the Overview, the players' panels). Renamed from *Generating Like the Launcher* with 0.19.15.

Generating: the spoiler log in three levels after the seed (the session starts at once), a playthrough that doesn't
give up, generations at once, generator settings per session, a session from a seed generated elsewhere. Keeping:
the gateway hears every game server and writes what happens into the database, the tracker replays it; a snapshot of
each dashboard and the finished sessions' archives in the database too, so nothing is lost while the tracker isn't
connected and the state volume holds nothing that matters. Showing: the slots' states in a new order with their
times, the Overview's tiles with icons and pills, the players' panels with two bars and icon pills, the available
checks counted by each session's logic, deaths and traps per slot, cheats for locked slots. Updating: nothing to do
(the database's new tables come with their migrations, V24 and V25; the old `tracker.json` and `archive.json` are
imported once and left where they are).

### 0.19.20: Phase 19 done
- TODO: Phase 19 done (the panels' item closed with it); Phase 21 (*Floating Panels – Slot & Player*) planned.

### 0.19.19: Cheatable locks
- **Two cheats for a locked slot** in the *Cheats* section (Session-Managers, Administrators, support), listed while a
  slot is locked, with the locked slots to pick from:
  - *Check its key's location*: the location that holds its key (from the spoiler log's placements, shown beside it)
    is checked as if its player found it. The key opens the slot; the check counts for the world it is in.
  - *Unlock*: its key is given to the World Locks slot (`/send`); no location is checked.
- Logged and in the live feed like every cheat ("checked the key to Alice-Locked at Chest B (in Bob)", "unlocked
  Alice-Locked").

### 0.19.18: Icons on the players' pills; deaths and traps per slot
- **The person cards' and slot rows' pills are an icon and a number**, what they are as their tooltip in one word, as
  in the Overview: *Done* (a flag, "1 / 3"), *Locked* (a lock), *Played* (a clock), *Received* (an arrow into a tray),
  *Sent* (an arrow away), *Available* (a target; while the logic doesn't run, the tooltip says it is the last known
  count). In the dialogs too.
- **Deaths and traps per slot**: what each slot passed on by DeathLink (a skull) and TrapLink (a warning sign), from
  the first one, summed on the person card; counted from what it sent, not from what it got. Older links count once
  the tracker replays them (every link since 0.19.9 is in the database).

### 0.19.17: Available checks
- **How many checks a slot can make now**, as a pill on each slot ("12 available"), summed on the person card and in
  the dialogs: its unchecked locations its game's logic reaches with the items it has (as a Universal Tracker shows
  them). Grey with the last known count while the logic doesn't run.
- **The session's logic**: the gateway generates the seed once more with its number and inputs (as for the spoiler
  log), but only up to the fill (`archipelago/logic_run.py`): its worlds with their rules, events and locked items,
  in seconds; where the items lie comes from the seed. That process answers the tracker whenever a slot's items or
  checks change. It starts with the game server and ends as long after it went to sleep as the game servers wait
  before they sleep (at least 10 min). Tried on the server: 10 slots and 2,868 locations built in 2.6 s and 195 MB,
  every slot counted in 0.27 s. Freezing it with CRIU while the game server sleeps worked too, but needs rights close
  to root: not used.
- **Its state in the top panel**, beside the game server's (*Logic*: being built, running, not running, failed; what
  that means as the tooltip). The panel's new *Logic* section: its state and *Build again* (Session-Managers and
  Administrators), its log with pages and search (Administrators).
- **The counts are kept** with the rest of the dashboard in the database (0.19.10), shown while the logic doesn't run
  and after a restart.
- Not for seeds from elsewhere, races and sessions from before 0.19.3 (their inputs aren't kept), nor after one of
  the seed's APWorlds changed. A slot whose locations differ from the seed's gets no count.
- The top panel's controls wrap onto a second line on a phone, where they don't fit beside each other.

### 0.19.16: Icons in the Overview's pills
- **The Overview's pills are an icon and a number**, what they are as their tooltip in one word: complete (a check
  mark), goal (a flag), released (an arrow out of a box), idle (two bars), not started (an empty ring), hints not
  found yet (*Open*, a magnifying glass), progression (a star), traps (a warning sign), deaths (a skull).
- **The Session tile's server time says what it is**: a power sign, green while the game server runs (*Running* or
  *Stopped* as its tooltip), and the time beside a server's icon, *Uptime* as its tooltip: how long the game server
  ran for this seed.
- **A section's title isn't squeezed any more**: where its filters don't fit beside the whole title, they go below
  it (on a phone, *Hints* broke into "Hin" and "ts").

### 0.19.15: No totals for the players; icons in the Overview
- **Received and sent without totals**: the pills say how many progression items a slot (or person) received and
  sent, not of how many: its players would learn how much is still missing. The tracker doesn't send those totals
  any more either (0.19.12 and 0.19.13 did). The *Progression checks* bar keeps its own world's x / y.
- **Slots and done in one pill** on the person card: "1 / 3 done" (green once one is).
- **Icons and pills in the Overview**: every tile has its icon (new: a bulb for hints, a gift for items, a chain link
  for links, a clock for play time, an hourglass for the session), and its parts are pills (complete, goal,
  released; idle, not started; not found yet; progression, traps; deaths, traps; running or stopped, and how long).
  The pills stand one below the other, so a tile's height comes out right (a wrapping line of them was cut off).
- Phase 19 renamed to *Generating, Keeping, Showing*.

### 0.19.14: Nothing cut off
- **The person cards and slot rows were cut off at their end**: the last slot of a card showed only its first bar,
  and on live often not even that. Cards side by side took the height of the tallest, and a slot row its height,
  from their content's measured minimum, which the lines of pills that wrap (0.19.13) underestimated. Now each card
  and each slot row is as tall as its content (cards side by side may end at different heights); the lock badge under
  a locked slot's game icon has a height of its own.
- **Icons instead of the bars' labels**: a check mark for *Checks*, a star (new) for *Progression checks*; the label
  shows as the icon's tooltip. The bars get the room.

### 0.19.13: Progression checks, received and sent
- **The second bar is the slot's progression checks**: of the locations in its own world, those that hold a
  progression item (for anyone), how many it checked. 0.19.12's second bar (progression received) becomes a pill.
- **Pills for received and sent**: *received x / y* (progression items it got of those meant for it) and *sent x / y*
  (progression items found in its world for other slots, of all such), in a new line between the slot line and the
  bars, with its play time. The same in the slot and person dialogs.
- **The person header as pills**: slots, locked, done, play time, received and sent, in the line under the name
  (was one grey line); the person's two bars below.
- A slot's own counts are known once it is scouted (its received total once every slot is).

### 0.19.12: Checks and progression, apart
- **Two bars instead of one** in every slot row, on the person card and in the slot and person dialogs: *Checks* (the
  locations checked in its own world, x / y) and *Progression* (the progression items received of those meant for
  it in the whole multiworld, x / y). Before, the line "64 / 100 · 2 progression" put two different things side by
  side. The goal's time below the bars goes (it is beside the state since 0.19.11).
- **Each slot's progression total**: every tracker connection to a slot scouts its own locations
  (`LocationScouts`, `create_as_hint` 0: no hints are made); together that is every placement, counted per slot it
  is meant for. Only the counts reach the dashboard. Until every slot is scouted the bar says how many were received
  so far. The starting inventory isn't counted on either side (received progression now leaves it out).
- The first of the slot and player panels' rework, planned with the user (they stay cards).

### 0.19.11: The dashboard's states and tiles
- **A slot's state in a new order**, the first that applies: *released* (before its goal, or by hand after it),
  *complete* (every location checked; the automatic release at the goal, release mode `auto`, counts as complete:
  a release less than 5 s after the slot's goal is that one), *goal* (locations left), *locked*, *online*, *idle*,
  *not started*. Before, `released` lived in memory only (after a restart a released slot read `complete`) and the
  goal beat it.
- **Online instead of playing**: a game client connected now. Whether someone really plays can't be known; the
  client's own status isn't used any more, so the grey "playing" of a slot nobody plays is gone. An archive of before
  is read with `playing` as online.
- **Idle and not started**: idle only once the slot was connected and has a check, else not started.
- **The time beside a state**: since it was complete or released, since its goal and its last check, the last check
  of an idle or online slot. Release and last-check times come from the events, so they survive restarts; a release
  from before 0.19.9 is taken from the feed of the imported history, older ones can't be known.
- **The Overview's tiles**: *Finished* (done of all: complete, goal, released, with the parts below; goal only when
  the release mode isn't `auto`), *Slots* (online of all, with idle and not started; Online merged in), *Unlocked*,
  *Hints*, *Items* (sent, of them progression and traps; new), *Links* (deaths and traps shared; new, from the first
  one on), *Play time*, *Session* (its age, the date as a tooltip; below the game server's running time). The
  Overview's subtitle goes; the top card reads "x of y checks done".
- "Done" for a person, a past session and the Finished tile counts complete slots too.

### 0.19.10: A session's state in the database
- **The dashboard isn't empty while a game server sleeps.** The tracker learnt a session's slots only from its
  connection to the game server, and every game server sleeps after the gateway starts (since 0.18.10): after each
  deploy every dashboard was empty until a player came. Now what a dashboard shows (the summary and every slot's
  details) is kept in the database (`session_snapshots`, V25) when the tracker's connection ends, when the tracker
  stops, and at most every 5 minutes while connected; until the tracker has been connected since its start, that is
  shown, with who is connected from the events.
- **The history is the events.** No history file any more (`tracker.json`, written every 15 s: a crash lost up to
  15 s, and it wasn't backed up). At its start the tracker sets a session's slots up from `room.json` and replays
  every event the gateway kept (since 0.19.9), so the feed, the connection logs, first check, goal and last
  activity come back from the database. A cheat's feed line is written there too. The history files of before are
  imported once as the base the newer events are replayed onto.
- **Finished sessions' archives are in the database** (the final snapshot), so in the daily dump; the files of
  before are imported once at the tracker's start and left where they are. Reopening makes it live again.
- A slot's status reply no longer counts as "seen playing" (every join is heard since 0.19.9).
- The tracker's state volume holds no session data any more (only the TOTP key file, without `TOTP_KEY`).

### 0.19.9: Nothing is lost while the tracker isn't connected
- **The gateway hears what happens on each game server and keeps it.** It starts every game server and is there
  whenever one runs: from the moment the server accepts clients it listens (a connection like the tracker's) and
  writes what is said to everyone into the database (`session_events`, V24): item sends, hints, goals, releases,
  who joins and leaves, chat, death and trap links, and the game server's starts and ends.
- **The tracker reads it from there**, about once a second, in its order, and saves how far it is with its history.
  So a slot released while the tracker restarted is in the live feed, with the time it happened, and after a restart
  the tracker knows who is connected from the joins and leaves since the game server's start (before: nobody, until
  they did something).
- **Whoever wakes a sleeping session is seen**: a player is let in once the gateway listens (3 s at most), so the
  join isn't said before anyone hears it. Before, the tracker was back about two seconds after the player and showed
  them as offline.
- The gateway's database role may add rows to `session_events`, nothing else of it (not read, not delete); a
  session's events go when it is deleted.

### 0.19.8: A session from a seed generated elsewhere
- **An Administrator makes a session from a seed Archipelago's Launcher (or `Generate.py`) made**: *Use a generated
  seed…* in the new session's form takes its `AP_<seed>.zip` (up to 64 MB) instead of YAMLs. The slots are assigned
  as with YAMLs; nothing is generated, and the session starts with the seed as it is.
- **Checked before anything of it is used**, since it comes from outside: only files named as a seed's (`AP_…`, no
  folders, each name once), one `.archipelago`, at most one spoiler log, 1,000 files and 512 MB unpacked. The gateway
  writes the zip anew from what it read (what is kept is what was checked), and reads the multidata with
  Archipelago's own restricted loader (`archipelago/seed_info.py`: as MultiServer reads it, no code of the file
  runs, and its unpacked size is bounded first), which also says whether the release can serve it. A refusal names
  the reason in the form.
- **What such a session doesn't have**: locks (they are generated in; a seed with a World Locks slot is refused), the
  spoiler log's later levels (it has the log the zip brings, or none and then no spoiler page), and a second
  session of the same seed. Its files for the players are the zip's, unchecked: they come from whoever generated it.
- The upload waits in the gateway for an hour for its session (`data/seed-uploads/`, at most eight at once), under a
  token only the Administrator who uploaded it can use.
- Routes: `POST /api/sessions/new/seed` (the upload, answered with what was found), `CreateSessionRequest.seed` (its
  token instead of YAMLs).

### 0.19.7: A playthrough that doesn't give up
- **A spoiler log's playthrough when Archipelago can't reduce it.** Archipelago works the playthrough out as the
  spheres every progression item is collected in, then reduces them to the items the goals need. That second step
  ends with "Not all required items reachable" when a world's logic contradicts itself, and no spoiler log was
  written: the session stayed at level 1. `Generate.py` has no option for it. The later levels now run through
  `archipelago/spoiler_run.py`, which wraps that one function while it runs (none of Archipelago's files change) and
  falls back to the unreduced spheres: every progression item in the order it can be collected, with the paths at
  level 3. The seed was beatable before (Archipelago checks that when it generates) and stays as it is.
- **The spoiler page says so** above such a playthrough (the log's last line marks it; that game's steps may be off),
  and the run's log names Archipelago's error.
- **A stopped spoiler run is tried again** with *Try again* under its reason (Session-Managers and Administrators;
  `POST …/manage/spoiler-run/retry`): after a new version of the gateway, as here, or of an APWorld.
- **Randogeddon Bridge's files say their version**: `randogeddon-bridge-<version>-<os>-<arch>` (`.exe` on Windows),
  so a downloaded file tells which one it is; the checksums name the same files. A bridge from before asks for its
  update by the old name and gets the build for its system.
- Planned (in the TODO): Phase 20, a hosted Minecraft Dig server reached through the bridge.

### 0.19.6: The room asks for the password
- **Clients that send a password only when the room asks for one** (Brotato's sends an empty one otherwise) were
  refused with `InvalidPassword`: MultiServer has no room password here, so its `RoomInfo` said `password: false`.
  The gateway now says `password: true` in the `RoomInfo` it passes on to a client; every slot has a password, and
  that field is where clients send it. Nothing else of the frame changes.
- **Links in the live feed**: a DeathLink (who died, and the cause if their game says one) and a TrapLink (who set
  off which trap). The tracker's connection has the two tags, so MultiServer passes those `Bounced` packets on to it;
  the texts are the sending client's own, cut and without control characters. Who it came from is the slot if the
  client names one. A new filter *Links*; RingLink isn't shown (one event per ring).
- **The live feed in pages, with a search**: 50 events at a time, the arrows under them go to older and newer ones
  (on the first page new events come in on top, on another one they stay put), and a search over the events' names
  and texts beside the filters, which stay.
- `scripts/deploy.sh --publish`: builds and pushes the images, and for a release the bridge's builds and the GitHub
  release, without touching the server (nothing copied there, its running tag not switched). A merged version is
  published this way from now on; the live server is updated only when asked for.

### 0.19.5: The generation's settings per session
- **In the new session's form**, with the options: *YAMLs may plan* (Archipelago's plando modules: *bosses*,
  *connections*, *texts*, *items*; its own default, all but the items, is ticked), *No progression balancing*, and
  *Race mode*. They are the session's options like the others, and go to `Generate.py` as arguments: `--plando` with
  the modules' known names only (always said, also for none), `--skip_prog_balancing`, `--race`. No raw `host.yaml`,
  and no file or program path comes from the form.
- **A seed keeps its settings** for its spoiler log's later levels (`stages.json`), which are generated with them
  again.
- **Race mode** generates with a random source nobody can reproduce and without a spoiler log (`--spoiler 0`): its
  session has none (the spoiler page says so), nothing runs in the background after it, and hints and cheats work
  without it.
- **A slot's connections on the dashboard**: a slot with more than one game client connected says how many
  (*2 connections*); Archipelago, the gateway and the bridge all take several per slot. A tracker or text client
  leaving a slot is no longer counted as a game leaving it (a `Part` has no tags, but its text ends with them).
- **The slots stay where they are**: a session's slots and their players are read by name (the database gave them in
  no order of its own, so a person's slots changed places from one look to the next), and the dashboard's *Sort*
  orders each person's slots as it orders the people: by name, by progress or by last activity, equal ones by name.
- Found on the way (in the TODO): why Skyward Sword HD doesn't generate here. Its APWorld wants the extracted game on
  the generating machine and falls back to an item pool with one *Progressive Loftwing* where its logic needs two; a
  bug of that APWorld, with a workaround in the YAML. And: no ROM is uploaded to the server (the plan to keep them
  encrypted is dropped; undecided).

### 0.19.4: Generations at once
- **Several generations run at once:** as many as the gateway has cores, less one (the server: three), or as many
  as `GENERATIONS_AT_ONCE` says. New sessions' seeds and the spoiler logs' later levels share these places; the
  spoiler logs' runs leave one free for a new session (where there is more than one). A session beyond that waits,
  as before.
- **A spoiler's level-3 run is paused for a new session** when no place is free (`SIGSTOP`; the panel says
  *paused*), and goes on where it was once there is room (`SIGCONT`); if pausing doesn't hold, it ends and starts
  over later. A level-2 run is never paused: the new session waits for it. The time a run is paused doesn't count
  towards its limit.
- **The APWorlds while generations run:** they share the release's `custom_worlds/` as it is; a change of the library
  (an upload, a removal) waits for the seeds and level-2 runs that are going, and holds off new ones until it is
  done; level-3 runs end for it and start over after.
- **Memory:** each generation needs its own (a big multiworld: gigabytes), and a paused one keeps its. One that is
  killed (exit code 137) says so: "probably out of memory; fewer generations at once need less".
- **No *Spoiler log* button and no *Cheats* (or support) section** for a session until its spoiler log has its
  playthrough (they answered "still being worked out" before).

### 0.19.3: The spoiler log in stages
- **A session starts sooner:** its seed is generated with a spoiler log without the playthrough (Archipelago's level
  1), which skips the longest part of a big multiworld's generation; the session's game server starts right after.
- **The rest in the background:** the gateway generates the seed once more with the same seed number and inputs, only
  its spoiler log (`--spoiler_only`): level 2 with the playthrough, then level 3 with the paths to every item. What it
  was generated from is kept in the session's `generation/` (its YAMLs, the seed number, the APWorlds of its games by
  their SHA-256, `stages.json`), so it goes on after a gateway restart; an APWorld changed since stops it. A later level
  replaces the earlier one only when it places every item the same as the seed (`spoiler.txt` next to the seed);
  otherwise it stays, and the panel says why.
- **One run at a time**, and none while a new session waits for its generation; a level-3 run makes way for one (and
  for a change of the APWorlds) and starts over after. Several generations at once come with 0.19.4.
- **The spoiler log, the hints and the cheats wait for level 2** ("still being worked out"); with a spoiler that
  couldn't be completed they go on with the seed's.
- **On the dashboard** the session's top panel shows *spoiler log being completed* with a working symbol, for
  everyone; **in the panel** a *Spoiler log* section (Session-Managers, Administrators, support) says its level, the
  run going now with Generate.py's share of a core (or that it waits for its turn), why it stopped, and the run's log.
- Sessions from before have their complete spoiler log; nothing changes for them.

### 0.19.2: It isn't stuck
- Under the generation's bar two pills: how long it runs since its start (each second), and what it does: *working*
  (with Generate.py's share of one core, which the gateway measures each time the page asks: a working fill or
  playthrough keeps one busy), *waiting for its turn*, or *no response* once it has used next to no CPU for a
  minute. So a long stage that says nothing (the playthrough) shows that it works.
- The texts: the fill no longer "takes the longest"; the playthrough does in a big multiworld (with Archipelago's
  spoiler level 3, its paths to every item), and says nothing meanwhile. The bar's shares fit that: the fill 30 to
  50 %, the output 58 to 66 %, the playthrough from 66 %.
- Tried for what comes next: with the same `--seed` and inputs, `--spoiler_only` at levels 2 and 3 gives the same
  seed name and every placement the same as the seed's (the demo's 1,934 locations). In the TODO: the spoiler log in
  stages for every generation (0.19.3), and several generations at once (0.19.4).

### 0.19.1: Back to the generation's bar
- *Generate and start* is at the new session form's end; the form shrinks to the generation's bar then, which left
  the page far below it. Now the page goes back up to the bar (also when it comes back after a reload).
- Found out: Archipelago 0.6.8 reads an APWorld's `requirements.txt` only from a world folder under `worlds/`, never
  from a zipped `.apworld`, so the Launcher installs no packages for one either. And the gateway runs on Linux, whose
  file names are case-sensitive: an APWorld that imports `.Items` for its `items.py` loads in the Launcher on Windows
  only. Both in the TODO, for what the failed generation's log will tell.

## Phase 18: Code Optimization
Measured first, then made leaner where it paid: the web files compressed when built, one look per session for all
its streams, the gateway image 40 % smaller and the builds cached, the code split and merged along what belongs
together (every module, the web app in packages). Asked for on the way: game servers without a port pool, that give
up after crashing again and again and sleep without players; generation in the background with its log and a
*Cancel*; tips that stay and can be copied; invited players' addresses kept to Administrators; the APWorlds'
versions. Updating: nothing to do (`AP_PORTS` is gone from the environment since 0.18.9; `GENERATION_MINUTES` is
new and optional).

### 0.18.18: The Archipelago section
- The panel's *APWorlds* is *Archipelago* now: the gateway's releases as pills under *Supported versions* (the one
  picked shows its games; there is one now, 0.6.8), then *Supported games* one level higher: the bundled games and
  the custom ones together by name, each custom one as its APWorld's chip (its game and version, its file name and
  size in its tip), *bundled* / *custom games* (was "from an APWorld") to show only those. *upload apworld*, *remove*
  and the search sit on that heading's row. The separate list of the uploaded files is gone: an APWorld that brings
  no game shows among the games (red), and the removal picks from the chips.

### 0.18.17: Phase 18 done
- TODO: Phase 18 done; Phase 19 (*Generating Like the Launcher*) to come.

### 0.18.16: An APWorld's version in the library
- The library shows each file as its game and its version, e.g. *Anno 1800 – 1.5.1* (was: the file name, its games
  and its size). The gateway reads them from the APWorld's `archipelago.json` (Archipelago 0.6 and newer: 27 of the
  30 on the live server have one) without loading it; an APWorld without one shows its game without a version.
- Its tip: the file name and size, the oldest Archipelago release it needs (`minimum_ap_version`), where it was
  copied from, or that it brings no game.

### 0.18.15: Tips to copy, invited players' addresses kept to Administrators
- **A tip's text can be selected and copied:** a tip is a window of its own with its own selection (and stays while
  the pointer is on it, since 0.18.12), so its text can be marked and copied with Ctrl+C. A long message (an APWorld's
  error on its pill: Archipelago's exception) has a *Copy* button above it.
- **An invited player's address only for Administrators:** in a session an invited player was called by the part of
  their address before the `@` until they signed up (shown on the dashboard and in the panel to everyone); now by the
  name suggested for them, or `Invited-` and their account ID's start. Signing up gives them their own name in the
  running sessions, as before. Sessions made before keep the old name until then.
- A session's view in the Management panel listed every account, the invited ones with their address, also to its
  Session-Managers; now only to Administrators (Session-Managers get the active accounts by name, as for a new
  session).

### 0.18.14: The audit's leftovers
- **One dialog frame:** a person's and a world's dialog on the dashboard, and an account's and a session person's panel
  in the Management panel, in one `DialogCard` (the card, its header with the close button, a divider, the body),
  with `DialogSection` and `TwoColumns`; the panels now fit the window's height and scroll inside.
- **One pill for people:** an account's pill (users) and a session person's (people) are one `AvatarPill`; the
  people's is 1 dp taller now.
- **The build files:** Java 25, the sources only in `src/main/kotlin` and the main class are set once in the root
  build for the tracker, the gateway and the database module, not in each.
- **The text fields:** `FilterField` and `NumberField` on the state-based `BasicTextField` (the old one is deprecated);
  the number field takes only digits, as before. The web app's last two deprecation warnings are gone.
- Screenshots of eight pages before and after: the same, pixel for pixel (but for a random session ID).
- To do, asked for (Phase 19, *Generating Like the Launcher*): why a seed the Launcher generates didn't here, an
  APWorld's packages installed on its upload, generator settings per session, ROMs and tools kept encrypted, a
  session from a seed generated elsewhere.

### 0.18.13: How the sessions' game servers work, explained
- The README's *Sessions and their game servers*: how a session is created (a diagram from the form to its game
  server: the tracker's checks, `Generate.py` in the gateway, one at a time, the setup, the start) and a game
  server's life (a diagram of its states: starting, running, asleep, crashed, given up, stopped), which of them count
  towards *Game servers at once*, and what a gateway start does. A seed isn't made by a game server; game servers
  only run while they are needed (since 0.18.10).
- The README's first diagram shows the player's PC with Randogeddon Bridge (optional: a game without TLS connects to it
  on `localhost`, it takes the connection on over `wss://` and logs in to the tracker with its key).
- The panel's count says that a sleeping session doesn't take a game server either.
- The TODO's "gateway on demand" set right: what was asked for were the game servers, done in 0.18.10.

### 0.18.12: Generation in the background, with its log; tips that stay
- **A new session generates in the background:** the page's request answered only when the session was there (up
  to `GENERATION_MINUTES`), and closing the page lost what came after the generation (the session's setup, its
  start). Now the tracker checks the form at once and runs the generation and the setup as a job of its own; the
  page follows it (and finds it again after a reload) and shows the session, the failure, or that it was cancelled.
- **Cancel:** while it generates (also while it waits for its turn), *Cancel* ends Generate.py on the gateway; its ID
  is free again. Too late once the seed is there (it is being set up then).
- **Generate.py's output under the bar:** its whole output (the last 20,000 lines), newest on top, 300 lines a page
  with a search (as the spoiler log's: the lines found counted and marked, Enter goes from one to the next). It
  starts with what ran: the directory and the command (`python Generate.py --player_files_path … --outputpath …`) and
  the YAMLs. A failed generation's log stays (an hour) and can be downloaded, with the command.
- **The game server's log too:** the panel keeps its last 10,000 lines (was 2,000) and shows them the same way, a page
  at a time and searchable; a browser lagged with thousands of lines at once.
- **Tips stay while the pointer is on them:** a tip with no room below came up under the pointer, which closed it,
  which showed it again, without end. A tip now stays while the pointer is on it or on what it explains (a moment's
  grace for the way between). The APWorld pills' errors (Archipelago's message) are as wide as the window allows.
- The routes: `POST /api/sessions` answers 202 with the creation; `GET /api/sessions/new/creation[/{id}]`, its
  `/log?since=` and `/log.txt`, `DELETE` to cancel (or forget one that ended); the creator's and Administrators'.

### 0.18.11: The web app's shared helpers, and the browser's own menu
- **One request helper:** the web app's requests go through `call` (with the answer's serializer instead of a decode
  of its own at each of 56 places) and `load` (for what is shown when it is there: the session list, a player's
  details, the connection, the slot files); the panel's game server actions use `post` instead of their own error
  handling. The requests that look at a status themselves (a share link that is gone, the login's check, the spoiler
  log) stay as they are.
- **The page frame and the clock shared:** the dashboard and a share link's page use one `PageFrame` (the page
  colour with the brand's tint, the scrolling column of at most 1400 dp, selectable text) and one `rememberNow` (the
  time, moved on every 30 s and with new data).
- **Components merged:** `Mono` (keys, passwords, file names, a program's output) and `SmallHeading` once in `ui`
  instead of twice; the new session's number fields (hint cost, points per check) use `ui`'s `NumberField`, with its
  arrows. The labels (`Labeled`, `Labelled`, `Label`) stay three: a row with a tip, a column that wraps on a phone, a
  fixed width beside a value.
- **The browser's own menu on a right click:** the app took the right click (Compose cancels the browser's menu to
  show its own, which had only *Copy*); now the browser shows its menu everywhere, and the app none of its own, also
  not as the toolbar over a selection. Ctrl+C still copies a selection.
- The browser interop is opted into once for the module (`ExperimentalWasmJsInterop`, with `-Xexpect-actual-classes`):
  the web app's 58 compiler warnings are 2 (the old `BasicTextField`, a migration of its own).
- Screenshots of eight pages before and after: the same, pixel for pixel (but for a random session ID and a button's
  fade).

### 0.18.10: Game servers that sleep
- **A game server nobody plays on goes to sleep:** after *Sleep after (minutes)* without a player (a setting under
  *Users*, beside the game servers at once; default 30, 0: never) it saves and stops (*asleep* in the panel). Players
  are game clients in a slot through the gateway's relay; the tracker doesn't count, nor do hosted slots (a game that
  runs as a server of its own would keep it up for good).
- **And starts when a player connects:** the gateway starts a sleeping game server for the first player who connects
  (if fewer run than may at once; else "Every game server is in use"), who waits meanwhile. One stopped by hand, one
  that gave up and a finished session's don't start this way.
- **The gateway starts none at its start:** every session sleeps until its first player (after a deploy too), so a
  deploy or a reboot doesn't start them all at once.
- **Keep running without players:** an Administrator's switch in a session's *State and Control*, for a game that
  only connects now and then (`sessions.keep_running`, migration V23).
- The dashboard says the game server sleeps and starts when a player connects, instead of a connection error.
- The gateway's database role may read the sessions' hosted slots now (to tell them from players).

### 0.18.9: Game servers without a port pool, and no crash loop
- **No port pool:** a game server takes a free port of the gateway's container when it starts (its last one again if
  free). The four ports of `AP_PORTS` (38300–38303) were the only limit of how many ran at once, but game clients come
  through the gateway's relay (`wss://`, the bridge for the others), so the ports are the gateway's own; `AP_PORTS` is
  gone.
- **How many run at once is a setting:** *Game servers at once* under *Users*, beside *Sessions per user* (default 8,
  empty: no limit; each one needs a few hundred MB of the gateway's memory). The tracker asks it before a session is
  created, before a game server starts or restarts from the panel and when a session is reopened (until now that
  relied on the gateway's "no free port"); the gateway keeps to it too, also at its own start (it reads the setting:
  its database role may read `app_settings` now).
- **A game server that keeps crashing gives up:** it was started again every 30 s without end and kept its place.
  After 5 crashes in a row, each within a minute of its start, it stays down (*gave up*, with its last exit code and
  its log) and doesn't count as running; *Start* tries again.
- **A generation may take 60 minutes** (was 10), set with `GENERATION_MINUTES`; one that takes longer says so ("took
  longer than 60 minutes and was stopped") instead of guessing at a game or a YAML. Generation stays on one core:
  Archipelago's fill is single-threaded Python and has no option to use more, so a big multiworld (a session of 23
  slots with 29,791 items was stopped at 10 minutes, 4,000 of 5,086 progression items placed) takes long.

### 0.18.8: The web app in packages by area
- **The web app's 26 files of one package are 45 files in six:** `app` (the start, the app's shell and routing, the
  platform, texts, fonts), `ui` (the theme and the shared components: surfaces, icons, tips, inputs, the scrollbar),
  `api` (the repositories of the dashboard, the panel and the share link, the requests' answers, the login's watch),
  `dashboard` (header, overview, players, hints, feed, the dialogs, the connection, the spoiler log, past sessions,
  the share link's page), `manage` (the Management panel: the running session, a new session, past sessions, the
  session switch, APWorlds, cheats, users, people) and `account` (settings, the own account, bridge keys, two-factor
  login, appearance).
- **The big files split by part:** `AdminSession.kt` (1,318 lines), `Dashboard.kt` (1,310), `Components.kt` (948),
  `Settings.kt`, `Admin.kt` and `Watch.kt`. Every declaration moved as it was; 24 private ones that the parts share
  are `internal` now.
- The pages look as before: 16 screenshots (8 pages in English and German) before and after are the same to the pixel,
  but for the breathing *New session* button and the suggested session ID, which is random.

### 0.18.7: The server's big files by part
- **`SessionState.kt` (789 lines):** the model (`ClientStatus`, `SlotType`, `NetworkItem`, `Player` and their
  constants) is in `state/Model.kt`, the lenient JSON accessors of the Archipelago packets in `util/Json.kt` (the login,
  the tracker, the archives and the room file use them too); the state keeps its own private parts (706 lines).
- **`LoginRoutes.kt`** keeps the login's routes; the guards (`RequireLogin`, `RequireBridgeKey`,
  `RequireSessionMember`, `RequireAdministrator`, `RequireSessionRole`, the logged-in user) are in `LoginGuards.kt`.
- **`PastRoutes.kt`** keeps the session routes; the live streams' `follow`, `OpenStreams` and their times are in
  `Streams.kt`.
- `SingleSignOn.createLink` is gone (nothing used it; the CLI makes its links itself), and the server's two compiler
  warnings are fixed (safe calls on a value that can't be null).

### 0.18.6: The stores in files by area
- **`Stores.kt` (1,166 lines, 33 types) split:** the accounts' stores (users, two-factor login, invitations, address
  changes, logins and login links) in `AccountStores.kt`, the sessions' (session time, setup, the sessions, slot
  passwords, finishing, the cheat log, roles, share links) in `SessionStores.kt`, the settings in `AppSettingsStore.kt`.
  Every declaration moved as it was; `StoresTest.kt` stays one file (most of its tests cover both).

### 0.18.5: `shared` in files by area
- **`Api.kt` (928 lines, 77 types) split by area:** `Api.kt` keeps the JSON format, the session IDs, `Me`, `ApiError`
  and `Versions`; the dashboard's summary is in `Summary.kt`, the connection in `Connection.kt`, the game server in
  `GameServer.kt`, the panel's sessions and their generation in `Sessions.kt`, and `Spoiler.kt`, `Cheats.kt`,
  `Apworlds.kt`.
- **`Accounts.kt` (429 lines)** keeps the account and its settings; the login's steps are in `Login.kt`, the users'
  administration in `Users.kt`, a session's people, watchers and share link in `SessionPeople.kt`.
- **The texts:** `GeneralTexts.kt` held the APWorlds' and the user administration's texts: now `ApworldTexts.kt` and
  `UserAdminTexts.kt`; the session tips have `SessionTipsTexts.kt` of their own.
- Nothing else changed: every declaration moved as it was (the same package, so the same names everywhere).

### 0.18.4: A slimmer gateway image, a build that redoes less
- **The gateway image is 725 MB instead of 1.2 GB:** Archipelago is installed in a stage of its own with git and the
  compilers it needs for that, and the image takes only the result (the installed packages, Archipelago's directory,
  `libtk8.6`).
- **The image build keeps Gradle's task results** (`--build-cache`, in its cache mount), the native images and the web
  app's optimisation included: a change to the tracker's code builds in 149 s instead of 416 s (the gateway and the
  web app come from the cache), one to the web app's in 193 s.
- **Fixed:** while a new session waited for its game server's `room.json`, its tracker connection ended with an
  exception (Kotlin's `error()` was called instead of the tracker's own) and was only started again 10 s later; the
  log got a stack trace each time.
- **`scripts/measure.sh`** times the first screen by the page's own signs (the splash gone, the session's state come,
  two frames after): screenshots, taken while the page starts, held its start up in headless Chromium.

### 0.18.3: One look per session, not per viewer
- **The live streams share one look at their session:** every open dashboard polled its session every 750 ms by
  itself, so the work grew with the viewers; now one poller per watched session tells all its streams
  (`LiveSession.changes`). With 60 viewers the tracker uses 0.8 % CPU instead of 5.7 %. A stream still ends at its
  next check (30 s) once its login or its right to the session does.
- **The options are decoded once** per setup (they were on every look of every stream), and **the share link's
  summary is serialized once** per change, not once per viewer.
- **The panel's overview** uses the sessions as they were read last (every 10 s, and right after each change made in
  the panel) and no longer reads them all again and asks the gateway twice per request: 2.9 ms instead of 10.7 ms
  (every open panel asks every 15 s). The gateway's releases are kept 30 s.
- **`scripts/measure.sh`** waits out the share link's rate limit (60 a minute) before timing the first screen.

### 0.18.2: The web app compressed once
- **The web app's files are compressed when the image is built**, Brotli (level 11) and gzip (level 9) beside
  each file, and the tracker sends them as they are (Ktor's `preCompressed`); before, it gzipped the 12 MB for every
  visitor. A browser over HTTPS takes Brotli: **3.4 MB instead of 4.5 MB to load**. The tracker's work per page load
  is about 10 ms instead of 207 ms. A local build without those files is compressed per request as before.
- **`scripts/measure.sh`** times the dashboard until it is drawn (the page's title showing), not until the loading
  splash goes: that can be before anything is drawn, so 0.18.1's 243 ms were wrong (it is about 1.2 s locally). The
  files' table also shows how long the tracker takes for each.

### 0.18.1: The baseline and the audit
- **`scripts/measure.sh`** measures a local stack with the demo's sessions running: the page's files as sent (plain,
  gzip, Brotli), the images and what is in them, the containers' memory and CPU idle and with viewers' live streams
  open, the API's answer times, and how long the first screen takes in headless Chromium; `--build` also times a
  build from nothing and one without changes. `demo/admin.py measure` opens the viewers (up to 15 by the login, the
  rest by the share link: one login holds at most 20 streams).
- **`.docs/MEASUREMENTS.md`:** 0.17.2's numbers and what the audit found: streams that each poll their session, the
  web files gzipped per request, little copied code but much repeated in kind (53 places decode an answer
  themselves), one unused function, 63 compiler warnings, and a verdict for every code file (keep, split, move or
  merge). Phase 18's next steps follow from it.

## Phase 17: A Repository That Can Be Shown
What told too much about the servers left the repository and its history: their docs and setup, their addresses and
names, the security scans' findings. The history was rewritten and signed again, and the GitHub repository made anew,
so the pull requests start again at #1. Updating: a clone made before has another history; clone again (a fetch
won't fit it). The Maven mirror, the image registry and the bridge's registry are each machine's settings now (see
the README: `randogeddon.maven`, `~/.config/randogeddon/deploy.env`).

### 0.17.2: Phase 17 done
- **The history without the servers' details:** every commit cleaned like the tree in 0.17.1 and signed again, every
  tag too, on the same dates. The GitHub repository was made anew with it; its pre-releases were made again (their
  notes cleaned).
- TODO: Phase 17 done; Phase 18 (*Code Optimization*).

### 0.17.1: The servers' details out of the tree
- **Moved out of the repository** (into one of their own, not on GitHub): `.docs/INFRA.md`, `.docs/MAIL_MIGRATION.md`,
  `infra/` (Authentik's and Artifact Keeper's compose files and setup scripts, the ports' firewall, the stack dump),
  the README's specifics of the live server, and the two security scans' findings (`.docs/TODO.md` keeps a summary
  line each). Randogeddon's own backup units moved to `scripts/`.
- **No other addresses in the text:** the servers' internal addresses, the VMs' IDs, host paths, service accounts and
  every domain but `randogeddon.eratiem.net` are placeholders now (`example.org`, `<internal-ip>`, *the live VM*, …).
- **The Maven mirror is a setting:** `randogeddon.maven` (Gradle property) or `RANDOGEDDON_MAVEN` (environment,
  `.env` for the Docker build); without one, Maven Central, the Gradle plugin portal and Google directly. Its login
  still comes from the Gradle init script, which can find it by its URL or its name (`randogeddon.maven.name`,
  `RANDOGEDDON_MAVEN_NAME`, default `mirror`).
- **`scripts/deploy.sh`** takes `IMAGES`, `RANDOGEDDON_MAVEN` and `BRIDGE_REGISTRY` from the environment or from
  `~/.config/randogeddon/deploy.env`; `IMAGES` has no default any more. `scripts/publish-bridge.sh` publishes to
  Artifact Keeper only with `BRIDGE_REGISTRY`.
- **The mails' sender** is `MAIL_FROM`, else the login's address (`MAIL_USER`); it had a fixed default.

## Phase 16: English and German Side by Side
Every page read and looked through in English and German, and what came up with it: support and the Management
panel, names with rules and a filter for unwanted words, a new session's progress, single hints, APWorld uploads as
pills, a spoiler log that stays usable, and a zoom that keeps what is going on. Updating: migration V22 runs by itself
(whether Authentik calls an address verified; known from the next login); the web app is built with Compose
Multiplatform 1.13.0-beta02 until 1.13.0 is stable.

### 0.16.15: Phase 16 done
- TODO: Phase 16's points as they were done, step by step, and the open one (Compose Multiplatform 1.13.0 once it
  is stable); Phase 16 done. Phase 17's new repository is `infra`.

### 0.16.14: Ctrl and the mouse wheel zoom, and which copy a hint names
- **Ctrl and the mouse wheel zoom the page again** (and a trackpad's pinch): the app's canvas took the wheel and
  scrolled with it, so the browser didn't zoom. The page keeps it from the app now; Ctrl+ and Ctrl− zoomed already.
- **Which copy an item hint names:** a switch beside it (with the spoiler log's placements known): *Random copy*,
  or *Next in playthrough*, the copy in the earliest sphere of the playthrough (a copy it doesn't need after those).
  Remembered in the browser.
- **Font mode:** its first choice is *Font's default* (German *Standard der Schrift*), not *Theme's default*: it is
  how the chosen font is meant to be used, whichever theme it belongs to.

### 0.16.13: Zoom without a reload
- **A browser zoom no longer reloads the page.** Compose 1.12 kept the pixel density it started with and drew
  blurry after a zoom, so the page reloaded itself, which kept the login but lost what was going on: a new session's
  YAMLs and form, a running generation's bar, an upload. It reloaded also when the window went to a screen with
  other scaling, which can come with resizing it. Compose 1.13 draws at the new density by itself
  ([CMP-7230](https://youtrack.jetbrains.com/issue/CMP-7230)), so the page's own handling is gone.
- **Compose Multiplatform 1.13.0-beta02** (was 1.12.2), a beta until 1.13.0 is stable.

### 0.16.12: The ID that is generated, and an upload without its line
- **While a new session generated, it showed another ID:** the panel loads its overview again every 15 seconds,
  each time with a new suggested ID, and the form took it over (the session itself got the one that was sent, and the
  bar asked about the wrong one). The form keeps its first suggestion now, and the generating view the ID it sent.
- **An APWorld upload no longer has its "Uploading …" line** above the pills; a try or a removal still says what it
  does.

### 0.16.11: An upload's errors in its pill
- **Why an APWorld was refused is in its pill's tip** (on hover, a long press on a phone), like what an added one
  brought; it is no longer written out below the pills too.

### 0.16.10: APWorld uploads as pills
- **Each uploaded APWorld is a pill** that fills like a bar: grey while its turn hasn't come, yellow while it is
  uploaded and Archipelago checks it (the bar creeps on, the server says nothing until it is done) or while it waits
  for the answer to *Replace*, then green once added, red if not, grey if skipped. Its tip says which games it
  brought or why not; what went wrong is also written out below them.
- **Cleared when done:** *Clear* takes away what the last upload, try or removal said (until now it stayed until the
  page was loaded again); after an upload without trouble the pills go by themselves after 10 seconds.

### 0.16.9: Newer lists for the word filter
- **The unwanted words come from three lists now**, about 9,600 words of 17 languages (was 1,400 of 15, from
  LDNOOBW alone, last changed in 2024): LDNOOBW, BadWords (FlacSy, MIT, updated November 2026; it brings Indonesian
  and Romanian and many more forms) and Google Profanity Words (Coffee & Fun, MIT, updated June 2026).
- **Ordinary words are taken out by checking**, not only by hand: against the 50,000 commonest words of each of
  the 17 languages and 4,900 first names. A word only BadWords has goes if it is common anywhere (it has many
  ordinary ones); the others' common words were looked through and the ordinary ones listed. A word found inside a
  common word or a name counts only as a whole part.
- `scripts/unwanted_words.py` downloads into `output/unwanted-words/` and keeps it for the next run.

### 0.16.8: A hint names one place
- **A hint for an item names one copy** of it, at random, that is neither checked nor hinted yet (`/hint_location`
  where it lies, from the spoiler log's placements). Archipelago's `/hint` named every copy at once (all
  Progressive Swords); it is only used for a seed whose spoiler log has no placements. Once every copy is checked or
  hinted, the hint is refused, and the item leaves the hint's list.
- **A location that is hinted already** isn't offered for a hint (and is refused).
- **The hints' state has an ⓘ** in the table's last column (beside the filter on a narrow screen): what *open*,
  *priority*, *no priority*, *avoid* and *found* mean.

### 0.16.7: More to see while a session is made, less for a locked slot
- **A locked slot's patch files aren't shown** under *Your connection* (they wait for the slot's key, like its
  password), and aren't handed out to its players until then. Session-Managers and Administrators still get every
  slot's in the panel.
- **The new session's progress says more:** four steps above the bar (*Check*, *Generate*, *Set up*, *Start*; done
  ones with a check mark), and new states: checking the YAMLs, players and locks; waiting while another session
  generates (the gateway generates one at a time); setting up the slots, players and passwords; starting the game
  server. The seed's own stages keep the bar's middle.

### 0.16.6: A new session, out of the way while it generates
- **While a new session generates** the form is gone: only the session's ID, the stage and the bar show. A failed
  generation brings the form back as it was, with the reason.
- **A session ID is checked as it is typed** (`GET /api/sessions/new/id?id=`): a word nobody wants in one, or an ID
  that is taken, shows beside the field a moment after the last key, not only when *Generate and start* is clicked.

### 0.16.5: The Management panel, and names with rules
- **User names:** only letters without accents, digits and `_` (so far almost anything but `@`). Names that pass for
  Randogeddon or someone with a say here are reserved (*Admin*, *Administrator*, *Moderator*, *Support*, *System*,
  *Root*, … in any case). Existing names stay; a name from Authentik is made to fit (`Ada Lovelace`: `Ada_Lovelace`),
  and a second account of a name gets `_2` instead of ` 2`.
- **Unwanted words** in user names and session IDs: a list of about 1,400 swear words and slurs of 15 languages
  (LDNOOBW, CC BY 4.0, curated by `scripts/unwanted_words.py`), read part by part, written together, behind digits
  (`sh1t`), and for longer words inside others. Checked against the commonest English and German words and 4,900
  first names for false hits. Session IDs keep their characters (lowercase letters, digits, `-`).
- **The panel is the *Management panel*** (German: *Verwaltung*), for Administrators with *· Administrator* after it.
- **Support helps there only:** the *Support* section is no longer on the dashboard as well.
- **Support adds watchers and makes the share link** (so far only players and Session-Managers could): *Watchers*
  is in the panel under a supported session's *Support* section.
- **Its *Support* section flickered:** every 15 seconds the panel forgot what it had loaded for a session someone
  only supports (it isn't among the ones loaded ahead), so the section emptied and filled again.

### 0.16.4: Support in the panel, and a spoiler log that stays usable
- **Support sees its sessions in the panel:** the sessions someone supports are in the panel's switch, each with how
  it stands, its dashboard and the *Support* section (give an item, check a location, a free hint; the spoiler
  log). Before, the panel only had the sessions someone manages, so support found nothing there.
- **Spoiler log:**
  - A new session showed steps as checked that weren't: a step counted as checked when its location wasn't among
    the open ones, and the tracker has none yet right after a session starts. Now a step is checked when its
    location is among the checked ones.
  - The fold arrows and the check mark are drawn: the themes' fonts don't all have those characters.
  - Both searches have an × to empty them.
  - The full log is no longer sent and shown whole (a big seed's froze the browser): 300 lines at a time, with
    arrows to the lines before and after (`GET …/spoiler/log`).
  - Its search runs on the server (`GET …/spoiler/search`): the page asks half a second after the last key, and the
    server gives each user one search at a time, half a second apart (another one meanwhile: 429). The arrows jump
    to the page a finding is on.
- **`RelayTest` waits for what it checks** instead of a fixed moment: the frames reach the stand-in MultiServer
  through two sockets, a moment after the client sent them. On a busy build machine (both native images compiling)
  the check came first and the image build failed (seen once in a demo's build). The time a connection gets for
  its Connect is longer in its test for the same reason.

### 0.16.3: The pages, after a look through them
- **Main page without a session:** the top card says *No session* and nothing more; below it, for who may create
  one, a bubble *New session?* points at the panel's button. A click on *RANDOGEDDON* goes to the main page.
- **Your account:** with single sign-on the first panel is *Managed via Authentik* (a line, the button, the *Local
  ID*). The language, the mode (with icons) and the font mode are buttons side by side instead of dropdowns; the
  font stays a dropdown; each has its label. *Randogeddon's* over the own themes.
- **Users' panel:** the login reads *SSO (Authentik)* for Administrators with single sign-on (not "password" too:
  theirs is one nobody knows); *Self-created sessions* and *Self-created sessions limit*, whose empty field shows
  the default and has arrows to step it (the default's field too); a time-out for 1, 3, 6 or 12 hours, 1, 2 or 3
  days, 1 or 2 weeks or 30 days.
- **Admin panel without a running session:** *Session none running* without a dropdown, *No session is running
  right now.*, and the *New session* button breathes. Past sessions and versions with clearer subtitles.
- **The address from Authentik:** a linked account takes the address Authentik has. Before, only a verified one
  was taken, and Authentik calls every address unverified, so an Administrator's account stayed without one.
  Finding an account that isn't linked yet by its address still needs a verified one. Whether Authentik calls the
  address verified is kept (V22, `users.email_verified_by_provider`) and shown in the account's window: *(not
  verified in Authentik)*.
- `GET /api/me` says whether the account may create sessions at all (`createsSessions`).

### 0.16.2: A clean first start
- **On a new database the gateway waits** for its login and the tables (up to 2 minutes), which the tracker makes a
  moment after both started. Before, it failed to log in, ended and was started again until they were there: it
  worked, with errors in the log of a fresh install.
- TODO: Phase 17 (what doesn't belong in a public repository moves out of it and its history).

### 0.16.1: The texts, read side by side
- **Every text read in both languages** for short, exact wording and the same words for the same things; about 150
  changed. Among them:
  - English: *Role* (not "part"), *Created* (not "made"), *Collapse*/*Expand* (not "fold"), "no longer works", "must
    start with" (not "starts with") for the rules of names and IDs, "limit 3" for the session limit.
  - German: *Sessions* everywhere (one "Sitzungen" was left), *Personen* (not "Leute"), *Lösungsweg* (not
    "Durchspielweg"), *Limit* (not "Grenze"), *Theme* (not "Design"), *Spielserver* (one "Game-Server" was left),
    *Gehostet*, "nur noch lesbar" (not "schreibgeschützt").
  - Both: "Administrator" where "admin" was left; the new-session form no longer says that new names get a login
    (people without an account are invited); an invited account's deletion date reads as coming, not as done.
- **The font's animation only after a change by hand:** picking a theme or changing the font settings animates the
  letters as before; when the page loads (or the account's choice arrives after the login) the font is just there.
  While a font's files load, the page keeps the font it shows instead of falling back to the original in between.
- Still to do in this phase: the pages looked through by hand in both languages.

## Phase 15: More Polish
What the Administrators missed for the accounts: an account can be timed out, suspended or deleted, and the limit of
running sessions is set per user, counting the sessions someone created. Updating: migrations V20 and V21 run by
themselves; the limit so far (the setting) stays as the default, and existing sessions get their creator from who
first managed them.

### 0.15.2: Sessions per user, per user; Phase 15 done
- **An account's own limit** of running sessions in its panel under *Users* (*Own limit*, *Use the default*); the
  setting so far is the default for all others and moved into the users' card (the *Settings* section is gone).
  `PUT /api/admin/users/{id}/session-limit`; `AdminUser.sessionLimit`, `sessionsRunning`.
- **It counts the sessions someone created** (`sessions.created_by`), not the ones they manage: asked when creating
  and when reopening (for the creator: `CREATOR_AT_LIMIT` if someone else reopens). Becoming Session-Manager of a
  running session is free again. Migration V21 (`users.session_limit`, `sessions.created_by`, filled from who first
  managed each session).
- **Fixed:** a call without a login to a route with a second guard (the admin panel's, a session's) was answered
  with 401 as it should, but threw on the server and wrote a stack trace into the log each time.
- TODO: Phase 15 done.

### 0.15.1: Time out, suspend or delete an account
- **Administrators keep an account out** (its panel under *Users*): *Time out* for 1 hour, 1 day, 7 days or 30 days,
  or *Suspend* until taken back, with an optional reason. The account's logins end at once (bridge keys too), a login
  is refused after the right password with until when and why (`ACCOUNT_TIMED_OUT`, `ACCOUNT_SUSPENDED`), and a mail
  tells them. *Let in again* ends it early. Never an Administrator's account, so never one's own.
- **Delete account** there too (asks once more): as an account deletes itself, told by mail; an invitation is taken
  back.
- **Their sessions go on.** A running session with a Session-Manager who is kept out (or gone) tells its people on
  the dashboard to turn to an Administrator (`Summary.managerUnavailable`).
- `POST` and `DELETE /api/admin/users/{id}/block`, `POST /api/admin/users/{id}/delete`; migration V20
  (`users.blocked_at`, `blocked_until`, `blocked_reason`, `blocked_by`).

## Phase 14: More Themes
More to pick from under *Your account*, *Appearance*: 26 themes in groups (Randogeddon's own, and the ones inspired by
Nintendo's, the PlayStation and the Xbox consoles and by SteamOS, with a trademark notice), each light and dark, and a
font per theme with a font choice of its own; Randogeddon Bridge follows both. Updating: migrations V18 and V19 run by themselves;
nothing to set. A new bridge (0.14.4 or newer) for the font on its page.

### 0.14.6: Where the font shows, for every font
- **Three choices for any font** beside the font under *Appearance*: as the font is made (the default: a display
  font in the headings only, every other in all text), all text, or the headings only. Before, only display fonts
  had a choice, of two. `FontScope`; `PUT /api/account/font` takes `scope`; migration V19 (`users.font_scope` in
  place of `users.font_everywhere`, which it carries over). The bridge's page follows.

### 0.14.5: Letters change the font one by one; Phase 14 done
- **A font change is animated:** the page's letters take the new font one by one, in no order, within about 0.6
  seconds (a font chosen, a theme with another font, or a font that just finished loading). Every text has its own
  order (`letterTurn`).
- TODO: Phase 14 done; the check of English and German side by side is Phase 16 now.

### 0.14.4: The bridge follows the font
- **Randogeddon Bridge's page shows the account's font** as it shows its theme: for all text, or for the headings
  only with a display font. `GET /bridge/info` names the font's files (`BridgeFont`); the bridge fetches them from the
  dashboard itself and serves them on its own page (`/font/regular`, `/font/bold`), so the page still loads nothing
  from elsewhere. Without a font, or if it can't be fetched, the system's font stays. Needs the new bridge (an older
  one keeps the system's font).

### 0.14.3: A font per theme
- **Every theme but the default brings a font** (25 free fonts, SIL OFL or Apache 2.0, their licences beside them):
  a pixel font for NES and SNES, a book serif for Sepia, a monospace for Terminal, and so on (README, *Appearance*).
- **A font choice** beside the mode under *Appearance*: the theme's (the default), Randogeddon's original, or another
  theme's. Display fonts (NES, SNES, PlayStation 2) show in the headings only, or in all text if chosen. Stored with
  the account (`PUT /api/account/font`, migration V18: `users.font`, `users.font_everywhere`).
- **Loaded only when used:** a font's files are fetched when it first shows; until then the original shows. The login
  page keeps the system's font.
- `scripts/fonts.py` makes the font files (cut down to Latin, a regular and a bold each).

### 0.14.2: Four more of Randogeddon's own themes
- **Midnight** (deep indigo and navy, cyan to violet), **Sakura** (soft pink and plum, rose), **Terminal** (green
  phosphor on near-black; in the light, ink on pale green) and **Sepia** (warm paper, brown ink, amber), each light
  and dark.
- TODO: Phase 15 (*More Polish*) with its first two points.

### 0.14.1: Console themes, in groups
- **Eleven more themes inspired by consoles and systems**, each light and dark: NES, SNES, PlayStation, PlayStation 2
  to 5, Xbox, Xbox 360, Xbox Series X|S and SteamOS (their colours only).
- **The themes in groups** under *Your account*, *Appearance*: Randogeddon's own, and the ones inspired by Nintendo's,
  the PlayStation and the Xbox consoles and by SteamOS, each under a small heading (`ThemeGroup`).
- **A trademark notice** under the cards and in the README: these themes are only inspired by the systems'
  colours, the names are their owners' trademarks, no affiliation with Nintendo, Sony, Microsoft or Valve.
- **The accent is readable as text:** `ThemesTest` now also asks that a theme's accent shows on the panel (it is the
  small buttons' text); Ember's light accent was too pale for that and is a darker orange now.

## Phase 13: Polish and Bug Fixes
Everything tested by hand and what came up fixed (the pages, the people of a session, the bridge, the logins), then
two security scans and their findings: the gateway refuses by default and limits what a client may send, limits and
security headers on the tracker, Administrators only through Authentik with a second factor, signed builds of the
bridge, hardened containers and servers. Updating: the migrations up to V17 run by themselves. In `.env`:
`GATEWAY_DB_USER`/`GATEWAY_DB_PASSWORD` and `TRACKER_DB_USER`/`TRACKER_DB_PASSWORD` for the two database roles
(README). Run `infra/auth/setup.py` again in Authentik (the second factor, the logout, the login's limit). On each VM
`infra/ports-firewall.sh` with its service. For signed builds: a key on the build machine (README, *Checking a
download*), and the signers' public keys there for the deploy.

### 0.13.19: The second scan's findings on the servers; Phase 13 done
- **Logs stay small:** every container's log is limited to 5 files of 10 MB (the three compose files).
- **A release is deployed only from a signed tag:** `scripts/deploy.sh` checks the tag's signature first.
- **Authentik** (`infra/auth/setup.py`, the compose file): no login from an address after 10 failed ones there, a
  second factor for Artifact Keeper too, the worker no longer runs as root.
- **Set on the servers:** the three VMs restarted for their new kernel and restart by themselves from now on (03:00
  UTC, only when an update asks for it); Traefik 3.7.19; on the host fail2ban for SSH, no X11 forwarding, its own
  Postfix on loopback only.
- TODO: Phase 13 done.
- By the owner: CAA records (Let's Encrypt only), and the Proxmox firewall restarted, which applied its "pending
  changes".

### 0.13.18: A second security scan; its Medium and the tracker's findings
- **A second scan** (the code since the first, what stays open after a right ends, ways around the limits, the
  servers' logs, versions and settings): one Medium, eleven Low, in `.docs/TODO.md`. Fixed here: the Medium and the
  tracker's four; the servers' are still open.
- **The limit of running sessions per user holds everywhere:** it was only asked when creating a session, so
  finishing, creating and reopening went around it, and so did passing sessions on to another account. Now also when
  reopening (for whoever reopens and for the session's Session-Managers: `ONLY_ADMINISTRATORS_REOPEN`,
  `SESSION_LIMIT`, `MANAGER_AT_LIMIT`) and when someone becomes Session-Manager of a running session. Administrators
  decide freely, as before.
- **Live streams end with the right to them** (checked every 30 seconds, like the login before): a session's stream
  when the part in it is taken, the game server's log when its reader no longer manages the session or logs out, a
  share link's stream when the link is switched off or replaced.
- **The login form shows only the server's words:** why a login through the provider failed comes in a short-lived
  cookie (`randogeddon_login_problem`) to `#/login-problem`; a text in the link is ignored (it showed anything).
- **The tracker's own database role** (`TRACKER_DB_USER`, `TRACKER_DB_PASSWORD`; the compose service
  `database-roles`): the tables' owner and no superuser, so a taken-over tracker runs no programs in the database's
  container. Tried on a throwaway database with live's tables and with the released tracker. The gateway's role is
  made without the attributes only a superuser may name.
- `signing-key.pub`, `SHA256SUMS.sig` are served as plain text.

### 0.13.17: The scan's Low findings
- **The page runs only its own scripts:** index.html's inline scripts are `theme.js` and `page.js`, and the policy is
  `default-src 'self'` with `script-src 'self' 'wasm-unsafe-eval'` (tried in a headless browser: nothing blocked).
- **No passwords in a session's log:** the gateway keeps the game server's lines without them (a refused `Connect`,
  `/option …password`, `/options`). The host's `!admin` password goes in over the console, not on the command line.
  Console lines lose every control character.
- **Recovery codes** are stored as a keyed hash (codes from before work until used or replaced).
- **Per account:** at most 10 bridge keys, 20 logins of a kind (a new one ends the oldest), 20 open live streams.
- **A new e-mail address:** the address so far is told by mail. The provider's callback shows only our own words.
- **Randogeddon Bridge:** the key isn't shown while it is typed (`login < keyfile` instead of `echo`), and the
  downloads have their checksums beside them (`/downloads/bridge/SHA256SUMS`).
- **The bridge's builds are signed, the free way:** their `SHA256SUMS` is signed at the build with an SSH key
  (`SHA256SUMS.sig`, checked with `ssh-keygen -Y verify` against `bridge/signing-key.pub`); the page offers
  *Checksums*, *Signature* and *Key*. The systems' own warnings stay (that would take bought certificates).
- **Containers:** `no-new-privileges` for all, no capabilities and limits for the tracker and the gateway.
- **On the servers:** only the host reaches the VMs' published ports (`infra/ports-firewall.sh`, on the three
  VMs); Traefik writes an access log, checks its backends' certificates (but the three with their own), and its
  dashboard is behind Authentik's login (`infra/auth/setup.py`: the application *Traefik*).
- **Build:** Archipelago is checked against its tag's commit (`AP_COMMIT`), the gateway image gets the newest
  `certifi`, and `scripts/audit.sh` looks the dependencies up for known flaws (Go and JVM clean at its first run).

### 0.13.16: The scan's Medium findings
- **The gateway's Python reaches less.** Archipelago's processes (Generate.py, the game servers, the APWorlds) get only
  what Python needs of the gateway's environment, and the gateway has a database role of its own
  (`GATEWAY_DB_USER`, `GATEWAY_DB_PASSWORD`; made by the tracker at each start): the sessions and the slot passwords to
  read, the session time to count, nothing of the accounts. Its container gets only that login.
- **Requests have a size:** 256 KB by default, 8 MB for a new session's YAMLs, 96 MB for APWorlds; larger ones (or a
  body without a length) are refused before they are read.
- **Creating a session is limited:** one at a time per user (the ones running count against the game servers), at
  most 100 YAML files and 250 slots, the gateway's container at 5 GB (`GATEWAY_MEMORY`).
- **An Administrator reads as one only with a login that may act as one** (two-factor, or through Authentik): every
  session, the spoiler logs, hosted slots' passwords and every slot's files go by that; a bridge key never does. A
  login through Authentik lasts a day instead of 30, so leaving the group there shows within a day.
- **Administrators only through Authentik** (with single sign-on): a CLI link no longer acts as one, and the CLI
  makes no Administrators and no login links then (`--revoke` stays). In Authentik, Randogeddon's application also
  asks for a second factor set up there (`infra/auth/setup.py`, already set on the server).
- **Mails per account:** at most 10 in 10 minutes and 40 a day (invitations, address confirmations).
- **Logins:** the rate limits count an IPv6 /64 as one address; after 10 wrong passwords or two-factor codes within 10
  minutes that account's login waits until the oldest is 10 minutes old.
- **Headers:** every answer carries `Strict-Transport-Security`, `nosniff`, `Referrer-Policy`, `X-Frame-Options` and a
  policy against framing; the API's answers aren't stored. Plain HTTP is redirected to HTTPS (Traefik).
- **Infrastructure:** the host's sshd takes keys only; the database dumps are written for root only.

### 0.13.15: The security scan's findings
- The scan (Fable 5.2) over the server's routes, the gateway, the bridge and the infrastructure: its 27 findings are in
  the to-do list (Phase 13), most severe first. Fixed so far, both in the gateway:
- A client no longer gets into a session without a password under an item link's group name: the gateway refuses every
  `Connect` whose name has no slot password with `InvalidSlot` itself (MultiServer knows the groups as names to connect
  as, and asks them for nothing). `Verdict.Join` tells a passed `Connect` from other frames.
- The gateway gives little to a client that isn't in a slot: frames of at most 1 MiB, on the wire and unpacked (they
  were unlimited), 5 minutes and 32 frames until its `Connect` passes, and at most 1024 connections of everyone waiting
  for theirs (`ConnectionLimits`). How many connections an address holds isn't limited: behind a VPN's or a shared
  gateway's address are many households. A refused name is logged without control characters.
- In front of the gateway, Traefik limits how fast one address opens new connections to the game servers (`rateLimit`
  on the `randogeddon-ap` router: 100 at once, then 2 a second, an IPv6 /64 as one address; 429 past it): set on the
  host, and in the README's example and `docker-compose.traefik.yml`.

### 0.13.14: Open points decided
- The earlier phases' open points looked at again: the only one, which languages follow English and German, is
  decided: those two for now, another one can come later.

### 0.13.13: An ended login shows the login form; folding the playthrough
- A login that ends while the page is open (logged out at Authentik, elsewhere, expired) shows the login form at the
  first request that finds it so (`NOT_LOGGED_IN`, `LoginWatch`), not that request's own error in its field. While the
  form is open, other requests answering the same don't open it again over what is typed.
- The playthrough's *Fold all* / *Unfold all* follows the list: it counts only the spheres shown (with the slot and
  the search), and folding them by hand changes it too. A filter unfolds what it finds as before, but each sphere
  folds by hand then too; without a filter they fold back.
- Authentik's Randogeddon provider has its *Logout URI* (*Back-channel*) now, set on the auth VM; logging out there ends
  the login here.

### 0.13.12: The bridge for hosted slots, on the command line; Authentik logouts; checks to a slot; the playthrough checked; scroll bars
- Randogeddon Bridge offers a session's hosted slots to its Session-Managers and Administrators (who see their
  password), for a hosted game that can't do TLS; its players still don't get them, and a locked slot never.
- A command line for a computer without a browser: `login` (the key from standard input), `slots`, `relay -session
  <id> -slot <name> [-port <port>]` (until Ctrl+C, on a fixed port if wanted) and `logout`.
- The cheat *Check a location* goes either way: *from the slot* (its own location, as before) or *to the slot*: an
  open location of another world holding one of the slot's items, listed with the item (from the spoiler log's
  `Locations:`; `CheatNames.incoming`). Checked there (`/send_location <that world> <location>`), the slot gets it;
  logged as that world's check.
- The spoiler log's playthrough marks the steps already checked (a ✓, as the tracker sees it when the page loads), and
  each sphere says how many of its checks are done (`Sphere.checked`). It reads *Item @ Check* (was an arrow), and a
  search keeps only the steps whose item, check or slot contains the text.
- Scroll bars: the page, the dialogs and the lists show where they are scrolled (a thumb in the theme's colours on the
  right edge, while there is more than fits); the mouse drags it, a click beside it jumps there. The browser shows
  none of its own, since the page is drawn on a canvas.
- A login through Authentik lasts only as long as the Authentik session: Authentik's back-channel logout (`POST
  /api/login/oidc/backchannel`, its signed logout token checked: issuer, client, signature, age, the logout event, no
  nonce, not seen before) ends the logins from the ended session (logged out there, ended by an admin, the person
  deactivated, expired). Migration V17: `login_sessions.provider_session`, the ID token's `sid`. An open dashboard's
  stream checks its login every 30 seconds. `infra/auth/setup.py` sets the provider's *Logout URI* (*Back-channel*).
  Bridge keys stay.
- The bridge quits once its page is closed, or the browser is: the page keeps a WebSocket to it (`/api/alive`), and
  without a page for 10 seconds (a reload comes back in time) it ends with its relays. Started with `-no-browser`, it
  runs until *Quit*.

### 0.13.11: One People
- The panel's *Players* and *People* are one *People*: everyone of the session as a pill with their part. A click opens
  their panel: their slots (game, how far, how it stands), how far they are in the session, played and last active;
  there they get another part (`PUT /api/sessions/{id}/people/{user}`: a player becomes Session-Manager or support
  besides their slots), or theirs is taken (a playing one only plays then).
- A session keeps its last Session-Manager, for Administrators too (`KEEP_MANAGER`): someone else becomes
  Session-Manager first, and the panel says so.
- What was left of *Players* is *Patch files* (every slot's files from the seed), after *People*.

### 0.13.10: Locked slots stay locked, more polish
- A locked slot can't connect until its key is found: the gateway refuses its game client (`SlotLocked`), hosted slots
  too. *Your connection* shows it as locked, without a password, and the bridge doesn't offer it. Migration V16:
  `slot_passwords.locked`, set when the session is created and kept in step by the tracker (a slot opens once its key
  has turned up; the gateway reads it with the passwords, every 10 seconds). No longer on the honour system.
- The generation's bar: asked about three times a second, across the form's whole width; the fill moves it (Fill.py
  counts every 1000 items placed), and balancing the progression is a stage of its own.
- *Your account* shows the selected session on the top card (its progress and its game server's state), as the panel
  does; without one, that none is chosen, as everywhere else.
- Randogeddon Bridge follows a theme, mode or language changed on the dashboard: it asks every 15 seconds (it was every
  minute), and right away when its page is back in view.
- `publish-bridge.sh` names the GitHub repository (`GH_REPO`, `EraTiem-Network/Randogeddon`): the deploy's clone has
  a local path as its remote, so gh couldn't tell where to make v0.13.9's pre-release (made by hand then).

### 0.13.9: Pre-releases, locked worlds that place their own items, the generation's progress
- Every release below 1.0.0 is a GitHub pre-release too, made by the deploy (`scripts/publish-bridge.sh`): its part of
  this file as the notes, no files. The ones before (v0.1.1 to v0.13.8) were made by hand.
- World Locks 1.1.0: a world that places items inside itself before the main fill (A Link Between Worlds its dungeon
  keys, in `pre_fill`) can be locked: the locks go on after every world's `pre_fill`, not before (generating failed
  with "No more spots to place 1 items", as nothing of the locked world was reachable without its key). Updating:
  nothing to do, the gateway image brings it.
- A right click no longer opens Compose's own menu (it had only *Copy*; Ctrl+C still copies a selection).
- *Generate and start* is only active once every slot has a player or is hosted; the form names the ones still
  without. The server refuses such a session too (`SLOT_WITHOUT_PLAYER`).
- While it generates, a bar shows the stage and about how far it is (e.g. *Filling the items… 35 %*): the gateway
  reads Generate.py's output as it comes (`GET /internal/sessions/<id>/progress`), the tracker passes it on
  (`GET /api/sessions/new/progress/<id>`), the form asks every second. Filling the items takes the longest and
  reports nothing on its way.
- The README's overview is a Mermaid diagram (GitHub draws it), no longer drawn with box characters.

### 0.13.8: The new-session form, invitations
- *Hosted* is a checkbox on a slot's row, next to *starts locked*, asked once more; it removes the slot's players and
  its player picker (unticked, they come back). Typing *Hosted* into the player field is gone.
- A slot starts without players (they were guessed from its name before).
- Players are added from a dropdown with a search; a typed e-mail address that no account has offers its invitation.
- A YAML whose game the release has no APWorld for gets no slot: one message names the files and their games (and
  tells someone who isn't an Administrator to ask one to upload them); the form checks again by itself every few
  seconds, so an upload by anyone, anywhere, makes them slots. Generating leaves those files out.
- A session that ends cancels its invitations nobody accepted: the invited accounts that no other running session has
  go, and with them their links.

### 0.13.7: Logging out starts fresh, the ID, the versions
- Without a login (logged out, or the login ended), the settings kept in the browser go back to the defaults: theme
  and mode, language, the live feed's place and visibility, the bridge folded. A login sets the account's theme, mode
  and language again.
- *Your account*: Administrators with single sign-on see their ID too, in *Your Authentik account*.
- The panel's *General* has *Versions* last (Administrators): the tracker's, the gateway's and this page's version,
  and the gateway's Archipelago releases, to check a deploy. The build writes the version into `shared`
  (`RandogeddonVersion`); the gateway answers it on `GET /internal/version`, the tracker on `GET /api/admin/versions`.

### 0.13.6: Hosted slots
- For games that run as a server of their own, which their players join with their games instead of Archipelago:
  *Hosted* typed into a slot's player field makes it a hosted slot (no players, no field to add any; × makes it an
  ordinary slot again). Migration V15: `session_hosted_slots`.
- The dashboard and the panel show the hosted slots on a card of their own, *Hosted*. *Your connection*: the session's
  players can pick a hosted slot and see its patch files (no name, no password); its Session-Managers see its whole
  connection, to set the game's server up, even if they don't play. Its files are the players' to download too.
- *Hosted* is reserved: no account can be named so (`NAME_RESERVED`). Randogeddon Bridge leaves hosted slots out.

### 0.13.5: More from the hand test
- *Your connection*: the patch files are back under the slot and its password; the right column is the bridge's. The
  bridge has a line of its own (`Randogeddon Bridge ⓘ`, *Create key*, *Fold away*): folded, the card is one narrow
  column again and the tiles beside it get the room (remembered in the browser). Its ⓘ is in parts: what it is, what
  it does, how to use it, starting it on Windows, macOS and Linux. A new key shows like a password (*Show* / *Hide*,
  *Copy*) with a × that closes it. Someone who doesn't play in the session gets the narrow card too.
- *APWorlds*, *Remove…*: *Select all* (and *Select none*); the buttons wrap on a phone.
- APWorlds that import `tkinter` load: the image has `libtk8.6` (e.g. `minecraft_dig.apworld`, for its client window).
- *People*: a Session-Manager can't take their own part (`OWN_MANAGER`); another one or an Administrator can, and
  the panel doesn't offer it.
- The live feed's place (*Right*, *Left*, *Below*) is only offered where it can be beside the players; the button that
  shows and hides the feed only on a session's dashboard (not in the panel, *Your account*, a spoiler log, or without a
  session).

### 0.13.4: The hand test's open points
- The dashboard of a session on a wide screen (from about 1270 px) showed nothing below the top card since 0.13.2, and
  every page after it stayed empty until a reload (also after picking a finished session): the row of the tiles
  measured *Your connection*, which then decided its columns by its own width, and Compose can't answer that. The
  dashboard now says whether it has room for two columns.
- *Users*: a click on a pill opens the account's information panel: name, address, ID (to copy), state, group, how it
  logs in (password, single sign-on), two-factor, language, when it was made, the invitation, the deletion, *n/a* for
  what it doesn't have, its sessions as pills that open them, and an invited one's *Send again* / *Other address*. A
  click inside it no longer leaves it impossible to close (it keeps its own text selection now).
- The top card shows the game server's state only for a session that has one: not finished, and not on the page of
  all sessions.
- Zoom: the login form's fields are 16 px (a phone zoomed into the smaller ones by itself, and over the app the zoom
  couldn't be pinched back out); the page notices a browser zoom by the window's size and its pixel density (a media
  query on the exact density could miss one like 1.3636… with the system's display scaling).

### 0.13.3: APWorlds faster, removed by picking
- Uploading and removing APWorlds is faster: the picked files go in one request (several, if they are large together)
  and the release lists its games once for all of them, not once per file (and once more if one is refused). And
  Archipelago is compiled in the gateway image: it can't write its `__pycache__` there, so every listing compiled it
  all again (listing: 5 s → 2 s; generating seeds and starting game servers gain the same way; the image grows by
  about 40 MB).
- Removing: *Remove…* next to *Upload APWorld…*; the uploaded files are picked by a click, then *Remove (n)*, asked
  once more, and they go out together. No × on each pill any more (on a long one it was squeezed away), and the
  question and its buttons are on their own lines (on a phone they didn't fit one).
- The gateway's internal API: `POST /internal/apworlds` takes several files and answers one result each;
  `POST /internal/apworlds/remove` takes several (instead of `DELETE …/<release>/<file>`). The tracker's routes alike.

### 0.13.2: From the hand test
- *APWorlds*: an upload is named after the world folder inside it, as Archipelago imports it (`Pokemon Emerald
  (1).apworld` with `pokemon_emerald/` becomes `pokemon_emerald.apworld`; before, such a name didn't load). One that
  would replace something asks first (*Replace* / *Skip*): a file of the same name, or another uploaded APWorld of the
  same game (by its manifest), which goes then. A game the release brings itself is refused with why (its own world
  always wins). An APWorld needs one world folder, named as Python can import it. The library shows each file as soon
  as it is in.
- *Your account*, *Appearance*: a new theme or mode fades in. The mode button on the top card shows the mode: a sun, a
  moon, a half-filled circle for *Automatic*.
- *Your connection*: two columns on a wide screen (the server and the slot; the slot's files and the bridge), beside
  the tiles from about 1300 px, else above them. The slot's files have a heading, *Patch files*, and say when there are
  none. The bridge: *Create key* (*Schlüssel erstellen*), the key above the downloads, the downloads under their own
  heading with the version, a row per system (Windows and Linux x86_64 and ARM64, macOS Intel and Apple Silicon), one
  ⓘ for all of it. Windows ARM64 is a new build.
- *Users*: every account as a pill, in a box that scrolls; a click shows it floating over the panel, with everything
  its row had.
- The panel opens the new-session form only after *New session*: with no session running it says so.

### 0.13.1: Every picked APWorld, themes to see
- *APWorlds*, *Upload APWorld…*: every picked file is uploaded, one after the other (with how far it is), and each gets
  its own line: the games it brings, or why it wasn't added. Before, only the first was.
- *Your account*, *Appearance*: the themes are cards instead of a dropdown, each a small page in its colours (the
  brand, a panel with text, the item and state colours), in the mode that shows now (with *Automatic*, the device's,
  switching with it). A click picks one; the mode stays a dropdown above them.

## Phase 12: Randogeddon Bridge
Games that can't do TLS connect to `localhost`: Randogeddon Bridge, a small app on the player's PC, takes them to
their session over `wss://` and fills in the slot's name and password. It logs in with a key from the dashboard
(*Your connection*) and is downloaded there. Updating: migration V14 runs by itself; for publishing the bridge, run
`infra/registry/provision.py` on the registry VM again (the repository `randogeddon-bridge`, the account `bridge`) and put its
token into WSL (`.docs/INFRA.md`).

### 0.12.3: Phase 12 done
- README: *Randogeddon Bridge* (what it is for, the download, the key, how it stays safe), its routes, publishing it
  under *Versions*; the changelog's preamble: the bridge's builds in Artifact Keeper, GitHub releases from 1.0.0 on;
  TODO: Phase 12 done.

### 0.12.2.1: Sessions by their ID in the bridge
- The bridge names the sessions by their ID (as their game addresses do): their title is the dashboard's own unless a
  session has one, so it didn't tell them apart.

### 0.12.2: The bridge app
- Randogeddon Bridge (`bridge/`, Go, no CGO): one binary each for Windows, Linux (x86-64, ARM) and macOS (Intel,
  Apple), its page in the default browser on `127.0.0.1`, in English and German, in the account's theme and mode.
  Log in with a key; session and slot as dropdowns, *Open*: a relay on a free local port (`localhost:<port>`, copy).
  Several at once, each with its state (waiting for the game, connecting, connected, error with the server's words).
- The relay passes every message both ways over `wss://` and puts the slot's name and password into the game's
  `Connect` (any name and no password in the game). After a refusal it fetches the password again. Connections from a
  web page (with an `Origin`) are refused.
- The page only answers on its own address, with the token of the link the bridge opened (a SameSite=Strict cookie),
  and changes need its own header. The dashboard only over HTTPS (plain HTTP on this computer, for the local demo).
- One instance: starting it again opens the running one's page. *Quit* on the page (Windows has no console window).
  A newer bridge on the dashboard shows a notice with its download. The key is checked every minute; once ended under
  *Your account*, the bridge logs out and closes its relays.
- The Docker build tests and builds it (`bridge` stage); the tracker serves the builds, *Your connection* offers them
  with how to start an unsigned app.
- `scripts/publish-bridge.sh` (deploy.sh, for a release): the builds and `SHA256SUMS` to Artifact Keeper's generic
  repository `randogeddon-bridge` (`provision.py`: the repository and the account `bridge`), and from 1.0.0 on a
  GitHub release with the version's notes. `--dry-run` shows what it would do.

### 0.12.1: Bridge keys
- A key for Randogeddon Bridge, made under *Your connection* (*Make a key*, shown once): a login of its own kind
  (`bridge`, 30 days), sent by the app as a bearer token. It reaches only `/bridge/…`: `info` (the bridge offered, the
  user's colours, mode and language), `me`, `sessions` and a session's `connection`, and `DELETE /bridge/key` (the
  app's logout); never `/api`, not even as the cookie. A new password ends the keys with every other login.
- *Your account* lists the keys (made, last used, until when) with *End*, once there are any.
- `GET /downloads/bridge` and `.../<file>`: the bridge's builds in `BRIDGE_DIR` (none yet: they come with 0.12.2).
- Migration V14: `login_sessions.last_used_at`, and `bridge` as a login method.

## Phase 11: The Administrators' Account Page
Administrators who log in through Authentik manage their account there: *Your account* links to it, and their name
and address here follow it. Updating: run `infra/auth/setup.py` again in Authentik (the rule for changed people);
`OIDC_ACCOUNT_URL` is optional.

### 0.11.2: Phase 11 done
- README: Authentik holds the Administrators' account, the webhooks; `.env.example`: `OIDC_ACCOUNT_URL`, the token for
  both webhooks; TODO: Phase 11 done.

### 0.11.1: Authentik holds their account
- With single sign-on, an Administrator's *Your account* starts with *Your Authentik account*: a link to Authentik's
  user settings (`<issuer's host>/if/user/`, else `OIDC_ACCOUNT_URL`). The name, password, e-mail address, two-factor
  login and deletion panels are gone for them; language and appearance stay. Without single sign-on nothing changes.
- The server refuses those changes for them too (`MANAGED_BY_SSO`): the name, the password, the address and the
  two-factor routes.
- Their name and address follow Authentik's: at every login through it, and at once through a new webhook
  (`POST /api/login/oidc/updated`, the deletion webhook's token; `infra/auth/setup.py` adds the rule). A two-factor
  login of before is removed at their next login through Authentik.

## Phase 10: More Themes
Everyone picks how Randogeddon looks for them: eleven themes, each light and dark, and the mode (light, dark,
automatic), stored with the account. Updating: nothing to set (migration V13); everyone starts with the default theme,
in the mode their browser had.

### 0.10.3: Phase 10 done
- README: *Appearance* (the themes, the mode, a new theme); TODO: Phase 10 done.

### 0.10.2: The themes
- Ten new themes, each light and dark, with their own item and status colours: Ocean (teal → blue), Forest
  (lime → green), Ember (red → amber), High contrast (black and white), and inspired by Nintendo's consoles (their
  colours only): N64, GameCube, Wii, Wii U, Switch, Switch 2. Their names in English and German.
- *Appearance* shows the chosen theme's colours (brand, items, states).
- In dark mode the top card's text takes the theme's text colour (the card is dark there, with the gradient's glow);
  `onBrand` is for the full gradient and the buttons.
- `ThemesTest` also checks the text on the gradient's start (at least 2.5:1, the default's coral has 2.6).

### 0.10.1.1: The license
- Randogeddon is under the EraTiem-Network Non-Commercial License 1.0 (`LICENSE`, as CoreSync); Archipelago and the
  libraries keep their own licenses. The README says so at its end.

### 0.10.1: Themes as a setting
- *Your account* has *Appearance*: the theme and the mode (light, dark, automatic: the device's, following it when it
  switches). Stored with the account (migration V13, `PUT /api/account/theme`, `theme` and `themeMode` in `/api/me`);
  before the login, and on a share link's page, the one last used in this browser. An account without a choice yet
  takes what its browser had, so the old light/dark button's choice stays.
- The top card's button cycles the mode: light, dark, automatic.
- The palettes are `:shared`'s (`Themes.kt`: `Theme`, `ThemeMode`, `ThemeColors`), with new colours for the text on
  the brand (`onBrand`) and the top card's dots; every colour of the web app comes from the theme (the QR code stays
  black on white). The login page takes the user's theme colours too (`ap-theme-css`), and the browser's bar colour.
- `ThemesTest`: every palette at least as readable as the default (text 7:1, muted 4.5:1, the text on the accent
  3:1, the item colours 3:1 and the status colours 2.2:1 on the panel) and its item and status colours apart.
- The new themes follow in 0.10.2.

## Phase 9: Multi-Language
Randogeddon speaks English and German: every page, the server's messages and the mails. Each user picks the language
under *Your account* (stored with the account; *Automatic* follows the browser), and dates and numbers follow it.
Updating: nothing to set (migration V12).

### 0.9.4: Phase 9 done
- README: the language (*Language*), how to add a text and a language (*Texts and languages*).
- To-do list: Phase 9 done; Phase 11 (Randogeddon Bridge: a local app that takes games without TLS to their session
  securely; the optional plain port is dropped) and Phase 12 (polishing: everything tested by hand, and a security scan with Fable 5.2) planned.

### 0.9.3: The server's words
- Every refusal of the server is a code (`Problem`, in `:shared`) with its values: `ApiError` carries `code`, `args`
  and, for what another program said (Generate.py, the gateway), `detail`. Its `message` is worded in the request's
  language: the `ap-language` cookie (the app and the login page keep it), else `Accept-Language`, else English.
  The web app words the code itself, so a change of the language applies at once. A test checks that every problem
  has its words in every language, with the same values.
- The name, password and session-ID rules (`UserNames`, `PasswordRules`, `SessionIds`) answer with a code too, and
  so do a new session's YAML checks and a failed generation (its output stays as Generate.py wrote it).
- The live feed's cheats come in their parts (`cheatAction`, `cheatBy`, `cheatRole`, `cheatSlot`, `cheatValue`) and
  are worded by the web app; events from before keep their English sentence. A key's hint (`hintLocation`,
  `hintSlot`) and the tracker's connection error (`errorRefused`) likewise.
- The mails go out in the recipient's language: an invitation in the inviting person's, the address confirmation in
  the requester's, the deletion warning in the account's (English without a choice; its date as the language writes
  it).

### 0.9.2: Every page in English and German
- The dashboard (overview, players, hints, live feed, the finish banner), *Your connection*, the person and slot
  overviews, the share link's page, the spoiler log, and the whole panel: the game server, its log and console, the
  session's state and actions, its people and watchers, cheats and support, past sessions, the new-session form with
  every ⓘ, the APWorlds, the users and the settings.
- The live feed's lines are templates (`{sender} sent {item} to {receiver}`), so German words them in its own order
  with the names in their styles; a test checks that every language has the same marks in each.
- Each area's texts sit in one file with both languages (`shared/…/i18n/*Texts.kt`).
- Still in English: the server's own messages (refusals, errors) and the mails, which follow in 0.9.3; names from
  Archipelago and its option values (`auto`, `enabled`, …) stay as they are.

### 0.9.1: The language setting
- Randogeddon speaks English and German (with "du"). Every text comes from one place per language
  (`shared/…/i18n`: `Texts`, `English`, `German`), typed Kotlin: a text missing in a language, or one that drops a
  value, doesn't compile or fails a test.
- The language: chosen under *Your account* (*Automatic* follows the browser), stored with the account (migration
  V12, `PUT /api/account/language`, `language` in `/api/me`). Before the login: the one last used in this browser,
  else the browser's; the login page has an English · Deutsch switch.
- Dates, times and numbers are written as the language writes them: `14 Nov 2026, 08:17` and `12,345.6` in English,
  `14.11.2026, 08:17` and `12.345,6` in German; "5m ago" and "4h 12m" in the language.
- In both languages so far: the top card, the list of sessions, *Your account*, and the login, sign-up and password
  forms. The other pages follow in 0.9.2, the server's messages and the mails in 0.9.3.

## Phase 8: The Live Server
The live server is updated with one command from images in our own registry and holds only what runs; the build
infrastructure (Artifact Keeper at `pkg.example.org`, Authentik at `auth.example.org`) runs in VMs of its own, and
Randogeddon's Administrators log in through Authentik. Updating: `OIDC_*` in `.env` (README); the images come from
`pkg.example.org` (`docker login` with the read-only token). The machines: `.docs/INFRA.md`.

### 0.8.4: Phase 8 done
- `.docs/INFRA.md`: the three VMs, what each keeps, updating each stack, the tokens and where they live.
- Daily database dumps of Authentik and Artifact Keeper too (`infra/stack-dump.sh`, `authentik-backup.timer`,
  `artifact-keeper-backup.timer`), kept 14 days like Randogeddon's. A restore of Randogeddon's daily dump was tested
  (into a scratch database: every table as live).
- `scripts/deploy.sh` cleans up the images: the server keeps the running and the previous ones (for a rollback), WSL
  only what was just pushed.
- `scripts/deploy.sh` pushes a release's images under its version and `latest` (`…/tracker:0.8.4`,
  `…/gateway:0.8.4`) when the deployed commit is the one tagged `v<version>`, and the server runs them by the
  version; any other commit's go out under the commit and `edge`. 0.8.3 and 0.8.2.1 got their version tags
  afterwards (0.8.3 `latest` too), the commit tags are gone.
- The images are plain `linux/amd64` images (`provenance: false`, `sbom: false` in the compose file): BuildKit's
  attestations had made each one an index, which the registry showed as multi-arch. The pushed ones were pointed at
  their amd64 image.

### 0.8.3: Administrators' accounts follow Authentik
- With single sign-on, Administrators need no two-factor login of Randogeddon's own (Authentik checks theirs): the
  account page says so instead of asking them to set it up, and an old one can be turned off.
- Their accounts don't delete themselves after inactivity any more: they go when Authentik deletes the person. A
  notification rule in Authentik (`infra/auth/setup.py`) calls `POST /api/login/oidc/deleted` with the person's
  subject and the bearer token `OIDC_DELETION_TOKEN`; the linked account is deleted. A deleted account's link to
  Authentik is freed.
- Versions: `0.<phase>.<step>` (was `0.1.0-SNAPSHOT` throughout), every release tagged; this changelog by
  version.

### 0.8.2.1: Artifact Keeper at pkg.example.org
- Artifact Keeper at `pkg.example.org` (the registry VM) replaces Zot and Reposilite: the images and the Maven repositories
  (the Nexus's proxies, `public` mirroring them all, hosted ones; `infra/registry/provision.py`), its login through
  Authentik.
- The Gradle build takes everything from `https://pkg.example.org/maven/public/` (the init script's entry
  `example`) instead of the Nexus.
- The images are `pkg.example.org/eratiem-network/randogeddon/{tracker,gateway}`; the compose file takes them from
  `TRACKER_IMAGE` and `GATEWAY_IMAGE` (instead of `IMAGE_REGISTRY`), `scripts/deploy.sh` pushes them there.
- Authentik's OIDC providers list their grant types (Authentik 2026.8 refuses a login otherwise).

### 0.8.2: Administrators through Authentik
- With `OIDC_ISSUER` (and the client) set, the login form has *Administrators: log in with Authentik* (OpenID Connect,
  authorization code with PKCE; our own small client, the ID token checked with the JDK). Members of
  `OIDC_ADMIN_GROUP` are the Administrators: linked by the provider's subject, the first time by their verified
  e-mail address (or a new account), the flag following the group at every login. Others are refused.
- Then Administrators act as such only after logging in through Authentik (its MFA instead of the app's two-factor
  login) and can't log in with a password; `randogeddon admins --login <person>` prints a one-time link (15 minutes)
  for when Authentik can't be reached. Players keep their name and password. Without `OIDC_ISSUER` nothing changes.
- Migration V11: `users.oidc_subject`, `login_sessions.method`, `login_links`. `login` and `login-problem` are reserved
  session IDs.

### 0.8.1: The live server
- The build infrastructure of example.org, its compose files in `infra/`: Zot at `img.example.org` for the images
  and Reposilite at `mvn.example.org` for the Maven repositories (the registry VM), Authentik at `auth.example.org`
  (the auth VM) with Zot's login through it (`infra/auth/setup.py` makes its groups and applications).
- `scripts/deploy.sh`: builds, pushes the images tagged with the commit, dumps the database, switches the server to
  the new tag and checks it; `--rollback` goes back to the tag before. The compose file takes the images from
  `IMAGE_REGISTRY` with `IMAGE_TAG` and `GATEWAY_TAG` (locally unchanged).
- A daily database dump on the server (`scripts/backup.sh`, `randogeddon-backup.timer`), kept 14 days, and how to
  restore one (README, "The live server"). The server holds only what runs.

## Phase 7: Patch File Downloads
Players download their slots' files from the seed (ROM patches, mods) under *Your connection*; Session-Managers and
Administrators every slot's in the panel. Nothing that shows the way (the spoiler, readable text), and nothing for
support, watchers or share links. Updating: nothing to set (no migration).

### 0.7.3: Phase 7 done
- The summary above, the README and the to-do list.

### 0.7.2: Patch file downloads in the web app
- *Your connection*: the shown slot's files (name, size) with *Download*, and how to patch with the Archipelago
  Launcher on its ⓘ.
- The panel's *Players*: every slot's files, for Session-Managers and Administrators.

### 0.7.1: Patch file downloads
- The seed's files of a slot (ROM patches like `.apsm` or `.aplttp`, Factorio's mod zip, other per-slot output) at
  `GET /api/sessions/<id>/files` and `.../files/<name>` (an attachment): each player those of their own slots (a
  shared slot: each of its players), Session-Managers and Administrators every slot's, support and watchers none. As
  long as the session exists, finished ones too. Never the multidata, a spoiler or readable text (`.txt`, `.log`,
  `.html`, …). Every download is logged.
- The gateway reads them from the seed's zip (`/internal/sessions/<id>/files`); a file's slot comes from `P<n>` in its
  name, its slot's name and game from the spoiler log.

## Phase 6: Roles: Administrators, Session-Managers, Support and Watchers
Sessions belong to whoever creates them now: every user can create one and is its Session-Manager; Administrators (the
admins of before) reach everything. Per session there are support (items, checks and free hints, logged) and watchers
(added by Session-Managers and players), and a share link shows it without a login. Updating: nothing to set
(migrations V9 and V10); existing sessions have no Session-Manager until an Administrator adds one, and every user may
create one running session at once (*Settings*).

### 0.6.4: Phase 6 done
- The summary above, the README and the to-do list.

### 0.6.3: The web app for every role
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

### 0.6.2: Support, watchers, share links
- **Support** (`/api/sessions/<id>/support`): gives items, checks locations and free hints (even without the session's
  cheat hints); no release or collect. Logged and shown in the feed: "Bob (support) gave Mantis Claw to Alice-HK".
- **Watchers** are added by the session's players too (`/api/sessions/<id>/watchers`): by name, or an invitation for an
  address without an account. Players take away watchers, not support or Session-Managers.
- **Share links** (`/api/sessions/<id>/share`, players and Session-Managers): the session without a login, read-only,
  at `/api/watch/<token>/state`, `.../stream`, `.../player/<slot>` (rate-limited; no passwords, spoiler or accounts).
  A new link replaces the old one; stored only as SHA-256. Migration V10.

### 0.6.1: Administrators and Session-Managers
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

## Phase 5: Proper User Management
Accounts belong to people now: invited by e-mail, their own name and password, deleted by themselves when they choose,
two-factor login (required for admins). Updating: `MAIL_*` and `TOTP_KEY` in `.env`, the mailbox
`randogeddon@example.net`; existing logins stay (they add an e-mail address under *Your account*), admins set up
two-factor login at their next login.

### 0.5.4: Phase 5 done
- The summary above, how to update (README), the decisions in the to-do list.

### 0.5.3: Two-factor login
- TOTP (RFC 6238, any authenticator app): after the password, the code (or one of 10 recovery codes, each usable once;
  `autocomplete="one-time-code"`). A code works once; the step before and after count too.
- Admins need it: an admin without it sets it up right after the password (QR code, the key to type in, a first code),
  and the admin API refuses an admin without it. Everyone else can turn it on and off under *Your account*, and get new
  recovery codes there.
- The secrets are stored encrypted (AES-256-GCM) with `TOTP_KEY`, or a key file the tracker makes in its state volume.
  `randogeddon admins --reset-2fa <person>` turns it off for someone who lost both the device and the codes.
- Migration V8. The demo's admin logs in with it too (secret in `output/demo/admin.totp`).

### 0.5.2: Sign-up, users, settings
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

### 0.5.1: Accounts of people
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

## Phase 4: Multiple Sessions

### 0.4.4: Phase 4 done
- The to-do list: the plain `ws://` fallback becomes optional.

### 0.4.3: Reachable from outside
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

### 0.4.2: Picking sessions, links
- Every view has a link of its own and survives a reload: `#/` the user's sessions, `#/<id>` a session's dashboard,
  `#/<id>/spoiler` its spoiler log, `#/admin`, `#/admin/<id>` and `#/admin/new` the admin panel. Back and forward
  move between them.
- After the login, the user's only running session opens; with several or none running, their sessions are listed as
  cards (running and finished, how far each got, their slots, who is online).
- The dashboard's top card has the session's ID as its heading, which switches between the user's sessions or goes
  back to the list; the list of other sessions below the dashboard is gone. "Randogeddon" is a headline above it.
- Admin panel: *Dashboard* opens a session's dashboard, also one the admin doesn't play in.

### 0.4.1: Several sessions at once
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

## Phase 3: Admin Panel

### 0.3.5: Phones, checked uploads, mixed locks
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

### 0.3.4: Finishing, past sessions, spoiler log, cheats
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

### 0.3.3: APWorlds, random locks, tooltips
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

### 0.3.2: Sessions in the admin panel
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

### 0.3.1: Admin panel
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

## Phase 2: User Login

### 0.2.1: Login, slot passwords, the gateway, the native build

#### Login
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

#### Native build
- Tracker and gateway run as GraalVM native executables (Linux x86_64, `-march=x86-64-v3` for the server's i7-8700),
  built in `kotlin.Dockerfile` (replaces `tracker.Dockerfile`); the tracker starts in milliseconds instead of seconds.
- Reachability metadata recorded with the tracing agent; Flyway's migrations are listed in an index instead of scanned.

## Phase 1: Basic Tracker

### 0.1.2: Randogeddon
- The project is now called Randogeddon: images `randogeddon-server` / `randogeddon-tracker`, compose project
  `randogeddon`, Kotlin package `net.eratiem.randogeddon`, dashboard and PWA title.
- Addresses from the roadmap: the dashboard at `randogeddon.eratiem.net` (`BASE_DOMAIN`), the game server at
  `<session-id>.randogeddon.eratiem.net:443` (`SESSION_ID`). Replaces `AP_HOST` and `TRACKER_HOST`.
- Migrating a running server: `docker compose -p archipelago down` before the first deploy with the new name. The
  dashboard's history volume starts fresh under the new project.

### 0.1.1: Kotlin tracker, Compose dashboard, World Locks

#### Tracker
- Rewritten in Kotlin: a Ktor/Netty server (`:server`) follows the room over the Archipelago network protocol and
  serves the API (`/api/state`, `/api/player/{slot}`, `/api/stream` as Server-Sent Events, `/healthz`). Same behaviour
  as the former Python tracker: one tracker connection per slot, event history kept across restarts, reconnect within
  5 seconds.
- The dashboard is a Compose Multiplatform web app (`:webApp`, Kotlin/Wasm) and installable as a PWA on Android
  and iOS. Player cards, live feed, hints, player details, light and dark theme.
- The API contract is shared between both in `:shared`.
- The tracker image is built from the Gradle build; the Nexus credentials are passed in as a BuildKit secret.

#### Locked worlds and people
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

#### Server
- Archipelago 0.6.8 in Docker (MultiServer), generation and deployment scripts, Traefik routes.
