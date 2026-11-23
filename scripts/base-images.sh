#!/usr/bin/env bash
# Shows, for each image the build and the stack take from outside, the digest it is pinned to beside the one its tag
# points at now. The pins are found where they are written, as `<image>:<tag>@sha256:<digest>`: kotlin.Dockerfile (its
# syntax line and FROM lines), the compose files and the scripts; there is no second list to keep.
# A tag that has moved is a newer image: raise the digest where it is written, build, run ./scripts/audit.sh and the
# demo. Run it before each phase's release, beside audit.sh: ./scripts/base-images.sh
# It ends with 0 when every pin is current, 1 when a tag has moved, 2 when a tag (or anything) couldn't be looked up.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v docker >/dev/null; then
  echo "No docker here: nothing could be looked up" >&2
  exit 2
fi

files=(kotlin.Dockerfile docker-compose*.yml scripts/*.sh)
# An image reference begins at the start of a line, after a space, a quote, `=` (the syntax line) or `-` (`${…:-…}`)
mapfile -t pins < <(grep -ohE "(^|[[:space:]\"'=-])[[:alnum:]][[:alnum:]._/-]*(:[[:alnum:]._-]+)?@sha256:[0-9a-f]{64}" "${files[@]}" \
  | sed -E 's/^[^[:alnum:]]//' | sort -u)
if [[ ${#pins[@]} -eq 0 ]]; then
  echo "No pinned image found in ${files[*]}" >&2
  exit 2
fi

status=0
for pin in "${pins[@]}"; do
  image="${pin%@*}"
  pinned="${pin#*@}"
  now="$(docker buildx imagetools inspect "$image" --format '{{.Manifest.Digest}}' 2>/dev/null || true)"
  if [[ ! $now =~ ^sha256:[0-9a-f]{64}$ ]]; then
    printf '%s\n  pinned %s\n  now    (could not be looked up)\n' "$image" "$pinned"
    status=2
  elif [[ $now == "$pinned" ]]; then
    printf '%s\n  pinned %s (current)\n' "$image" "$pinned"
  else
    printf '%s\n  pinned %s\n  now    %s (moved)\n' "$image" "$pinned" "$now"
    [[ $status -eq 2 ]] || status=1
  fi
done

case "$status" in
  0) echo "Every pinned image is current." ;;
  1) echo "Some tags have moved: see above." ;;
  2) echo "Some tags couldn't be looked up (network, registry, login): see above." ;;
esac
exit "$status"
