# syntax=docker/dockerfile:1
# The tracker: Kotlin server (:server) + the Compose web app (:webApp), built from this repository.
#
# The Nexus credentials come from your Gradle init script as a BuildKit secret (see docker-compose.yml); they are only
# mounted while Gradle runs and never end up in an image layer.

FROM eclipse-temurin:25-jdk AS build
# Node.js, which the Kotlin/Wasm build downloads for webpack, needs libatomic
# hadolint ignore=DL3008
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
ENV GRADLE_USER_HOME=/gradle-home
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY shared shared
COPY server server
COPY webApp webApp
# Mounted with a .kts name: Gradle picks the script language from the file extension
RUN --mount=type=secret,id=gradle_init,required=true,target=/run/secrets/init.gradle.kts \
    --mount=type=cache,target=/gradle-home \
    ./gradlew --no-daemon --console=plain --init-script /run/secrets/init.gradle.kts \
      :server:test :server:installDist :webApp:wasmJsBrowserDistribution

FROM eclipse-temurin:25-jre
# The named volume behind /state takes this directory's owner, so the tracker can write its history there. uid 1000
# (instead of the image's own "ubuntu" user) also matches volumes created by the first, Python tracker.
RUN userdel --remove ubuntu && useradd --uid 1000 --home /app tracker && mkdir -p /state && chown tracker /state
COPY --from=build /src/server/build/install/server /app
COPY --from=build /src/webApp/build/dist/wasmJs/productionExecutable /app/web
ENV WEB_DIR=/app/web \
    STATE_PATH=/state/tracker.json \
    ROOM_JSON=/data/room.json \
    JAVA_OPTS="--enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=50"
USER tracker
WORKDIR /app
EXPOSE 8000
# No curl in the image: a plain HTTP request through bash
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8000 && printf "GET /healthz HTTP/1.0\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3'
CMD ["/app/bin/server"]
