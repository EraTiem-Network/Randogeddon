#!/usr/bin/env bash
# Generate a multiworld from every YAML in players/. Output: output/AP_<seed>.zip
# Extra args go to Generate.py, e.g.  ./scripts/generate.sh --spoiler 3 --seed 1234
set -euo pipefail
cd "$(dirname "$0")/.."

shopt -s nullglob
yamls=(players/*.yaml players/*.yml)
if (( ${#yamls[@]} == 0 )); then
  echo "No player YAMLs in players/ — add one per player first." >&2
  exit 1
fi
# Generate.py parses *every* non-hidden file in players/ as YAML.
for f in players/*; do
  case "$f" in *.yaml|*.yml) ;; *) echo "Refusing: players/ contains a non-YAML file ($f) — Generate.py would try to parse it." >&2; exit 1;; esac
done
echo "Generating with ${#yamls[@]} player file(s): ${yamls[*]}"

docker compose --profile tools run --rm -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" generate generate "$@"

latest="$(ls -t output/AP_*.zip 2>/dev/null | head -n1 || true)"
[[ -n "$latest" ]] || { echo "Generation produced no AP_*.zip — check the log above." >&2; exit 1; }
echo
echo "Done: $latest"
unzip -l "$latest"
echo
echo "Next: ./scripts/deploy-seed.sh $latest <user@server:/path/to/this/repo>"
