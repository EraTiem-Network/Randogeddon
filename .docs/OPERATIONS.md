# Randogeddon: running the server

Setting up, deploying and updating a server, as a compact reference. The README has the overview.

## Building

```sh
git clone <this repo> /srv/randogeddon && cd /srv/randogeddon
cp .env.example .env     # the comments in it say what each setting is
docker compose build     # BuildKit (docker buildx); the native images need about 8 GB of RAM
```

- Gradle's libraries come from Maven Central, the Gradle plugin portal and Google, or a mirror of them
  (`RANDOGEDDON_MAVEN`; its login from your Gradle init script, found by URL or by `RANDOGEDDON_MAVEN_NAME`). The
  init script is passed into the build as a secret (`GRADLE_INIT_SCRIPT`, default `~/.gradle/init.gradle.kts`),
  never into an image; `BUILDX_BAKE_ENTITLEMENTS_FS=0` spares the build's question for it.
- Too little memory on the server: build elsewhere, then
  `docker save randogeddon-tracker:latest randogeddon-gateway:0.6.8 randogeddon-minecraft:latest | ssh you@server docker load`
  (the gateway's tag is the Archipelago release).
- Without a Maven mirror: `GRADLE_INIT_SCRIPT=/dev/null` (the file the setting names must exist).
- Before the first start: `install -d -o 1000 -g 1000 data minecraft` (the gateway's and the minecraft service's
  folders, both uid 1000's; Docker would make them root's).
- **What a build takes is pinned by content:** the base images, the Dockerfile's frontend, postgres and the audit's
  scanner by digest (`name:tag@sha256:…` where they are written); Archipelago's Python packages by
  `archipelago/requirements-<AP_VERSION>.lock` (hashes; `.git.txt`: the two packages that only come from a git
  repository, by commit; `.newer.lock`: what is kept newer than Archipelago's own pin, the CA bundle); Gradle's
  libraries and plugins by `gradle/verification-metadata.xml`, its wrapper by `distributionSha256Sum`; the web
  build's npm tooling by `kotlin-js-store/`. A build stops when one of them doesn't fit.
  `./scripts/base-images.sh` says which pinned image has a newer digest under its tag (exit 1); to raise one,
  change its digest where it is written, build, run the audit and the demo.

## Traefik and DNS

- **DNS:** `randogeddon.eratiem.net` and `*.randogeddon.eratiem.net` pointing at the server; a new session needs no
  change anywhere.
- **The wildcard certificate** needs the DNS challenge: a second cert resolver (`TRAEFIK_DNS_CERTRESOLVER`, default
  `letsencrypt-dns`), e.g. `dnsChallenge: {provider: cloudns}`. In ClouDNS a sub-user for this zone only (*API
  Sub-Users*: a password of letters and digits, *Read and write*, limits 0 but one zone; the server's IPv4 **and**
  IPv6 allowed; the zone delegated to it); `CLOUDNS_SUB_AUTH_ID` and `CLOUDNS_AUTH_PASSWORD` in Traefik's environment
  (e.g. a systemd drop-in with `EnvironmentFile=`, mode 600).
- **With Traefik's labels:** `COMPOSE_FILE=docker-compose.yml:docker-compose.traefik.yml`; check `TRAEFIK_NETWORK`,
  `TRAEFIK_ENTRYPOINT`, `TRAEFIK_CERTRESOLVER`, `TRAEFIK_DNS_CERTRESOLVER`.
- **A second port for web pages, 38281:** a tracker or client that is a web page (built on archipelago.js) can't
  reach port 443: a browser's URL drops the default port of `wss://`, and the library then fills in Archipelago's
  own, 38281. So the game servers' router also listens there, with the same certificate: an entry point in
  Traefik's static configuration (`archipelago: {address: ":38281"}`, with `websecure`'s timeouts), the port open
  in the firewall, and the entry point on the router (`TRAEFIK_AP_ENTRYPOINTS=websecure,archipelago`, or in the
  file below). Such a page then takes `<session-id>.randogeddon.eratiem.net` without a port. Everything else
  stays on 443, which the dashboard keeps showing.
- **With the file provider** (Traefik on a host, Randogeddon in a VM): `docker-compose.proxy.yml` publishes 38281 and
  8000; the routers:

```yaml
http:
  routers:
    randogeddon-ap:                     # every session's game server: the gateway (WebSocket)
      rule: "HostRegexp(`^[a-z0-9-]+[.]randogeddon[.]eratiem[.]net$`)"   # [.]: no escaping in YAML's quotes
      entryPoints: [websecure, archipelago]   # archipelago: 38281, for web pages (above)
      service: randogeddon-ap           # http://<vm>:38281
      middlewares: [randogeddon-ap-rate]
      tls: &randogeddon-tls
        certResolver: letsencrypt-dns
        domains: [{main: "randogeddon.eratiem.net", sans: ["*.randogeddon.eratiem.net"]}]
    randogeddon:                        # the dashboard: the tracker (SSE)
      rule: "Host(`randogeddon.eratiem.net`)"
      entryPoints: [websecure]
      service: randogeddon              # http://<vm>:8000
      tls: *randogeddon-tls
    randogeddon-http:                   # plain HTTP to HTTPS
      rule: "HostRegexp(`^([a-z0-9-]+[.])?randogeddon[.]eratiem[.]net$`)"
      entryPoints: [web]
      middlewares: [web-to-websecure]
      service: noop@internal
  middlewares:
    randogeddon-ap-rate:                # new connections per address (an IPv6 /64 as one): 100 at once, then 2 a second
      rateLimit: {average: 2, burst: 100, sourceCriterion: {ipStrategy: {ipv6Subnet: 64}}}
```

## Settings worth knowing

- **Database logins:** each program always has a role of its own, never the superuser's: the tracker's owns the
  tables and has twelve connections at most, the gateway's has only what the gateway needs (Archipelago's Python
  runs beside it) and six. Neither may make temporary tables (taken from everyone but the superuser). The one-off `database-roles` makes both before each start, the tracker gives the gateway's its login and
  rights. `TRACKER_DB_USER` / `TRACKER_DB_PASSWORD` and `GATEWAY_DB_USER` / `GATEWAY_DB_PASSWORD` name them
  (`openssl rand -hex 32` each; printable characters); unset, the names are `randogeddon_tracker` and
  `randogeddon_gateway` and the passwords are derived (the tracker's from `POSTGRES_PASSWORD`, the gateway's from
  `INTERNAL_TOKEN`). A name that is the superuser's, or the same for both, is refused: the tracker doesn't start
  then (`docker compose logs database-roles`). A gateway role renamed by hand leaves the old one behind
  (`DROP OWNED BY <old>; DROP ROLE <old>`).
- **The browser's side:** over HTTPS the login cookie is `__Host-randogeddon_session` (the plain name on a plain
  `http://` test instance; a browser that still sends the plain one over HTTPS has that login ended on the
  server); a change asked for by another site's page is refused (403 `OTHER_SITE`: the `Origin` or
  `Sec-Fetch-Site` header says so; the command line, the bridge and scripts send neither). The page may use full
  screen and write the clipboard, nothing else (`Permissions-Policy`); its content policy loads only from itself.
- **The database's reach:** on a network of its own that only the tracker, the gateway and `database-roles` join
  (the minecraft service doesn't); its superuser logs in only inside its own container
  (`docker compose exec postgres psql -U randogeddon`). A statement ends after 20 s and a wait for a lock after 5 s
  (not for a migration); the programs' logs are in UTC.
- **`TRUSTED_PROXIES`** (addresses or CIDR ranges, comma-separated): only these peers' `X-Forwarded-For` is believed,
  by the tracker (the rate limits, the streams' counts) and the gateway (the waiting connections per address). Unset:
  every peer's. With `docker-compose.proxy.yml` and `docker-compose.traefik.yml` it is required, as `BASE_DOMAIN`
  is: compose refuses every command without them (the proxy overlay: the address Traefik's host reaches the VM
  from; the Traefik overlay: Traefik's address on its network).
- **`PUBLISH_ADDRESS`** (the proxy overlay; default `0.0.0.0`, IPv4 only): the address the two ports are published
  on, best the VM's own on the network Traefik comes from. `docker-compose.local.yml` publishes on `127.0.0.1` only
  (local testing, with a mail catcher that has no login: never on a reachable server).
- **`POSTGRES_MEMORY`** (default `1g`): the database's memory limit; it also has a limit on processes and only the
  capabilities its start needs. The tracker's own file system is read-only (it writes its state volume).
- **Mail:** `MAIL_HOST`, `MAIL_USER`, `MAIL_PASSWORD`, `MAIL_FROM` (`MAIL_ADDRESS`: an internal address for a server
  whose public one leads back to this host). Without `MAIL_HOST` mails only go to the tracker's log (whole, with their
  links: for local testing); with it, the log names a mail's account, never its address.
- **`TOTP_KEY`** (`openssl rand -base64 32`) encrypts the two-factor secrets: keep it with the backups. The made-up
  hidden addresses of the forgotten-password page are derived with it too (another key: other made-up addresses).
  That page is only offered with `MAIL_HOST` set.
- **Hosted Minecraft:** `MINECRAFT_EULA=true` accepts Mojang's EULA for every hosted server, and `MINECRAFT_TOKEN`
  (`openssl rand -hex 32`, not the internal one) is what the gateway calls the minecraft service with (nothing runs
  without both; the deploy sets a token if there is none); `MINECRAFT_SERVERS` at once, `MINECRAFT_MEMORY` for all of
  them together. The service mounts only `minecraft/` beside `data/` (owned by uid 1000; out of `data/` since
  0.23.7, which the gateway's user could change: the deploy moves an existing `data/minecraft/` there once).
- **The gateway's two users:** the gateway is uid 1000 and owns `data/`; Archipelago's processes (the game servers,
  generations, APWorld code) are uid 1100 and get only the session folders, which the gateway gives to group 1100
  at each start (the host needs no group of that number). Its container starts as root with `cap_add: [SETUID,
  SETGID, KILL]` and nothing else, and `init: true`; it is limited by `GATEWAY_MEMORY` (5 GB), and out of memory the
  kernel ends a generation or a game server before the gateway. `docker compose exec gateway …` is root now:
  `-u randogeddon` or `-u archipelago` for one of the two. Before going back to a version before 0.23.2.1:
  `chown -R 1000:1000 data/`.

## Administrators

- **Without single sign-on** nobody is an Administrator, unless `ADMIN_PASSWORD_LOGIN=true` is set: for local
  testing and the demo (`docker-compose.local.yml` sets it), never for a public server. With it:
  `docker compose exec tracker randogeddon admins --create <person>` (prints the password once), `--grant` /
  `--revoke` for an existing login; their login needs two-factor. Without it those commands refuse (`--revoke`
  works). Granting the flag, resetting two-factor (`--reset-2fa`) or an Administrator turning two-factor on ends
  the account's other logins. Since 0.23.1: a server that ran without single sign-on has no Administrators after
  the update until the setting is set.
- **Through Authentik** (`OIDC_*`): members of `OIDC_ADMIN_GROUP` (default `randogeddon-administrators`) are the
  Administrators, linked by their verified address, with Authentik's MFA instead of the app's; their login lasts a day
  and only such a login reads as an Administrator (no password or CLI login for them). In Authentik:
  - an OAuth2/OpenID provider: confidential, redirect `https://<BASE_DOMAIN>/api/login/oidc/callback`, scopes
    `openid email profile`, back-channel logout `https://<BASE_DOMAIN>/api/login/oidc/backchannel`;
  - its application bound to the group, with a second factor required;
  - webhooks `POST /api/login/oidc/updated` and `…/deleted`, both with `OIDC_DELETION_TOKEN` in their header.

  Authentik holds their name, address and password (*Your account* links there); their accounts go when Authentik
  deletes them. While Authentik can't be reached nobody administrates; the sessions run on.

## The live server

The live Randogeddon runs in a VM behind a host with Traefik (`docker-compose.proxy.yml`). It holds only what runs;
the images are built on a PC (WSL) and come from a registry. Its machines, addresses and accounts are written down
outside this repository.

| What | Where |
|---|---|
| `.env` (mode 600), `docker-compose.yml`, `docker-compose.proxy.yml`, `data/`, `minecraft/`, `custom_worlds/` | `/srv/randogeddon/` |
| each kept version's compose files (the running one's and the good one before it) | `/srv/randogeddon/releases/<tag>/` |
| the database | the volume `randogeddon_postgres-data` (the TOTP key file without `TOTP_KEY`: `randogeddon_tracker-state`) |
| dumps (each update's, a daily one; 14 days; root only) | `/srv/backups/` |
| the daily dump | `/usr/local/sbin/randogeddon-backup`, `randogeddon-backup.timer` (01:45) |

`scripts/deploy.sh` keeps the image settings in its `.env`: `TRACKER_IMAGE`, `GATEWAY_IMAGE`, `MINECRAFT_IMAGE`,
`IMAGE_TAG` and `GATEWAY_TAG` (the running version; for an `edge` build the commit), `GOOD_IMAGE_TAG` and
`GOOD_GATEWAY_TAG` (the last version that passed the switch's check), `PREVIOUS_IMAGE_TAG` and
`PREVIOUS_GATEWAY_TAG` (the good one before that); `GRADLE_INIT_SCRIPT=/dev/null`. The server is logged in to the
registry with a read-only token. With `.env` at mode 600, `docker compose` by hand there needs `sudo`.

## Deploying

In WSL, from a committed state. A release only from its tag `v<version>`, signed by a key whose fingerprint the
tagged commit's `release-signers` names (the signers' public keys in the machine's keyring: `gpg --import`; a
change to that file decides who can publish and is reviewed like a change to the deploy), anything else as `edge`,
unchecked:

```sh
./scripts/deploy.sh             # build, audit, push, switch, check; a release's bridge and GitHub release
./scripts/deploy.sh --publish   # build, audit and push only; the server isn't touched
./scripts/deploy.sh --switch    # build nothing: the server to this commit's images as published
./scripts/deploy.sh --rollback  # back to the last good version (code only: a migration that ran needs its dump)
```

- **A release is built** from the checkout alone (its `.env` isn't read; `NATIVE_MARCH` and `NATIVE_OPTIMIZATION`
  must be unset), from a Gradle cache only releases write, and needs its section in `CHANGELOG.md`. Then
  `scripts/audit.sh` looks at what was just built: a finding in the project's own dependencies (Go, JVM and
  Kotlin, Python) stops the release before anything is pushed, as does an audit that couldn't run. A finding that
  is known and accepted goes into `osv-scanner.toml` with its reason and a date; `SKIP_AUDIT=1` passes one release.
  The images' operating system packages are reported (a line each, the full lists in the folder the audit names)
  and stop nothing: they are the base images', and `scripts/base-images.sh` says when newer ones exist.
- **The switch on the server**, in this order: the images pulled; the tracker and the gateway stopped; the
  database dumped; the version's compose files and `.env` put in place; started; checked (the tracker's `/healthz`,
  the gateway's and the minecraft service's ports, neither starting again within 15 seconds). The dashboard and
  the game servers are down for the dump's seconds and the start. A failure before the settings are written
  starts the version before again and says so; after it, the message names the last good version.
  It refuses, with nothing changed: a database that is there but doesn't run (start it first), a missing
  `TRUSTED_PROXIES` or `BASE_DOMAIN`, a minecraft folder that is a symbolic link. It says how old the newest
  nightly dump is, and warns above 36 hours (nothing else tells when the nightly dump fails).
- **A rollback** goes to the last version that passed the check, or from that one to the good one before it, each
  with its own compose files (kept in `releases/<tag>/`; a version deployed before 0.23.7 has none and is refused
  until its two files are put there: `git show <tag>:docker-compose.yml`). The minecraft folder goes back to where
  that version mounts it.

Needed once on the PC: `~/.config/randogeddon/deploy.env` with `IMAGES` (the registry's path), `RANDOGEDDON_MAVEN` and
`BRIDGE_REGISTRY`; `docker login` with a token that may push; an SSH alias (`DEPLOY_SSH`, default `randogeddon-live`);
for the bridge, `~/.config/randogeddon/bridge-publish.token` (mode 0600) and the signing key
`~/.config/randogeddon/bridge-signing-key` (without a passphrase, the key of `bridge/signing-key.pub`; keep a copy:
a new key means a new `bridge/signing-key.pub` and a new fingerprint in the README and the web app). A release stops
before it builds without that key, and before it pushes unless the built bridge's checksums are signed with it
(`scripts/publish-bridge.sh <version> <image> --check`; `ssh-keygen` needed); only a build that isn't a release may
stay unsigned. `BRIDGE_REGISTRY` must be `https://`; `gh` logged in for every release. The server keeps the
running and the good version before it (images and compose files), WSL only what was just pushed.

**Restoring a dump:**

```sh
cd /srv/randogeddon
gzip -t /srv/backups/<dump>.sql.gz          # a damaged file stops here
docker compose stop tracker gateway
docker compose exec -T postgres psql -X -U randogeddon -d postgres -v ON_ERROR_STOP=1 \
  -c 'DROP DATABASE randogeddon' -c 'CREATE DATABASE randogeddon OWNER randogeddon'
docker compose run --rm database-roles      # the roles the dump's policy names
gunzip -c /srv/backups/<dump>.sql.gz | docker compose exec -T postgres \
  psql -X -U randogeddon -d randogeddon -v ON_ERROR_STOP=1 --single-transaction
docker compose up -d                        # the tables to the tracker's role, the gateway's rights made again
```

It stops at the first error and then leaves the database empty: nothing is restored by half. A dump is whole or not
there (written under `.part`, renamed once `pg_dump` finished and its last line is in it; a switch stops before it
changes anything if its dump fails). Dumps hold no owners or rights (made at every start); they name the gateway's
role in a policy, so `GATEWAY_DB_USER` must be the server's. A dump from before 0.23.4 carries owners and rights
and restores this way only where those roles exist. What a dump doesn't hold: `TOTP_KEY` or the key file in the
tracker's state volume (without it the two-factor secrets are lost), and `data/` (seeds, saves). On a new server:
`docker compose up -d postgres` first, then from `run --rm database-roles` on.

## Updating Archipelago

A session runs only with the release it was generated with (`/opt/archipelago/<version>` in the gateway image). To
update: `AP_VERSION` to the new tag and `AP_COMMIT` to its commit
(`git ls-remote https://github.com/ArchipelagoMW/Archipelago refs/tags/<version>`; the build checks it), `docker
compose build`, then recreate the gateway once no session of the old release runs. The new release starts with an
empty APWorld library; the old one's can be tried with it (*From other releases*).

The new release needs its lock, `archipelago/requirements-<version>.lock`, or the build stops: build the
`archipelago-build` stage once with Archipelago's own updater installing (as the Dockerfile did before 0.23.7:
`python ModuleUpdate.py -y` in place of the lock's `pip install`), take `pip freeze --all` from it, and write each
`name==version` line with the sha256 of every file PyPI has for it (`https://pypi.org/pypi/<name>/<version>/json`),
at Archipelago's own pin where it has one. Lines with a git address go unchanged into `…<version>.git.txt` (each
must end in its commit), what is kept newer on purpose (certifi) into `…<version>.newer.lock`. The build then
checks that Archipelago's updater finds nothing to install.
