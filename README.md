# Randogeddon

Our self-hosted [Archipelago](https://archipelago.gg) multiworld server (plain `MultiServer`, AP **0.6.8**)
plus a custom live dashboard. Everything runs in Docker behind an existing Traefik. The dashboard is at
`randogeddon.eratiem.net` and needs a login. Several sessions can run at once, each with its own game server at
`<session-id>.randogeddon.eratiem.net:443`, where every slot has its own generated password. The gateway runs the game
servers; every user creates sessions and, as their Session-Manager, starts, stops and commands their game servers from
the panel; support helps the players, watchers follow along. The Kotlin apps (tracker and gateway) run as native
executables (GraalVM Native Image, Linux x86_64).

What's done and what comes next (login, the panel, multiple sessions, user management, roles): [.docs/TODO.md](.docs/TODO.md).

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
        │  slot password            │  :8001 (control)│  the panel,          │
        │  ┌─────────────────────┐  │                 │  Wasm app            │
        │  │ MultiServer per     │◄─┼─────────────────┤  Tracker-tag clients │
        │  │ session :38300…     │  │                 │  per session         │
        │  └─────────────────────┘  │                 └───┬──────────────────┘
        └──────┬──────────┬─────────┘                     │
  ./data/sessions/<id>/   │                       ┌───────▼──────────────┐
                          └──────────────────────►│  postgres            │
                            reads slot passwords  │  logins, slot pw     │
                                                  └──────────────────────┘
```

The gateway runs one MultiServer per running session as a child process, each on a port of `AP_PORTS` (default
`38300-38303`: four sessions at once). Only the tracker reaches them directly (those ports and the internal API on
8001 are not published). The gateway relays each game client to its session's MultiServer: by the host name it
connected to (`<session-id>.<BASE_DOMAIN>`), or locally by the path (`ws://localhost:38281/<session-id>`). A tracker
restart or update doesn't drop the players.

| Path | What it is |
|---|---|
| `archipelago/` | `serve.py` (MultiServer with the hint switches), `room_info.py`, `list_games.py`; the gateway image installs Archipelago next to them |
| `server/` | Tracker server: Kotlin + Ktor. Follows every session's room, serves the login, the API and the web app; host commands (`randogeddon admins`) |
| `gateway/` | The game host: runs one MultiServer per session, relays each client connection to its session's and checks the slot password of every `Connect`; internal API for the panel |
| `database/` | PostgreSQL schema (Flyway migrations) and Exposed tables, shared by tracker and gateway |
| `webApp/` | The dashboard: Compose Multiplatform (Kotlin/Wasm), installable as a PWA |
| `bridge/` | Randogeddon Bridge (Go): takes game clients that can't do TLS from `localhost` to their session, see *Randogeddon Bridge* |
| `shared/` | The API contract (Kotlin Multiplatform data classes) used by both |
| `apworlds/world_locks/` | Our own APWorld: slots that start locked until their key is found (part of the gateway image) |
| `demo/` | Two example sessions (YAMLs and who plays which slot, in `a/` and `b/`), simulated players — see *Demo* below |
| `kotlin.Dockerfile` | Builds the tracker image and the gateway image (native, plus the pinned Archipelago release) |
| `custom_worlds/` | Extra `.apworld` files for games that aren't bundled, for every release (gitignored); or upload them in the panel (Administrators) |
| `output/` | The demo's files (gitignored) |
| `data/` | Server side: each session's seed, `.apsave` savegame and `room.json` in `sessions/<id>/`, deleted ones in `old/`, the uploaded APWorlds in `apworlds/<release>/` (gitignored) |
| `scripts/demo.sh` | The demo |

## One-time server setup

```sh
git clone <this repo> /srv/randogeddon && cd /srv/randogeddon
cp .env.example .env     # set POSTGRES_PASSWORD, INTERNAL_TOKEN, BASE_DOMAIN, TRAEFIK_* …
docker compose build     # takes a few minutes (every game's requirements, the native builds)
```

Building the tracker and gateway images needs **BuildKit** (the `docker buildx` plugin, included in Docker's own
packages; on Arch: `pacman -S docker-buildx`) and your Gradle init script with the token for `pkg.example.org`
(an entry `example`: any user name, an Artifact Keeper API token as password, e.g. `<service-account>`'s). Compose passes
the init script into the build as a secret (default `~/.gradle/init.gradle.kts`, change with `GRADLE_INIT_SCRIPT` in
`.env`); it never ends up in an image. Compose asks once to let the build read that file outside the build context
(`additional privileges requested`); answer yes, or set `BUILDX_BAKE_ENTITLEMENTS_FS=0` as the scripts do. The native
builds need about 8 GB of RAM (peak, tracker). If the server has no init script (or
not the memory), build on your PC and copy the images over:
`docker save randogeddon-tracker:latest randogeddon-gateway:latest | ssh you@server docker load`.

`.env` sets `COMPOSE_FILE=docker-compose.yml:docker-compose.traefik.yml`, so plain `docker compose …`
always uses the Traefik labels. Check `TRAEFIK_NETWORK`, `TRAEFIK_ENTRYPOINT` and
`TRAEFIK_CERTRESOLVER` and `TRAEFIK_DNS_CERTRESOLVER` against your Traefik setup (v3).

DNS: a wildcard record `*.randogeddon.eratiem.net` (and `randogeddon.eratiem.net` itself) pointing at the server,
so a new session ID needs no DNS change.

One router takes every session, `*.randogeddon.eratiem.net`, to the gateway, which relays each game client by the
host name it connected to; a new session needs no change in Traefik or DNS. Its wildcard certificate needs the DNS
challenge (the HTTP challenge has no wildcard certificates), so Traefik needs a second cert resolver that uses the DNS
API, `TRAEFIK_DNS_CERTRESOLVER` (default `letsencrypt-dns`). With ClouDNS, in Traefik's static configuration:

```yaml
certificatesResolvers:
  letsencrypt-dns:
    acme:
      email: you@example.org
      storage: /letsencrypt/acme-dns.json
      dnsChallenge:
        provider: cloudns
```

and the ClouDNS API login in Traefik's environment. Use a sub-user that may only edit this zone: in ClouDNS,
*API & Resellers* → *API Sub-Users* → *Add new sub-user*:

- a password of letters and digits only (`openssl rand -hex 20`; systemd's env files and shells trip over the
  rest), access level *Read and write*, all limits 0 except *DNS zones* 1 and *DNS records* (e.g. 500);
- as allowed IPs, the server's outgoing IPv4 **and** IPv6 (the API is reached over either);
- save, then delegate the zone (`example.net`) to it; its ID is in the sub-users table.

```sh
# <traefik-dir>/cloudns.env, chmod 600
CLOUDNS_SUB_AUTH_ID=<the sub-user's ID>
CLOUDNS_AUTH_PASSWORD=<its password>
```

With Traefik as a systemd service, a drop-in loads it (`/etc/systemd/system/traefik.service.d/cloudns.conf`:
`[Service]` and `EnvironmentFile=<traefik-dir>/cloudns.env`, then `systemctl daemon-reload` and a restart). Traefik
asks for the certificate when it first sees a router that uses the resolver, and renews it by itself.

With the file provider instead of the labels (e.g. Traefik on the host, Randogeddon in a VM), one router for both,
on one certificate:

```yaml
http:
  routers:
    randogeddon-ap:
      # [.] rather than \. : no escaping in YAML's double quotes
      rule: "HostRegexp(`^[a-z0-9-]+[.]randogeddon[.]eratiem[.]net$`)"
      entryPoints: [websecure]
      service: randogeddon-ap          # http://<vm>:38281, the gateway (websocket; no extra config)
      tls: &randogeddon-tls
        certResolver: letsencrypt-dns
        domains:
          - main: "randogeddon.eratiem.net"
            sans: ["*.randogeddon.eratiem.net"]
    randogeddon:
      rule: "Host(`randogeddon.eratiem.net`)"
      entryPoints: [websecure]
      service: randogeddon             # http://<vm>:8000, the tracker (SSE; Traefik doesn't buffer it by default)
      tls: *randogeddon-tls
```

Randogeddon then uses `docker-compose.proxy.yml` instead of the Traefik overlay
(`COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml` in `.env`): it publishes 38281 and 8000 and keeps the
public addresses (`BASE_DOMAIN`, the login cookie only over HTTPS).

The gateway runs as uid 1000 and writes the sessions' files to `data/`; when moving from the old `archipelago`
container (which ran as root), hand the directory over once: `sudo chown -R 1000:1000 data`. Seeds from before
several sessions (one seed in `data/` itself, finished ones in `data/sessions/<seed>/`) are moved into
`data/sessions/s-<seed>/` on the first start, and get the session ID `s-<seed>`; their players were in `session.yaml`,
which is no longer read, so only Administrators see them.

The first Administrator, who then invites the others (every user can create sessions):

```sh
docker compose exec tracker randogeddon admins --create <person>   # prints the password once
docker compose exec tracker randogeddon admins --grant <person>    # an existing login; --revoke takes it back
```

**Administrators through Authentik** (single sign-on, `OIDC_*` in `.env`): with `OIDC_ISSUER` set, the login form
has *Administrators: log in with Authentik*. Members of the Authentik group `OIDC_ADMIN_GROUP` (default
`randogeddon-administrators`) are the Administrators: their account is linked by its verified e-mail address on the
first login (or made then), the flag follows the group at every login, and Authentik's MFA takes the place of the
app's two-factor login for them. They can't log in with a password any more; if Authentik can't be reached,
`randogeddon admins --login <person>` prints a one-time link (15 minutes). In Authentik: an OAuth2/OpenID provider
(confidential, redirect `https://<BASE_DOMAIN>/api/login/oidc/callback`, scopes `openid email profile`) and its
application bound to the group (`infra/auth/setup.py` makes both). Players keep their name and password.
Authentik holds their account: *Your account* starts with *Your Authentik account*, a link to Authentik's user
settings (`<issuer's host>/if/user/`, or `OIDC_ACCOUNT_URL`), where they change their name, password, e-mail address
and second factor; the panels for those and for the deletion aren't there for them, and the server refuses those
changes (language and appearance stay). Their name and address follow Authentik's at every login, and at once
through its webhook `POST /api/login/oidc/updated` (when Authentik changes the person); a two-factor login from before
goes at their next login through it. Their accounts here don't delete themselves after a while: they go when
Authentik deletes the person, whose webhook calls `POST /api/login/oidc/deleted`. Both webhooks carry
`OIDC_DELETION_TOKEN` (the same token in Authentik; `setup.py` makes the rules and prints it). More on the machines
around it: `.docs/INFRA.md`.

**Mail:** invitations, address confirmations and deletion warnings go out through the SMTP server in `.env`
(`MAIL_HOST`, `MAIL_USER`, `MAIL_PASSWORD`; sender `randogeddon@example.net`, a mailbox on the mail server). The
certificate is checked against `MAIL_HOST`; if the server's public address leads back to this host (a VM behind the
same host as the mail server), `MAIL_ADDRESS` names its internal one. Without `MAIL_HOST` the mails, links included,
only go to the tracker's log.

**Updating to the accounts of Phase 5** (migrations V7 and V8 run by themselves): set `MAIL_*` (and `MAIL_ADDRESS`
if needed) and `TOTP_KEY` in `.env`, and create the `randogeddon@example.net` mailbox. Existing logins stay, without
an e-mail address: each person adds theirs under *Your account*. Administrators set up two-factor login at their next
login; until then what only they do is closed to them.

**Updating to the roles of Phase 6** (migrations V9 and V10 run by themselves): nothing to set. The admins are the
Administrators; existing sessions have no Session-Manager (Administrators reach them, and can add one under *People*).
Every user may then create one running session at once; Administrators change that under *Settings* (0: only they
create sessions).

**Updating to the patch file downloads of Phase 7:** nothing to set, no migration; the downloads work for the
sessions that exist already, finished ones too.

## The live server

The live Randogeddon runs on the live VM (`<internal-ip>`, behind the Proxmox host example.org and its Traefik). It holds
only what runs; the images are built on a PC (WSL) and come from the registry `pkg.example.org` (Artifact Keeper, VM
1020):

| What | Where |
|---|---|
| `.env`, `docker-compose.yml`, `docker-compose.proxy.yml` | `/srv/randogeddon/` (`COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml`) |
| `data/` (sessions, `old/`, the APWorld library), `custom_worlds/` | `/srv/randogeddon/` |
| the database, the tracker's archives and TOTP key file | the volumes `randogeddon_postgres-data`, `randogeddon_tracker-state` |
| dumps (each update's, a daily one kept 14 days) | `/srv/backups/` |
| the daily dump | `/usr/local/sbin/randogeddon-backup`, `randogeddon-backup.timer` (01:45, before the Proxmox backup) |

Besides the settings above, the server's `.env` has the image settings that `scripts/deploy.sh` keeps:
`TRACKER_IMAGE=pkg.example.org/eratiem-network/randogeddon/tracker` and `GATEWAY_IMAGE=…/gateway`, `IMAGE_TAG` and
`GATEWAY_TAG` (the running version, e.g. `0.8.3`; for a commit that isn't a release the commit, the gateway's with the
Archipelago release before it), `PREVIOUS_IMAGE_TAG` and
`PREVIOUS_GATEWAY_TAG` (the one before, for a rollback); and `GRADLE_INIT_SCRIPT=/dev/null` (nothing is built there,
but the compose file names the build's secret).

**Updating** (in WSL, from a committed state; for a release, after its tag `v<version>`, see *Versions*):

```sh
./scripts/deploy.sh             # build, push as <version> (or <commit>), dump, switch, check (healthz, schema)
./scripts/deploy.sh --rollback  # back to the previous tag (code only; a migration that ran needs its dump)
```

Old images are removed as it goes: the server keeps the running and the previous ones, WSL only what was just
pushed. The dumps (each update's and the daily ones) are kept 14 days.

It needs once: `docker login pkg.example.org` with the `<service-account>` token as password (`AK_TOKEN_DEPLOY` in
`<registry-dir>/.secrets` on the registry VM), and an SSH alias for the server (`DEPLOY_SSH`, default `randogeddon-live`; see
the script's head). The server itself is logged in to the registry with the read-only `<service-account>` token.
For publishing the bridge (*Versions*): the `<service-account>` token (`AK_TOKEN_BRIDGE` in `.secrets`) in
`~/.config/randogeddon/bridge-publish.token` (mode 0600), and for 1.0.0 on `gh` logged in.

**Restoring a dump** (the stack's database replaced by the dump; the tracker and gateway stopped meanwhile):

```sh
cd /srv/randogeddon && docker compose stop tracker gateway
docker compose exec -T postgres psql -U randogeddon -d postgres -c 'DROP DATABASE randogeddon' -c 'CREATE DATABASE randogeddon OWNER randogeddon'
gunzip -c /srv/backups/<dump>.sql.gz | docker compose exec -T postgres psql -U randogeddon -d randogeddon
docker compose up -d
```

## Session day

### In the panel (*Manage*)

1. **Collect the YAMLs**, one per slot, each with a unique `name:` (e.g. `Alice-HK`, `Alice-Celeste`). Players export
   them from the game's options page on archipelago.gg or via the Launcher's *Generate Template Options*. A game that
   isn't bundled with Archipelago needs its `.apworld` (see *apworlds* below).
2. **Create the session** (the gear button, *Manage*, then *New session*; every user can, as many running ones at
   once as the Administrators allow, and is then its Session-Manager): its *Session ID* (prefilled with a free suggestion like
   `brave-otter`; lowercase letters, digits and `-`, starting with a letter; it becomes the game server's host name,
   so it can't be changed later), *Add YAMLs…*, then for every slot who
   plays it (one or more players, picked from the accounts, invited ones too: a slot with several players is played
   together); the locks
   (*Choose per slot*: tick the slots that start locked; *Random*: a number of slots picked at random start open,
   all others start locked; *Mixed*: each slot's switch says open, locked or left to chance, and the slider how many
   start open in all; at least one slot starts open, and a single slot can't be locked); the hint and release options;
   *Generate and start*. Every option has an ⓘ that says what it does; buttons with a tip show it on hover, or on a
   long press on a phone. The gateway generates the seed with the chosen
   Archipelago release and starts the game server on it. A file that isn't a player YAML (not `.yaml`/`.yml`, no
   name or game, not readable) isn't taken, and the panel says why. A game the release has no APWorld for is flagged
   before generating; if Generate.py fails, the panel names the games that may be the cause (one the error mentions,
   or one whose APWorld comes from another release) above its output.
3. **New players are invited** by e-mail, right in a slot's player field (only an address that has no account; someone
   who has one is picked by name), or beforehand by an Administrator (*Users*): they get a link, valid for 7 days,
   and choose their name and password themselves. Until they sign up, the session shows them by the suggested name.
   **Hosted slots:** for a game that runs as a server of its own (its players join that server with their games, not
   Archipelago), type *Hosted* into the slot's player field. The slot then has no players and no field to add any; the
   dashboard shows it on a card of its own, *Hosted*. Under *Your connection*, the session's players can pick it and
   see its patch files; its Session-Managers see its whole connection (server, slot, password), to set the game's
   server up. No account can be named *Hosted*; Randogeddon Bridge leaves hosted slots out.
4. **Players log in** (with their name or their e-mail address) at `https://randogeddon.eratiem.net`. The dashboard opens their running session; with several
   (or none running), they pick one from their sessions, and switch between them on top of the dashboard. Each view
   has its own link: `#/<session-id>` a session's dashboard, `#/<session-id>/spoiler` its spoiler log, `#/manage` and
   `#/manage/<session-id>` the panel (`#/admin` still works), `#/watch/<token>` a share link. Under *Your connection*
   they find the server
   (`<session-id>.randogeddon.eratiem.net:443`; clients try `wss://` first, so TLS through Traefik just works; for a game that can't, see *Randogeddon Bridge*), their
   slot names and each slot's password, and for a ROM-based game (or one that needs a mod) the slot's file from the
   seed to download: a patch is opened with the Archipelago Launcher (*Open Patch*) and their own ROM. Session-Managers
   and Administrators download every slot's files in the panel (*Players*). Phones can install the dashboard as an
   app (see below).

Patch files for ROM-based games (and mods some games need) are in the seed, `data/sessions/<id>/AP_<seed>.zip` on the
server; players download their own from the dashboard (step 4), as long as the session exists. **Deleting** a
running session (the panel) stops its game server and moves its files to `data/old/<id>-<time>/`; deleting a
finished one deletes them for good. The accounts stay. As many sessions run at once as the gateway has ports for
(`AP_PORTS`); a finished session doesn't take one.

### Finishing, past sessions and the spoiler log

- **Ending and pausing:** 2 minutes after every slot has reached its goal or was released, the session ends by
  itself. Meanwhile the dashboard shows a countdown; its Session-Managers can *End now* or *Cancel* (it then doesn't
  start again until a slot is no longer done). A Session-Manager can also end it: *Pause session* while not every
  check is done
  (e.g. when someone drops out), *End session* once it is at 100 %. Either way the game server stops (the gateway
  refuses to start that seed, also after a restart), and the dashboard keeps showing how far it got: the session is
  read-only from then on.
- **Reopening:** within 14 days of the finish (*Reopen*), unless the session was completed (100 %), and while a game
  server is free. Its history goes on from where it was.
- **Finished sessions** keep their files (`data/sessions/<id>/`) and what the dashboard showed when they finished
  (`/state/sessions/<id>/archive.json` in the tracker's volume). Their players see them under *Other sessions* on the
  dashboard, Administrators see all; a finished session is kept until it is deleted in the panel, or
  automatically `SESSION_RETENTION_DAYS` after it finished (90 by default, `0`: never; the date shows in the lists).
  *Disable auto-deletion* (asked once more) keeps it until it is deleted by hand; enabled again, the days count from
  then. Someone who plays only in finished sessions can still log in and see those.
- **Spoiler log:** the seed's spoiler log (from its zip), with the playthrough sphere by sphere and the whole log
  (the search marks and counts the findings; the arrows, or Enter and Shift+Enter, jump between them).
  Administrators, its Session-Managers and support can open it at any time (behind a warning: it is a full spoiler);
  its players and watchers see it once it is finished.

### APWorlds

The panel's *apworlds* (Administrators) lists, for every Archipelago release of the gateway, the games it can generate and serve,
and its APWorld library:

- **Upload APWorld…** adds `.apworld` files to that release's library (kept in `data/apworlds/<release>/`), several
  at once, together (the release lists its games once for all of them). The gateway checks that each is an APWorld (a zip with one world folder), names it
  after that folder (Archipelago imports it by its file name: `Pokemon Emerald (1).apworld` with `pokemon_emerald/`
  inside becomes `pokemon_emerald.apworld`), loads the release's worlds again and says which game it brought. One that
  doesn't load (e.g. it needs a newer Archipelago) is refused: it isn't kept, a file it would have replaced stays, and
  the panel says why. A game the release brings itself is refused too (its own world always wins). An upload that
  would replace something asks first (*Replace* / *Skip*): a file of the same name, or another uploaded APWorld of the
  same game (by its manifest, `archipelago.json`), which goes then. *Remove…* takes files out again: pick them (the
  uploaded ones; *Select all*), *Remove (n)*, confirm. An APWorld may import `tkinter` (for its client window): the
  image has the Tk library it needs.
- **From other releases:** an APWorld uploaded for another release can be tried with this one (*Try with …*); it is
  copied and marked "untested here" (or refused, like an upload, if it doesn't load). If generating fails, such APWorlds are among the suspects.
- APWorlds put into the server's `custom_worlds/` by hand count for every release (they can't be removed in the
  panel).

The gateway puts exactly these files into each release's `custom_worlds/` before it lists the games or generates.

### The panel: the game server and its console

Everyone gets a gear button in the dashboard's header: the panel (*Manage*). It lists the sessions the user manages
(an Administrator's: every session; how far each is, its game server); a click picks one. For that session it shows
who plays which slot and its actions (pause or end, reopen, auto-deletion, delete, spoiler log), its **People**
(everyone with a part, with role and slots; add a Session-Manager, support or a watcher by name, or invite a new
address; remove them; the share link), the cheats, its game server's state (running, starting, stopped, crashed), lets
them start, stop and restart it (stop and restart ask once more: every player is disconnected), shows its log live,
and has the server console:

```
/players              # who's connected
/send <slot> <item>   # give an item ("Mantis Claw" in quotes if it has spaces)
/hint <slot> <item>   # free hint
/release <slot>       # release a slot's items (e.g. someone has to drop out)
/collect <slot>
```

Archipelago's commands say `<player>` for a slot.

Up and down in the console bring back earlier commands. Every action is logged with the name of who did it in the tracker's
log.

**Cheats** (the panel, *Cheats*): give a slot an item, check one of its locations, release or collect for it, pick
the slot and the item or location from searchable lists. Free hints (`/hint`, `/hint_location`) only if the session
was created with *Cheat hints*. Every cheat is stored with who did it (and as what) and shown in the live feed ("Cheat: Alice
gave Mantis_Claw to Alice-HK"). Items a player got with the client's `!getitem` (if the server allows it) are
marked as cheats there too. A crashed MultiServer is started again by the gateway after a pause (1 s, doubling up to 30 s); one stopped by
hand stays stopped until started again.

Below the finished sessions, Administrators find *General*: *Users* (every account as a pill, marked if invited or an
Administrator; a click shows its information panel: name, address, ID, state, group, how it logs in, two-factor,
language, when it was made, the invitation, the deletion, *n/a* where there is none, its sessions as pills that open
them, and for an invited one *Send again* and *Other address*), *APWorlds*, *Settings* (how many running sessions one
user may manage at once) and *Versions* (what runs now: the tracker's, the gateway's and this page's version, the
gateway's Archipelago releases; to check a deploy). With no session running, the panel says so; *New session* opens the
form.

**On the dashboard, by role:** support gets a *Support* section (give an item, check a location, a free hint; the
spoiler log), logged and in the feed as "Erin (support) gave …". Players get *Watchers*: add someone by name or a new
address, remove watchers, and the share link. Watchers see a *Watching* pill in place of their slots. A **share
link** (`#/watch/<token>`) opens the session's dashboard without a login, read-only: progress, tiles, players, hints
and the live feed, no passwords and no spoiler; turned off or replaced, it says so.

Players can also use `!hint <item>`, `!remaining`, `!release`, `!collect` and `!countdown 10` from their
client, depending on the session's options. `AP_ADMIN_PASSWORD` enables `!admin` for remote server commands.

### Progress and restarts

Each game server saves to `data/sessions/<id>/AP_<seed>.apsave` continuously, and `restart: unless-stopped` brings
the gateway back after crashes or reboots; it starts every session's game server again that isn't finished. Stopping
with `docker compose down` and starting with `up -d` keeps the progress.

## Logins and passwords

Nobody sees anything of the session without logging in, and nobody joins the game without their own password.

- **Accounts** belong to people, not to a session. An Administrator invites someone by e-mail (*Users*), or anyone right
  in a new session's player field); the link (single use, 7 days, a new one replaces it) leads to the sign-up page,
  where they choose their name (3–32 characters, no `@`,
  unique without regard to case), a password (at least 12 characters, stored only as an Argon2id hash) and when their
  account deletes itself (2 or 4 weeks, 3 or 6 months, 1 year after the end of their last session, or never; a mail
  warns 14 days before). They log in with their name or their e-mail address. `randogeddon logins --create <player>`
  makes a login without an invitation (local use, the demo), `randogeddon logins --reset <player>` a new password
  (ending that player's logins). A login lasts 30 days (HttpOnly, Secure, SameSite=Strict cookie). Failed logins and
  sign-ups are slowed down and limited to 5 per minute per address. A player sees the sessions they play in, and no
  other; Administrators see every session. Each session keeps the names its players had in it: a rename shows in the running
  ones, a finished one keeps the old name, also of a deleted account.
- **Two-factor login** (TOTP, any authenticator app): after the password, a code, or one of 10 recovery codes (each
  works once). **Administrators need it** unless they log in through Authentik (above): an Administrator without it
  sets it up right after the password (QR code or the key, then a first code); everyone else can turn it on in
  *Your account*. The secrets are stored encrypted with `TOTP_KEY`
  (`openssl rand -base64 32` in `.env`) or, without it, with a key file the tracker makes in its state volume; keep
  either with the backups. `randogeddon admins --reset-2fa <person>` turns it off for someone who lost both the
  device and the recovery codes (an Administrator sets it up again at the next login).
- **Roles:** an **Administrator** (`randogeddon admins --grant`) reaches everything: every session, the APWorlds, the
  users and the settings. **Every user can create a session** and is its **Session-Manager**: they manage it (its
  state, finishing and reopening, deleting it, its game server, console and log, the cheats, its spoiler log while it
  runs) and give its people a part: Session-Managers (co-managers), support and watchers. How many running sessions one
  user may manage at once is the Administrators' setting (default 1); the game servers' number still applies. Every
  cheat is logged and shown in the feed with who did it, and as what ("Bob (Session-Manager) gave …").
- **Support** helps the players of a session: gives items, checks locations and gives free hints (also in a session
  without cheat hints), and sees its spoiler log while it runs; no release or collect, game server or console. **Watchers**
  follow a session (dashboard, feed, hints; no slots, the spoiler log once it is finished). Its players add watchers too
  (by name, or invite an address that has no account), and take them away again. A **share link** (players and
  Session-Managers) shows the session to anyone who has it, without a login and read-only (for a stream): no
  passwords, no spoiler, no accounts. It is shown once; a new link replaces the old one, and it can be turned off.
- **Your account** (the person button on the top card): change the name, the password (the other logins end), the
  e-mail address (a link to the new address confirms it; until then the old one stays), two-factor login and the
  deletion period. A
  name's tip shows its account's ID, which tells a deleted account's name in an old session from a new account with
  the same name.
- **Slot passwords:** every slot of a seed gets its own generated password when the tracker first sees the
  seed; they stay the same across restarts, a new seed gets new ones. The gateway compares the password of every
  client's `Connect` with the slot's, the same field clients already send the room password in, so no client needs
  changes. A wrong one gets `InvalidPassword`. There is no room password (`AP_PASSWORD` is gone).
- Slot passwords use only characters every client accepts and that don't break config files (printable ASCII without
  spaces, quotes or backslashes). Some games only have an in-game text field without paste, so the dashboard shows
  them in a monospaced font.
- `docker compose exec tracker randogeddon slots <session-id>` prints a session's slot passwords for the host, e.g.
  for the Locks slot (`!hint World Key 2`) or slots nobody plays; `randogeddon sessions` lists the sessions.

The data lives in the `postgres-data` volume (and in the Proxmox backup of the whole VM).

## Hints

A hint tells a player where one of their items is ("Alice-HK's *Mantis Claw* is at *Sky Meadow: Chest 4* in
Bob-RoR2"). There are two systems, each switched per session when it is created:

- **Point hints:** checking a location earns *Points per check* (default 1); `!hint <item>` or
  `!hint_location <location>` costs *Hint cost* % of the slot's locations (default 10 %, `0` = free).
  Players can mark hints *priority*, *avoid* or *no priority* in their client; the dashboard shows that.
- **Automatic hints:** some games create hints by themselves, e.g. a shop that hints what it sells.

MultiServer has no switches for this, so the server runs through `archipelago/serve.py`, a thin wrapper: with
point hints off, `!hint`/`!hint_location` answer "Hints are turned off"; with automatic hints off, games can still
scout locations but no hints are created. The host can always give hints from the server console (`/hint`).
With both off, the dashboard hides hints entirely (unless the host handed some out). With point hints on, every
slot shows its points and how many hints they buy.

## Locked slots

Some slots can start locked and open only when their key is found in another slot. This is part of the generated
seed, not just of the dashboard: the `World Locks` APWorld (`apworlds/world_locks/`, installed in the gateway image)
adds one key item per locked slot and tells the generator that nothing in that slot is reachable without its key. So
every seed stays completable, chains included (a key inside a slot that is itself locked).

In the panel, tick *starts locked* per slot, choose *Random* locks with the number of slots that start open
(at least one; the others are locked), or *Mixed*: per slot open, locked or left to chance, and how many start open in
all. The gateway adds the World Locks slot (`Locks`, nobody plays it) to the seed.

The game clients don't know about the lock, so it's on the honour system: don't start a locked game before the
dashboard says it's unlocked. Keys show up as "World Key 1, 2, …" in game clients (`!hint World Key 2` works from the
Locks slot), and as "Key to Alice-Celeste" on the dashboard, which also shows who found it or where it was hinted.

## Randogeddon Bridge

Game clients reach their session only over TLS (`wss://<session-id>.randogeddon.eratiem.net:443`); there is no plain
port. For a game whose client can't do TLS (older or unmaintained mods that only speak `ws://`), Randogeddon Bridge
runs on the player's PC: the game connects to `localhost:<port>`, the bridge takes it to the session over `wss://`.

- **Download:** under *Your connection*, for Windows and Linux (x86_64, ARM64) and macOS (Intel, Apple Silicon); one
  file, nothing to install. It isn't signed, so the system asks first: Windows *More info* → *Run anyway*; macOS
  right-click → *Open* (or `xattr -d com.apple.quarantine <file>`); Linux `chmod +x <file>`.
- **Login:** with a key, made under *Your connection* (*Create key*, shown once) and pasted into the bridge's page. The
  bridge never sees a password, so Administrators who log in through Authentik use it too. A key is a login for 30
  days; *Your account* lists them (made, last used, until when) with *End*, and a new password ends them all.
- **Use:** the bridge opens its page in the default browser, in the account's theme, mode and language. Pick the
  session and the slot, *Open*: a relay on a free local port, `localhost:<port>` to copy into the game. Several at
  once (each with its state: waiting for the game, connecting, connected, the server's error). The game's name and
  password don't matter: the bridge fills in the slot's. Starting it again opens the running one's page; *Quit* ends
  it (on Windows it has no console window). A newer bridge on the dashboard shows a notice with its download.
- **Kept:** the dashboard's address and the key in the user's config directory, `Randogeddon/bridge.json` (readable
  only by them; removed at *Log out*), and `bridge.log` of the last run. `-server <url>` points it at another dashboard
  (the local demo: `-server http://localhost:8000`), `-no-browser` only writes the page's link to the log.

How it stays safe:

- A key reaches only the bridge's routes (`/bridge/…`: who, the sessions, a session's connection, the bridge offered,
  its own logout), sent as `Authorization: Bearer`; never `/api`, not even as the cookie. It is stored as its SHA-256
  like every login; the bridge checks it every minute and logs out once it is ended.
- The relays listen on `127.0.0.1` only and refuse connections from a web page (an `Origin` header), so no site in the
  browser can play a slot through them.
- The page answers only on its own address (`127.0.0.1:<port>`/`localhost:<port>`, against DNS rebinding), with the
  token of the link the bridge opened (a SameSite=Strict cookie); changes need its own header too. The dashboard is
  reached only over HTTPS (plain HTTP only on the same computer).

The Docker build tests it and builds the six binaries (`kotlin.Dockerfile`, the `bridge` stage: plain Go, no CGO);
the tracker serves them at `/downloads/bridge/<file>` (`BRIDGE_DIR`, `/app/bridge` in the image), with the version
the dashboard offers. `go test -race ./...` in `bridge/` runs its tests.

## The dashboard

For every running session, the tracker connects to its room like any other Archipelago tracker client (tag
`Tracker`). It doesn't read server internals. It opens one connection per slot, because the server only tells each
slot about its own checked locations and received items. The slot names come from `data/sessions/<id>/room.json`,
which the gateway writes before it starts the session's game server.

- **Your connection:** the server address, your slot names and their passwords (shown on request, copyable), the
  slot's patch files (or that it has none); beside them on a wide screen Randogeddon Bridge (its ⓘ, *Create key*, its
  downloads). The bridge folds away (*Fold away*, remembered in the browser): then the card is one narrow column and
  the tiles beside it get the room. A new key shows like a password (*Show*, *Copy*) until its line is closed.
- **Overview:** overall completion, slots at goal, game clients online, locked slots opened, hint count.
- **Players:** one card per player of the session with their slots (a slot played together
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
- **Other sessions:** the viewer's other sessions (all for Administrators), running or finished, below; a click shows one.

Known limitations:

- Each tracker connection makes the server post "X tracking … has joined" to the chat. That happens
  once per slot whenever the tracker (re)connects.
- "Online" comes from join/leave messages. After a tracker restart a slot counts as online again as soon as it
  does something (finds its own item, chats, changes status); such sessions are marked "seen playing" in the
  slot's connection log, since their real start is unknown.
- The event history keeps the last 500 events per session, stored in the `tracker-state` volume.

API: `POST /api/login` (JSON with `username`, a name or an e-mail address, and `password`; sets the cookie, or with
two-factor login answers `{"next": "code"|"setup", "pending": …}` for `POST /api/login/code` or
`.../setup` and `.../setup/confirm`),
`POST /api/logout`; with single sign-on `GET /api/login/options`, `GET /api/login/oidc` (to the provider) and
`.../callback`, `POST /api/login/link` (a CLI login link), and Authentik's webhooks `POST /api/login/oidc/deleted` and
`.../updated`; `GET /api/invitations/<token>`, `POST /api/signup` (the
sign-up page) and `POST /api/email/confirm`
(a new address's link); with a login: `GET /api/account` and `PUT .../name`, `.../password`, `.../auto-delete`,
`POST .../email`, `POST .../2fa/setup`, `.../2fa/enable`, `.../2fa/disable`, `.../2fa/codes` (the settings),
`GET /api/me` (name, Administrator, the sessions they manage), `GET /api/sessions` (the sessions the user sees), and per session
`GET /api/sessions/<id>/state` (summary JSON), `.../stream` (SSE), `.../player/<slot>`, `.../connection` (address,
own slots and passwords), `.../archive` (a finished one as it ended), `.../spoiler`, `.../files` and
`.../files/<name>` (the seed's files of the user's slots: patches, mods; every slot's for its Session-Managers and
Administrators). The panel's are under
`/api/manage` (the sessions the user manages), `/api/sessions/new` and `POST /api/sessions` (creating one, for
everyone), a session's management under `/api/sessions/<id>/manage/…` and its people under `.../people` (its Session-Managers and Administrators), support's
`.../support`, the watchers under `.../watchers` and the share link under `.../share` (players and Session-Managers),
the share link's read-only `/api/watch/<token>/state`, `.../stream`, `.../player/<slot>` (without a login), and the
Administrators' `/api/admin/sessions`, `/api/admin/users`, `/api/admin/settings`. A login's bridge keys are under `/api/account/bridge-keys` (`GET`, `POST`, `DELETE .../<id>`);
the bridge's own routes, with a key as `Authorization: Bearer`, are `GET /bridge/info`, `/bridge/me`,
`/bridge/sessions`, `/bridge/sessions/<id>/connection` and `DELETE /bridge/key`, and its builds
`GET /downloads/bridge` and `.../<file>` (open). Without a login these answer 401, for a session the user isn't part of 403. `GET /healthz` is
open and says nothing about the sessions.

The login form is a plain HTML form in `index.html` rather than part of the Compose app, so password managers can
save and fill the generated passwords.

### Install on your phone

The dashboard is a web app (PWA), so no app store or Apple account is needed:

- **Android (Chrome):** open `https://randogeddon.eratiem.net` → menu ⋮ → *Install app* (or *Add to Home screen*).
- **iPhone / iPad (Safari):** open the page → Share → *Add to Home Screen*. It opens full screen with its own icon.

It needs a browser with WebAssembly GC: iOS/Safari 18.2+, Chrome/Edge 119+, Firefox 120+. Older browsers get a
message asking for an update. The first load is about 4 MB (gzip); after that the browser has it cached.

### Appearance

Each user picks a theme and a mode under *Your account*, *Appearance*: Randogeddon (the default), Ocean, Forest,
Ember, High contrast, or one inspired by Nintendo's consoles (N64, GameCube, Wii, Wii U, Switch, Switch 2), each light
or dark; *Automatic* follows the device and switches with it. The themes are cards, each a small page in its colours
in the mode that shows now. The button on the top card cycles light, dark and automatic, and shows the mode it is in
(a sun, a moon, a half-filled circle). A new theme or mode fades in. It is stored with the account, so it follows them to every device; before the login, and on a share
link's page, the browser's last choice applies (the login page takes its colours too).

A new theme: an entry in `Theme` and its two palettes in `shared/…/shared/Themes.kt`, its name in the texts
(*Texts and languages*). `ThemesTest` checks that it is at least as readable as the default (text, muted text and the
text on the brand, the item and status colours on the panel) and that its item and status colours stay apart.

### Language

Randogeddon speaks English and German. Each user picks theirs under *Your account* (*Automatic* follows the
browser); it is stored with the account, so it follows them to every device, and their mails go out in it too.
Before the login the page takes the language last used in this browser, else the browser's; the login form has an
English · Deutsch switch. Dates, times and numbers are written as the language writes them. Names from Archipelago
(games, items, locations, slots) and its option values stay as they are.

## Demo

```sh
./scripts/demo.sh        # starts the stack, creates two sessions (demo/a, demo/b) through the API, plays both
./scripts/demo.sh loop   # the same, again and again: once both rounds are at 100 % and finished, new sessions start
./scripts/demo.sh stop   # stops the simulated players; `docker compose down` stops everything

nohup ./scripts/demo.sh loop > output/demo/loop.log 2>&1 &   # the loop in the background
```

Two sessions at once, with players of their own, created like in the panel (`demo/admin.py` calls its API):

- **demo-a-…:** Alice three slots (two of them start locked), Bob two (one locked) and, with Carol, Risk of Rain 2,
  which they play together (a shared slot: on both cards, both get its slot password).
- **demo-b-…:** Dave and Erin, two slots each, with mixed locks: Dave's Yacht Dice starts open, Erin's Hollow Knight
  locked, the other two are left to chance with two slots open in all.

The simulator (`demo/simulate.py`, one per session) connects through the gateway with the slot passwords, checks
locations quickly for half a minute and then evenly until the end, and finds the keys on a schedule (the last one late,
hinted before). Every slot reaches its goal within about `DEMO_MINUTES` (default 15); then the simulator ends. 2
minutes later the session finishes by itself (the countdown shows meanwhile); once both are done, the next round
starts after a pause with new session IDs. The round before stays as finished sessions, older demo rounds are
deleted. About one in three random seeds can't be generated ("unbeatable"), so it retries with another one.

Alice is the demo's admin: her login is made on the first run (`randogeddon admins --create`), and its password is
printed once and kept in `output/demo/admin.password`. The other logins are printed once, when the first session with
them is created (a new password: `docker compose exec tracker randogeddon logins --reset Bob`). Open
http://localhost:8000 and log in.

The demo builds the native images, so it needs Docker for Linux containers; on Windows, run it in WSL (see
*Native build*).

## Local testing

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml   # publishes 38281 + 8000, no Traefik
export POSTGRES_PASSWORD=local INTERNAL_TOKEN=local                # or set them in .env
docker compose up -d
docker compose exec tracker randogeddon admins --create Admin
# dashboard on http://localhost:8000: create a session in the panel; its game server is
# ws://localhost:38281/<session-id>
```

For a test seed that needs no ROM or installed game, use a few YAMLs like
`name: Alice` / `game: APQuest` / `APQuest: {}`.

## Developing the tracker and the gateway

Gradle 9.8.2 (wrapper), Kotlin 2.4, JDK 25 toolchain; dependencies come from `https://pkg.example.org/maven/public/`
(Artifact Keeper's mirror of Maven Central, the plugin portal, Google and the others) with the token from your init
script. Run Gradle on JDK 25 (e.g. `JAVA_HOME=/usr/lib/jvm/java-25-temurin`).

```sh
./gradlew :server:test :gateway:test :database:test  # unit tests (also run in the Docker build)
./gradlew :database:integrationTest             # schema and stores against PostgreSQL (Testcontainers, Linux Docker)
./gradlew :webApp:wasmJsBrowserDistribution     # build the web app -> webApp/build/dist/wasmJs/productionExecutable
./gradlew :server:run                           # tracker on :8000 against the gateway on localhost, PostgreSQL on :5432
AP_ROOT=<dir with one Archipelago checkout per release> ./gradlew :gateway:run   # gateway on :38281
./gradlew :server:run --args="sessions"         # a host command, against the same database
```

Both use the sessions in `data/` of this checkout; `:server:run` builds the web app first. All
settings are in `server/src/main/resources/application.yaml` and `gateway/src/main/resources/application.yaml`, each
with the environment variable that overrides it (database: `DB_URL`, `DB_USER`, `DB_PASSWORD`). For UI work,
`./gradlew :webApp:wasmJsBrowserDevelopmentRun` starts a dev server with hot reload that proxies `/api` to the tracker
on port 8000.

The schema is in `database/src/main/resources/db/migration`; a new migration must also be listed in `index.txt` there
(a native image can't scan the classpath; a test checks the list).

### Versions

While Randogeddon is in development, its version (root `build.gradle.kts`) is `0.<phase>.<step>`: the phase (as in
`.docs/TODO.md`) and its PR. A PR's branch builds `0.<phase>.<step>-SNAPSHOT`; its last commit before the merge drops
the suffix (the release `0.<phase>.<step>`), its merge commit is tagged `v0.<phase>.<step>` (`scripts/deploy.sh` pushes
that commit's images under the version and `latest`: `…/tracker:0.<phase>.<step>`, `…/gateway:0.<phase>.<step>`; any
other commit's under the commit and `edge`), and the next PR starts
with the next step's `-SNAPSHOT`. A follow-up to a step that was merged already gets a fourth number (`0.8.2.1`),
which the next step drops again. `CHANGELOG.md` lists every version by phase.

For a release, `scripts/deploy.sh` also publishes Randogeddon Bridge (`scripts/publish-bridge.sh`, copied out of the
tracker image, so exactly the builds it serves): to `pkg.example.org`, the generic repository `randogeddon-bridge`,
as `<version>/<file>` with `SHA256SUMS`. From 1.0.0 on, every release is also a GitHub release `v<version>`, with the
bridge's builds and the version's part of `CHANGELOG.md` as its notes. `--dry-run` shows what it would do.

### Texts and languages

Every text lives in `shared/src/commonMain/kotlin/net/eratiem/randogeddon/shared/i18n/`, as typed Kotlin: an
interface per area (`DashboardTexts`, `PanelTexts`, …, listed in `Texts`) and its English and German objects in the
same file. A text with values is a function (`fun slots(n: Int)`), so plurals and word order are plain Kotlin; feed
lines with styled names are templates with marks (`{sender} sent {item} to {receiver}`). The web app reads them
through `LocalTexts.current`; the server's refusals are `Problem` codes worded in `ProblemTexts` (the server in the
request's language, the web app in the viewer's); the mails come from `MailTexts`. `index.html`'s forms have a small
table of their own in its script.

- **A new text:** a member in its area's interface, then its words in `English…` and `German…` (it doesn't compile
  before). `TextsTest` checks that German borrows nothing from English, that no text drops a value, and that every
  template and every problem has the same marks and values in each language.
- **A new language:** an entry in `Language` (code, tag for dates, whether its dates are numeric, its own name), an
  object per area like `German…`, and `texts()` returning them; plus its column in `index.html`'s table.

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
GraalVM 25 (`sdk install java 25-graal`) and the init script (with `example`) in `~/.gradle/init.gradle.kts`:
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
it doesn't have can't be started (the panel and the gateway's log say so). The gateway image has one release
(`AP_VERSION` in `.env`) under `/opt/archipelago/<version>`; the panel offers what it has.

To update: set `AP_VERSION` to the new release tag, `docker compose build`, and recreate the gateway once no session
of the old release is running (finish or delete them first). Everyone's client and APWorld versions should be compatible with
the release. The new release starts with an empty APWorld library: the APWorlds of the old one show under *From
other releases* (*apworlds* in the panel) and can be tried with it, or upload versions made for the new release.

## License

Randogeddon is released under the [EraTiem-Network Non-Commercial License 1.0](LICENSE): free to use and to pass on
unchanged for non-commercial purposes; no modified copies, no paid use without a separate license
(info@eratiem.net). Archipelago and the libraries Randogeddon uses keep their own licenses.
