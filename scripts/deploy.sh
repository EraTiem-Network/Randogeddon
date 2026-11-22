#!/usr/bin/env bash
# Updates the live server from this working tree, run in WSL from the repository:
#   ./scripts/deploy.sh             build the native images, push them to the registry tagged with the version and
#                                   `latest` (the commit tagged v<version>), or else the commit and `edge`; dump the
#                                   database, switch the server to the new (fixed) tag, check that it runs
#   ./scripts/deploy.sh --publish   build and push the same, and a release's bridge and GitHub release, but leave the
#                                   server alone: nothing is copied there and it keeps running what it runs
#   ./scripts/deploy.sh --switch    build nothing: switch the server to this commit's images as they are in the
#                                   registry (published before, with --publish), with the dump and the checks
#   ./scripts/deploy.sh --rollback  back to the tag before (the code only: a migration that ran needs the dump, see
#                                   .docs/OPERATIONS.md, restoring)
# A release also publishes Randogeddon Bridge's builds (scripts/publish-bridge.sh: Artifact Keeper, and from 1.0.0 on a
# GitHub release).
#
# The server holds only what runs (.docs/OPERATIONS.md, "The live server"); this script copies its compose files and the backup
# script there. Needs: `docker login` to the registry (a token that may push), the Gradle init script for the build, and
# an SSH alias DEPLOY_SSH (default randogeddon-live) for the server, e.g. in ~/.ssh/config:
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
# The project's images in the registry: <IMAGES>/tracker, <IMAGES>/gateway
IMAGES="${IMAGES:?set IMAGES (the registry and path of the images, e.g. registry.example.org/randogeddon) in $settings}"
export RANDOGEDDON_MAVEN="${RANDOGEDDON_MAVEN:-}" RANDOGEDDON_MAVEN_NAME="${RANDOGEDDON_MAVEN_NAME:-}"
DEPLOY_SSH="${DEPLOY_SSH:-randogeddon-live}"
DIR=/srv/randogeddon
AP_VERSION="$(sed -n 's/.*AP_VERSION:-\([0-9.]*\)}.*/\1/p' docker-compose.yml | head -1)"

remote() { ssh "$DEPLOY_SSH" "$@"; }

# The server's switch to a tag, its checks; run there as root with the new tags as arguments (empty: the previous ones)
read -r -d '' SWITCH <<'SH' || true
set -euo pipefail
cd /srv/randogeddon
# Empty if missing (the first deploy has no tags yet)
setting() { grep -E "^$1=" .env | tail -1 | cut -d= -f2- || true; }
set_setting() {
  if grep -qE "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else printf '%s=%s\n' "$1" "$2" >> .env; fi
}
tag="$1" gateway_tag="$2"
if [[ -z "$tag" ]]; then
  tag="$(setting PREVIOUS_IMAGE_TAG)" gateway_tag="$(setting PREVIOUS_GATEWAY_TAG)"
  [[ -n "$tag" ]] || { echo "No previous tag to roll back to"; exit 1; }
fi
current="$(setting IMAGE_TAG)" current_gateway="$(setting GATEWAY_TAG)"
# Before every switch: the database as it is
if docker compose ps --status running --services 2>/dev/null | grep -qx postgres; then
  mkdir -p /srv/backups
  chmod 700 /srv/backups
  dump="/srv/backups/randogeddon-$(date +%Y%m%d-%H%M%S)-before-${tag}.sql.gz"
  # </dev/null: this script itself comes over stdin, which `exec -T` would read. The dump (password hashes, addresses,
  # the slot passwords) for root only
  (umask 077; docker compose exec -T postgres pg_dump -U randogeddon randogeddon </dev/null | gzip > "$dump")
  echo "Database dumped to $dump"
fi
sed -i '/^IMAGE_REGISTRY=/d' .env
set_setting TRACKER_IMAGE "$IMAGES/tracker"
set_setting GATEWAY_IMAGE "$IMAGES/gateway"
set_setting MINECRAFT_IMAGE "$IMAGES/minecraft"
set_setting IMAGE_TAG "$tag"
set_setting GATEWAY_TAG "$gateway_tag"
if [[ -n "$current" && "$current" != "$tag" ]]; then
  set_setting PREVIOUS_IMAGE_TAG "$current"
  set_setting PREVIOUS_GATEWAY_TAG "$current_gateway"
fi
docker compose pull -q tracker gateway minecraft
docker compose up -d --no-build --remove-orphans 2>&1 | tail -3
for _ in $(seq 1 60); do
  curl -sf http://localhost:8000/healthz >/dev/null && break
  sleep 2
done
curl -sf http://localhost:8000/healthz >/dev/null || { echo "The tracker isn't healthy: docker compose logs tracker"; exit 1; }
echo "Running: tracker $tag, gateway $gateway_tag; schema version $(docker compose exec -T postgres psql -U randogeddon -d randogeddon -tAc 'select max(version::int) from flyway_schema_history' </dev/null)"
# Only the running images and the ones before (for a rollback) stay on the server
keep=" $(setting IMAGE_TAG) $(setting GATEWAY_TAG) $(setting PREVIOUS_IMAGE_TAG) $(setting PREVIOUS_GATEWAY_TAG) "
docker images --format '{{.Repository}}:{{.Tag}}' "$IMAGES/*" | while IFS= read -r image; do
  [[ "$keep" == *" ${image##*:} "* ]] || docker rmi -f "$image" >/dev/null || true
done
docker image prune -f >/dev/null
SH

if [[ "${1:-}" == "--rollback" ]]; then
  remote "sudo IMAGES='$IMAGES' bash -s -- '' ''" <<<"$SWITCH"
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
if [[ "$(git rev-parse -q --verify "refs/tags/v$version^{commit}" || true)" == "$(git rev-parse HEAD)" ]]; then
  # A release is what its tag's signature vouches for: checked against this machine's keyring (the signers' public
  # keys: gpg --import). The repository has no branch protection, and a tag can be moved; a signature can't be made.
  # Check it before running this script too (git verify-tag v<version>): a changed tree could bring a changed script.
  if ! git verify-tag --raw "v$version" 2>&1 | grep -q '^\[GNUPG:\] GOODSIG '; then
    echo "The tag v$version has no signature that checks out here (git verify-tag v$version); not deploying it"
    exit 1
  fi
  echo "== v$version is signed by $(git verify-tag --raw "v$version" 2>&1 | sed -n 's/^\[GNUPG:\] GOODSIG [0-9A-F]* //p' | head -1)"
  tag="$version" gateway_tag="$version" moving=latest
else
  tag="$(git rev-parse --short=12 HEAD)" gateway_tag="$AP_VERSION-$(git rev-parse --short=12 HEAD)" moving=edge
fi

if [[ -n "$build" ]]; then
echo "== Building $tag"
export COMPOSE_FILE=docker-compose.yml BUILDX_BAKE_ENTITLEMENTS_FS=0 BRIDGE_VERSION="$version"
# The bridge's checksums are signed with this machine's key, if it has one (ssh-keygen -t ed25519 -N "" -f <file>;
# its public half is bridge/signing-key.pub)
signing_key="${BRIDGE_SIGNING_KEY:-$HOME/.config/randogeddon/bridge-signing-key}"
if [[ -r "$signing_key" ]]; then export BRIDGE_SIGNING_KEY="$signing_key"; else echo "No $signing_key: the bridge's checksums won't be signed"; fi
# One after the other: each native-image compile takes several GB at its peak
POSTGRES_PASSWORD=build INTERNAL_TOKEN=build docker compose build tracker
POSTGRES_PASSWORD=build INTERNAL_TOKEN=build docker compose build gateway
POSTGRES_PASSWORD=build INTERNAL_TOKEN=build docker compose build minecraft
docker tag randogeddon-tracker:latest "$IMAGES/tracker:$tag"
docker tag "randogeddon-gateway:$AP_VERSION" "$IMAGES/gateway:$gateway_tag"
docker tag randogeddon-tracker:latest "$IMAGES/tracker:$moving"
docker tag "randogeddon-gateway:$AP_VERSION" "$IMAGES/gateway:$moving"
docker tag randogeddon-minecraft:latest "$IMAGES/minecraft:$tag"
docker tag randogeddon-minecraft:latest "$IMAGES/minecraft:$moving"
for image in "tracker:$tag" "gateway:$gateway_tag" "minecraft:$tag" "tracker:$moving" "gateway:$moving" "minecraft:$moving"; do
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
remote "mkdir -p /tmp/randogeddon-deploy"
scp -q docker-compose.yml docker-compose.proxy.yml scripts/backup.sh scripts/randogeddon-backup.service \
  scripts/randogeddon-backup.timer "$DEPLOY_SSH:/tmp/randogeddon-deploy/"
remote sudo bash -s <<SH
set -euo pipefail
cd /tmp/randogeddon-deploy
install -m 644 docker-compose.yml docker-compose.proxy.yml $DIR/
install -m 755 backup.sh /usr/local/sbin/randogeddon-backup
install -m 644 randogeddon-backup.service randogeddon-backup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now randogeddon-backup.timer >/dev/null 2>&1
rm -rf /tmp/randogeddon-deploy
SH

echo "== Switching to $tag"
remote "sudo IMAGES='$IMAGES' bash -s -- '$tag' '$gateway_tag'" <<<"$SWITCH"
else
  echo "== Published only: the server keeps running what it runs"
fi

# Only what was built here is published and tidied up
[[ -n "$build" ]] || exit 0

# A release's bridge, as its image serves it
[[ "$moving" != latest ]] || ./scripts/publish-bridge.sh "$version" "$IMAGES/tracker:$tag"

# Here too only what was just pushed stays (the build's own images and its cache are kept for the next build)
docker images --format '{{.Repository}}:{{.Tag}}' "$IMAGES/*" | while IFS= read -r image; do
  case "${image##*:}" in "$tag" | "$gateway_tag" | "$moving") ;; *) docker rmi "$image" >/dev/null || true ;; esac
done
docker image prune -f >/dev/null
