#!/usr/bin/env bash
# Measures a local stack (./scripts/demo.sh running, both sessions live), run in WSL from the repository:
#   ./scripts/measure.sh [viewers] [seconds]          default: 20 viewers for 60 s
#   ./scripts/measure.sh --build [viewers] [seconds]  also a build from nothing (BuildKit's cache cleared first)
#                                                      and one without changes
# Prints a Markdown report: the page's files as sent (plain, gzip, Brotli), the images and what is in them, the
# containers' memory and CPU idle and with the viewers' live streams open, the API's answer times, and how long the
# first screen takes in headless Chromium (a share link's dashboard, no login). Phase 18 compares each step with it.
set -euo pipefail
cd "$(dirname "$0")/.."
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml BUILDX_BAKE_ENTITLEMENTS_FS=0
if [[ -z "${POSTGRES_PASSWORD:-}" ]] && ! grep -qs '^POSTGRES_PASSWORD=.' .env; then export POSTGRES_PASSWORD=demo; fi
if [[ -z "${INTERNAL_TOKEN:-}" ]] && ! grep -qs '^INTERNAL_TOKEN=.' .env; then export INTERNAL_TOKEN=demo-internal; fi
build=""
[[ "${1:-}" == "--build" ]] && { build=1; shift; }
viewers="${1:-20}" seconds="${2:-60}"
AP_VERSION="${AP_VERSION:-0.6.8}"
gateway_image="randogeddon-gateway:$AP_VERSION"
BASE=http://localhost:8000
PLAYWRIGHT="${PLAYWRIGHT_IMAGE:-mcr.microsoft.com/playwright/python:v1.49.1-noble}"
[[ -r output/demo/admin.password ]] || { echo "No demo: ./scripts/demo.sh first"; exit 1; }
curl -sf "$BASE/healthz" >/dev/null || { echo "The tracker isn't up at $BASE: ./scripts/demo.sh first"; exit 1; }

echo "# Randogeddon $(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts) ($(git rev-parse --short HEAD), $(date -u +%F))"

if [[ -n "$build" ]]; then
  echo; echo "## Build"; echo; echo "| What | Time |"; echo "|---|---|"
  docker builder prune -af >/dev/null
  start=$(date +%s); POSTGRES_PASSWORD=build INTERNAL_TOKEN=build docker compose build -q tracker gateway >/dev/null
  echo "| from nothing (no cache) | $(( $(date +%s) - start )) s |"
  start=$(date +%s); POSTGRES_PASSWORD=build INTERNAL_TOKEN=build docker compose build -q tracker gateway >/dev/null
  echo "| again, nothing changed | $(( $(date +%s) - start )) s |"
fi

echo; echo "## The page's files"; echo
echo "| File | Plain | gzip | Brotli | Served with gzip (median of 5) |"; echo "|---|---|---|---|---|"
size() { curl -s -o /dev/null -H "Accept-Encoding: $2" -w '%{size_download}' "$BASE/$1"; }
kb() { awk -v b="$1" 'BEGIN { printf "%.0f KB", b / 1024 }'; }
# How long the tracker takes for the whole file, locally: what compressing it per request costs shows here
served() {
  for _ in 1 2 3 4 5; do curl -s -o /dev/null -H 'Accept-Encoding: gzip' -w '%{time_total}\n' "$BASE/$1"; done \
    | sort -n | awk 'NR == 3 { printf "%.1f ms", $1 * 1000 }'
}
files="index.html page.js theme.js webApp.js $(curl -s "$BASE/webApp.js" | grep -o -E '[0-9a-f]{20}\.wasm' | sort -u | tr '\n' ' ')"
total_plain=0 total_gzip=0 total_br=0
for f in $files; do
  plain=$(size "$f" identity) gz=$(size "$f" gzip) br=$(size "$f" br)
  total_plain=$((total_plain + plain)) total_gzip=$((total_gzip + gz)) total_br=$((total_br + br))
  echo "| $f | $(kb "$plain") | $(kb "$gz") | $(kb "$br") | $(served "$f") |"
done
echo "| **all** | **$(kb $total_plain)** | **$(kb $total_gzip)** | **$(kb $total_br)** | |"

echo; echo "## Images"; echo; echo "| Image | Size | Inside |"; echo "|---|---|---|"
mb() { awk -v b="$1" 'BEGIN { printf "%.0f MB", b / 1048576 }'; }
inside() { docker run --rm --entrypoint sh "$1" -c "du -sm $2 2>/dev/null" | awk '{printf "%s %s MB, ", $2, $1}' | sed 's/, $//'; }
echo "| tracker | $(mb "$(docker image inspect -f '{{.Size}}' randogeddon-tracker:latest)") | $(inside randogeddon-tracker:latest '/app/*') |"
echo "| gateway | $(mb "$(docker image inspect -f '{{.Size}}' "$gateway_image")") | $(inside "$gateway_image" '/app/* /opt/archipelago /usr/local/lib/python3*/site-packages') |"

stats() { docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' | grep '^randogeddon-' | grep -v demo-sim; }
echo; echo "## Containers"; echo; echo "| Container | Idle: CPU, memory | With $viewers viewers: CPU (most), memory (most) |"; echo "|---|---|---|"
idle="$(stats)"
# The viewers' streams held open in the background; the containers sampled meanwhile
out="$(mktemp)"
docker run --rm --network randogeddon_default -v "$PWD/demo:/demo:ro" -v "$PWD/output/demo:/out" \
  -e DEMO_ADMIN=Alice -e DEMO_PASSWORD="$(cat output/demo/admin.password)" --entrypoint python "$gateway_image" \
  /demo/admin.py measure "$seconds" "$viewers" > "$out" &
measuring=$!
until grep -q VIEWERS_OPEN "$out" 2>/dev/null || ! kill -0 "$measuring" 2>/dev/null; do sleep 1; done
sleep 5
samples=""
for _ in $(seq 1 $(( (seconds - 10) / 5 > 1 ? (seconds - 10) / 5 : 1 ))); do samples+="$(stats)"$'\n'; sleep 5; done
wait "$measuring"
while read -r name cpu mem _; do
  most="$(awk -v n="$name" '$1 == n { c = $2 + 0; if (c > cpu) cpu = c; m = $3; if (m + 0 > mem + 0 || mem == "") mem = m } END { printf "%.1f%%, %s", cpu, mem }' <<<"$samples")"
  echo "| ${name#randogeddon-} | $cpu, $mem | $most |"
done <<<"$idle"

result="$(grep '^{' "$out")"
echo; echo "## API"; echo; echo "| Request | Median | Slowest | Size |"; echo "|---|---|---|---|"
python3 - "$result" <<'PY'
import json, re, sys
r = json.loads(sys.argv[1])
for a in r["api"]:
    path = re.sub(r"/watch/[^/]+", "/watch/<token>", a["path"])
    print(f"| `GET {path}` | {a['medianMs']} ms | {a['maxMs']} ms | {a['bytes'] / 1024:.0f} KB |")
for s, ms in r.get("firstEventMs", {}).items():
    print(f"| a stream to its first event ({s}) | {ms} ms | | |")
v = r["viewers"]
print()
print(f"{v['count']} viewers ({v['byLink']} by the share link) for {v['seconds']} s got {v['events']} updates; {v['withoutUpdate']} got none"
      + (f" (ended: {v['errors']})" if v['errors'] else "") + ".")
PY
token="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("shareToken", ""))' "$result")"
rm -f "$out"

echo; echo "## First screen (headless Chromium, a share link's dashboard)"; echo
# The image has the browsers; the Python package comes with pip (once per run)
docker run --rm -i --ipc=host --network randogeddon_default -e URL="http://tracker:8000/#/watch/$token" "$PLAYWRIGHT"   sh -c 'pip install -q --break-system-packages "playwright==${PLAYWRIGHT_VERSION:-1.49.1}" >/dev/null 2>&1; python3 -' <<'PY'
import os, statistics
from playwright.sync_api import sync_playwright
# The page's title ("RANDOGEDDON", drawn by the app into its canvas): once it shows, the dashboard is there. The
# loading splash goes earlier, sometimes before anything is drawn, and the canvas sits in a shadow root: so the
# title's area is photographed until it is no longer plain
TITLE = {"x": 440, "y": 16, "width": 400, "height": 48}
runs = []
with sync_playwright() as p:
    browser = p.chromium.launch(args=["--use-gl=swiftshader", "--enable-unsafe-swiftshader"])
    for n in range(5):
        # The page's CSP has no unsafe-eval, which Playwright's waiting needs: the measuring only, not the app
        page = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True).new_page()
        page.goto(os.environ["URL"], wait_until="commit")
        blank = len(page.screenshot(clip=TITLE))
        while len(page.screenshot(clip=TITLE)) <= blank * 3:
            if page.evaluate("() => performance.now()") > 60000:
                raise SystemExit("The dashboard didn't appear within a minute")
        t = page.evaluate("() => ({load: performance.timing.loadEventEnd - performance.timing.navigationStart, drawn: performance.now()})")
        runs.append(t)
        page.context.close()
    browser.close()
print(f"| Of {len(runs)} loads | Files loaded | Dashboard drawn |")
print("|---|---|---|")
print(f"| median | {statistics.median(r['load'] for r in runs):.0f} ms | {statistics.median(r['drawn'] for r in runs):.0f} ms |")
print(f"| fastest | {min(r['load'] for r in runs)} ms | {min(r['drawn'] for r in runs):.0f} ms |")
PY
echo; echo "Each run uses a new browser context (an empty cache); the server is local, so this is the app's own start, not the network's."
