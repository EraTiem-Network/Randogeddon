#!/usr/bin/env bash
# Runs two demo sessions at once locally, to look at the dashboard:
#   ./scripts/demo.sh        start the stack, create the sessions of demo/a and demo/b through the admin API, start
#                            the simulated players of both
#   ./scripts/demo.sh loop   the same over and over: when every slot of a session is done (100 %), it finishes 2 minutes
#                            later by itself; once both are, a new round starts after a pause (with new session IDs;
#                            the round before stays as finished sessions, older demo rounds are deleted; a new start
#                            deletes every earlier demo session)
#   ./scripts/demo.sh stop   stop the simulated players (the stack keeps running; `docker compose down` stops it)
#
# Dashboard: http://localhost:8000. The simulated players connect through the gateway with their slot passwords, like
# real ones (ws://gateway:38281/<session>), and finish in about DEMO_MINUTES (default 15). Alice is the demo's admin:
# her password is printed once, when her login is made, and kept in output/demo/admin.password for the next runs. The
# other logins (Bob, Carol, Dave, Erin) are made the same way (no invitations in the demo) and printed once.
#
# For the loop, keep it running in the background:  nohup ./scripts/demo.sh loop > output/demo/loop.log 2>&1 &
set -euo pipefail
cd "$(dirname "$0")/.."
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml
# Lets the build read the Gradle init script outside the build context without asking (see .docs/OPERATIONS.md, setup)
export BUILDX_BAKE_ENTITLEMENTS_FS=0
# A local demo needs no secret database password or internal token; a real .env sets its own
if [[ -z "${POSTGRES_PASSWORD:-}" ]] && ! grep -qs '^POSTGRES_PASSWORD=.' .env; then export POSTGRES_PASSWORD=demo; fi
if [[ -z "${INTERNAL_TOKEN:-}" ]] && ! grep -qs '^INTERNAL_TOKEN=.' .env; then export INTERNAL_TOKEN=demo-internal; fi
AP_VERSION="${AP_VERSION:-0.6.8}"
SIM=randogeddon-demo-sim
GROUPS_=(a b)
PAUSE=240 # seconds between the end of a round (100 %) and the next one: it finishes after 120, then shows that
ADMIN=Alice
PASSWORD_FILE=output/demo/admin.password
for group in "${GROUPS_[@]}"; do docker rm -f "$SIM-$group" >/dev/null 2>&1 || true; done
[[ "${1:-}" == "stop" ]] && { echo "Simulated players stopped."; exit 0; }
mkdir -p output/demo

# The gateway image has Archipelago (and Python, for the simulated players and the admin calls)
image="randogeddon-gateway:$AP_VERSION"
# The native tracker and gateway (minutes the first time, then cached), one after the other: each native-image
# compile takes several GB at its peak
docker compose build tracker
docker compose build gateway
docker compose build minecraft
docker compose up -d
echo "Waiting for the tracker…"
for _ in $(seq 1 90); do
  curl -sf http://localhost:8000/healthz >/dev/null 2>&1 && break
  sleep 2
done

# Alice's login as an admin: made once; if she exists already (an earlier demo) without a kept password, a new one
admin_password() {
  [[ -s "$PASSWORD_FILE" ]] && return 0
  local out
  if ! out="$(docker compose exec -T tracker randogeddon admins --create "$ADMIN" 2>/dev/null)"; then
    out="$(docker compose exec -T tracker randogeddon logins --reset "$ADMIN")"
    docker compose exec -T tracker randogeddon admins --grant "$ADMIN" >/dev/null
  fi
  echo "$out" | awk -v name="$ADMIN" '$1 == name { print $2; exit }' > "$PASSWORD_FILE"
  chmod 600 "$PASSWORD_FILE"
  echo "Admin login: $ADMIN / $(cat "$PASSWORD_FILE")"
}

# The demo's admin calls (demo/admin.py), in the compose network
admin() {
  docker run --rm --network randogeddon_default -v "$PWD/demo:/demo:ro" -v "$PWD/output/demo:/out" \
    -e DEMO_ADMIN="$ADMIN" -e DEMO_PASSWORD="$(cat "$PASSWORD_FILE")" --entrypoint python "$image" /demo/admin.py "$@"
}

admin_password
# A kept password that no longer works (the database was reset): a new one
if ! admin check >/dev/null 2>&1; then
  rm -f "$PASSWORD_FILE" output/demo/admin.totp
  admin_password
  # Her two-factor login too: admin.py sets it up again
  docker compose exec -T tracker randogeddon admins --reset-2fa "$ADMIN" >/dev/null
fi

# The players of demo/*/session.json get a login (once; their passwords are printed then). Real players are invited
# by e-mail in the admin panel instead
for name in $(grep -oh '"users": *\[[^]]*\]' demo/*/session.json | grep -o '"[^"]*"' | tr -d '"' | grep -vx users | sort -u); do
  if out="$(docker compose exec -T tracker randogeddon logins --create "$name" 2>/dev/null)"; then echo "$out" | head -2; fi
done

# A new session of a group (admin.py tries another seed when one can't be generated)
create() {
  admin create "$1" "$2" || { echo "No session $1." >&2; return 1; }
}

# The simulated players of session $1 (group $2), with its slot passwords
simulate() {
  local id="$1" group="$2" finisher
  # The gateway writes room.json when the game server starts; the slot passwords follow
  rm -f "output/demo/$id.json"
  for _ in $(seq 1 60); do
    docker compose exec -T tracker randogeddon slots --json "$id" > "output/demo/$id.json" 2>/dev/null \
      && grep -q '"Locks"' "output/demo/$id.json" && break
    sleep 2
  done
  finisher="$(sed -n 's/.*"finisher": *"\([^"]*\)".*/\1/p' "demo/$group/session.json")"
  docker rm -f "$SIM-$group" >/dev/null 2>&1 || true
  docker run -d --name "$SIM-$group" --network randogeddon_default -e DEMO_MINUTES="${DEMO_MINUTES:-15}" \
    -e DEMO_FINISHER="$finisher" -v "$PWD/demo:/demo:ro" -v "$PWD/output/demo:/slots:ro" \
    --entrypoint python "$image" /demo/simulate.py "ws://gateway:38281/$id" "/slots/$id.json" >/dev/null
}

# One round: the older demo rounds deleted (the round before stays, as finished sessions; on the first round every
# earlier demo session goes, also one left running), a new session per group, the simulated players started
previous=()
round() {
  local stamp ids=()
  admin prune "${previous[@]}" || true
  stamp="$(date +%m%d-%H%M)"
  for group in "${GROUPS_[@]}"; do
    create "demo-$group-$stamp" "$group" || return 1
    ids+=("demo-$group-$stamp")
  done
  for i in "${!GROUPS_[@]}"; do simulate "${ids[$i]}" "${GROUPS_[$i]}"; done
  previous=("${ids[@]}")
}

round
echo
echo "Demo running: http://localhost:8000 as $ADMIN (password in $PASSWORD_FILE); stop the players with ./scripts/demo.sh stop"

if [[ "${1:-}" == "loop" ]]; then
  while true; do
    # A simulator exits when every slot of its session is done (or when it crashes; the round goes on all the same)
    for group in "${GROUPS_[@]}"; do
      code="$(docker wait "$SIM-$group" 2>/dev/null || echo 1)"
      echo "$(date '+%F %T') Session of group $group over (simulator exit $code)"
    done
    echo "$(date '+%F %T') Round over; next one in $((PAUSE / 60)) minutes"
    sleep "$PAUSE"
    round || { echo "$(date '+%F %T') Round failed; trying again in a minute"; sleep 60; }
  done
fi
