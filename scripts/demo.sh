#!/usr/bin/env bash
# Runs the demo session locally, to look at the dashboard:
#   ./scripts/demo.sh        generate demo/players, deploy it locally, start the stack and the simulated players
#   ./scripts/demo.sh stop   stop the simulated players (the stack keeps running; `docker compose down` stops it)
#
# The demo replaces the seed in data/ (the previous one is moved to data/old/). Dashboard: http://localhost:8000
set -euo pipefail
cd "$(dirname "$0")/.."
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml
SIM=archipelago-demo-sim
docker rm -f "$SIM" >/dev/null 2>&1 || true
[[ "${1:-}" == "stop" ]] && { echo "Simulated players stopped."; exit 0; }

image="archipelago-server:${AP_VERSION:-0.6.8}"
docker image inspect "$image" >/dev/null 2>&1 || docker compose build archipelago

./scripts/build-apworlds.sh >/dev/null
mkdir -p output/demo
rm -f output/demo/AP_*.zip
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
docker run -d --name "$SIM" --network archipelago_default -v "$PWD/demo:/demo:ro" --entrypoint python \
  "$image" /demo/simulate.py ws://archipelago:38281 >/dev/null
echo "Demo running: http://localhost:8000  (stop the players with ./scripts/demo.sh stop)"
