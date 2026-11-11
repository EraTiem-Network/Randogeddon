#!/usr/bin/env bash
# Runs the demo session locally, to look at the dashboard:
#   ./scripts/demo.sh        generate demo/players, deploy it locally, start the stack and the simulated players
#   ./scripts/demo.sh stop   stop the simulated players (the stack keeps running; `docker compose down` stops it)
#
# The demo replaces the seed in data/ (the previous one is moved to data/old/). Dashboard: http://localhost:8000
# The simulated players connect through the gateway with their slot passwords, like real ones. The logins of Alice,
# Bob and Carol are printed at the end (only when they are created; a new password: randogeddon logins --reset <name>).
set -euo pipefail
cd "$(dirname "$0")/.."
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml
# Lets the build read the Gradle init script outside the build context without asking (see README, setup)
export BUILDX_BAKE_ENTITLEMENTS_FS=0
# A local demo needs no secret database password; a real .env sets its own
if [[ -z "${POSTGRES_PASSWORD:-}" ]] && ! grep -qs '^POSTGRES_PASSWORD=.' .env; then export POSTGRES_PASSWORD=demo; fi
SIM=randogeddon-demo-sim
docker rm -f "$SIM" >/dev/null 2>&1 || true
[[ "${1:-}" == "stop" ]] && { echo "Simulated players stopped."; exit 0; }

image="randogeddon-server:${AP_VERSION:-0.6.8}"
docker image inspect "$image" >/dev/null 2>&1 || docker compose build archipelago
# The native tracker and gateway (minutes the first time, then cached); deploy-seed.sh below builds quietly
docker compose build tracker gateway

./scripts/build-apworlds.sh >/dev/null
mkdir -p output/demo
rm -f output/demo/AP_*.zip output/demo/slots.json
docker run --rm -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  -v "$PWD/demo/players:/players:ro" -v "$PWD/output/demo:/output" -v "$PWD/custom_worlds:/ap/custom_worlds:ro" \
  "$image" generate --spoiler 2 | tail -n 3
seed="$(ls -t output/demo/AP_*.zip | head -n1)"

SESSION_FILE=demo/session.yaml ./scripts/deploy-seed.sh "$seed" >/dev/null
echo "Waiting for the server…"
for _ in $(seq 1 60); do
  docker compose logs archipelago --since 2m 2>&1 | grep -q "server listening" && break
  sleep 2
done
# The tracker creates the slot passwords as soon as it sees the new seed
for _ in $(seq 1 30); do
  docker compose exec -T tracker randogeddon slots --json > output/demo/slots.json 2>/dev/null \
    && grep -q '"Alice-HK"' output/demo/slots.json && break
  sleep 2
done
docker run -d --name "$SIM" --network randogeddon_default -v "$PWD/demo:/demo:ro" -v "$PWD/output/demo:/slots:ro" \
  --entrypoint python "$image" /demo/simulate.py ws://gateway:38281 /slots/slots.json >/dev/null
echo
docker compose exec -T tracker randogeddon logins || true
echo
echo "Demo running: http://localhost:8000  (stop the players with ./scripts/demo.sh stop)"
