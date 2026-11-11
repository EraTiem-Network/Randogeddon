#!/usr/bin/env bash
# Seed generation in the gateway image (installed as ap-generate): Generate.py on /players, writing to /output.
# Extra args go to Generate.py. Runs as root (see the generate service in docker-compose.yml) and hands the output back
# to HOST_UID. The game server itself is run by the gateway (gateway/.../GameServer.kt), not by this script.
set -euo pipefail
cd "${AP_HOME:?}"

rc=0
python Generate.py --player_files_path /players --outputpath /output "$@" || rc=$?
# Hand the output back to the invoking host user instead of root.
if [[ -n "${HOST_UID:-}" ]]; then chown -R "${HOST_UID}:${HOST_GID:-$HOST_UID}" /output || true; fi
exit "$rc"
