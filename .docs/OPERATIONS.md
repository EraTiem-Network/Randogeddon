# Randogeddon: running the server

Setting up, running and updating a Randogeddon server, in detail. The README has the overview.

## One-time server setup

```sh
git clone <this repo> /srv/randogeddon && cd /srv/randogeddon
cp .env.example .env     # set POSTGRES_PASSWORD, INTERNAL_TOKEN, TRACKER_DB_*, GATEWAY_DB_*, BASE_DOMAIN, TRAEFIK_* …
docker compose build     # takes a few minutes (every game's requirements, the native builds)
```

Building the tracker, gateway and minecraft images needs **BuildKit** (the `docker buildx` plugin, included in Docker's own
packages; on Arch: `pacman -S docker-buildx`). Gradle takes its libraries from Maven Central, the Gradle plugin
portal and Google, or from a Maven repository that mirrors them, `RANDOGEDDON_MAVEN` in `.env` (e.g. Artifact
Keeper's virtual repository); its login comes from your Gradle init script, which can find it by its URL or its
name (`RANDOGEDDON_MAVEN_NAME`, default `mirror`). Compose passes the init script into the build as a secret (default `~/.gradle/init.gradle.kts`, change with `GRADLE_INIT_SCRIPT` in
`.env`); it never ends up in an image. Compose asks once to let the build read that file outside the build context
(`additional privileges requested`); answer yes, or set `BUILDX_BAKE_ENTITLEMENTS_FS=0` as the scripts do. The native
builds need about 8 GB of RAM (peak, tracker). If the server has not the memory (or no login for the mirror), build on your PC and copy the images over:
`docker save randogeddon-tracker:latest randogeddon-gateway:latest randogeddon-minecraft:latest | ssh you@server docker load`.

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
- save, then delegate the zone (your domain's) to it; its ID is in the sub-users table.

```sh
# /etc/traefik/cloudns.env (beside Traefik's configuration), chmod 600
CLOUDNS_SUB_AUTH_ID=<the sub-user's ID>
CLOUDNS_AUTH_PASSWORD=<its password>
```

With Traefik as a systemd service, a drop-in loads it (`/etc/systemd/system/traefik.service.d/cloudns.conf`:
`[Service]` and `EnvironmentFile=/etc/traefik/cloudns.env`, then `systemctl daemon-reload` and a restart). Traefik
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
      middlewares: [randogeddon-ap-rate]
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
    # Plain HTTP goes to HTTPS (a redirectScheme middleware; the tracker's answers then say HTTPS only for a year)
    randogeddon-http:
      rule: "HostRegexp(`^([a-z0-9-]+[.])?randogeddon[.]eratiem[.]net$`)"
      entryPoints: [web]
      middlewares: [web-to-websecure]
      service: noop@internal
  middlewares:
    # How fast one address (an IPv6 /64 as one) opens new connections to the game servers: 100 at once, then 2 a
    # second, 429 past it. Not how many it holds (behind a VPN's or a shared gateway's address are many households)
    randogeddon-ap-rate:
      rateLimit:
        average: 2
        burst: 100
        sourceCriterion:
          ipStrategy:
            ipv6Subnet: 64
```

Randogeddon then uses `docker-compose.proxy.yml` instead of the Traefik overlay
(`COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml` in `.env`): it publishes 38281 and 8000 and keeps the
public addresses (`BASE_DOMAIN`, the login cookie only over HTTPS).

**A release is deployed only from a signed tag:** `scripts/deploy.sh` checks `v<version>`'s signature against the
keyring of the machine it runs on and stops without one that checks out (the signers' public keys are imported there:
`gpg --import`). Anything else than a release (a commit without its version's tag) goes out as `edge`, unchecked.

**The tracker's own database login:** `POSTGRES_PASSWORD` is the database's superuser, who can run programs in the
database's container. With `TRACKER_DB_USER` and `TRACKER_DB_PASSWORD` in `.env` (`openssl rand -hex 32`), the compose
service `database-roles` makes the tracker a role of its own before each start of the tracker (as the superuser: the
tables become the role's, it may make the gateway's role, and it is no superuser), and the tracker's container gets
only this login; the superuser's password then stays with that one-off service and the dumps. Without them the tracker
logs in as the superuser. It can be switched on for a database that already runs (tried with live's tables); to go
back, take the two lines out (the superuser may still do everything). A tracker older than 0.13.18 can't make the
gateway's role with it: take the lines out before rolling back that far.

**The gateway's own database login:** the gateway runs Archipelago's Python next to itself (Generate.py, the game
servers, the APWorlds), which must not reach the accounts. With `GATEWAY_DB_USER` and `GATEWAY_DB_PASSWORD` in `.env`
(`openssl rand -hex 32`), the tracker makes that role at each start with only the gateway's rights (the sessions and
the slot passwords to read, the session time to count, what it hears on the game servers to add), and the gateway's
container gets only this login; without
them it uses the tracker's. Archipelago's processes get nothing of the gateway's environment either way, and the
gateway's container is limited to 5 GB of memory (`GATEWAY_MEMORY`): a seed too large for it ends there.

The gateway runs as uid 1000 and writes the sessions' files to `data/`; when moving from the old `archipelago`
container (which ran as root), hand the directory over once: `sudo chown -R 1000:1000 data`. Seeds from before
several sessions (one seed in `data/` itself, finished ones in `data/sessions/<seed>/`) are moved into
`data/sessions/s-<seed>/` on the first start, and get the session ID `s-<seed>`; their players were in `session.yaml`,
which is no longer read, so only Administrators see them.

Without single sign-on (a local setup, the demo), the first Administrator, who then invites the others (every user
can create sessions):

```sh
docker compose exec tracker randogeddon admins --create <person>   # prints the password once
docker compose exec tracker randogeddon admins --grant <person>    # an existing login; --revoke takes it back
```

**Administrators through Authentik** (single sign-on, `OIDC_*` in `.env`): with `OIDC_ISSUER` set, the login form
has *Administrators: log in with Authentik*. Members of the Authentik group `OIDC_ADMIN_GROUP` (default
`randogeddon-administrators`) are the Administrators: their account is linked by its verified e-mail address on the
first login (or made then; a linked account takes over the name and the address Authentik has, verified or not), the flag follows the group at every login, and Authentik's MFA takes the place of the
app's two-factor login for them. Their login lasts a day (the group is asked again at each one, so leaving it
shows within a day), and only such a login reads as an Administrator: an older password login, or a bridge key, sees
no more than any user. There is no other way to an Administrator then: no password login for them, and the CLI
makes none (`admins --create`, `--grant` and `--login` are refused; `--revoke` still takes the flag). While Authentik
can't be reached nobody administrates (the sessions run on, their Session-Managers manage them). The application in
Authentik also asks for a second factor: someone in the group without one set up there doesn't get in. In Authentik:
an OAuth2/OpenID provider
(confidential, redirect `https://<BASE_DOMAIN>/api/login/oidc/callback`, scopes `openid email profile`, *Logout URI*
`https://<BASE_DOMAIN>/api/login/oidc/backchannel` with the method *Back-channel*) and its application bound to the
group. Their login here lasts only as long as their Authentik session: when it ends
(they log out there, an admin ends it, the person is deactivated, it expires), Authentik's back-channel logout
(`POST /api/login/oidc/backchannel`, a logout token signed by Authentik) ends the logins here from that session, and an
open dashboard notices within 30 seconds and shows the login form (as does any request that finds a login ended). Their Randogeddon Bridge keys stay (a login of their own, ended under *Your
account*). Players keep their name and password.
Authentik holds their account: *Your account* starts with *Your Authentik account*, a link to Authentik's user
settings (`<issuer's host>/if/user/`, or `OIDC_ACCOUNT_URL`), where they change their name, password, e-mail address
and second factor; the panels for those and for the deletion aren't there for them, and the server refuses those
changes (language and appearance stay). Their name and address follow Authentik's at every login, and at once
through its webhook `POST /api/login/oidc/updated` (when Authentik changes the person); a two-factor login from before
goes at their next login through it. Their accounts here don't delete themselves after a while: they go when
Authentik deletes the person, whose webhook calls `POST /api/login/oidc/deleted`. Both webhooks carry
`OIDC_DELETION_TOKEN` (the same token in Authentik, in the webhook transport's header).

**Mail:** invitations, address confirmations and deletion warnings go out through the SMTP server in `.env`
(`MAIL_HOST`, `MAIL_USER`, `MAIL_PASSWORD`; the sender is `MAIL_FROM`, else the login's address). The
certificate is checked against `MAIL_HOST`; if the server's public address leads back to this host (a VM behind the
same host as the mail server), `MAIL_ADDRESS` names its internal one. Without `MAIL_HOST` the mails, links included,
only go to the tracker's log.

**Updating to the accounts of Phase 5** (migrations V7 and V8 run by themselves): set `MAIL_*` (and `MAIL_ADDRESS`
if needed) and `TOTP_KEY` in `.env`, and create the mailbox it sends from. Existing logins stay, without
an e-mail address: each person adds theirs under *Your account*. Administrators set up two-factor login at their next
login; until then what only they do is closed to them.

**Updating to the roles of Phase 6** (migrations V9 and V10 run by themselves): nothing to set. The admins are the
Administrators; existing sessions have no Session-Manager (Administrators reach them, and can add one under *People*).
Every user may then create one running session at once; Administrators change that under *Settings* (0: only they
create sessions).

**Updating to the patch file downloads of Phase 7:** nothing to set, no migration; the downloads work for the
sessions that exist already, finished ones too.

## The live server

The live Randogeddon runs in a VM behind a host with Traefik (`docker-compose.proxy.yml`: Traefik's file provider
on the host reaches the VM's published ports). It holds only what runs; the images are built on a PC (WSL) and come
from a registry (Artifact Keeper). Its machines, addresses and accounts are written down outside this repository.

| What | Where |
|---|---|
| `.env`, `docker-compose.yml`, `docker-compose.proxy.yml` | `/srv/randogeddon/` (`COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml`) |
| `data/` (sessions, `old/`, the APWorld library), `custom_worlds/` | `/srv/randogeddon/` |
| the database (with the sessions' events, snapshots and archives); the TOTP key file without `TOTP_KEY`, and the tracker's files of before 0.19.10 | the volumes `randogeddon_postgres-data`, `randogeddon_tracker-state` |
| dumps (each update's, a daily one kept 14 days; for root only) | `/srv/backups/` |
| the daily dump | `/usr/local/sbin/randogeddon-backup`, `randogeddon-backup.timer` (01:45, before the VM's own backup) |

Besides the settings above, the server's `.env` has the image settings that `scripts/deploy.sh` keeps:
`TRACKER_IMAGE=<IMAGES>/tracker`, `GATEWAY_IMAGE=<IMAGES>/gateway` and `MINECRAFT_IMAGE=<IMAGES>/minecraft` (since
0.21.6; it takes the tracker's tag, so a rollback to a version before it finds none: tag one for it first on the
server, `docker tag <IMAGES>/minecraft:0.21.6 <IMAGES>/minecraft:<the tag rolled back to>`),
`IMAGE_TAG` and
`GATEWAY_TAG` (the running version, e.g. `0.8.3`; for a commit that isn't a release the commit, the gateway's with the
Archipelago release before it), `PREVIOUS_IMAGE_TAG` and
`PREVIOUS_GATEWAY_TAG` (the one before, for a rollback); and `GRADLE_INIT_SCRIPT=/dev/null` (nothing is built there,
but the compose file names the build's secret).

**Updating** (in WSL, from a committed state; for a release, after its tag `v<version>`, see [Versions](DEVELOPMENT.md#versions)):

```sh
./scripts/deploy.sh             # build, push as <version> (or <commit>), dump, switch, check (healthz, schema)
./scripts/deploy.sh --publish   # build and push only (and a release's bridge and GitHub release); the server isn't touched
./scripts/deploy.sh --rollback  # back to the previous tag (code only; a migration that ran needs its dump)
```

Old images are removed as it goes: the server keeps the running and the previous ones, WSL only what was just
pushed. The dumps (each update's and the daily ones) are kept 14 days.

It needs once, on the PC: `~/.config/randogeddon/deploy.env` with `IMAGES` (the registry and path of the images,
e.g. `registry.example.org/randogeddon`), `RANDOGEDDON_MAVEN` (the Maven mirror, if any) and `BRIDGE_REGISTRY` (where
the bridge's builds go, e.g. `https://registry.example.org`); `docker login` to the registry with a token that may
push; and an SSH alias for the server (`DEPLOY_SSH`, default `randogeddon-live`; see the script's head). The server
itself is logged in to the registry with a read-only token. For publishing the bridge ([Versions](DEVELOPMENT.md#versions)): a token that may
write its repository in `~/.config/randogeddon/bridge-publish.token` (mode 0600), and for 1.0.0 on `gh` logged in.

**Restoring a dump** (the stack's database replaced by the dump; the tracker and gateway stopped meanwhile):

```sh
cd /srv/randogeddon && docker compose stop tracker gateway
docker compose exec -T postgres psql -U randogeddon -d postgres -c 'DROP DATABASE randogeddon' -c 'CREATE DATABASE randogeddon OWNER randogeddon'
gunzip -c /srv/backups/<dump>.sql.gz | docker compose exec -T postgres psql -U randogeddon -d randogeddon
docker compose up -d
```

## Updating Archipelago

A session keeps the Archipelago release it was generated with, and the gateway runs it only with that release: one
it doesn't have can't be started (the panel and the gateway's log say so). The gateway image has one release
(`AP_VERSION` in `.env`) under `/opt/archipelago/<version>`; the panel offers what it has.

To update: set `AP_VERSION` to the new release tag and `AP_COMMIT` to that tag's commit
(`git ls-remote https://github.com/ArchipelagoMW/Archipelago refs/tags/<version>`; the build stops if a tag doesn't
stand for its commit any more), `docker compose build`, and recreate the gateway once no session
of the old release is running (finish or delete them first). Everyone's client and APWorld versions should be compatible with
the release. The new release starts with an empty APWorld library: the APWorlds of the old one show under *From
other releases* (*Archipelago* in the panel) and can be tried with it, or upload versions made for the new release.
