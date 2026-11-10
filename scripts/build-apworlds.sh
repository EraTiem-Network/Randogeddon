#!/usr/bin/env bash
# Packs every folder in apworlds/ into custom_worlds/<name>.apworld (a zip with the folder at its root).
set -euo pipefail
cd "$(dirname "$0")/.."
for dir in apworlds/*/; do
  name="$(basename "$dir")"
  rm -f "custom_worlds/$name.apworld"
  (cd apworlds && python3 -c 'import sys, zipfile, pathlib
name = sys.argv[1]; out = sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(pathlib.Path(name).rglob("*")):
        if p.is_file() and "__pycache__" not in p.parts: z.write(p, p.as_posix())' "$name" "../custom_worlds/$name.apworld")
  echo "custom_worlds/$name.apworld"
done
