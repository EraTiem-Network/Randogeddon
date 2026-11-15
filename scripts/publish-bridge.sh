#!/usr/bin/env bash
# Publishes Randogeddon Bridge's builds of a release, exactly as its tracker image serves them; run in WSL from the
# repository (deploy.sh does, for a release):
#   ./scripts/publish-bridge.sh <version> <tracker image> [--dry-run]
# - Artifact Keeper: pkg.example.org, the generic repository `randogeddon-bridge`, as <version>/<file> with
#   SHA256SUMS. The token of its account `bridge` (infra/registry/provision.py) is read from
#   ~/.config/randogeddon/bridge-publish.token (BRIDGE_TOKEN_FILE); without it, nothing is published there.
# - From 1.0.0 on also a GitHub release v<version> (gh, logged in) with the same files, its notes the version's
#   section of CHANGELOG.md; left alone if it exists.
# --dry-run: says what would be uploaded and created, and does neither.
set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:?the version}" image="${2:?the tracker image}" dry="${3:-}"
REGISTRY="${BRIDGE_REGISTRY:-https://pkg.example.org}"
REPOSITORY="${BRIDGE_REPOSITORY:-randogeddon-bridge}"
TOKEN_FILE="${BRIDGE_TOKEN_FILE:-$HOME/.config/randogeddon/bridge-publish.token}"

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
mkdir "$out/files"
container="$(docker create "$image")"
docker cp "$container:/app/bridge/." "$out/files/" >/dev/null
docker rm "$container" >/dev/null
built="$(cat "$out/files/VERSION" 2>/dev/null || true)"
[[ "$built" == "$version" ]] || { echo "The image's bridge is ${built:-missing}, not $version"; exit 1; }
rm "$out/files/VERSION"
(cd "$out/files" && sha256sum randogeddon-bridge-* > SHA256SUMS)
files=("$out"/files/*)

echo "== The bridge's builds to $REGISTRY ($REPOSITORY/$version/)"
if [[ -n "$dry" ]]; then
  for file in "${files[@]}"; do echo "would upload $(basename "$file") ($(stat -c %s "$file") bytes)"; done
elif [[ -r "$TOKEN_FILE" ]]; then
  # The token in a header file (never on a command line, where other processes could see it)
  (umask 077 && printf 'Authorization: Bearer %s\n' "$(tr -d '\r\n' < "$TOKEN_FILE")" > "$out/auth")
  for file in "${files[@]}"; do
    status="$(curl -sS -o "$out/answer" -w '%{http_code}' -H @"$out/auth" -H 'Content-Type: application/octet-stream' \
      -T "$file" "$REGISTRY/api/v1/repositories/$REPOSITORY/artifacts/$version/$(basename "$file")")"
    case "$status" in
      2??) echo "uploaded $(basename "$file")" ;;
      409) echo "$(basename "$file") is there already" ;;
      *) echo "$(basename "$file"): $status $(head -c 300 "$out/answer")"; exit 1 ;;
    esac
  done
else
  echo "No token in $TOKEN_FILE: not published there (.docs/INFRA.md, Artifact Keeper)"
fi

# The version's part of the changelog: from its heading to the next one
awk -v heading="### $version:" '
  index($0, heading) == 1 { found = 1; next }
  found && (/^### / || /^## /) { exit }
  found { print }
' CHANGELOG.md > "$out/notes"

if [[ "${version%%.*}" -lt 1 ]]; then
  echo "== No GitHub release before 1.0.0 ($version)"
  [[ -z "$dry" ]] || echo "(it would be v$version with $(wc -l < "$out/notes") lines of notes and ${#files[@]} files)"
elif ! command -v gh >/dev/null; then
  echo "== gh isn't installed here: no GitHub release v$version (install and log in, then run this script again)"
  [[ -n "$dry" ]] || exit 1
elif gh release view "v$version" >/dev/null 2>&1; then
  echo "== The GitHub release v$version is there already"
elif [[ -n "$dry" ]]; then
  echo "== would create the GitHub release v$version: $(wc -l < "$out/notes") lines of notes, ${#files[@]} files"
else
  echo "== The GitHub release v$version"
  gh release create "v$version" --verify-tag --title "Randogeddon $version" --notes-file "$out/notes" "${files[@]}"
fi
