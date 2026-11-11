#!/usr/bin/env bash
# Put a generated seed on the server and (re)start the stack.
#
#   ./scripts/deploy-seed.sh output/AP_123.zip                         # on the server itself
#   ./scripts/deploy-seed.sh output/AP_123.zip me@host:/srv/randogeddon  # from your PC via ssh
#
# Older seeds in data/ are moved to data/old/ (with their .apsave) so the server picks the new one.
# session.yaml (who plays which worlds, for the dashboard) is copied along if it exists; another file can be given
# with SESSION_FILE=path.
# WARNING: switching seeds mid-session means starting over; the old save stays in data/old/.
set -euo pipefail
cd "$(dirname "$0")/.."

seed="${1:?usage: deploy-seed.sh <AP_xxx.zip|.archipelago> [user@host:/repo/path]}"
target="${2:-}"
[[ -f "$seed" ]] || { echo "No such file: $seed" >&2; exit 1; }
name="$(basename "$seed")"
session="${SESSION_FILE:-session.yaml}"
[[ -f "$session" ]] || session=""

remote_script='
set -euo pipefail
cd "$REPO"
mkdir -p data/old
for f in data/*.archipelago data/AP_*.zip data/*.apsave data/room.json data/session.yaml; do
  if [[ -e "$f" && "$(basename "$f")" != "$NAME" ]]; then mv "$f" data/old/; fi
done
mv "data/.incoming-$NAME" "data/$NAME"
if [[ -f data/.incoming-session.yaml ]]; then mv data/.incoming-session.yaml data/session.yaml; fi
# The build reads the Gradle init script outside the build context; no prompt for that here (see README, setup)
BUILDX_BAKE_ENTITLEMENTS_FS=0 docker compose up -d --build --force-recreate archipelago tracker gateway
docker compose ps
'

if [[ -z "$target" ]]; then
  cp "$seed" "data/.incoming-$name"
  if [[ -n "$session" ]]; then cp "$session" data/.incoming-session.yaml; fi
  REPO="$PWD" NAME="$name" bash -c "$remote_script"
else
  host="${target%%:*}"; repo="${target#*:}"
  scp "$seed" "$host:$repo/data/.incoming-$name"
  if [[ -n "$session" ]]; then scp "$session" "$host:$repo/data/.incoming-session.yaml"; fi
  ssh "$host" "REPO=$(printf %q "$repo") NAME=$(printf %q "$name") bash -s" <<<"$remote_script"
fi
echo "Seed $name is live."
