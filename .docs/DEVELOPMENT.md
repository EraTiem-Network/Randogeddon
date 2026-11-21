# Randogeddon: development

Trying it locally, building, testing and the API, in detail. The README has the overview.

## Demo

```sh
./scripts/demo.sh        # starts the stack, creates two sessions (demo/a, demo/b) through the API, plays both
./scripts/demo.sh loop   # the same, again and again: once both rounds are at 100 % and finished, new sessions start
./scripts/demo.sh stop   # stops the simulated players; `docker compose down` stops everything

nohup ./scripts/demo.sh loop > output/demo/loop.log 2>&1 &   # the loop in the background
```

Two sessions at once, with players of their own, created like in the panel (`demo/admin.py` calls its API):

- **demo-a-…:** Alice three slots (two of them start locked), Bob two (one locked) and, with Carol, Risk of Rain 2,
  which they play together (a shared slot: on both cards, both get its slot password).
- **demo-b-…:** Dave and Erin, two slots each, with mixed locks: Dave's Yacht Dice starts open, Erin's Hollow Knight
  locked, the other two are left to chance with two slots open in all.

The simulator (`demo/simulate.py`, one per session) connects through the gateway with the slot passwords, checks
locations quickly for half a minute and then evenly until the end, and finds the keys on a schedule (the last one late,
hinted before). Every slot reaches its goal within about `DEMO_MINUTES` (default 15); then the simulator ends. 2
minutes later the session finishes by itself (the countdown shows meanwhile); once both are done, the next round
starts after a pause with new session IDs. The round before stays as finished sessions, older demo rounds are
deleted. About one in three random seeds can't be generated ("unbeatable"), so it retries with another one.

Alice is the demo's admin: her login is made on the first run (`randogeddon admins --create`), and its password is
printed once and kept in `output/demo/admin.password`. The other logins are printed once, when the first session with
them is created (a new password: `docker compose exec tracker randogeddon logins --reset Bob`). Open
http://localhost:8000 and log in.

The demo builds the native images, so it needs Docker for Linux containers; on Windows, run it in WSL (see
*Native build*).

## Local testing

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml   # publishes 38281 + 8000, no Traefik
export POSTGRES_PASSWORD=local INTERNAL_TOKEN=local                # or set them in .env
docker compose up -d
docker compose exec tracker randogeddon admins --create Admin
# dashboard on http://localhost:8000: create a session in the panel; its game server is
# ws://localhost:38281/<session-id>
```

For a test seed that needs no ROM or installed game, use a few YAMLs like
`name: Alice` / `game: APQuest` / `APQuest: {}`.

## Developing the tracker and the gateway

Gradle 9.8.2 (wrapper), Kotlin 2.4, JDK 25 toolchain; dependencies come from Maven Central, the plugin portal and Google, or from
a mirror of them: its URL as `randogeddon.maven` in `~/.gradle/gradle.properties` (or `RANDOGEDDON_MAVEN`), its login
from your init script. Run Gradle on JDK 25 (e.g. `JAVA_HOME=/usr/lib/jvm/java-25-temurin`).

```sh
./gradlew :server:test :gateway:test :database:test  # unit tests (also run in the Docker build)
./gradlew :database:integrationTest             # schema and stores against PostgreSQL (Testcontainers, Linux Docker)
./gradlew :webApp:wasmJsBrowserDistribution     # build the web app -> webApp/build/dist/wasmJs/productionExecutable
./gradlew :server:run                           # tracker on :8000 against the gateway on localhost, PostgreSQL on :5432
AP_ROOT=<dir with one Archipelago checkout per release> ./gradlew :gateway:run   # gateway on :38281
./gradlew :server:run --args="sessions"         # a host command, against the same database
```

Both use the sessions in `data/` of this checkout; `:server:run` builds the web app first. All
settings are in `server/src/main/resources/application.yaml` and `gateway/src/main/resources/application.yaml`, each
with the environment variable that overrides it (database: `DB_URL`, `DB_USER`, `DB_PASSWORD`). For UI work,
`./gradlew :webApp:wasmJsBrowserDevelopmentRun` starts a dev server with hot reload that proxies `/api` to the tracker
on port 8000.

The schema is in `database/src/main/resources/db/migration`; a new migration must also be listed in `index.txt` there
(a native image can't scan the classpath; a test checks the list).

### Versions

While Randogeddon is in development, its version (root `build.gradle.kts`) is `0.<phase>.<step>`: the phase (as in
`.docs/TODO.md`) and its PR. A PR's branch builds `0.<phase>.<step>-SNAPSHOT`; its last commit before the merge drops
the suffix (the release `0.<phase>.<step>`), its merge commit is tagged `v0.<phase>.<step>` (`scripts/deploy.sh` pushes
that commit's images under the version and `latest`: `…/tracker:0.<phase>.<step>`, `…/gateway:0.<phase>.<step>`; any
other commit's under the commit and `edge`), and the next PR starts
with the next step's `-SNAPSHOT`. A follow-up to a step that was merged already gets a fourth number (`0.8.2.1`),
which the next step drops again. `CHANGELOG.md` lists every version by phase.

For a release, `scripts/deploy.sh` also publishes Randogeddon Bridge (`scripts/publish-bridge.sh`, copied out of the
tracker image, so exactly the builds it serves): to Artifact Keeper at `BRIDGE_REGISTRY`, the generic repository `randogeddon-bridge`,
as `<version>/<file>` with `SHA256SUMS`. Every release is also a GitHub release `v<version>`, the version's part of
`CHANGELOG.md` as its notes: before 1.0.0 a pre-release without files, from 1.0.0 on with the bridge's builds.
`--dry-run` shows what it would do.

### Texts and languages

Every text lives in `shared/src/commonMain/kotlin/net/eratiem/randogeddon/shared/i18n/`, as typed Kotlin: an
interface per area (`DashboardTexts`, `PanelTexts`, …, listed in `Texts`) and its English and German objects in the
same file. A text with values is a function (`fun slots(n: Int)`), so plurals and word order are plain Kotlin; feed
lines with styled names are templates with marks (`{sender} sent {item} to {receiver}`). The web app reads them
through `LocalTexts.current`; the server's refusals are `Problem` codes worded in `ProblemTexts` (the server in the
request's language, the web app in the viewer's); the mails come from `MailTexts`. `index.html`'s forms have a small
table of their own in its script.

- **A new text:** a member in its area's interface, then its words in `English…` and `German…` (it doesn't compile
  before). `TextsTest` checks that German borrows nothing from English, that no text drops a value, and that every
  template and every problem has the same marks and values in each language.
- **A new language:** an entry in `Language` (code, tag for dates, whether its dates are numeric, its own name), an
  object per area like `German…`, and `texts()` returning them; plus its column in `index.html`'s table.

### Native build

The images contain native executables (GraalVM CE 25), built in `kotlin.Dockerfile` with the setup in the root
`build.gradle.kts` (the same as CoreSync's server):

- `-march=x86-64-v3` by default: AVX2, FMA and BMI2, which the server's **Intel i7-8700** (Coffee Lake) has; it has
  no AVX-512, so `x86-64-v4` would not run. Build with `NATIVE_MARCH=native` (in `.env`) only on that machine itself.
- Optimisation follows the version: `-Ob` (fast build) for `-SNAPSHOT`, `-O3` for releases
  (`-PnativeOptimization=quick|release` overrides it).
- `--static-nolibc`: only glibc is linked dynamically. The build stage (Oracle Linux 9, glibc 2.34) is older than the
  runtime image (Debian 12, glibc 2.36); glibc is backwards compatible.

Native images can't be cross-compiled, so the build runs on Linux: in the Docker build, or directly in WSL Debian with
GraalVM 25 (`sdk install java 25-graal`) and, with a mirror, its login in `~/.gradle/init.gradle.kts`:
`./gradlew :server:nativeCompile :gateway:nativeCompile`. Docker on Windows may run only Windows containers; then use
the Docker of WSL (also for `:database:integrationTest`).

Reflection and resources that the libraries' shared metadata doesn't cover are in
`*/src/main/resources/META-INF/native-image/`. After adding a library, record what it needs with the tracing agent
and review the result:

```sh
./gradlew -Pagent=standard :server:run          # exercise login, stream, commands; stop it normally
./gradlew :server:metadataCopy                  # likewise :gateway:run and :gateway:metadataCopy
```

## Measuring

`./scripts/measure.sh [viewers] [seconds]` (in WSL, with `./scripts/demo.sh` running) prints a report of the stack:
the page's files as sent (plain, gzip, Brotli), the images and what is in them, the containers' memory and CPU idle
and with that many viewers' live streams open (default 20 for 60 s), the API's answer times and how long the first
screen takes in headless Chromium. `--build` first times a build from nothing (it clears BuildKit's cache) and one
without changes. The baseline, the audit of the code and what changed since are in `.docs/MEASUREMENTS.md`.

## Known flaws in the dependencies

`./scripts/audit.sh` looks the bridge's Go modules, the tracker's and the gateway's JVM libraries and the gateway
image's Python packages up in the OSV database (osv-scanner, in Docker) and ends with 1 if it finds something. Run
it before a release and when a dependency changes. Where Gradle and Docker aren't on the same machine, give it
Gradle's list: `GRADLE_DEPENDENCIES=<file> ./scripts/audit.sh`, the file from `./gradlew :server:dependencies
--configuration runtimeClasspath :gateway:dependencies --configuration runtimeClasspath`.

## API

API: `POST /api/login` (JSON with `username`, a name or an e-mail address, and `password`; sets the cookie, or with
two-factor login answers `{"next": "code"|"setup", "pending": …}` for `POST /api/login/code` or
`.../setup` and `.../setup/confirm`),
`POST /api/logout`; with single sign-on `GET /api/login/options`, `GET /api/login/oidc` (to the provider) and
`.../callback`, `POST /api/login/link` (a CLI login link), Authentik's back-channel logout
`POST /api/login/oidc/backchannel`, and its webhooks `POST /api/login/oidc/deleted` and `.../updated`; `GET /api/invitations/<token>`, `POST /api/signup` (the
sign-up page) and `POST /api/email/confirm`
(a new address's link); with a login: `GET /api/account` and `PUT .../name`, `.../password`, `.../auto-delete`,
`POST .../email`, `POST .../2fa/setup`, `.../2fa/enable`, `.../2fa/disable`, `.../2fa/codes` (the settings),
`GET /api/me` (name, Administrator, the sessions they manage), `GET /api/sessions` (the sessions the user sees), and per session
`GET /api/sessions/<id>/state` (summary JSON), `.../stream` (SSE), `.../player/<slot>`, `.../connection` (address,
own slots and passwords), `.../archive` (a finished one as it ended), `.../spoiler` (the playthrough and how many lines the log has),
`.../spoiler/log?from=&count=` (some of its lines, at most 400), `.../spoiler/search?q=` (the lines that contain a
text of 2 to 100 characters, the first 500; answers 429 while another search of the same user runs), `.../files` and
`.../files/<name>` (the seed's files of the user's slots: patches, mods; every slot's for its Session-Managers and
Administrators). The panel's are under
`/api/manage` (the sessions the user manages or supports), `/api/sessions/new` (with `/id?id=`: whether an ID can
be a new session's) and `POST /api/sessions` (creating one, for
everyone), a session's management under `/api/sessions/<id>/manage/…` and its people under `.../people` (its Session-Managers and Administrators), support's
`.../support`, the watchers under `.../watchers` and the share link under `.../share` (players, support and Session-Managers),
the share link's read-only `/api/watch/<token>/state`, `.../stream`, `.../player/<slot>` (without a login), and the
Administrators' `/api/admin/sessions`, `/api/admin/users`, `/api/admin/settings`. A login's bridge keys are under `/api/account/bridge-keys` (`GET`, `POST`, `DELETE .../<id>`);
the bridge's own routes, with a key as `Authorization: Bearer`, are `GET /bridge/info`, `/bridge/me`,
`/bridge/sessions`, `/bridge/sessions/<id>/connection` and `DELETE /bridge/key`, and its builds
`GET /downloads/bridge` and `.../<file>` (open). Without a login these answer 401, for a session the user isn't part of 403. `GET /healthz` is
open and says nothing about the sessions.

The login form is a plain HTML form in `index.html` rather than part of the Compose app, so password managers can
save and fill the generated passwords.
