#!/usr/bin/env bash
# Modes:
#   server    (default) run MultiServer on the newest seed found in /data
#   generate  run Generate.py on /players, writing to /output (extra args passed through)
#   anything else is executed as-is (e.g. `python Launcher.py ...`, `bash`)
set -euo pipefail
cd /ap

newest_seed() {
  # Newest .archipelago or AP_*.zip in /data (MultiServer can load either).
  ls -t /data/*.archipelago /data/AP_*.zip 2>/dev/null | head -n1 || true
}

mode="${1:-server}"
[[ $# -gt 0 ]] && shift

case "$mode" in
  server)
    seed="$(newest_seed)"
    while [[ -z "$seed" ]]; do
      echo "[ap] No seed in /data yet (expecting *.archipelago or AP_*.zip). Checking again in 15s..."
      sleep 15
      seed="$(newest_seed)"
    done
    echo "[ap] Using seed: $seed"

    # Slot list for the tracker dashboard.
    python room_info.py "$seed" /data/room.json || echo "[ap] WARNING: could not write /data/room.json"

    echo "[ap] Hints: points ${AP_POINT_HINTS:-true}, automatic ${AP_AUTO_HINTS:-true}"
    # serve.py = MultiServer + the hint switches (AP_POINT_HINTS, AP_AUTO_HINTS)
    args=(serve.py "$seed"
      --host 0.0.0.0
      --port 38281
      --hint_cost "${AP_HINT_COST:-10}"
      --location_check_points "${AP_LOCATION_CHECK_POINTS:-1}"
      --release_mode "${AP_RELEASE_MODE:-auto}"
      --collect_mode "${AP_COLLECT_MODE:-auto}"
      --remaining_mode "${AP_REMAINING_MODE:-goal}"
      --countdown_mode "${AP_COUNTDOWN_MODE:-auto}"
      --auto_shutdown 0
      --logtime)
    [[ -n "${AP_ADMIN_PASSWORD:-}" ]] && args+=(--server_password "$AP_ADMIN_PASSWORD")
    exec python "${args[@]}" "$@"
    ;;
  generate)
    rc=0
    python Generate.py --player_files_path /players --outputpath /output "$@" || rc=$?
    # Hand the output back to the invoking host user instead of root.
    if [[ -n "${HOST_UID:-}" ]]; then chown -R "${HOST_UID}:${HOST_GID:-$HOST_UID}" /output || true; fi
    exit "$rc"
    ;;
  *)
    exec "$mode" "$@"
    ;;
esac
