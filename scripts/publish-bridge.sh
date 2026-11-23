#!/usr/bin/env bash
# Publishes Randogeddon Bridge's builds of a release, exactly as its tracker image serves them; run in WSL from the
# repository (deploy.sh does, for a release):
#   ./scripts/publish-bridge.sh <version> <tracker image> [--dry-run | --check]
# First, always: the image's builds are the version's, all in its checksums, and the checksums signed with the key of
# this checkout's bridge/signing-key.pub (ssh-keygen); else nothing is published.
# - Artifact Keeper at BRIDGE_REGISTRY (https:// only, e.g. https://registry.example.org; deploy.sh passes it on from
#   its settings), the generic repository `randogeddon-bridge`, as <version>/<file> with SHA256SUMS. The token of an account that may
#   write there is read from ~/.config/randogeddon/bridge-publish.token (BRIDGE_TOKEN_FILE); without it or without
#   BRIDGE_REGISTRY, nothing is published there.
# - A GitHub release v<version> (gh, logged in), its notes the version's section of CHANGELOG.md; left alone if it
#   exists. From 1.0.0 on a release with the same files; before, a pre-release without files.
# --dry-run: says what would be uploaded and created, and does neither.
# --check: only the checks (deploy.sh, before it pushes a release's images).
set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:?the version}" image="${2:?the tracker image}" mode="${3:-}"
case "$mode" in
  "" | --dry-run | --check) ;;
  *) echo "Unknown option $mode (--dry-run, --check)"; exit 1 ;;
esac
dry=""
[[ "$mode" != --dry-run ]] || dry=1
REGISTRY="${BRIDGE_REGISTRY:-}"
# The publishing token goes there: never over plain http
[[ -z "$REGISTRY" || "$REGISTRY" == https://* ]] || { echo "BRIDGE_REGISTRY isn't an https:// address: not publishing there"; exit 1; }
command -v ssh-keygen >/dev/null || { echo "ssh-keygen isn't installed here (openssh-client): the builds' signature can't be checked"; exit 1; }
REPOSITORY="${BRIDGE_REPOSITORY:-randogeddon-bridge}"
TOKEN_FILE="${BRIDGE_TOKEN_FILE:-$HOME/.config/randogeddon/bridge-publish.token}"
# The GitHub repository by name: the deploy's clone (~/rg-deploy) has a local path as its remote, which gh can't place
export GH_REPO="${GH_REPO:-EraTiem-Network/Randogeddon}"

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
mkdir "$out/files"
container="$(docker create "$image")"
docker cp "$container:/app/bridge/." "$out/files/" >/dev/null
docker rm "$container" >/dev/null
built="$(cat "$out/files/VERSION" 2>/dev/null || true)"
[[ "$built" == "$version" ]] || { echo "The image's bridge is ${built:-missing}, not $version"; exit 1; }
rm "$out/files/VERSION"
# The image brings its builds, their checksums, the checksums' signature and the public key; every build in the
# checksums, and the signature checked against this checkout's key (the image's own would prove nothing)
compgen -G "$out/files/randogeddon-bridge-*" >/dev/null || { echo "The image has no builds of the bridge"; exit 1; }
[[ -s "$out/files/SHA256SUMS" ]] || { echo "The image has no checksums of the bridge's builds"; exit 1; }
(cd "$out/files" && sha256sum --quiet -c SHA256SUMS) || { echo "The image's checksums don't match its builds"; exit 1; }
for file in "$out"/files/randogeddon-bridge-*; do
  awk '{ sub(/^\*/, "", $2); print $2 }' "$out/files/SHA256SUMS" | grep -xF "$(basename "$file")" >/dev/null \
    || { echo "$(basename "$file") isn't in the image's checksums"; exit 1; }
done
[[ -s "$out/files/SHA256SUMS.sig" ]] \
  || { echo "The image's checksums aren't signed (built without the signing key): not publishing $version"; exit 1; }
ssh-keygen -Y verify -q -f bridge/signing-key.pub -I bridge@randogeddon -n randogeddon-bridge \
  -s "$out/files/SHA256SUMS.sig" < "$out/files/SHA256SUMS" >/dev/null \
  || { echo "The image's signature doesn't check out against bridge/signing-key.pub: not publishing $version"; exit 1; }
cmp -s "$out/files/signing-key.pub" bridge/signing-key.pub \
  || { echo "The image's signing-key.pub isn't bridge/signing-key.pub: not publishing $version"; exit 1; }
echo "== The bridge's builds of $version are signed with the key of bridge/signing-key.pub"
[[ "$mode" != --check ]] || exit 0
files=("$out"/files/*)

echo "== The bridge's builds to $REGISTRY ($REPOSITORY/$version/)"
if [[ -n "$dry" ]]; then
  for file in "${files[@]}"; do echo "would upload $(basename "$file") ($(stat -c %s "$file") bytes)"; done
elif [[ -z "$REGISTRY" ]]; then
  echo "No BRIDGE_REGISTRY: not published there"
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
  echo "No token in $TOKEN_FILE: not published there"
fi

# The version's part of the changelog: from its heading to the next one
awk -v heading="### $version:" '
  index($0, heading) == 1 { found = 1; next }
  found && (/^### / || /^## /) { exit }
  found { print }
' CHANGELOG.md > "$out/notes"

# Before 1.0.0 a pre-release, without the files
pre=""
[[ "${version%%.*}" -ge 1 ]] || pre="pre-"
if ! command -v gh >/dev/null; then
  echo "== gh isn't installed here: no GitHub release v$version (install and log in, then run this script again)"
  [[ -n "$dry" ]] || exit 1
elif gh release view "v$version" >/dev/null 2>&1; then
  echo "== The GitHub ${pre}release v$version is there already"
elif [[ -n "$pre" ]]; then
  if [[ -n "$dry" ]]; then
    echo "== would create the GitHub pre-release v$version: $(wc -l < "$out/notes") lines of notes, no files"
  else
    echo "== The GitHub pre-release v$version"
    gh release create "v$version" --verify-tag --prerelease --title "Randogeddon $version" --notes-file "$out/notes"
  fi
elif [[ -n "$dry" ]]; then
  echo "== would create the GitHub release v$version: $(wc -l < "$out/notes") lines of notes, ${#files[@]} files"
else
  echo "== The GitHub release v$version"
  gh release create "v$version" --verify-tag --title "Randogeddon $version" --notes-file "$out/notes" "${files[@]}"
fi
