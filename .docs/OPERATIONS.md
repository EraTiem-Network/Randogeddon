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
  `docker save randogeddon-tracker:latest randogeddon-gateway:latest randogeddon-minecraft:latest | ssh you@server docker load`.

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
- **With the file provider** (Traefik on a host, Randogeddon in a VM): `docker-compose.proxy.yml` publishes 38281 and
  8000; the routers:

```yaml
http:
  routers:
    randogeddon-ap:                     # every session's game server: the gateway (WebSocket)
      rule: "HostRegexp(`^[a-z0-9-]+[.]randogeddon[.]eratiem[.]net$`)"   # [.]: no escaping in YAML's quotes
      entryPoints: [websecure]
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

- **Database logins of their own** (`openssl rand -hex 32` each): `TRACKER_DB_USER` / `TRACKER_DB_PASSWORD` (made by
  the one-off `database-roles` before each start; the superuser's password then stays with it and the dumps) and
  `GATEWAY_DB_USER` / `GATEWAY_DB_PASSWORD` (made by the tracker; only what the gateway needs, since Archipelago's
  Python runs beside it). Without them the tracker logs in as the superuser and the gateway as the tracker.
- **`TRUSTED_PROXIES`** (addresses or CIDR ranges, comma-separated): only these peers' `X-Forwarded-For` is believed
  for the rate limits and the streams' counts. Unset: every peer's, as before 0.23.1; set it to Traefik's address
  wherever port 8000 can be reached past Traefik (`docker-compose.proxy.yml` publishes it).
- **Mail:** `MAIL_HOST`, `MAIL_USER`, `MAIL_PASSWORD`, `MAIL_FROM` (`MAIL_ADDRESS`: an internal address for a server
  whose public one leads back to this host). Without `MAIL_HOST` mails only go to the tracker's log (whole, with their
  links: for local testing); with it, the log names a mail's account, never its address.
- **`TOTP_KEY`** (`openssl rand -base64 32`) encrypts the two-factor secrets: keep it with the backups.
- **Hosted Minecraft:** `MINECRAFT_EULA=true` accepts Mojang's EULA for every hosted server (nothing runs without
  it); `MINECRAFT_SERVERS` at once, `MINECRAFT_MEMORY` each.
- The gateway runs as uid 1000 and writes `data/`; its container is limited by `GATEWAY_MEMORY` (5 GB).

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
| `.env`, `docker-compose.yml`, `docker-compose.proxy.yml`, `data/`, `custom_worlds/` | `/srv/randogeddon/` |
| the database | the volume `randogeddon_postgres-data` (the TOTP key file without `TOTP_KEY`: `randogeddon_tracker-state`) |
| dumps (each update's, a daily one; 14 days; root only) | `/srv/backups/` |
| the daily dump | `/usr/local/sbin/randogeddon-backup`, `randogeddon-backup.timer` (01:45) |

`scripts/deploy.sh` keeps the image settings in its `.env`: `TRACKER_IMAGE`, `GATEWAY_IMAGE`, `MINECRAFT_IMAGE`,
`IMAGE_TAG` and `GATEWAY_TAG` (the running version; for an `edge` build the commit), `PREVIOUS_IMAGE_TAG` and
`PREVIOUS_GATEWAY_TAG` (for a rollback); `GRADLE_INIT_SCRIPT=/dev/null`. The server is logged in to the registry
with a read-only token.

## Deploying

In WSL, from a committed state. A release only from its signed tag `v<version>` (checked against the machine's
keyring: `gpg --import` the signers' keys there), anything else as `edge`, unchecked:

```sh
./scripts/deploy.sh             # build, push, dump, switch, check (healthz, schema); a release's bridge and GitHub release
./scripts/deploy.sh --publish   # build and push only; the server isn't touched
./scripts/deploy.sh --switch    # build nothing: the server to this commit's images as published (dump, switch, check)
./scripts/deploy.sh --rollback  # back to the previous tags (code only: a migration that ran needs its dump)
```

Needed once on the PC: `~/.config/randogeddon/deploy.env` with `IMAGES` (the registry's path), `RANDOGEDDON_MAVEN` and
`BRIDGE_REGISTRY`; `docker login` with a token that may push; an SSH alias (`DEPLOY_SSH`, default `randogeddon-live`);
for the bridge, `~/.config/randogeddon/bridge-publish.token` (mode 0600) and the signing key
`~/.config/randogeddon/bridge-signing-key` (keep a copy: a new key means a new `bridge/signing-key.pub`); from 1.0.0
on, `gh` logged in. The server keeps the running and the previous images, WSL only what was just pushed.

A rollback to before 0.21.6 needs a `minecraft` image tagged for it first
(`docker tag <IMAGES>/minecraft:0.21.6 <IMAGES>/minecraft:<that tag>`).

**Restoring a dump:**

```sh
cd /srv/randogeddon && docker compose stop tracker gateway
docker compose exec -T postgres psql -U randogeddon -d postgres -c 'DROP DATABASE randogeddon' -c 'CREATE DATABASE randogeddon OWNER randogeddon'
gunzip -c /srv/backups/<dump>.sql.gz | docker compose exec -T postgres psql -U randogeddon -d randogeddon
docker compose up -d
```

## Updating Archipelago

A session runs only with the release it was generated with (`/opt/archipelago/<version>` in the gateway image). To
update: `AP_VERSION` to the new tag and `AP_COMMIT` to its commit
(`git ls-remote https://github.com/ArchipelagoMW/Archipelago refs/tags/<version>`; the build checks it), `docker
compose build`, then recreate the gateway once no session of the old release runs. The new release starts with an
empty APWorld library; the old one's can be tried with it (*From other releases*).
