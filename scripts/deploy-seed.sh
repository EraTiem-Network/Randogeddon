#!/usr/bin/env bash
# Put a generated seed on the server and (re)start the stack.
#
#   ./scripts/deploy-seed.sh output/AP_123.zip                         # on the server itself
#   ./scripts/deploy-seed.sh output/AP_123.zip me@host:/srv/archipelago  # from your PC via ssh
#
# Older seeds in data/ are moved to data/old/ (with their .apsave) so the server picks the new one.
# WARNING: switching seeds mid-session means starting over; the old save stays in data/old/.
set -euo pipefail
cd "$(dirname "$0")/.."

seed="${1:?usage: deploy-seed.sh <AP_xxx.zip|.archipelago> [user@host:/repo/path]}"
target="${2:-}"
[[ -f "$seed" ]] || { echo "No such file: $seed" >&2; exit 1; }
name="$(basename "$seed")"

remote_script='
set -euo pipefail
cd "$REPO"
mkdir -p data/old
for f in data/*.archipelago data/AP_*.zip data/*.apsave data/room.json; do
  if [[ -e "$f" && "$(basename "$f")" != "$NAME" ]]; then mv "$f" data/old/; fi
done
mv "data/.incoming-$NAME" "data/$NAME"
docker compose up -d --build --force-recreate archipelago tracker
docker compose ps
'

if [[ -z "$target" ]]; then
  cp "$seed" "data/.incoming-$name"
  REPO="$PWD" NAME="$name" bash -c "$remote_script"
else
  host="${target%%:*}"; repo="${target#*:}"
  scp "$seed" "$host:$repo/data/.incoming-$name"
  ssh "$host" "REPO=$(printf %q "$repo") NAME=$(printf %q "$name") bash -s" <<<"$remote_script"
fi
echo "Seed $name is live."
