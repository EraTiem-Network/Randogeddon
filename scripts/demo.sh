#!/usr/bin/env bash
# Runs the demo session locally, to look at the dashboard:
#   ./scripts/demo.sh        generate demo/players, deploy it locally, start the stack and the simulated players
#   ./scripts/demo.sh loop   the same over and over: when every slot is done (100 %), wait 2 minutes, start a new round
#   ./scripts/demo.sh stop   stop the simulated players (the stack keeps running; `docker compose down` stops it)
#
# The demo replaces the seed in data/ (the previous one is moved to data/old/). Dashboard: http://localhost:8000
# The simulated players connect through the gateway with their slot passwords, like real ones, and finish in about
# DEMO_MINUTES (default 15). The logins of Alice, Bob and Carol are printed once, when they are created; new rounds keep
# them (a new password: randogeddon logins --reset <name>). Alice is an admin (the admin panel in the header).
#
# For the loop, keep it running in the background:  nohup ./scripts/demo.sh loop > output/demo/loop.log 2>&1 &
set -euo pipefail
cd "$(dirname "$0")/.."
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml
# Lets the build read the Gradle init script outside the build context without asking (see README, setup)
export BUILDX_BAKE_ENTITLEMENTS_FS=0
# A local demo needs no secret database password or internal token; a real .env sets its own
if [[ -z "${POSTGRES_PASSWORD:-}" ]] && ! grep -qs '^POSTGRES_PASSWORD=.' .env; then export POSTGRES_PASSWORD=demo; fi
if [[ -z "${INTERNAL_TOKEN:-}" ]] && ! grep -qs '^INTERNAL_TOKEN=.' .env; then export INTERNAL_TOKEN=demo-internal; fi
AP_VERSION="${AP_VERSION:-0.6.8}"
SIM=randogeddon-demo-sim
PAUSE=120 # seconds between the end of a round (100 %) and the next one
docker rm -f "$SIM" >/dev/null 2>&1 || true
[[ "${1:-}" == "stop" ]] && { echo "Simulated players stopped."; exit 0; }

# The gateway image has Archipelago (for generation and the simulated players) and runs the game server
image="randogeddon-gateway:$AP_VERSION"
# The native tracker and gateway (minutes the first time, then cached); deploy-seed.sh below builds quietly
docker compose build tracker gateway

# A new seed. About one in three random seeds of the demo can't be generated ("Game appears as unbeatable"): retry.
generate() {
  # World Locks is part of the gateway image; an old packed copy here would be loaded twice
  rm -f custom_worlds/world_locks.apworld
  mkdir -p output/demo
  for attempt in 1 2 3 4 5; do
    rm -f output/demo/AP_*.zip
    if docker run --rm --user 0 --entrypoint ap-generate -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
      -v "$PWD/demo/players:/players:ro" -v "$PWD/output/demo:/output" \
      -v "$PWD/custom_worlds:/opt/archipelago/$AP_VERSION/custom_worlds:ro" \
      "$image" --spoiler 2 >/dev/null 2>&1 && ls output/demo/AP_*.zip >/dev/null 2>&1; then
      ls -t output/demo/AP_*.zip | head -n1
      return 0
    fi
    echo "Generation failed (attempt $attempt), trying another seed…" >&2
  done
  echo "No seed after 5 attempts." >&2
  return 1
}

# One round: a new seed deployed, the slot passwords read, the simulated players started
round() {
  local seed
  seed="$(generate)"
  echo "Seed: $seed"
  SESSION_FILE=demo/session.yaml ./scripts/deploy-seed.sh "$seed" >/dev/null
  echo "Waiting for the server…"
  for _ in $(seq 1 60); do
    docker compose logs gateway --since 2m 2>&1 | grep -q "server listening" && break
    sleep 2
  done
  # The tracker creates the slot passwords as soon as it sees the new seed
  rm -f output/demo/slots.json
  for _ in $(seq 1 30); do
    docker compose exec -T tracker randogeddon slots --json > output/demo/slots.json 2>/dev/null \
      && grep -q '"Alice-HK"' output/demo/slots.json && break
    sleep 2
  done
  docker rm -f "$SIM" >/dev/null 2>&1 || true
  docker run -d --name "$SIM" --network randogeddon_default -e DEMO_MINUTES="${DEMO_MINUTES:-15}" \
    -v "$PWD/demo:/demo:ro" -v "$PWD/output/demo:/slots:ro" \
    --entrypoint python "$image" /demo/simulate.py ws://gateway:38281 /slots/slots.json >/dev/null
}

round
echo
docker compose exec -T tracker randogeddon logins || true
# Alice sees the admin panel
docker compose exec -T tracker randogeddon admins --grant Alice >/dev/null || true
echo
echo "Demo running: http://localhost:8000  (stop the players with ./scripts/demo.sh stop)"

if [[ "${1:-}" == "loop" ]]; then
  while true; do
    # The simulator exits when every slot is done (or when it crashes; then the next round starts all the same)
    code="$(docker wait "$SIM" 2>/dev/null || echo 1)"
    echo "$(date '+%F %T') Round over (simulator exit $code); next one in $((PAUSE / 60)) minutes"
    sleep "$PAUSE"
    round || { echo "$(date '+%F %T') Round failed; trying again in a minute"; sleep 60; }
  done
fi
