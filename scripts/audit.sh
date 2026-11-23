#!/usr/bin/env bash
# Looks what the project ships up in the OSV database of known vulnerabilities (osv-scanner, pinned, in Docker):
#   - the bridge's Go modules (bridge/go.mod), and with govulncheck (pinned) its code with them and Go's standard library,
#   - the JVM and Kotlin/Wasm libraries of the tracker, the gateway and the web app (Gradle's lists),
#   - Archipelago's Python packages in the gateway image (pip freeze),
#   - the three images and the database image docker-compose.yml names, each as a whole: its operating system packages
#     and what else osv-scanner recognizes in it (Forge's and Minecraft's libraries, the Go runtimes, …). Reported only:
#     one summary line per image here, the whole of each in a folder named at the end. Their findings are the base
#     images' (scripts/base-images.sh shows when newer ones exist) and never change the exit.
# Which images:
#   ./scripts/audit.sh                                            the local build's (the names docker compose build gives)
#   ./scripts/audit.sh --images <tracker> <gateway> <minecraft>   exactly these (scripts/deploy.sh: the ones just built)
# Exit 0: nothing known in the project's own dependencies (the Go modules and code, the JVM and Kotlin/Wasm libraries,
# the Python packages); 1: something found there; 2: a part couldn't be looked up, an image's scan too (the result is
# incomplete; an empty list is that as well, never "nothing known"). A finding that can't be fixed yet is passed in
# osv-scanner.toml at the repository's root ([[IgnoredVulns]] with its id, reason and expiry), which every osv-scanner
# run here reads when the file is there.
set -euo pipefail
cd "$(dirname "$0")/.."
SCANNER=ghcr.io/google/osv-scanner:v2.7.1@sha256:a70ffffc2bfd33482953e5d6b64c6fcdb30239717a07b0f0d3ab2859ce3e4cde
# A release tag of golang.org/x/vuln (v1.x.y); go run checks its module against Go's checksum database
GOVULNCHECK_VERSION=v1.10.0

usage() {
  echo "Usage: $0 [--images <tracker> <gateway> <minecraft>]  (exit 0 nothing in the project's own dependencies, 1 found, 2 incomplete)" >&2
  exit 2
}
if [[ "${1:-}" == "--images" ]]; then
  [[ $# -eq 4 ]] || usage
  tracker="$2" gateway="$3" minecraft="$4"
elif [[ $# -eq 0 ]]; then
  # The gateway's tag is the Archipelago release the compose file names
  ap_default="$(sed -n 's/^ *AP_VERSION: \${AP_VERSION:-\([^}]*\)}$/\1/p' docker-compose.yml)"
  ap_version="${AP_VERSION:-$ap_default}"
  [[ -n "$ap_version" ]] || { echo "docker-compose.yml names no AP_VERSION" >&2; exit 2; }
  tracker=randogeddon-tracker:latest gateway="randogeddon-gateway:$ap_version" minecraft=randogeddon-minecraft:latest
  echo "The local build's images (others: --images <tracker> <gateway> <minecraft>): $tracker $gateway $minecraft"
else
  usage
fi
missing=()
for ref in "$tracker" "$gateway" "$minecraft"; do docker image inspect "$ref" >/dev/null 2>&1 || missing+=("$ref"); done
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "Not here: ${missing[*]}. Build them first (docker compose build tracker gateway minecraft) or name the ones to look at with --images" >&2
  exit 2
fi
# The database's image as the compose file pins it (database-roles runs the same one)
mapfile -t databases < <(sed -n 's/^ *image: \(postgres:[^ ]*\)$/\1/p' docker-compose.yml | sort -u)
if [[ ${#databases[@]} -eq 0 ]]; then echo "docker-compose.yml names no postgres image" >&2; exit 2; fi

work="$(mktemp -d)" || exit 2
trap 'rm -rf "$work"' EXIT
found=0 failed=0
found_in=()
# Known findings passed on purpose, with their reasons (the script runs from the repository's root, however called)
config=()
if [[ -f osv-scanner.toml ]]; then cp osv-scanner.toml "$work/" && config=(--config=/scan/osv-scanner.toml); fi

# osv-scanner on what is in $work (/scan in its container). Its exit: 0 nothing known, 1 something found, anything else
# (128: no package found at all) couldn't look
osv() { # <what> <source|image> <its arguments, paths under /scan>
  local what="$1" mode="$2" code=0
  shift 2
  docker run --rm -v "$work:/scan:ro" "$SCANNER" scan "$mode" ${config[@]+"${config[@]}"} "$@" || code=$?
  case "$code" in
    0) ;;
    1) found=1; found_in+=("$what") ;;
    *) echo "   $what couldn't be looked up (osv-scanner's exit $code)"; failed=1 ;;
  esac
}

# The images' scans: kept whole in this folder (left for reading), only their summary printed
reports="$(mktemp -d "${TMPDIR:-/tmp}/randogeddon-audit.XXXXXX")" || exit 2
images_found=()
osv_image() { # <image reference> <its file name in $reports>
  local ref="$1" report="$reports/$2.txt" code=0
  docker run --rm -v "$work:/scan:ro" "$SCANNER" scan image ${config[@]+"${config[@]}"} --archive /scan/image.tar \
    > "$report" 2>&1 || code=$?
  case "$code" in
    0|1)
      grep -E '^Total [0-9]+ packages? affected|vulnerabilit(y|ies) can be fixed|^No issues found' "$report" \
        | sed 's/^/   /' || echo "   (no summary line: see $report)"
      [[ "$code" == 0 ]] || images_found+=("$ref") ;;
    *) tail -5 "$report" | sed 's/^/   /'; echo "   $ref couldn't be looked up (osv-scanner's exit $code)"; failed=1 ;;
  esac
}

# Go: the module file as it is
echo "== Randogeddon Bridge (Go modules)"
mkdir -p "$work/bridge"
cp -r bridge/. "$work/bridge/"
osv "The bridge's modules" source --lockfile=/scan/bridge/go.mod
# And govulncheck (Go's own database) in the image the bridge is built with: also the toolchain's standard library
# (net/http, crypto/tls), at the exact version that builds it, and only what the code calls. Its exit: 3 found
go_image="$(sed -n 's/^FROM \(golang:[^ ]*\) AS bridge.*/\1/p' kotlin.Dockerfile)"
echo "== Randogeddon Bridge (Go code, govulncheck $GOVULNCHECK_VERSION in ${go_image:-no golang image})"
if [[ -z "$go_image" ]]; then
  echo "   kotlin.Dockerfile has no 'FROM golang:... AS bridge'"; failed=1
elif [[ ! "$GOVULNCHECK_VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "   GOVULNCHECK_VERSION isn't a release tag: $GOVULNCHECK_VERSION"; failed=1
else
  code=0
  docker run --rm -v "$work/bridge:/src:ro" -w /src "$go_image" \
    go run "golang.org/x/vuln/cmd/govulncheck@$GOVULNCHECK_VERSION" ./... || code=$?
  case "$code" in
    0) ;;
    3) found=1; found_in+=("The bridge's code (govulncheck)") ;;
    *) echo "   govulncheck couldn't run (exit $code)"; failed=1 ;;
  esac
fi

# JVM and Kotlin/Wasm: every library on the runtime classpaths, as a plain list osv-scanner reads (a pom.xml)
# (GRADLE_DEPENDENCIES: Gradle's output from elsewhere, where Gradle runs but Docker doesn't)
echo "== Tracker, gateway and web app (JVM and Kotlin/Wasm libraries)"
listed=""
if [[ -n "${GRADLE_DEPENDENCIES:-}" ]]; then
  cp "$GRADLE_DEPENDENCIES" "$work/gradle.txt" && listed=1
elif ./gradlew -q --console=plain :server:dependencies --configuration runtimeClasspath \
    :gateway:dependencies --configuration runtimeClasspath :webApp:dependencies --configuration wasmJsRuntimeClasspath \
    > "$work/gradle.txt" 2> "$work/gradle.err"; then
  listed=1
else
  tail -5 "$work/gradle.err" | sed 's/^/   /'
fi
if [[ -z "$listed" ]]; then
  echo "   Gradle could not list the dependencies"; failed=1
else
  {
    echo '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    echo '<groupId>net.eratiem</groupId><artifactId>randogeddon-audit</artifactId><version>0</version><dependencies>'
    grep -oE '[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+( -> [A-Za-z0-9_.+-]+)?' "$work/gradle.txt" \
      | sed -E 's/^([^:]+):([^:]+):[^ ]+ -> (.+)$/\1:\2:\3/' | sort -u \
      | awk -F: '{print "<dependency><groupId>"$1"</groupId><artifactId>"$2"</artifactId><version>"$3"</version></dependency>"}' || true
    echo '</dependencies></project>'
  } > "$work/pom.xml"
  libraries="$(grep -c '<dependency>' "$work/pom.xml" || true)"
  echo "   $libraries libraries"
  if [[ "$libraries" -eq 0 ]]; then
    echo "   No library in Gradle's lists"; failed=1
  else
    osv "The JVM and Kotlin/Wasm libraries" source --lockfile=/scan/pom.xml
  fi
fi

# Python: what is installed in the gateway image, by version (a package installed from a URL has none to look up)
echo "== Archipelago's Python packages (pip freeze in $gateway)"
if docker run --rm --network none --entrypoint pip "$gateway" freeze > "$work/freeze.txt" 2> "$work/pip.err"; then
  grep '==' "$work/freeze.txt" > "$work/requirements.txt" || true
  packages="$(wc -l < "$work/requirements.txt")"
  from_urls="$(grep -c ' @ ' "$work/freeze.txt" || true)"
  echo "   $packages packages by version, $from_urls from a URL (not looked up here)"
  if [[ "$packages" -eq 0 ]]; then
    echo "   No package by version in $gateway"; failed=1
  else
    osv "Archipelago's Python packages" source --lockfile=/scan/requirements.txt
  fi
else
  sed 's/^/   /' "$work/pip.err"
  echo "   pip freeze failed in $gateway"; failed=1
fi

# The images as a whole, each saved as an archive osv-scanner reads (one at a time: they take gigabytes). Reported, not
# counted: their findings are the base images' packages and the third-party code in them
n=0
for ref in "$tracker" "$gateway" "$minecraft" "${databases[@]}"; do
  n=$((n + 1))
  echo "== $ref (operating system packages and what else is recognized in it; reported only)"
  if ! docker image inspect "$ref" >/dev/null 2>&1 && ! docker pull -q "$ref" >/dev/null; then
    echo "   $ref couldn't be pulled"; failed=1; continue
  fi
  if docker save -o "$work/image.tar" "$ref"; then
    osv_image "$ref" "$n-$(basename "${ref%%[:@]*}")"
  else
    echo "   $ref couldn't be saved"; failed=1
  fi
  rm -f "$work/image.tar"
done

echo
echo "The images' scans in full: $reports"
if [[ ${#images_found[@]} -gt 0 ]]; then
  echo "Found in the images (the base images' packages; not counted: scripts/base-images.sh shows when newer ones exist): ${images_found[*]}"
fi
if [[ ${#found_in[@]} -gt 0 ]]; then
  echo "Found in the project's own dependencies: $(IFS=';'; echo "${found_in[*]}" | sed 's/;/; /g')"
fi
if [[ "$failed" != 0 ]]; then
  echo "Incomplete: a part couldn't be looked up$([[ "$found" == 0 ]] || echo ", and something was found"): see above."
  exit 2
elif [[ "$found" != 0 ]]; then
  echo "Something was found: see above (a finding that can't be fixed yet: osv-scanner.toml, or SKIP_AUDIT=1 for one release)."
  exit 1
fi
echo "Nothing known in the project's own dependencies."
