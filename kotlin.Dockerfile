# syntax=docker/dockerfile:1
# The images of this repository; the Kotlin apps are native executables (GraalVM Native Image, Linux x86_64):
#   target tracker   the dashboard server (:server) with the Compose web app (:webApp)
#   target gateway   the game host: runs MultiServer (Archipelago, pinned release) and is the game clients' entry to it,
#                    checking the slot passwords (:gateway); also generates seeds (`ap-generate`)
# The Kotlin apps are built in one stage, so `docker compose build` runs Gradle once.
#
# The Nexus credentials come from your Gradle init script as a BuildKit secret (see docker-compose.yml); they are only
# mounted while Gradle runs and never end up in an image layer.

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
# `docker compose exec tracker randogeddon logins` finds the binary on the PATH
RUN ln -s /app/randogeddon /usr/local/bin/randogeddon
ENV WEB_DIR=/app/web \
    STATE_PATH=/state/tracker.json \
    ROOM_JSON=/data/room.json
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
# have (and fails). So the directory itself (not what's in it) belongs to the user.
RUN useradd --uid 1000 --create-home randogeddon \
 && mkdir -p logs custom_worlds \
 && touch host.yaml \
 && chown randogeddon . logs host.yaml
COPY archipelago/serve.py archipelago/room_info.py ./
# Our own apworlds (World Locks), next to the bundled worlds
COPY apworlds/ worlds/
# Seed generation: `docker compose run --rm generate` (see docker-compose.yml)
COPY archipelago/entrypoint.sh /usr/local/bin/ap-generate
COPY --from=build /src/gateway/build/native/nativeCompile/randogeddon-gateway /src/gateway/build/native/nativeCompile/*.so /app/
ENV ROOM_JSON=/data/room.json
USER randogeddon
EXPOSE 38281
CMD ["/app/randogeddon-gateway"]
