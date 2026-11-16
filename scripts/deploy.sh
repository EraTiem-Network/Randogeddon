#!/usr/bin/env bash
# Updates the live server (the live VM) from this working tree, run in WSL from the repository:
#   ./scripts/deploy.sh             build the native images, push them to the registry tagged with the version and
#                                   `latest` (the commit tagged v<version>), or else the commit and `edge`; dump the
#                                   database, switch the server to the new (fixed) tag, check that it runs
#   ./scripts/deploy.sh --rollback  back to the tag before (the code only: a migration that ran needs the dump, see
#                                   README, restoring)
# A release also publishes Randogeddon Bridge's builds (scripts/publish-bridge.sh: Artifact Keeper, and from 1.0.0 on a
# GitHub release).
#
# The server holds only what runs (README, "The live server"); this script copies its compose files and the backup
# script there. Needs: `docker login pkg.example.org` (the deploy token), the Gradle init script for the build, and
# an SSH alias DEPLOY_SSH (default randogeddon-live) for the server, e.g. in ~/.ssh/config:
#   Host randogeddon-live
#     HostName <internal-ip>
#     User randogeddon
#     ProxyJump root@example.org
set -euo pipefail
cd "$(dirname "$0")/.."

# The project's images in the registry: <IMAGES>/tracker, <IMAGES>/gateway
IMAGES="${IMAGES:-pkg.example.org/eratiem-network/randogeddon}"
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
set_setting IMAGE_TAG "$tag"
set_setting GATEWAY_TAG "$gateway_tag"
if [[ -n "$current" && "$current" != "$tag" ]]; then
  set_setting PREVIOUS_IMAGE_TAG "$current"
  set_setting PREVIOUS_GATEWAY_TAG "$current_gateway"
fi
docker compose pull -q tracker gateway
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

[[ -z "$(git status --porcelain)" ]] || { echo "Uncommitted changes: deploy a commit"; exit 1; }
# A release (the commit tagged v<version>, see README, versions) goes out under its version and `latest`, anything else
# under its commit (the gateway's with the Archipelago release in front) and `edge`. The server runs the fixed tag.
version="$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)"
if [[ "$(git rev-parse -q --verify "refs/tags/v$version^{commit}" || true)" == "$(git rev-parse HEAD)" ]]; then
  tag="$version" gateway_tag="$version" moving=latest
else
  tag="$(git rev-parse --short=12 HEAD)" gateway_tag="$AP_VERSION-$(git rev-parse --short=12 HEAD)" moving=edge
fi

echo "== Building $tag"
export COMPOSE_FILE=docker-compose.yml BUILDX_BAKE_ENTITLEMENTS_FS=0 BRIDGE_VERSION="$version"
POSTGRES_PASSWORD=build INTERNAL_TOKEN=build docker compose build tracker gateway
docker tag randogeddon-tracker:latest "$IMAGES/tracker:$tag"
docker tag "randogeddon-gateway:$AP_VERSION" "$IMAGES/gateway:$gateway_tag"
docker tag randogeddon-tracker:latest "$IMAGES/tracker:$moving"
docker tag "randogeddon-gateway:$AP_VERSION" "$IMAGES/gateway:$moving"
for image in "tracker:$tag" "gateway:$gateway_tag" "tracker:$moving" "gateway:$moving"; do docker push -q "$IMAGES/$image"; done

echo "== The server's files"
remote "mkdir -p /tmp/randogeddon-deploy"
scp -q docker-compose.yml docker-compose.proxy.yml scripts/backup.sh infra/randogeddon/randogeddon-backup.service \
  infra/randogeddon/randogeddon-backup.timer "$DEPLOY_SSH:/tmp/randogeddon-deploy/"
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

# A release's bridge, as its image serves it
[[ "$moving" != latest ]] || ./scripts/publish-bridge.sh "$version" "$IMAGES/tracker:$tag"

# Here too only what was just pushed stays (the build's own images and its cache are kept for the next build)
docker images --format '{{.Repository}}:{{.Tag}}' "$IMAGES/*" | while IFS= read -r image; do
  case "${image##*:}" in "$tag" | "$gateway_tag" | "$moving") ;; *) docker rmi "$image" >/dev/null || true ;; esac
done
docker image prune -f >/dev/null
