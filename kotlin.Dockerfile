# syntax=docker/dockerfile:1
# The images of this repository; the Kotlin apps are native executables (GraalVM Native Image, Linux x86_64):
#   target tracker   the dashboard server (:server) with the Compose web app (:webApp)
#   target gateway   the game host: runs MultiServer (Archipelago, pinned release) and is the game clients' entry to it,
#                    checking the slot passwords (:gateway); also generates seeds (`ap-generate`)
# The Kotlin apps are built in one stage, so `docker compose build` runs Gradle once. Randogeddon Bridge (bridge/, Go)
# is built in its own stage for every system the dashboard offers it for; the tracker serves the builds.
#
# The token for pkg.example.org comes from your Gradle init script as a BuildKit secret (see docker-compose.yml); it is
# only mounted while Gradle runs and never ends up in an image layer.

FROM ghcr.io/graalvm/native-image-community:25 AS build
# Node.js, which the Kotlin/Wasm build downloads for webpack, needs libatomic; gradlew needs xargs (findutils)
RUN microdnf install -y libatomic findutils && microdnf clean all
WORKDIR /src
ENV GRADLE_USER_HOME=/gradle-home
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY shared shared
COPY database database
COPY server server
COPY gateway gateway
COPY webApp webApp
# CPU level of the native code: x86-64-v3 runs on the i7-8700 (and every AVX2 CPU); `native` only when building on it
ARG NATIVE_MARCH=x86-64-v3
# Mounted with a .kts name: Gradle picks the script language from the file extension
RUN --mount=type=secret,id=gradle_init,required=true,target=/run/secrets/init.gradle.kts \
    --mount=type=cache,target=/gradle-home \
    ./gradlew --no-daemon --console=plain --init-script /run/secrets/init.gradle.kts -PnativeMarch="$NATIVE_MARCH" \
      :server:test :gateway:test :server:nativeCompile :gateway:nativeCompile :webApp:wasmJsBrowserDistribution

# Randogeddon Bridge: tested, then built for Windows, Linux and macOS (plain Go, no CGO, so every target builds here);
# Windows without a console window. BRIDGE_VERSION is the project's version (deploy.sh passes it).
FROM golang:1.27.4-bookworm AS bridge
WORKDIR /src
COPY bridge/go.mod bridge/go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY bridge/ ./
ARG BRIDGE_VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go vet ./... && go test -count=1 ./... \
 && mkdir -p /out && echo "$BRIDGE_VERSION" > /out/VERSION \
 && for target in windows/amd64 windows/arm64 linux/amd64 linux/arm64 darwin/amd64 darwin/arm64; do \
      os="${target%/*}" arch="${target#*/}" flags=""; \
      name="randogeddon-bridge-$os-$arch"; \
      if [ "$os" = darwin ]; then name="randogeddon-bridge-macos-$arch"; fi; \
      if [ "$os" = windows ]; then name="$name.exe" flags="-H windowsgui"; fi; \
      CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" go build -trimpath \
        -ldflags "-s -w $flags -X main.version=$BRIDGE_VERSION" -o "/out/$name" . || exit 1; \
    done

# The executables link only glibc (--static-nolibc). The build stage (Oracle Linux 9, glibc 2.34) is older than
# Debian 12 (glibc 2.36), and glibc is backwards compatible.
FROM debian:bookworm-slim AS runtime
RUN useradd --uid 1000 --home /app --no-create-home randogeddon && mkdir -p /app && chown randogeddon /app
WORKDIR /app

FROM runtime AS tracker
# The named volume behind /state takes this directory's owner, so the tracker can write its history there
RUN mkdir -p /state && chown randogeddon /state
COPY --from=build /src/server/build/native/nativeCompile/randogeddon /src/server/build/native/nativeCompile/*.so /app/
COPY --from=build /src/webApp/build/dist/wasmJs/productionExecutable /app/web
# Randogeddon Bridge's builds, offered under Your connection (/downloads/bridge/<file>)
COPY --from=bridge /out /app/bridge
# `docker compose exec tracker randogeddon admins` finds the binary on the PATH
RUN ln -s /app/randogeddon /usr/local/bin/randogeddon
ENV WEB_DIR=/app/web \
    BRIDGE_DIR=/app/bridge \
    STATE_DIR=/state \
    DATA_DIR=/data
USER randogeddon
EXPOSE 8000
# No curl in the image: a plain HTTP request through bash
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8000 && printf "GET /healthz HTTP/1.0\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3'
CMD ["/app/randogeddon"]

# Archipelago from a pinned upstream release, for the game server and seed generation. Debian 12 (glibc 2.36), so the
# native gateway runs on it too.
FROM python:3.12-slim-bookworm AS archipelago
ARG AP_VERSION=0.6.8
ENV PYTHONUNBUFFERED=1 \
    AP_HOME=/opt/archipelago/${AP_VERSION}
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends git gcc g++ libc6-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR ${AP_HOME}
RUN git clone --depth 1 --branch "${AP_VERSION}" https://github.com/ArchipelagoMW/Archipelago.git . \
 && rm -rf .git
# Core requirements + compiled speedups (otherwise AP tries to build them at runtime)
RUN pip install --no-cache-dir -r requirements.txt cython "setuptools>=75,<81" \
 && cythonize -b -i _speedups.pyx
# The requirements of every bundled world, so generation works for all games
RUN python ModuleUpdate.py -y
# Never try to pip-install anything at runtime
ENV SKIP_REQUIREMENTS_UPDATE=true

FROM archipelago AS gateway
# MultiServer runs as this user and writes host.yaml and logs/ next to itself. Archipelago checks whether its own
# directory is writable: if not, it moves to the home directory and copies folders there that a plain checkout doesn't
# have (and fails). So the directory itself (not what's in it) belongs to the user. custom_worlds/ too: the gateway
# fills it with the apworlds of the library (uploaded in the admin panel, or in the server's custom_worlds/).
RUN useradd --uid 1000 --create-home randogeddon \
 && mkdir -p logs custom_worlds \
 && touch host.yaml \
 && chown randogeddon . logs host.yaml custom_worlds
COPY archipelago/serve.py archipelago/room_info.py archipelago/list_games.py ./
# Our own apworlds (World Locks), next to the bundled worlds
COPY apworlds/ worlds/
COPY --from=build /src/gateway/build/native/nativeCompile/randogeddon-gateway /src/gateway/build/native/nativeCompile/*.so /app/
USER randogeddon
EXPOSE 38281
CMD ["/app/randogeddon-gateway"]
