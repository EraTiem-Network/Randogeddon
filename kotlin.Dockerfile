# syntax=docker/dockerfile:1
# The images of this repository; the Kotlin apps are native executables (GraalVM Native Image, Linux x86_64):
#   target tracker   the dashboard server (:server) with the Compose web app (:webApp)
#   target gateway   the game host: runs MultiServer (Archipelago, pinned release) and is the game clients' entry to it,
#                    checking the slot passwords (:gateway); also generates seeds (`ap-generate`)
#   target minecraft the hosted Minecraft servers of Minecraft Dig slots (Java 17, Forge with the mod, minecraft_host.py);
#                    nothing of Archipelago
# The Kotlin apps are built in one stage, so `docker compose build` runs Gradle once. Randogeddon Bridge (bridge/, Go)
# is built in its own stage for every system the dashboard offers it for; the tracker serves the builds.
#
# Gradle takes its libraries from the Maven mirror in RANDOGEDDON_MAVEN if there is one (else Maven Central, the Gradle
# plugin portal and Google); its login comes from your Gradle init script as a BuildKit secret (see docker-compose.yml),
# only mounted while Gradle runs and never in an image layer.

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
# native-image's heap while it builds (it takes most of the machine's memory otherwise), and its optimisation: `quick`
# (-Ob) or `release` (-O3); empty: by the version (quick for a -SNAPSHOT)
ARG NATIVE_MAX_HEAP=6g
ARG NATIVE_OPTIMIZATION=
ARG RANDOGEDDON_MAVEN=
ARG RANDOGEDDON_MAVEN_NAME=
# Mounted with a .kts name: Gradle picks the script language from the file extension. --build-cache: the tasks' results
# are kept in the cache mount (Gradle's home), so a change to one app doesn't compile, test and natively build the
# other again (the build directories start empty in every image build)
RUN --mount=type=secret,id=gradle_init,required=true,target=/run/secrets/init.gradle.kts \
    --mount=type=cache,target=/gradle-home \
    RANDOGEDDON_MAVEN="$RANDOGEDDON_MAVEN" RANDOGEDDON_MAVEN_NAME="$RANDOGEDDON_MAVEN_NAME" ./gradlew --no-daemon --build-cache --console=plain --init-script /run/secrets/init.gradle.kts -PnativeMarch="$NATIVE_MARCH" \
      -PnativeMaxHeap="$NATIVE_MAX_HEAP" ${NATIVE_OPTIMIZATION:+-PnativeOptimization="$NATIVE_OPTIMIZATION"} \
      :server:test :gateway:test :server:nativeCompile :gateway:nativeCompile :webApp:wasmJsBrowserDistribution

# Randogeddon Bridge: tested, then built for Windows, Linux and macOS (plain Go, no CGO, so every target builds here);
# Windows without a console window. BRIDGE_VERSION is the project's version (deploy.sh passes it); it is in each
# build's file name, so a downloaded file says which version it is.
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
      name="randogeddon-bridge-$BRIDGE_VERSION-$os-$arch"; \
      if [ "$os" = darwin ]; then name="randogeddon-bridge-$BRIDGE_VERSION-macos-$arch"; fi; \
      if [ "$os" = windows ]; then name="$name.exe" flags="-H windowsgui"; fi; \
      CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" go build -trimpath \
        -ldflags "-s -w $flags -X main.version=$BRIDGE_VERSION" -o "/out/$name" . || exit 1; \
    done
# The builds' checksums, and their signature if this build has the key (the secret bridge_signing_key: an SSH key
# without a passphrase; bridge/signing-key.pub is its public half, which people check the downloads against). The
# key is only there for this step, never in a layer.
RUN --mount=type=secret,id=bridge_signing_key,required=false \
    cd /out && sha256sum randogeddon-bridge-* > SHA256SUMS && cp /src/signing-key.pub signing-key.pub \
 && if [ -s /run/secrets/bridge_signing_key ]; then \
      ssh-keygen -Y sign -q -f /run/secrets/bridge_signing_key -n randogeddon-bridge SHA256SUMS \
      && ssh-keygen -Y verify -q -f signing-key.pub -I bridge@randogeddon -n randogeddon-bridge -s SHA256SUMS.sig < SHA256SUMS \
      && echo "The builds' checksums are signed"; \
    else echo "No bridge_signing_key: the builds' checksums aren't signed"; fi

# The executables link only glibc (--static-nolibc). The build stage (Oracle Linux 9, glibc 2.34) is older than
# Debian 12 (glibc 2.36), and glibc is backwards compatible.
FROM debian:bookworm-slim AS runtime
RUN useradd --uid 1000 --home /app --no-create-home randogeddon && mkdir -p /app && chown randogeddon /app
WORKDIR /app

# The web app's files compressed once, at their best (Brotli 11, gzip 9), next to each file as .br and .gz: the
# tracker sends those as they are (Routings.kt, preCompressed) instead of compressing every request. Only what is
# worth it: text and wasm of 1 KB and more; images and woff2 are compressed already.
FROM debian:bookworm-slim AS web
RUN apt-get update && apt-get install -y --no-install-recommends brotli && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/webApp/build/dist/wasmJs/productionExecutable /web
RUN find /web -type f -size +1k \( -name '*.wasm' -o -name '*.js' -o -name '*.mjs' -o -name '*.html' -o -name '*.css' \
      -o -name '*.json' -o -name '*.webmanifest' -o -name '*.svg' -o -name '*.map' -o -name '*.ttf' -o -name '*.otf' \
      -o -name '*.txt' \) -exec brotli -q 11 -k {} \; -exec gzip -9 -k -n {} \;

FROM runtime AS tracker
# The named volume behind /state takes this directory's owner, so the tracker can write its history there
RUN mkdir -p /state && chown randogeddon /state
COPY --from=build /src/server/build/native/nativeCompile/randogeddon /src/server/build/native/nativeCompile/*.so /app/
COPY --from=web /web /app/web
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
# Archipelago installed with what its install needs (git, compilers for the speedups and the worlds' packages); the
# stage below takes only the result, without them (they were 350 MB of the gateway image)
FROM python:3.12-slim-bookworm AS archipelago-build
ARG AP_VERSION=0.6.8
# The commit the release's tag stands for: a tag can be moved, the commit can't (empty: not checked)
ARG AP_COMMIT=54803be064fc7e80c4628777ed0b46a9390f255f
ENV PYTHONUNBUFFERED=1 \
    AP_HOME=/opt/archipelago/${AP_VERSION}
# libtk8.6: Python's tkinter needs it, and APWorlds import it for their client windows (only importing it, which needs
# no screen, while the worlds load)
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends git gcc g++ libc6-dev libtk8.6 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR ${AP_HOME}
RUN git clone --depth 1 --branch "${AP_VERSION}" https://github.com/ArchipelagoMW/Archipelago.git . \
 && { [ -z "${AP_COMMIT}" ] || [ "$(git rev-parse HEAD)" = "${AP_COMMIT}" ] \
      || { echo "Archipelago ${AP_VERSION} is $(git rev-parse HEAD), not ${AP_COMMIT}: set AP_COMMIT with AP_VERSION" >&2; exit 1; }; } \
 && rm -rf .git
# Core requirements + compiled speedups (otherwise AP tries to build them at runtime)
RUN pip install --no-cache-dir -r requirements.txt cython "setuptools>=75,<81" \
 && cythonize -b -i _speedups.pyx
# The requirements of every bundled world, so generation works for all games
RUN python ModuleUpdate.py -y
# The newest certificate bundle, whatever the requirements pulled in (scripts/audit.sh: an old one has known flaws)
# hadolint ignore=DL3013
RUN pip install --no-cache-dir --upgrade certifi

# Archipelago as it runs: the installed packages and its directory, and the one system library it needs (libtk8.6)
FROM python:3.12-slim-bookworm AS archipelago
ARG AP_VERSION=0.6.8
ENV PYTHONUNBUFFERED=1 \
    AP_HOME=/opt/archipelago/${AP_VERSION}
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends libtk8.6 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=archipelago-build /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=archipelago-build /usr/local/bin /usr/local/bin
COPY --from=archipelago-build ${AP_HOME} ${AP_HOME}
WORKDIR ${AP_HOME}
# Never try to pip-install anything at runtime
ENV SKIP_REQUIREMENTS_UPDATE=true

# Forge's server for Minecraft Dig, with the APWorld's mod: each download checked against its checksum. The installer
# fetches the libraries (Minecraft's server among them) and checks them itself
FROM eclipse-temurin:17-jre-jammy AS forge
ARG FORGE_VERSION=1.19.4-45.3.15
ARG DIG_MOD=aprandomizer-MC1.19.4-0.0.11.jar
WORKDIR /opt/forge
ADD --checksum=sha256:bf622e9f00b9a1e7ce36ff7b220ccc9350dc036d6f98e2767b5dce132562861c \
    https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE_VERSION}/forge-${FORGE_VERSION}-installer.jar installer.jar
ADD --checksum=sha256:36ebbe566deaa1a5a71ba657650189982c5aa4e0586e3a066aeadd472c52242e \
    https://github.com/jacobmix/Minecraft_AP_Randomizer/releases/download/0.0.11_dig/${DIG_MOD} mods/${DIG_MOD}
RUN java -jar installer.jar --installServer /opt/forge > /dev/null \
 && rm -f installer.jar installer.jar.log run.sh run.bat user_jvm_args.txt \
 && test -f "libraries/net/minecraftforge/forge/${FORGE_VERSION}/unix_args.txt" \
 && chmod -R a+rX /opt/forge

FROM archipelago AS gateway
# Two users: the gateway (randogeddon, uid 1000, the data directory's owner, in Archipelago's group too) and
# Archipelago's Python (archipelago, uid and group 1100), which runs third-party APWorld code and so can't read the
# gateway's environment or memory. The image starts as root (docker-entrypoint.sh, with CAP_SETUID, CAP_SETGID and
# CAP_KILL only), which becomes the gateway's user at once; the gateway starts each of Archipelago's processes as the
# other one with setpriv and raises its out-of-memory score with choom (both util-linux: checked here).
# MultiServer writes host.yaml and logs/ next to itself. Archipelago checks whether its own directory is writable: if
# not, it moves to the home directory and copies folders there that a plain checkout doesn't have (and fails). So the
# directory itself (not what's in it) belongs to Archipelago's user. custom_worlds/ is the gateway's: it fills it with
# the apworlds of the library (uploaded in the admin panel, or in the server's custom_worlds/), which Archipelago's
# group only reads (setgid: what is put there is that group's)
RUN groupadd --gid 1100 archipelago \
 && useradd --uid 1100 --gid archipelago --create-home archipelago \
 && useradd --uid 1000 --create-home --groups archipelago randogeddon \
 && mkdir -p logs custom_worlds \
 && touch host.yaml \
 && chown archipelago . logs host.yaml \
 && chown randogeddon:archipelago custom_worlds \
 && chmod 2750 custom_worlds \
 && setpriv --version && choom --version
COPY archipelago/serve.py archipelago/room_info.py archipelago/list_games.py archipelago/spoiler_run.py archipelago/logic_run.py \
     archipelago/seed_info.py ./
# Our own apworlds (World Locks), next to the bundled worlds
COPY apworlds/ worlds/
# Compiled now: Archipelago's user can't write __pycache__ here, so every listing of the games, generation and game
# server start compiled all of Archipelago again (listing the games: 5 s, compiled 2 s; the image grows by about 40 MB)
RUN python -m compileall -q -j 0 .
COPY --from=build /src/gateway/build/native/nativeCompile/randogeddon-gateway /src/gateway/build/native/nativeCompile/*.so /app/
COPY --chmod=0755 gateway/docker-entrypoint.sh /app/
EXPOSE 38281
# As root, which the script gives up at once (docker-compose.yml gives it no capability but those three)
# hadolint ignore=DL3002
ENTRYPOINT ["/app/docker-entrypoint.sh"]

# The hosted Minecraft servers of Minecraft Dig slots (the minecraft service): Forge with the mod on Java 17, and the
# program that runs them on the gateway's orders (minecraft_host.py: Python's standard library only). Nothing of
# Archipelago or the gateway: the servers run third-party mod code, so their image holds no more than they need
FROM forge AS minecraft
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --uid 1000 --create-home randogeddon
COPY archipelago/minecraft_host.py /app/
# It must run on this image's Python, not only on a newer one
RUN python3 -m py_compile /app/minecraft_host.py
USER randogeddon
EXPOSE 8002
CMD ["python3", "/app/minecraft_host.py"]
