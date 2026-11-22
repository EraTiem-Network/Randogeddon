# Randogeddon: development

Trying it locally, building, testing and the API, as a compact reference. The README has the overview.

## Demo

```sh
./scripts/demo.sh        # starts the stack, creates two sessions (demo/a, demo/b) through the API, plays both
./scripts/demo.sh loop   # again and again: once both rounds are finished, new sessions start
./scripts/demo.sh stop   # stops the simulated players; `docker compose down` stops everything
DEMO_IMAGES=<version> ./scripts/demo.sh   # that version's published images, only the web app built here
```

- The demo builds its images quickly (`NATIVE_OPTIMIZATION=quick`; `release` for what a release runs). With
  `DEMO_IMAGES` nothing native is built: the images come from the registry (`IMAGES`, as `scripts/deploy.sh` has it)
  and the web app from Gradle here, mounted over the tracker's (`docker-compose.web.yml`). For a change to the web
  app alone: the tracker and the gateway are that version's.

- **demo-a-…:** Alice three slots (two start locked), Bob two (one locked), and Risk of Rain 2 played by Bob and Carol
  together. **demo-b-…:** Dave and Erin, two slots each, mixed locks.
- `demo/simulate.py` plays each session through the gateway: checks quickly, then evenly, finds the keys on a
  schedule; every slot reaches its goal within `DEMO_MINUTES` (15). An unbeatable seed is retried with another one.
- Alice is the demo's admin (her password in `output/demo/admin.password`); the others' are printed once
  (`randogeddon logins --reset <name>` for a new one). http://localhost:8000.
- It builds the native images (tracker, gateway, minecraft, one after another): Linux containers, on Windows in WSL.

## Local testing

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.local.yml   # publishes 38281 + 8000, no Traefik
export POSTGRES_PASSWORD=local INTERNAL_TOKEN=local                # or in .env
docker compose up -d
docker compose exec tracker randogeddon admins --create Admin     # then ws://localhost:38281/<session-id>
```

The local overlay sets `ADMIN_PASSWORD_LOGIN=true` (an Administrator by password and two-factor, without single
sign-on); a tracker run without it (`./gradlew :server:run`) needs it in its environment for one.

A test seed without a ROM or an installed game: YAMLs like `name: Alice` / `game: APQuest` / `APQuest: {}`.

## Building and testing

Gradle 9.8.2 (wrapper), Kotlin 2.4, JDK 25; a Maven mirror as `randogeddon.maven` in `~/.gradle/gradle.properties` (or
`RANDOGEDDON_MAVEN`), its login from your init script.

```sh
./gradlew :server:test :gateway:test :database:test  # unit tests (also in the Docker build)
./gradlew :database:integrationTest             # against PostgreSQL (Testcontainers, Linux Docker)
./gradlew :server:run                           # tracker on :8000 (builds the web app first)
AP_ROOT=<one Archipelago checkout per release> ./gradlew :gateway:run   # gateway on :38281
./gradlew :webApp:wasmJsBrowserDevelopmentRun   # the web app with hot reload, /api proxied to :8000
```

- Settings: `server/…/application.yaml` and `gateway/…/application.yaml`, each with the environment variable that
  overrides it.
- A new migration in `database/src/main/resources/db/migration` is also listed in its `index.txt` (a native image
  can't scan the classpath; a test checks it).
- `./scripts/audit.sh`: the dependencies (Go, JVM, the gateway's Python) looked up in OSV (osv-scanner, in Docker);
  before a release and when a dependency changes. `GRADLE_DEPENDENCIES=<file>` where Gradle and Docker are apart.
- `./scripts/measure.sh [viewers] [seconds]` (WSL, the demo running): the page's sizes, the images, memory and CPU
  idle and with viewers, answer times, the first screen; `--build` times a build. Results: `MEASUREMENTS.md`.

## Native build

GraalVM CE 25, in `kotlin.Dockerfile` with the root `build.gradle.kts`'s setup:

- `-march=x86-64-v3` (AVX2; the live server's i7-8700 has no AVX-512); `NATIVE_MARCH=native` only on that machine.
- `-Ob` for `-SNAPSHOT`, `-O3` for releases (`-PnativeOptimization=quick|release`; an image build:
  `NATIVE_OPTIMIZATION`, which the demo sets to `quick`).
- native-image's heap is capped at 6 GB (`-PnativeMaxHeap`, an image build: `NATIVE_MAX_HEAP`; empty: its default,
  three quarters of the machine's memory).
- `--static-nolibc`: only glibc linked dynamically (built on glibc 2.34, run on 2.36).
- Not cross-compiled: in Docker, or in WSL with GraalVM 25 (`./gradlew :server:nativeCompile :gateway:nativeCompile`).
- Reflection and resources the libraries don't cover: `*/src/main/resources/META-INF/native-image/`. After adding a
  library: `./gradlew -Pagent=standard :server:run` (use it, stop it), then `:server:metadataCopy`, and review.

## Versions

`0.<phase>.<step>` while in development: the phase as in `TODO.md`, the step its PR. A branch builds `-SNAPSHOT`, its
last commit before the merge drops it, the merge is tagged `v<version>` (signed). A follow-up to a merged step gets a
fourth number (`0.8.2.1`). A PR with docs only (the TODO, a phase's close) gets no version. `CHANGELOG.md` lists every
version by phase.

A release's deploy pushes its images as `<version>` and `latest` (other commits as `<commit>` and `edge`), publishes
Randogeddon Bridge (`scripts/publish-bridge.sh`: Artifact Keeper at `BRIDGE_REGISTRY`, `randogeddon-bridge/<version>/`,
with `SHA256SUMS` signed) and makes a GitHub release with its CHANGELOG part (before 1.0.0 a pre-release without
files). `--dry-run` shows what it would do.

## Texts and languages

Every text is typed Kotlin in `shared/src/commonMain/kotlin/net/eratiem/randogeddon/shared/i18n/`: an interface per
area (listed in `Texts`) and its English and German objects in the same file; a text with values is a function; feed
lines are templates with marks (`{sender} sent {item} to {receiver}`). The web app reads `LocalTexts.current`; the
server's refusals are `Problem` codes worded in `ProblemTexts`; mails come from `MailTexts`; `index.html`'s forms
have a table of their own.

- **A new text:** a member in the interface, then its English and German words (it doesn't compile before).
  `TextsTest` checks that no text drops a value and the templates' marks match.
- **A new language:** an entry in `Language`, an object per area, `texts()` returning them, its column in
  `index.html`.

## API

All under `/api`, JSON; without a login 401, for someone else's session 403. `GET /healthz` is open.

| Area | Routes |
|---|---|
| Login | `POST /api/login` (name or address, password; with two-factor `{"next": "code"\|"setup"}` for `…/code`, `…/setup`, `…/setup/confirm`), `POST /api/logout`; single sign-on `GET /api/login/options`, `/api/login/oidc`, `…/callback`, `POST …/backchannel`, `…/updated`, `…/deleted`; `POST /api/login/link` (a CLI login) |
| Sign-up | `GET /api/invitations/<token>`, `POST /api/signup`, `POST /api/email/confirm` |
| Account | `GET /api/account`; `PUT …/name`, `…/password`, `…/auto-delete`; `POST …/email`, `…/2fa/setup`, `…/2fa/enable`, `…/2fa/disable`, `…/2fa/codes`; bridge keys `/api/account/bridge-keys` |
| Sessions | `GET /api/me`, `/api/sessions`; per session `…/state`, `…/stream` (SSE), `…/player/<slot>`, `…/connection`, `…/archive`, `…/spoiler`, `…/spoiler/log?from=&count=` (≤ 400), `…/spoiler/search?q=` (one at a time per user), `…/files`, `…/files/<name>` |
| Managing | `/api/manage`, `/api/sessions/new` (`/id?id=`), `POST /api/sessions`; `/api/sessions/<id>/manage/…`, `…/people`, `…/support`, `…/watchers`, `…/share`; Administrators `/api/admin/sessions`, `…/users`, `…/settings` |
| Share link | `/api/watch/<token>/state`, `…/stream`, `…/player/<slot>` (no login) |
| Bridge | with a key (`Authorization: Bearer`): `GET /bridge/info`, `/bridge/me`, `/bridge/sessions`, `…/<id>/connection`, `DELETE /bridge/key`; builds `GET /downloads/bridge`, `…/<file>` (open) |
