#!/usr/bin/env bash
# Updates the live server from this working tree, run in WSL from the repository:
#   ./scripts/deploy.sh             build the native images, push them to the registry tagged with the version (the
#                                   commit tagged v<version>), or else with the commit; dump the database, switch the
#                                   server to the new (fixed) tag, check that it runs; only then the moving tag
#                                   (`latest`, or `edge`) and a release's bridge and GitHub release
#   ./scripts/deploy.sh --publish   build and push the same, and a release's bridge and GitHub release, but leave the
#                                   server alone: nothing is copied there and it keeps running what it runs
#   ./scripts/deploy.sh --switch    build nothing: switch the server to this commit's images as they are in the
#                                   registry (published before, with --publish), with the dump and the checks
#   ./scripts/deploy.sh --rollback  back to the last version that passed the switch's check, with its own compose files
#                                   (the code only: a migration that ran needs the dump, see .docs/OPERATIONS.md,
#                                   restoring)
# A release is the commit of a tag v<version> signed by a key named in release-signers. Before anything of it is
# pushed it is audited (scripts/audit.sh on the three images just built; SKIP_AUDIT=1 passes a known finding). It also
# publishes Randogeddon Bridge's builds (scripts/publish-bridge.sh: Artifact Keeper and a GitHub release). Its builds
# are signed: without the signing key (BRIDGE_SIGNING_KEY) a release isn't built, and one whose image doesn't bring a
# signature that checks out against bridge/signing-key.pub isn't pushed.
#
# The server holds only what runs (.docs/OPERATIONS.md, "The live server"); this script copies its compose files there
# (kept per version in /srv/randogeddon/releases/<tag>/, for a rollback), and the backup script and its systemd units.
# Needs: `docker login` to the registry (a token that may push), the Gradle init script for the build, and an SSH alias
# DEPLOY_SSH (default randogeddon-live) for the server, e.g. in ~/.ssh/config:
#   Host randogeddon-live
#     HostName <the server's address>
#     User randogeddon
#     ProxyJump <a host in front of it, if any>
# The registry and the mirrors are this machine's settings, not the repository's: IMAGES, RANDOGEDDON_MAVEN (and
# RANDOGEDDON_MAVEN_NAME) and BRIDGE_REGISTRY from the environment or from ~/.config/randogeddon/deploy.env (DEPLOY_SETTINGS), lines like
#   IMAGES=registry.example.org/randogeddon
set -euo pipefail
cd "$(dirname "$0")/.."

settings="${DEPLOY_SETTINGS:-$HOME/.config/randogeddon/deploy.env}"
if [[ -r "$settings" ]]; then set -a; source "$settings"; set +a; fi
# The project's images in the registry: <IMAGES>/tracker, <IMAGES>/gateway, <IMAGES>/minecraft
IMAGES="${IMAGES:?set IMAGES (the registry and path of the images, e.g. registry.example.org/randogeddon) in $settings}"
# It goes to the server as an argument and into its .env: a registry's name and path, nothing else
[[ "$IMAGES" =~ ^[A-Za-z0-9._:/-]+$ ]] || { echo "IMAGES isn't a registry's name and path ('$IMAGES'): see $settings"; exit 1; }
export RANDOGEDDON_MAVEN="${RANDOGEDDON_MAVEN:-}" RANDOGEDDON_MAVEN_NAME="${RANDOGEDDON_MAVEN_NAME:-}"
DEPLOY_SSH="${DEPLOY_SSH:-randogeddon-live}"
AP_VERSION="$(sed -n 's/.*AP_VERSION:-\([0-9.]*\)}.*/\1/p' docker-compose.yml | head -1)"
[[ -n "$AP_VERSION" ]] || { echo "No Archipelago version (AP_VERSION's default) in docker-compose.yml"; exit 1; }

remote() { ssh "$DEPLOY_SSH" "$@"; }
# A script on stdin run as root on the server; its values are arguments, quoted here, never part of its text
remote_root() { remote "sudo bash -s -- $(printf '%q ' "$@")"; }

# The server's files from this checkout, out of a folder of the SSH user's own (mktemp): the version's compose files
# to /srv/randogeddon/releases/<tag>/ (the switch puts them in place), the backup script and its systemd units.
# Arguments: the folder, the tag
read -r -d '' FILES <<'SH' || true
set -euo pipefail
upload="$1" tag="$2"
[[ "$upload" =~ ^/tmp/randogeddon-deploy\.[A-Za-z0-9]+$ ]] || { echo "Not the deploy's folder: '$upload'; nothing copied"; exit 1; }
trap 'rm -rf -- "$upload"' EXIT
[[ "$tag" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Not a tag: '$tag'; nothing copied"; exit 1; }
exec 9>/run/randogeddon-deploy.lock
flock -n 9 || { echo "Another deploy or rollback runs on the server: nothing copied"; exit 1; }
cd "$upload"
install -d -m 755 "/srv/randogeddon/releases/$tag"
install -m 644 docker-compose.yml docker-compose.proxy.yml "/srv/randogeddon/releases/$tag/"
install -m 755 backup.sh /usr/local/sbin/randogeddon-backup
install -m 644 randogeddon-backup.service randogeddon-backup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now randogeddon-backup.timer >/dev/null \
  || { echo "The daily dump's timer couldn't be enabled (systemctl status randogeddon-backup.timer): nothing switched"; exit 1; }
SH

# The server's switch to a tag, its checks; run there as root. Arguments: IMAGES, the tag, the gateway's tag (both
# empty: back to the last good version). In this order, so that a failure before the stop changes nothing, and one
# before the new settings are written puts the minecraft service's folder back and starts the version before again:
# pull, stop the tracker and the gateway, dump, the minecraft folder's move, the version's compose files and .env,
# start, check
read -r -d '' SWITCH <<'SH' || true
set -euo pipefail
IMAGES="$1" tag="$2" gateway_tag="$3"
cd /srv/randogeddon
fail() { echo "$*"; exit 1; }
# Without stdin: this script itself comes over it
compose() { docker compose "$@" </dev/null; }
# One at a time: a deploy and a rollback at once would mix their settings and their containers
exec 9>/run/randogeddon-deploy.lock
flock -n 9 || fail "Another deploy or rollback runs on the server: nothing switched"
[[ "$IMAGES" =~ ^[A-Za-z0-9._:/-]+$ ]] || fail "Not a registry's name and path: '$IMAGES'; nothing switched"
# The settings hold every secret: their owner's and root's only
if [[ -e .env ]]; then chmod 600 .env; else install -m 600 /dev/null .env; fi
# A last line without its newline would run into the next setting written
[[ ! -s .env || -z "$(tail -c1 .env)" ]] || echo >> .env
# Empty if missing (the first deploy has no tags yet)
setting() { grep -E "^$1=" .env | tail -1 | cut -d= -f2- || true; }
# The values written are names (checked: no | or &, which sed would take as its own)
set_setting() {
  if grep -qE "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else printf '%s=%s\n' "$1" "$2" >> .env; fi
}
check_name() { [[ "$2" =~ ^[A-Za-z0-9._-]+$ ]] || fail "Not a $1: '$2'; nothing switched"; }
# The tracker where Traefik reaches it: on the address the proxy overlay publishes its port on
address="$(setting PUBLISH_ADDRESS)"
case "$address" in "" | 0.0.0.0) address=localhost ;; esac
healthz="http://$address:8000/healthz"

current="$(setting IMAGE_TAG)" current_gateway="$(setting GATEWAY_TAG)"
# The last version that passed this switch's check (the running one, unless its switch failed) and the one before it
good="$(setting GOOD_IMAGE_TAG)" good_gateway="$(setting GOOD_GATEWAY_TAG)"
previous="$(setting PREVIOUS_IMAGE_TAG)" previous_gateway="$(setting PREVIOUS_GATEWAY_TAG)"
# Before 0.23.7 no version was marked good: the running one is if it answers now, else the one before it
if [[ -z "$good" && -n "$current" ]]; then
  if curl -sf "$healthz" >/dev/null; then
    good="$current" good_gateway="$current_gateway"
  else
    good="$previous" good_gateway="$previous_gateway" previous="" previous_gateway=""
  fi
fi
rollback=""
if [[ -z "$tag" ]]; then
  rollback=1
  # Back to the last good version; if that is the running one, to the one before it
  if [[ -n "$good" && "$good" != "$current" ]]; then
    tag="$good" gateway_tag="$good_gateway"
  else
    tag="$previous" gateway_tag="$previous_gateway"
  fi
  [[ -n "$tag" ]] || fail "No version to roll back to (none before the running one is known): nothing switched"
  echo "Rolling back to $tag"
fi
check_name tag "$tag"
check_name "gateway tag" "$gateway_tag"

# Each version's compose files as it was deployed: a rollback starts the version before with its own. The running
# version's are taken from the server's the first time (a version deployed before 0.23.7 has no folder yet)
if [[ "$current" =~ ^[A-Za-z0-9._-]+$ && ! -e "releases/$current" && -f docker-compose.yml ]]; then
  install -d -m 755 "releases/$current"
  for file in docker-compose.yml docker-compose.proxy.yml; do
    if [[ -f "$file" ]]; then install -m 644 "$file" "releases/$current/"; fi
  done
  echo "The running version's compose files are kept in /srv/randogeddon/releases/$current/"
fi
release="releases/$tag"
[[ -f "$release/docker-compose.yml" ]] || fail "No compose files of $tag in /srv/randogeddon/$release/ (a version deployed before 0.23.7 has none): nothing switched. Put that version's docker-compose.yml and docker-compose.proxy.yml there (git show <its tag or commit>:docker-compose.yml) and run this again"

# The minecraft service's folder: ./minecraft since 0.23.7 (out of data/, which the gateway's user can change), before
# that data/minecraft; it goes where the version's compose file mounts it. Never through a link: Docker would mount
# whatever it points to
if grep -qE '^[[:space:]]*-[[:space:]]*\./data/minecraft:/data/minecraft' "$release/docker-compose.yml"; then
  minecraft=data/minecraft other=minecraft
else
  minecraft=minecraft other=data/minecraft
fi
empty() { [[ -z "$(ls -A "$1")" ]]; }
check_minecraft() {
  local path
  for path in minecraft data/minecraft; do
    [[ ! -L "$path" ]] || fail "/srv/randogeddon/$path is a symbolic link: nothing switched (remove it, and look at what it points to and who made it)"
    [[ ! -e "$path" || -d "$path" ]] || fail "/srv/randogeddon/$path isn't a directory: nothing switched"
  done
  if [[ -d "$minecraft" && -d "$other" ]] && ! empty "$minecraft" && ! empty "$other"; then
    fail "Both /srv/randogeddon/minecraft/ and data/minecraft/ hold files: only one can be the minecraft service's (move the other away); nothing switched"
  fi
}
check_minecraft

# The dump is taken once the tracker and the gateway are stopped (nothing written after it is lost by a restore). A
# database that is there but doesn't run is refused: its migration would have no dump before it
first=""
[[ -f docker-compose.yml ]] || first=1
running=""
if [[ -z "$first" ]]; then
  running="$(compose ps --status running --services)" || fail "docker compose ps failed (above): nothing switched"
fi
dump=""
if grep -qx postgres <<<"$running"; then
  dump=1
elif docker volume inspect randogeddon_postgres-data >/dev/null 2>&1; then
  fail "The database (the volume randogeddon_postgres-data) is there, but postgres doesn't run: no dump can be taken. Start it (docker compose up -d postgres) and run this again; nothing switched"
else
  echo "No database yet (a first deploy): no dump"
fi
# The nightly dumps (randogeddon-backup.timer): nobody else is told when they fail
newest="$({ find /srv/backups -maxdepth 1 -name 'randogeddon-*-daily.sql.gz' -printf '%T@\n' 2>/dev/null || true; } | sort -n | tail -1)"
if [[ -z "$newest" ]]; then
  echo "Warning: no nightly dump in /srv/backups (systemctl status randogeddon-backup)"
else
  hours=$(( ($(date +%s) - ${newest%.*}) / 3600 ))
  if (( hours > 36 )); then
    echo "Warning: the newest nightly dump is $hours hours old (systemctl status randogeddon-backup)"
  else
    echo "The newest nightly dump is $hours hours old"
  fi
fi

# The images before anything stops: the version's three, and what else its compose files name that isn't here yet
# (postgres, by its digest). Its compose files are read with this server's .env here: a setting they need and .env
# lacks stops the switch now
echo "Pulling $tag"
for image in "$IMAGES/tracker:$tag" "$IMAGES/gateway:$gateway_tag" "$IMAGES/minecraft:$tag"; do
  docker pull -q "$image" >/dev/null || fail "$image couldn't be pulled: nothing switched"
done
files=""
IFS=: read -ra names <<<"$(setting COMPOSE_FILE)"
(( ${#names[@]} )) || names=(docker-compose.yml)
for file in "${names[@]}"; do
  if [[ -f "$release/${file##*/}" ]]; then
    file="$PWD/$release/${file##*/}"
  elif [[ "$file" != /* ]]; then
    file="$PWD/$file"
  fi
  files+="${files:+:}$file"
done
# Behind a proxy (the proxy or the Traefik overlay) compose refuses every command without these two: said here, while
# the version before still runs (and its nightly dump still works)
if [[ "$files" == *docker-compose.proxy.yml* || "$files" == *docker-compose.traefik.yml* ]]; then
  for name in TRUSTED_PROXIES BASE_DOMAIN; do
    [[ -n "$(setting "$name")" ]] \
      || fail "/srv/randogeddon/.env has no $name, which $tag's compose files need (TRUSTED_PROXIES: Traefik's address; BASE_DOMAIN: the dashboard's domain): add it there and run this again; nothing switched"
  done
fi
images="$(COMPOSE_FILE="$files" TRACKER_IMAGE="$IMAGES/tracker" GATEWAY_IMAGE="$IMAGES/gateway" \
  MINECRAFT_IMAGE="$IMAGES/minecraft" IMAGE_TAG="$tag" GATEWAY_TAG="$gateway_tag" \
  docker compose --project-directory "$PWD" config --images </dev/null)" \
  || fail "$tag's compose files don't fit this server's .env (above): nothing switched"
for image in $images; do
  docker image inspect "$image" >/dev/null 2>&1 || docker pull -q "$image" >/dev/null \
    || fail "$image couldn't be pulled: nothing switched"
done

# The tracker and the gateway stop (the gateway's game servers save on /exit), the minecraft service too if its folder
# moves. Until the new settings are written, a failure starts again what was stopped
stop=(tracker gateway)
if [[ -d "$other" ]]; then stop+=(minecraft); fi
was_running=()
for service in "${stop[@]}"; do
  if grep -qx "$service" <<<"$running"; then was_running+=("$service"); fi
done
# What became of the minecraft service's folder before the new settings: moved (from $other/ to $minecraft/), removed
# (an empty $other/, with its owner and mode in other_mode), failed (a move that stopped halfway), or nothing
state="" moved="" other_mode=""
on_exit() {
  local status=$?
  local restart=()
  if (( ${#was_running[@]} )); then restart=("${was_running[@]}"); fi
  # Not switched: the folder goes back where the version before mounts it, before that one starts again
  if (( status != 0 )) && [[ "$state" == "" || "$state" == stopped ]]; then
    if [[ "$moved" == moved ]]; then
      if mv -T "$minecraft" "$other"; then
        echo "The minecraft service's folder is back in $other/"
      else
        moved=failed
      fi
    elif [[ "$moved" == removed ]]; then
      install -d -o "${other_mode%% *}" -g "$(cut -d' ' -f2 <<<"$other_mode")" -m "${other_mode##* }" "$other" \
        || echo "The empty /srv/randogeddon/$other/ couldn't be made again (the version before mounts it): make it before the minecraft service starts"
    fi
    # A move that failed without moving anything (a rename either happens or not) left it where it was
    if [[ "$moved" == failed && -d "$other" && ! -e "$minecraft" ]]; then moved=""; fi
    if [[ "$moved" == failed ]]; then
      echo "The minecraft service's folder isn't wholly in /srv/randogeddon/$other/ (look at $other/ and $minecraft/): put it back there by hand, then docker compose start minecraft"
      local service kept=()
      for service in ${restart[@]+"${restart[@]}"}; do [[ "$service" == minecraft ]] || kept+=("$service"); done
      restart=(${kept[@]+"${kept[@]}"})
    fi
  fi
  if (( status != 0 )) && [[ "$state" == stopped ]] && (( ${#restart[@]} )); then
    echo "Starting the version before again: ${restart[*]}"
    compose start "${restart[@]}" || echo "They didn't start: docker compose up -d (the settings are still the version before's)"
  elif (( status != 0 )) && [[ "$state" == switched && -n "$rollback" ]]; then
    echo "The server is on $tag's compose files and settings, which didn't pass: look at its logs (docker compose logs)"
  elif (( status != 0 )) && [[ "$state" == switched ]]; then
    echo "The server is on $tag's compose files and settings, which didn't pass; the last good version is ${good:-not known}: ./scripts/deploy.sh --rollback"
  fi
}
trap on_exit EXIT
if (( ${#was_running[@]} )); then
  state=stopped
  compose stop "${stop[@]}"
fi
if [[ -n "$dump" ]]; then
  # As the nightly one: whole or not there, for root only
  /usr/local/sbin/randogeddon-backup "before-$tag" </dev/null || fail "The database dump failed (above): nothing switched"
fi
# Checked again: the gateway, which could change data/, is stopped now
check_minecraft
[[ -d data ]] || install -d -o 1000 -g 1000 data
# Until the new settings are written, a failure puts it back (on_exit)
if [[ -d "$other" ]]; then
  if [[ -d "$minecraft" ]] && empty "$other"; then
    other_mode="$(stat -c '%u %g %a' -- "$other")"
    rmdir "$other"
    moved=removed
  else
    if [[ -d "$minecraft" ]]; then rmdir "$minecraft"; fi
    mv -T "$other" "$minecraft" || { moved=failed; fail "The minecraft service's folder couldn't be moved from $other/ to $minecraft/ (above): nothing switched"; }
    moved=moved
    echo "The minecraft service's folder moved from $other/ to $minecraft/"
  fi
fi
# There before the service starts, the gateway's user's (Docker would make it as root)
[[ -d "$minecraft" ]] || install -d -o 1000 -g 1000 "$minecraft"

state=switched
install -m 644 "$release"/docker-compose*.yml .
sed -i '/^IMAGE_REGISTRY=/d' .env
if [[ -n "$good" ]]; then
  set_setting GOOD_IMAGE_TAG "$good"
  set_setting GOOD_GATEWAY_TAG "$good_gateway"
fi
set_setting TRACKER_IMAGE "$IMAGES/tracker"
set_setting GATEWAY_IMAGE "$IMAGES/gateway"
set_setting MINECRAFT_IMAGE "$IMAGES/minecraft"
set_setting IMAGE_TAG "$tag"
set_setting GATEWAY_TAG "$gateway_tag"
# Its own token, never INTERNAL_TOKEN: made once where the server has none; without it no Minecraft is hosted
if [[ -z "$(setting MINECRAFT_TOKEN)" ]]; then
  set_setting MINECRAFT_TOKEN "$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  echo "No MINECRAFT_TOKEN in .env: a new one is set there"
fi
if ! out="$(compose up -d --no-build --remove-orphans 2>&1)"; then
  printf '%s\n' "$out"
  fail "docker compose up failed"
fi
printf '%s\n' "$out" | tail -3

# The check: the tracker answers; the gateway (only once it has its database login) and the minecraft service accept
# connections on their ports; neither starts again and again
for _ in $(seq 1 60); do
  curl -sf "$healthz" >/dev/null && break
  sleep 2
done
curl -sf "$healthz" >/dev/null || { compose logs --tail 30 database-roles tracker; fail "The tracker isn't healthy (its log above)"; }
listens() { compose exec -T "$1" timeout 3 bash -c "exec 3<>/dev/tcp/127.0.0.1/$2" >/dev/null 2>&1; }
state_of() {
  local id
  id="$(compose ps -q "$1" || true)"
  if [[ -n "$id" ]]; then docker inspect -f '{{.State.Status}}/{{.RestartCount}}' "$id" 2>/dev/null || echo gone; else echo gone; fi
}
for check in gateway:38281 minecraft:8002; do
  service="${check%:*}" port="${check#*:}"
  for _ in $(seq 1 90); do
    listens "$service" "$port" && break
    sleep 2
  done
  listens "$service" "$port" || { compose logs --tail 30 "$service"; fail "The $service doesn't accept connections on its port $port (its log above)"; }
done
declare -A seen=()
for service in gateway minecraft; do seen[$service]="$(state_of "$service")"; done
sleep 15
for service in gateway minecraft; do
  now="$(state_of "$service")"
  [[ "$now" == running/* && "$now" == "${seen[$service]}" ]] \
    || { compose logs --tail 30 "$service"; fail "The $service started again or stopped within 15 seconds (status/restarts: ${seen[$service]}, then $now; its log above)"; }
done
schema="$(compose exec -T postgres psql -U randogeddon -d randogeddon -tAc 'select max(version::int) from flyway_schema_history' || true)"
echo "Running: tracker and minecraft $tag, gateway $gateway_tag; schema version ${schema:-unknown}"

# Passed: the last good version now; the good one before it is kept for a rollback (after a rollback to that one,
# none before it is known any more)
state=done
if [[ -n "$rollback" ]]; then
  if [[ "$tag" != "$good" ]]; then sed -i '/^PREVIOUS_IMAGE_TAG=/d; /^PREVIOUS_GATEWAY_TAG=/d' .env; fi
elif [[ -n "$good" && "$good" != "$tag" ]]; then
  set_setting PREVIOUS_IMAGE_TAG "$good"
  set_setting PREVIOUS_GATEWAY_TAG "$good_gateway"
fi
set_setting GOOD_IMAGE_TAG "$tag"
set_setting GOOD_GATEWAY_TAG "$gateway_tag"
# Only the running version and the good one before it (for a rollback) stay on the server: images and compose files
keep=" $tag $gateway_tag $(setting PREVIOUS_IMAGE_TAG) $(setting PREVIOUS_GATEWAY_TAG) "
docker images --format '{{.Repository}}:{{.Tag}}' "$IMAGES/*" | while IFS= read -r image; do
  [[ "$keep" == *" ${image##*:} "* ]] || docker rmi -f "$image" >/dev/null || true
done
for folder in releases/*/; do
  folder="${folder%/}"
  [[ ! -d "$folder" || "$keep" == *" ${folder#releases/} "* ]] || rm -rf -- "$folder"
done
docker image prune -f >/dev/null
SH

if [[ "${1:-}" == "--rollback" ]]; then
  remote_root "$IMAGES" "" "" <<<"$SWITCH"
  exit
fi

live=1 build=1
case "${1:-}" in
  "") ;;
  --publish) live= ;;
  --switch) build= ;;
  *) echo "Unknown option $1 (--publish, --switch, --rollback)"; exit 1 ;;
esac

[[ -z "$(git status --porcelain)" ]] || { echo "Uncommitted changes: deploy a commit"; exit 1; }
# A release (the commit tagged v<version>, see .docs/DEVELOPMENT.md, versions) goes out under its version and `latest`, anything else
# under its commit (the gateway's with the Archipelago release in front) and `edge`. The server runs the fixed tag.
version="$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)"
[[ "$version" =~ ^[0-9]+(\.[0-9]+)*(-SNAPSHOT)?$ ]] || { echo "No version in build.gradle.kts that this script can read ('$version')"; exit 1; }
if [[ "$(git rev-parse -q --verify "refs/tags/v$version^{commit}" || true)" == "$(git rev-parse HEAD)" ]]; then
  # A release is what its tag's signature vouches for: made by a key whose (primary) fingerprint the tagged commit's
  # release-signers names, checked against this machine's keyring (the signers' public keys: gpg --import). The
  # repository has no branch protection, and a tag can be moved; a signature can't be made. Check it before running
  # this script too (git verify-tag v<version>): a changed tree could bring a changed script and a changed list.
  signers="$(git show "v$version:release-signers" 2>/dev/null | sed 's/#.*//' | tr -d ' \t\r' | tr 'a-f' 'A-F' \
    | grep -E '^([0-9A-F]{40}|[0-9A-F]{64})$' || true)"
  [[ -n "$signers" ]] || { echo "v$version has no release-signers naming a key's fingerprint: its signer can't be checked; not deploying it"; exit 1; }
  verified="$(git verify-tag --raw "v$version" 2>&1 || true)"
  if ! grep -q '^\[GNUPG:\] GOODSIG ' <<<"$verified"; then
    echo "The tag v$version has no signature that checks out here (git verify-tag v$version); not deploying it"
    exit 1
  fi
  # VALIDSIG's last field: the primary key's fingerprint (of a subkey's signature too)
  signer="$(awk '$1 == "[GNUPG:]" && $2 == "VALIDSIG" && NF >= 12 { print toupper($12); exit }' <<<"$verified")"
  if [[ -z "$signer" ]] || ! grep -qxF "$signer" <<<"$signers"; then
    echo "v$version is signed by ${signer:-a key gpg gave no fingerprint for}, which release-signers doesn't name; not deploying it"
    exit 1
  fi
  echo "== v$version is signed by $(sed -n 's/^\[GNUPG:\] GOODSIG [0-9A-F]* //p' <<<"$verified" | head -1) ($signer, in release-signers)"
  tag="$version" gateway_tag="$version" moving=latest
else
  tag="$(git rev-parse --short=12 HEAD)" gateway_tag="$AP_VERSION-$(git rev-parse --short=12 HEAD)" moving=edge
fi

if [[ -n "$build" ]]; then
echo "== Building $tag"
# What is built is this checkout with the compose file's defaults: the checkout's .env isn't read (--env-file
# /dev/null), the image names and tags are set here (the images are built under the registry's names, so nothing older
# can be tagged and pushed in their place), and the Archipelago version is the compose file's. A release is built for
# every CPU the server may have and fully optimised
for name in NATIVE_MARCH NATIVE_OPTIMIZATION; do
  if [[ -n "${!name:-}" && "$moving" == latest ]]; then
    echo "$name is set (${!name}): a release is built with the compose file's default; not building v$version"
    exit 1
  elif [[ -n "${!name:-}" ]]; then
    echo "$name=${!name} for this build"
  fi
done
export COMPOSE_FILE=docker-compose.yml BUILDX_BAKE_ENTITLEMENTS_FS=0 BRIDGE_VERSION="$version"
# A release from a Gradle cache only releases write, and without Gradle's build cache: from its own sources
release_args=()
[[ "$moving" != latest ]] || release_args=(--build-arg GRADLE_CACHE_ID=gradle-release --build-arg GRADLE_NO_BUILD_CACHE=1)
compose_build() {
  env -u AP_VERSION -u AP_COMMIT POSTGRES_PASSWORD=build INTERNAL_TOKEN=build TRACKER_IMAGE="$IMAGES/tracker" \
    GATEWAY_IMAGE="$IMAGES/gateway" MINECRAFT_IMAGE="$IMAGES/minecraft" IMAGE_TAG="$tag" GATEWAY_TAG="$gateway_tag" \
    docker compose --env-file /dev/null build ${release_args[@]+"${release_args[@]}"} "$@"
}
# The bridge's checksums are signed with this machine's key, if it has one (ssh-keygen -t ed25519 -N "" -f <file>;
# its public half is bridge/signing-key.pub); a release's must be
signing_key="${BRIDGE_SIGNING_KEY:-$HOME/.config/randogeddon/bridge-signing-key}"
# The key's public half (no secret; empty: no key): the build's signing step is cached by it (kotlin.Dockerfile)
signing_key_id=""
if [[ -r "$signing_key" ]]; then
  export BRIDGE_SIGNING_KEY="$signing_key"
  signing_key_id="$(ssh-keygen -y -P "" -f "$signing_key" 2>/dev/null | cut -d' ' -f2 || true)"
else
  echo "No $signing_key: the bridge's checksums won't be signed"
fi
if [[ "$moving" == latest ]]; then
  public_key="$(awk '{ sub(/\r$/, ""); for (i = 1; i < NF; i++) if ($i ~ /^ssh-/) { print $(i + 1); exit } }' bridge/signing-key.pub)"
  if [[ -z "$signing_key_id" || "$signing_key_id" != "$public_key" ]]; then
    echo "A release's bridge builds are signed: $signing_key is missing, has a passphrase or isn't the key of bridge/signing-key.pub; not building v$version"
    exit 1
  fi
fi
# One after the other: each native-image compile takes several GB at its peak
compose_build --build-arg BRIDGE_SIGNING_KEY_ID="$signing_key_id" tracker
compose_build gateway
compose_build minecraft
if [[ "$moving" == latest ]]; then
  # A release's bridge goes out signed: its builds in the new image checked against bridge/signing-key.pub before
  # anything is pushed
  ./scripts/publish-bridge.sh "$version" "$IMAGES/tracker:$tag" --check
  # And nothing known to be vulnerable in what was just built: 1 is a finding, 2 an audit that couldn't run
  echo "== Auditing v$version (scripts/audit.sh)"
  audit=0
  ./scripts/audit.sh --images "$IMAGES/tracker:$tag" "$IMAGES/gateway:$gateway_tag" "$IMAGES/minecraft:$tag" || audit=$?
  if (( audit != 0 )); then
    if (( audit == 1 )); then outcome="found something"; else outcome="couldn't run (exit $audit)"; fi
    if [[ "${SKIP_AUDIT:-}" == 1 ]]; then
      echo "== The audit $outcome: going on anyway (SKIP_AUDIT=1)"
    else
      echo "The audit $outcome (above): not pushing v$version (SKIP_AUDIT=1 passes a known finding)"
      exit 1
    fi
  fi
fi
docker tag "$IMAGES/tracker:$tag" "$IMAGES/tracker:$moving"
docker tag "$IMAGES/gateway:$gateway_tag" "$IMAGES/gateway:$moving"
docker tag "$IMAGES/minecraft:$tag" "$IMAGES/minecraft:$moving"
# The fixed tags now; the moving one once the server runs the version (or at once, with --publish)
for image in "tracker:$tag" "gateway:$gateway_tag" "minecraft:$tag"; do
  docker push -q "$IMAGES/$image"
done
else
  # Published before: all three are there, or nothing is switched
  for image in "tracker:$tag" "gateway:$gateway_tag" "minecraft:$tag"; do
    docker manifest inspect "$IMAGES/$image" >/dev/null 2>&1 || { echo "$image isn't in the registry: publish it first (--publish)"; exit 1; }
  done
  echo "== $tag as it is in the registry (nothing built)"
fi

if [[ -n "$live" ]]; then
  echo "== The server's files"
  upload="$(remote mktemp -d /tmp/randogeddon-deploy.XXXXXXXX)"
  [[ "$upload" =~ ^/tmp/randogeddon-deploy\.[A-Za-z0-9]+$ ]] || { echo "The server's mktemp gave '$upload': nothing copied"; exit 1; }
  scp -q docker-compose.yml docker-compose.proxy.yml scripts/backup.sh scripts/randogeddon-backup.service \
    scripts/randogeddon-backup.timer "$DEPLOY_SSH:$upload/"
  switched=1
  remote_root "$upload" "$tag" <<<"$FILES" || switched=""
  if [[ -n "$switched" ]]; then
    echo "== Switching to $tag"
    remote_root "$IMAGES" "$tag" "$gateway_tag" <<<"$SWITCH" || switched=""
  fi
  if [[ -z "$switched" ]]; then
    echo "== The switch to $tag failed (the server's words above)"
    if [[ -n "$build" && "$moving" == latest ]]; then
      echo "   v$version is in the registry under its own tag only: not as latest, and its bridge and GitHub release aren't published; once it is right, --publish completes that"
    elif [[ -n "$build" ]]; then
      echo "   $tag is in the registry under its own tag only, not as edge"
    fi
    exit 1
  fi
else
  echo "== Published only: the server keeps running what it runs"
fi

# Only what was built here is published and tidied up
[[ -n "$build" ]] || exit 0

echo "== $tag as $moving"
for service in tracker gateway minecraft; do
  docker push -q "$IMAGES/$service:$moving"
done
# A release's bridge, as its image serves it
[[ "$moving" != latest ]] || ./scripts/publish-bridge.sh "$version" "$IMAGES/tracker:$tag"

# Here too only what was just pushed stays (the build's cache is kept for the next build)
docker images --format '{{.Repository}}:{{.Tag}}' "$IMAGES/*" | while IFS= read -r image; do
  case "${image##*:}" in "$tag" | "$gateway_tag" | "$moving") ;; *) docker rmi "$image" >/dev/null || true ;; esac
done
docker image prune -f >/dev/null
