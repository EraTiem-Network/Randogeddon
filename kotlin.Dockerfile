# syntax=docker/dockerfile:1.29.0@sha256:572cebbc2b15e6f84c077742952674889cb63cd5e9b80abdffcbe587b6f19183
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
#
# Every image from outside (the `syntax` line above too) is pinned by its digest beside its tag, as are postgres and
# mailpit in the compose files: a base changes only when its digest here is raised. ./scripts/base-images.sh shows
# which tags have moved since.

FROM ghcr.io/graalvm/native-image-community:25@sha256:0d936f32bb8acb5bc60c41b33e05f064d7a6aaf36b726538296c54949bd4a3c0 AS build
# Node.js, which the Kotlin/Wasm build downloads for webpack, needs libatomic; gradlew needs xargs (findutils); the
# npm lock's check needs diff (diffutils)
RUN microdnf install -y libatomic findutils diffutils && microdnf clean all
WORKDIR /src
ENV GRADLE_USER_HOME=/gradle-home
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
# gradle/ holds the wrapper (with the distribution's checksum) and verification-metadata.xml, which Gradle checks
# every download against
COPY gradle gradle
# The npm locks: kotlin-npm-tooling/<hash>/yarn.lock is the lock of the Kotlin plugin's npm tooling (webpack and its
# tools, which it installs for the Wasm build; <hash> names the tooling of one Kotlin version). The project's own lock
# (wasm/yarn.lock) isn't there: the project has no npm dependencies, and the plugin stores no empty lock
COPY kotlin-js-store kotlin-js-store
COPY shared shared
COPY database database
COPY server server
COPY gateway gateway
COPY webApp webApp
# :shared's tests check the fingerprint the web app shows against it
COPY bridge/signing-key.pub bridge/signing-key.pub
# CPU level of the native code: x86-64-v3 runs on the i7-8700 (and every AVX2 CPU); `native` only when building on it
ARG NATIVE_MARCH=x86-64-v3
# native-image's heap while it builds (it takes most of the machine's memory otherwise), and its optimisation: `quick`
# (-Ob) or `release` (-O3); empty: by the version (quick for a -SNAPSHOT)
ARG NATIVE_MAX_HEAP=6g
ARG NATIVE_OPTIMIZATION=
ARG RANDOGEDDON_MAVEN=
ARG RANDOGEDDON_MAVEN_NAME=
# Gradle's home is a cache mount kept between builds. GRADLE_CACHE_ID gives a build a cache of its own (for a release:
# one no other build has written to); GRADLE_NO_BUILD_CACHE (any value) takes no task's result from the build cache,
# so the executables are made from this build's sources. The defaults: the one shared cache, with the build cache.
# GRADLE_VERIFICATION=sha256 writes gradle/verification-metadata.xml anew from what this build downloads (the target
# verification-metadata hands it out); empty: every download is checked against the committed file.
ARG GRADLE_CACHE_ID=/gradle-home
ARG GRADLE_NO_BUILD_CACHE=
ARG GRADLE_VERIFICATION=
# Mounted with a .kts name: Gradle picks the script language from the file extension. --build-cache: the tasks' results
# are kept in the cache mount (Gradle's home), so a change to one app doesn't compile, test and natively build the
# other again (the build directories start empty in every image build). What Gradle would apply from its home by itself
# (init scripts, gradle.properties) is removed first: nothing an earlier build left in the cache runs in this one.
# The unit tests of every module but :webApp (the integration tests need Docker: not here).
# The npm tooling's locks: the plugin installs its tooling in <user home>/.kotlin/kotlin-npm-tooling/yarn/<hash>/ and,
# when a yarn.lock is there already, installs from it and leaves it as it is. This stage runs as root (no USER), and
# nothing moves .kotlin (no kotlin.user.home), so that is /root/.kotlin, which is no cache mount: each build starts
# without it, and each committed lock is put in its place (over an older one, should there be one). Afterwards the
# build fails if the plugin used a tooling no lock is committed for (a Kotlin update) or changed a lock. And
# kotlin-js-store/ must come out as it went in: a lock the plugin would change or add there (the project's own, once
# it has npm dependencies) fails the build instead of being taken silently.
RUN --mount=type=secret,id=gradle_init,required=true,target=/run/secrets/init.gradle.kts \
    --mount=type=cache,id=${GRADLE_CACHE_ID},target=/gradle-home \
    rm -rf /gradle-home/init.d /gradle-home/init.gradle /gradle-home/init.gradle.kts /gradle-home/gradle.properties \
 && cp -a kotlin-js-store /tmp/kotlin-js-store \
 && tooling=/root/.kotlin/kotlin-npm-tooling/yarn \
 && for locked in kotlin-js-store/kotlin-npm-tooling/*/; do \
      [ -f "${locked}yarn.lock" ] || continue; \
      mkdir -p "$tooling/$(basename "$locked")" && cp "${locked}yarn.lock" "$tooling/$(basename "$locked")/yarn.lock" || exit 1; \
    done \
 && RANDOGEDDON_MAVEN="$RANDOGEDDON_MAVEN" RANDOGEDDON_MAVEN_NAME="$RANDOGEDDON_MAVEN_NAME" ./gradlew --no-daemon \
      --${GRADLE_NO_BUILD_CACHE:+no-}build-cache --console=plain --init-script /run/secrets/init.gradle.kts \
      ${GRADLE_VERIFICATION:+--write-verification-metadata "$GRADLE_VERIFICATION"} -PnativeMarch="$NATIVE_MARCH" \
      -PnativeMaxHeap="$NATIVE_MAX_HEAP" ${NATIVE_OPTIMIZATION:+-PnativeOptimization="$NATIVE_OPTIMIZATION"} \
      :shared:jvmTest :database:test :server:test :gateway:test \
      :server:nativeCompile :gateway:nativeCompile :webApp:wasmJsBrowserDistribution \
 && for used in "$tooling"/*/; do \
      [ -d "$used" ] || continue; \
      hash="$(basename "$used")"; \
      if [ ! -f "kotlin-js-store/kotlin-npm-tooling/$hash/yarn.lock" ]; then \
        echo "The Kotlin plugin's npm tooling is a new one ($hash; a Kotlin update): no lock is committed for it. Take" \
             "its yarn.lock (<user home>/.kotlin/kotlin-npm-tooling/yarn/$hash/yarn.lock after a local build) into" \
             "kotlin-js-store/kotlin-npm-tooling/$hash/, review it and commit it (see .docs/DEVELOPMENT.md)" >&2; \
        exit 1; \
      fi; \
      cmp "kotlin-js-store/kotlin-npm-tooling/$hash/yarn.lock" "$used/yarn.lock" \
        || { echo "The Kotlin plugin changed the npm tooling's lock $hash instead of installing from it" >&2; exit 1; }; \
    done \
 && { diff -r /tmp/kotlin-js-store kotlin-js-store \
      || { echo "The build changed kotlin-js-store/ (see above): a new or changed lock of the project's own, to commit" \
                "after a review (see .docs/DEVELOPMENT.md)" >&2; exit 1; }; }

# gradle/verification-metadata.xml as the build stage wrote it with GRADLE_VERIFICATION=sha256 (only built on purpose:
# docker buildx build --target verification-metadata --output type=local,dest=gradle ...)
FROM scratch AS verification-metadata
COPY --from=build /src/gradle/verification-metadata.xml /

# Randogeddon Bridge: tested, then built for Windows, Linux and macOS (plain Go, no CGO, so every target builds here);
# Windows without a console window. BRIDGE_VERSION is the project's version (deploy.sh passes it); it is in each
# build's file name, so a downloaded file says which version it is.
FROM golang:1.27.4-bookworm@sha256:4fa7c968ba93f1635670522dbb01a2c78150443e1385746924566079f36570ea AS bridge
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
# BRIDGE_SIGNING_KEY_ID: the key's public half (deploy.sh passes it; empty: no key). A secret is no part of the
# build cache's key, so without it a build with the key would take this step's unsigned result from an earlier build
# without one (or one signed with another key); with it, each key (and none) has its own cached result.
ARG BRIDGE_SIGNING_KEY_ID=
RUN --mount=type=secret,id=bridge_signing_key,required=false \
    cd /out && sha256sum randogeddon-bridge-* > SHA256SUMS && cp /src/signing-key.pub signing-key.pub \
 && if [ -s /run/secrets/bridge_signing_key ]; then \
      ssh-keygen -Y sign -q -f /run/secrets/bridge_signing_key -n randogeddon-bridge SHA256SUMS \
      && ssh-keygen -Y verify -q -f signing-key.pub -I bridge@randogeddon -n randogeddon-bridge -s SHA256SUMS.sig < SHA256SUMS \
      && echo "The builds' checksums are signed"; \
    elif [ -n "$BRIDGE_SIGNING_KEY_ID" ]; then echo "BRIDGE_SIGNING_KEY_ID is set, but no bridge_signing_key came" >&2; exit 1; \
    else echo "No bridge_signing_key: the builds' checksums aren't signed"; fi

# The executables link only glibc (--static-nolibc). The build stage (Oracle Linux 9, glibc 2.34) is older than
# Debian 12 (glibc 2.36), and glibc is backwards compatible.
FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc AS runtime
RUN useradd --uid 1000 --home /app --no-create-home randogeddon && mkdir -p /app && chown randogeddon /app
WORKDIR /app

# The web app's files compressed once, at their best (Brotli 11, gzip 9), next to each file as .br and .gz: the
# tracker sends those as they are (Routings.kt, preCompressed) instead of compressing every request. Only what is
# worth it: text and wasm of 1 KB and more; images and woff2 are compressed already.
FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc AS web
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
FROM python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df AS archipelago-build
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
# The packages of Archipelago and of every bundled world (so generation works for all games), as locked for this
# release: archipelago/requirements-<AP_VERSION>.lock holds each one's exact version and its files' hashes, cython and
# setuptools for the speedups among them, each at Archipelago's own pin where it has one (what is kept newer comes
# after the check below; .docs/OPERATIONS.md, "Updating Archipelago", says how the locks are made). Nothing beyond it is installed
# (--no-deps). A package that only comes from a git repository can't carry a hash: requirements-<AP_VERSION>.git.txt,
# if there is one, names it by its commit (checked here). Then the compiled speedups (otherwise AP tries to build them
# at runtime).
COPY archipelago/requirements-${AP_VERSION}.* /tmp/requirements/
RUN pip install --no-cache-dir --require-hashes --no-deps -r "/tmp/requirements/requirements-${AP_VERSION}.lock" \
 && git_file="/tmp/requirements/requirements-${AP_VERSION}.git.txt" \
 && if [ -f "$git_file" ]; then \
      if grep -vE '^[[:space:]]*(#|$)' "$git_file" | grep -vE '@[0-9a-f]{40}([[:space:]]*(#.*)?)?$'; then \
        echo "$git_file: each line must end in its commit (@ and 40 hex digits)" >&2; exit 1; \
      fi; \
      pip install --no-cache-dir --no-deps -r "$git_file"; \
    fi \
 && cythonize -b -i _speedups.pyx
# Archipelago's own updater (what `ModuleUpdate.py -y` did here before) must find every requirement of Archipelago and
# of each world satisfied by the lock: it runs without network and with its installing replaced by a stop, so a
# requirement the lock misses fails the build instead of being fetched, and nothing installed may have changed after it
RUN --network=none pip freeze --all > /tmp/requirements/installed.txt \
 && python -c 'import sys, ModuleUpdate as m; \
ok = callable(getattr(m, "update_command", None)) and not getattr(m, "update_ran", True); \
ok or sys.exit("ModuleUpdate.py is not as this check expects (update_command, update_ran): look at it again"); \
m.update_command = lambda *a, **k: sys.exit("ModuleUpdate.py would install packages: the lock misses a requirement of Archipelago or of a world"); \
m.update(yes=True)' \
 && pip freeze --all | diff /tmp/requirements/installed.txt -
# What is kept newer on purpose than Archipelago's own pin, after the check (which holds Archipelago to its pins):
# requirements-<AP_VERSION>.newer.lock, if there is one, with hashes as the lock; today the CA bundle (certifi, which
# Archipelago pins to an older one). scripts/audit.sh says when one of these is outdated
RUN newer="/tmp/requirements/requirements-${AP_VERSION}.newer.lock" \
 && if [ -f "$newer" ]; then pip install --no-cache-dir --require-hashes --no-deps -r "$newer"; fi \
 && rm -rf /tmp/requirements

# Archipelago as it runs: the installed packages and its directory, and the one system library it needs (libtk8.6)
FROM python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df AS archipelago
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

# The Python tests (archipelago/tests: the wrappers, World Locks and minecraft_host.py, each with stand-ins for what
# they use of Archipelago) on the gateway's Python; the gateway's image isn't built unless they pass
FROM archipelago AS python-tests
WORKDIR /tests
COPY archipelago/ archipelago/
COPY apworlds/ apworlds/
RUN python -m unittest discover -s archipelago/tests && touch /tests/passed

# Forge's server for Minecraft Dig, with the APWorld's mod: each download checked against its checksum. The installer
# fetches the libraries (Minecraft's server among them) and checks them itself
FROM eclipse-temurin:17-jre-jammy@sha256:c6a5dd70fb24f4e07ef88cbda8efb613a5a649a1af071a41de8eaabd1ee6b5da AS forge
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
# other one with setpriv, bounds it with prlimit and raises its out-of-memory score with choom (all util-linux: checked
# here).
# MultiServer writes host.yaml and logs/ next to itself. Archipelago checks whether its own directory is writable: if
# not, it moves to the home directory and copies folders there that a plain checkout doesn't have (and fails). So the
# directory is its group's to write, but root's and sticky (1775): Archipelago's user can add files (host.yaml's
# rewrite, its logs) but not rename, replace or delete what is root's (the code, worlds/, custom_worlds/, the scripts).
# A file it adds can't stand in for a module either: the gateway runs Python with PYTHONSAFEPATH (the script's
# directory isn't put first on the path), and randogeddon.pth puts the working directory (the release's) last, after
# Python's own modules and the installed packages. logs/ and host.yaml are that user's (it writes them at every run).
# custom_worlds/ is the gateway's: it fills it with the apworlds of the library (uploaded in the admin panel, or in the
# server's custom_worlds/), which Archipelago's group only reads (setgid: what is put there is that group's)
RUN groupadd --gid 1100 archipelago \
 && useradd --uid 1100 --gid archipelago --create-home archipelago \
 && useradd --uid 1000 --create-home --groups archipelago randogeddon \
 && mkdir -p logs custom_worlds \
 && touch host.yaml \
 && chown root:archipelago . && chmod 1775 . \
 && chown archipelago logs host.yaml \
 && chown randogeddon:archipelago custom_worlds \
 && chmod 2750 custom_worlds \
 && echo 'import os, sys; sys.flags.safe_path and sys.path.append(os.getcwd())' \
      > /usr/local/lib/python3.12/site-packages/randogeddon.pth \
 && setpriv --version && choom --version && prlimit --version
COPY archipelago/serve.py archipelago/room_info.py archipelago/list_games.py archipelago/spoiler_run.py archipelago/logic_run.py \
     archipelago/seed_info.py ./
# Our own apworlds (World Locks), next to the bundled worlds
COPY apworlds/ worlds/
# Compiled now: Archipelago's user can't write __pycache__ here, so every listing of the games, generation and game
# server start compiled all of Archipelago again (listing the games: 5 s, compiled 2 s; the image grows by about 40 MB).
# The Python tests' result is mounted here only so that this image depends on them; nothing of it stays in the image
RUN --mount=type=bind,from=python-tests,source=/tests/passed,target=/tmp/python-tests-passed \
    python -m compileall -q -j 0 .
COPY --from=build /src/gateway/build/native/nativeCompile/randogeddon-gateway /src/gateway/build/native/nativeCompile/*.so /app/
COPY --chmod=0755 gateway/docker-entrypoint.sh /app/
EXPOSE 38281
# As root, which the script gives up at once (docker-compose.yml gives it no capability but those three)
# hadolint ignore=DL3002
ENTRYPOINT ["/app/docker-entrypoint.sh"]

# The hosted Minecraft servers of Minecraft Dig slots (the minecraft service): Forge with the mod on Java 17, and the
# program that runs them on the gateway's orders (minecraft_host.py: Python's standard library only). Nothing of
# Archipelago or the gateway: the servers run third-party mod code, so their image holds no more than they need.
# Two users, as in the gateway's image: minecraft_host.py runs as randogeddon (uid 1000, the data folder's owner), the
# Java servers as minecraft-server (uid 1200, in randogeddon's group for the servers' folders), which can't read the
# host's environment (MINECRAFT_TOKEN) in /proc, signal or trace it. The image starts as root (minecraft-entrypoint.sh,
# with CAP_SETUID, CAP_SETGID and CAP_KILL only), which becomes randogeddon at once keeping just those three; each
# server's Java starts through java-as-server (JAVA), which drops to the servers' user with nothing left, bounded by
# prlimit (both util-linux: checked here). Started as any other user (a compose file of before: user "1000"), all of it
# runs as that one, as before
FROM forge AS minecraft
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --uid 1000 --create-home randogeddon \
 && useradd --uid 1200 --gid randogeddon --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin minecraft-server \
 && setpriv --version && prlimit --version
COPY archipelago/minecraft_host.py /app/
# It must run on this image's Python, not only on a newer one
RUN python3 -m py_compile /app/minecraft_host.py
# Its start and the servers' Java (beside the gateway's start, whose pattern they follow)
COPY --chmod=0755 gateway/minecraft-entrypoint.sh /app/minecraft-entrypoint.sh
COPY --chmod=0755 gateway/java-as-server.sh /app/java-as-server
ENV JAVA=/app/java-as-server
EXPOSE 8002
# As root, which the script gives up at once (docker-compose.yml gives it no capability but those three)
# hadolint ignore=DL3002
ENTRYPOINT ["/app/minecraft-entrypoint.sh"]
