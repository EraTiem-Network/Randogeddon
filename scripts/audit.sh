#!/usr/bin/env bash
# Looks the project's dependencies up in the OSV database of known vulnerabilities (osv-scanner, in Docker):
#   - the bridge's Go modules (bridge/go.mod), and with govulncheck its code with them and Go's standard library,
#   - the tracker's and the gateway's JVM libraries (their runtime classpaths, written out by Gradle),
#   - Archipelago's Python requirements in the gateway image, if one is built here.
# Run it before each phase's release and when a dependency changes: ./scripts/audit.sh
# It ends with 1 if anything is found, so it can gate a release.
set -uo pipefail
cd "$(dirname "$0")/.."
SCANNER="${OSV_SCANNER_IMAGE:-ghcr.io/google/osv-scanner:latest}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
found=0

scan() { # <what> <args for osv-scanner, paths under /scan>
  echo "== $1"
  shift
  docker run --rm -v "$work:/scan:ro" "$SCANNER" scan source "$@" || found=1
}

# Go: the module file as it is
mkdir -p "$work/bridge" && cp bridge/go.mod bridge/go.sum "$work/bridge/"
scan "Randogeddon Bridge (Go)" --lockfile=/scan/bridge/go.mod
# And govulncheck (Go's own database) in the image the bridge is built with: also the toolchain's standard library
# (net/http, crypto/tls), at the exact version that builds it, and only what the code calls
go_image="$(sed -n 's/^FROM \(golang:[^ ]*\) AS bridge.*/\1/p' kotlin.Dockerfile)"
echo "== Randogeddon Bridge (Go, govulncheck in ${go_image:-no golang image})"
if [ -n "$go_image" ]; then
  cp -r bridge/. "$work/bridge/"
  docker run --rm -v "$work/bridge:/src:ro" -w /src "$go_image" \
    go run "golang.org/x/vuln/cmd/govulncheck@${GOVULNCHECK_VERSION:-latest}" ./... || found=1
else
  echo "   kotlin.Dockerfile has no 'FROM golang:... AS bridge'" && found=1
fi

# JVM: every library on the runtime classpaths, as a CycloneDX-less plain list osv-scanner reads (a pom.xml)
# (GRADLE_DEPENDENCIES: that list from elsewhere, where Gradle runs but Docker doesn't)
if { [ -n "${GRADLE_DEPENDENCIES:-}" ] && cp "$GRADLE_DEPENDENCIES" "$work/gradle.txt"; } ||
  ./gradlew -q --console=plain :server:dependencies --configuration runtimeClasspath :gateway:dependencies --configuration runtimeClasspath > "$work/gradle.txt" 2>/dev/null; then
  {
    echo '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    echo '<groupId>net.eratiem</groupId><artifactId>randogeddon-audit</artifactId><version>0</version><dependencies>'
    grep -oE '[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+( -> [A-Za-z0-9_.+-]+)?' "$work/gradle.txt" \
      | sed -E 's/^([^:]+):([^:]+):[^ ]+ -> (.+)$/\1:\2:\3/' | sort -u \
      | awk -F: '{print "<dependency><groupId>"$1"</groupId><artifactId>"$2"</artifactId><version>"$3"</version></dependency>"}'
    echo '</dependencies></project>'
  } > "$work/pom.xml"
  echo "   $(grep -c '<dependency>' "$work/pom.xml") libraries"
  scan "Tracker and gateway (JVM)" --lockfile=/scan/pom.xml
else
  echo "== Tracker and gateway (JVM): Gradle could not list the dependencies" && found=1
fi

# Python: what is installed in the gateway image, if there is one
image="$(docker images --format '{{.Repository}}:{{.Tag}}' | grep -m1 -E 'randogeddon[-/]gateway' || true)"
if [ -n "$image" ]; then
  docker run --rm --entrypoint pip "$image" freeze 2>/dev/null > "$work/requirements.txt"
  echo "   $(wc -l < "$work/requirements.txt") packages of $image"
  scan "Archipelago's Python packages" --lockfile=/scan/requirements.txt
else
  echo "== Archipelago's Python packages: no gateway image here (build one, or run this where one is)"
fi

[ "$found" = 0 ] && echo "Nothing known." || echo "Something was found (or a part couldn't be looked up): see above."
exit "$found"
