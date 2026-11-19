# Measurements and the code audit (Phase 18)

`scripts/measure.sh` measures a local stack (`./scripts/demo.sh` running, both demo sessions live) in WSL; each
Phase 18 step compares its numbers with the baseline below. The audit lists what the code repeats, what nothing uses,
what the compiler warns about, and a verdict for every code file.

## Baseline: 0.17.2 (2026-11-18)

Measured locally (WSL, 32 threads) with the two demo sessions running and their simulated players playing.

### Build

| What | Time |
|---|---|
| from nothing (BuildKit's cache cleared, Gradle's libraries from the mirror) | 435 s |
| again, nothing changed | 3 s |

### The page's files (as the tracker sends them)

| File | Plain | gzip (per request) | Brotli |
|---|---|---|---|
| `index.html` | 11 KB | 3 KB | not offered |
| `page.js` | 17 KB | 5 KB | not offered |
| `theme.js` | 1 KB | 1 KB | not offered |
| `webApp.js` | 715 KB | 106 KB | not offered |
| Compose's Skia (`…79727d.wasm`) | 7,241 KB | 2,993 KB | not offered |
| the app (`…f1f023ef8b.wasm`) | 4,236 KB | 1,349 KB | not offered |
| **all** | **12,221 KB** | **4,457 KB** | |

The tracker compresses each file for every request (Ktor's `Compression`, gzip only); the hashed files are cached a
year. The fonts (2.3 MB in all) load only when chosen.

### First screen (headless Chromium, a share link's dashboard, empty cache, local server)

| | Files loaded | First screen drawn |
|---|---|---|
| median of 4 | 108 ms | ~~243 ms~~ 1,220 ms (corrected, see 0.18.2) |

Locally the download costs nothing: on a real connection the 4.5 MB above decide it (about 4 s at 10 Mbit/s).
The 243 ms first written here were wrong: the loading splash goes once Compose has started, sometimes before it has
drawn anything, and that was what was timed. Measured again (0.18.2) until the page's title shows: 1,216–1,235 ms
with the files compressed per request.

### Images

| Image | Size | Inside |
|---|---|---|
| tracker | 327 MB | the native tracker 99 MB, the bridge's builds 46 MB, the web app 16 MB, the base image the rest |
| gateway | 1,177 MB | the native gateway 88 MB, Archipelago 116 MB, its Python packages 202 MB, the base image (Python) the rest |

### Containers

| Container | Idle (2 sessions playing): CPU, memory | With 20 viewers (15 logged in, 5 by the share link) |
|---|---|---|
| tracker | 0.5 %, 103–173 MiB (grows over the run) | 1.5–2.6 %, 115–173 MiB |
| gateway | 0.5 %, 459 MiB | 0.8 %, 460 MiB |
| PostgreSQL | 0.0 %, 90 MiB | 3.2 %, 91 MiB |

On live, idle without a session: tracker 102 MiB, gateway 363 MiB, PostgreSQL 61 MiB.

### API (median of 20, local)

| Request | Median | Slowest | Size |
|---|---|---|---|
| `GET /api/me` | 1.7 ms | 2.3 ms | <1 KB |
| `GET /api/admin/sessions` (the panel's overview, every 15 s) | 8.1 ms | 10.2 ms | 1 KB |
| `GET /api/sessions/<id>/state` | 1.5 ms | 1.9 ms | 5–49 KB |
| `GET /api/watch/<token>/state` | 1.5 ms | 4.7 ms | 5 KB |
| a stream to its first event | 1.6–2.8 ms | | |

20 viewers for 60 s got 60 updates, none was left without. One login holds at most 20 streams (`OpenStreams.MAX`, on
purpose): more viewers of one account get nothing (measured: 30 streams of one login, 12 without an update).

## 0.18.2: the web files compressed once

The image's build compresses the web app's files once (Brotli 11 and gzip 9, `.br` and `.gz` beside each), and the
tracker sends those as they are; a local build without them is compressed per request as before.

| File | Plain | gzip before → after | Brotli (new) | The tracker's time for it, before → after |
|---|---|---|---|---|
| `index.html` | 11 KB | 3 → 3 KB | 3 KB | 1.1 → 1.9 ms |
| `page.js` | 17 KB | 5 → 5 KB | 5 KB | 1.2 → 1.6 ms |
| `webApp.js` | 715 KB | 106 → 103 KB | 80 KB | 8.0 → 1.1 ms |
| Compose's Skia wasm | 7,241 KB | 2,993 → 2,967 KB | 2,348 KB | 137.4 → 3.9 ms |
| the app's wasm | 4,236 KB | 1,349 → 1,328 KB | 986 KB | 59.6 → 2.3 ms |
| **all** | **12,221 KB** | **4,457 → 4,408 KB** | **3,422 KB** | **207 → 10 ms** |

- **Download:** browsers take Brotli over HTTPS (all of them on live): **3.4 MB instead of 4.5 MB, 23 % less**.
  Locally (plain HTTP) Chromium asks for gzip only.
- **The tracker's work per page load:** about **10 ms instead of 207 ms** (it compressed 12 MB for every visitor); a
  fast connection gets the files that much sooner.
- **Dashboard drawn** (local, the page's title showing): 1,195 ms median with the compressed files, 1,226 ms
  compressing per request (two rounds each, alternating); 1,207 ms in the full run. The rest is the wasm's compiling
  and start.
- **The tracker image:** 347 MB (was 327); its web app 26 MB (was 16) with the `.br` and `.gz` files.

## 0.18.3: one look per session, not per viewer

- **The streams:** each session's version is looked at by one poller (`LiveSession.changes`, every 750 ms while
  anyone follows it), and every stream of it waits for that instead of polling by itself; a stream only looks every
  5 s whether its keepalive is due and, every 30 s, whether its login still holds. The session's options are decoded
  once per setup (they were decoded on every look), the share link's summary is serialized once per change.
- **The panel's overview** uses the sessions as they were read last (every 10 s, and right after every change made in
  the panel) instead of reading them all again, and asking the gateway twice, for every request; the gateway's
  releases are kept 30 s.

Measured on the same running demo, 0.18.2 and then the changed tracker, 60 viewers (15 logged in, 45 by the share
link) for 60 s:

| | 0.18.2 | 0.18.3 |
|---|---|---|
| the tracker's CPU with 60 viewers (most) | 5.7 % | **0.8 %** |
| the tracker's memory with them | 136 MiB | 130 MiB |
| `GET /api/admin/sessions` (median) | 10.7 ms | **2.9 ms** |
| the other answers, a stream's first event | 1.7–3.7 ms | the same |

A stream still gets its first summary at once, a keepalive after 15 s, and ends at its next check once its login
ended (tried: logged out, closed 30 s after it opened). (The share link allows 60 requests a minute per address: of
the 45 viewers by link, 5 got 429, before as after; `measure.sh` waits that minute out before the first screen.)

Looked into and left as they are:
- **The gateway's 363 MiB "without a session"** were a running session: its MultiServer (Python) held 330 MB, the
  native gateway 89 MB.
- **The tracker's memory** (145 MB on live, 103–173 MiB locally): the native image's collector lets the heap grow
  before it collects (up to a share of the container's 2 GB); no sign of something kept.
- **The panel's game server stream** asks the gateway per open panel; only Session-Managers and Administrators have
  one.

## 0.18.4: a slimmer gateway image, a build that redoes less

- **The gateway image:** Archipelago is installed in a stage of its own (`archipelago-build`, with git and the
  compilers its speedups and the worlds' packages need); the image takes only the installed packages and Archipelago's
  directory, and of the system packages only `libtk8.6`. Nothing at runtime used git, gcc or pip.
- **The image build keeps Gradle's task results** (`--build-cache`, in the cache mount): the build directories start
  empty in every image build, so a change to one app compiled, tested and natively built the other again. The native
  images and Binaryen's optimisation of the web app are cached too (their plugins don't mark them so; the GraalVM
  version counts as an input of the native ones).

| | 0.18.3 | 0.18.4 |
|---|---|---|
| gateway image | 1,177 MB | **725 MB** (−38 %) |
| tracker image | 347 MB | 347 MB |
| an image build after a change to the tracker's code | 416 s | **149 s** |
| after a change to the web app's code | 416 s | **193 s** |
| after a change of a comment only (the same classes) | 416 s | 63 s |
| from nothing | 398–435 s | 446–647 s (see below) |

With the demo on the new images: both seeds generated, the players play, the gateway's log is clean.

- **From nothing** varies a lot here (446 s, then 647 s); twice it failed in Gradle's step, both times right after
  BuildKit's cache was cleared (every library downloaded from the mirror again), and the next try built. The error
  itself wasn't kept; a deploy's log keeps it if it comes again.
- **Left as it is:** the tracker image's 46 MB of the bridge's builds (they are downloads; packing them would change
  how they are offered, for 20 MB).
- **Found on the way, fixed:** while a new session waited for its `room.json`, its tracker connection ended with an
  exception instead of noting it and trying again (`TrackerService.start` called Kotlin's `error()`, which throws,
  instead of its own: the parameter `refused` has no default). The 10 s reading started it again, so it only showed as
  stack traces in the log (8 for two new sessions).
- **`measure.sh`'s first screen** waits for the page's own signs now (the splash gone, the session's state come, two
  frames after); screenshots, its way so far, hold up the page's start in headless Chromium, so with the new images
  it never saw the dashboard. 1,478–1,678 ms here this time.

## What the audit found

### Server and page
- **Every open stream polls its session every 750 ms** (`follow` in `PastRoutes.kt`): it computes the session's
  version each time (the people map, hashes of the options, the time, the finish), so the work grows with viewers,
  not with changes. A change could be told once to everyone (a shared flow per session).
- **PostgreSQL works for idle viewers:** 3.2 % CPU with 20 open streams and no change (each logged-in stream asks
  every 30 s whether its login and part still hold: `stillAllowed`, `LOGIN_CHECK`).
- **The share link's summary is serialized again for every viewer and change** (`LiveSession.publicJson`); the
  logged-in one is cached (`json()`).
- **The panel's overview is the slowest answer** (8 ms, 5× the rest) and is asked every 15 s by every open panel.
- **The web files are gzipped per request**, and no Brotli is offered.
- **The tracker's memory grows** from 103 to 173 MiB over a run of half an hour (to be watched: the native heap's
  growth or something kept).
- **The gateway holds 363 MiB without a session** (459 MiB with two).

### Repeated code (a scan over the 183 code files: runs of 5 or more equal lines, imports not counted)
38 repeated runs, 260 lines (under 1 %): little is copied exactly. What repeats in kind matters more:
- **Request, check, decode** in the web app: 53 places decode an answer themselves
  (`Platform.request(…)?.takeIf { it.status == 200 }?.let { runCatching { ApiJson.decodeFromString(…) } }`); a
  helper for it exists (`call` / `Answer` in `AdminSession.kt`) but only the panel uses it.
- **The page's frame** (background, gradient, scrolling column, width) and **the 30 s clock** (`now`): the same in
  `Dashboard.kt` (`TrackerApp`) and `Watch.kt` (`WatchApp`).
- **Small components twice:** `NumberField` (`Components.kt`, `AdminSession.kt`), `Mono` (`AdminSession.kt`,
  `Connection.kt`), `Labeled` / `Labelled` / `Label` (`AdminSession.kt`, `Settings.kt`, `Connection.kt`),
  `SmallHeading` / `SubHeading` (`Connection.kt`, `AdminApworlds.kt`).
- **Dialogs:** `PersonDialog.kt` and `PlayerDialog.kt` share their frame (13 equal lines and more alike).
- **Pills and panels of people:** `AdminUsers.kt` and `People.kt` (the pill and its panel, 7 and 6 equal lines).
- **Build files:** `server/` and `gateway/build.gradle.kts` repeat their toolchain and source set lines.
- Equal on purpose: the English and German texts (the same structure in two languages), the test files' setup.

### Unused code and warnings
- Unused: `SingleSignOn.createLink` (the CLI makes its links another way). Nothing else: the rest is reached
  (Koin's annotations find `MailSender` and `ServerModule`).
- 63 compiler warnings: 50 for the browser interop (`ExperimentalWasmJsInterop`, one opt-in for the module), 2 a
  deprecated `BasicTextField` (`Components.kt`), unneeded `?.`/`!!` (`SingleSignOn.kt`, `Admin.kt`), a condition that
  is always true (`AdminSession.kt:875`), expect/actual classes (a compiler flag). Gradle warns about features it
  drops in 10.
- The bridge: `go vet` and its tests run in every Docker build (clean).

### Every code file: keep, split, move or merge

**web app** (`webApp/src/…/webapp/`, 26 files in one package): the packages and splits done in 0.18.8 (45 files);
the merges of what repeats (the request helper, the doubled components, the page frame) follow in 0.18.9. Rearranged into packages by area —
`app` (start, routing, platform, theme, texts, fonts), `ui` (the shared components), `dashboard`, `manage` (the
Management panel), `account`, `api` (the repositories and the request helper).

| File | Lines | Verdict |
|---|---|---|
| `AdminSession.kt` | 1,318 | **split**: the running session's section, the new session's form (worlds, players, YAMLs, locks), the generating view, past sessions, patch files; `call`/`Answer` to `api`; its `NumberField`, `Mono`, `Labeled`, `Button` merged into `ui` |
| `Dashboard.kt` | 1,310 | **split**: the app's shell (`App`, `TrackerApp`, the page frame), header (`Headline`, `Hero`, `Banner`, `Bubble`), tiles and stats, people, hints, feed |
| `Components.kt` | 948 | **split** by kind: text and surfaces, pills and chips, tips and popups, inputs (select, search, number, filter, slider, choice) |
| `Admin.kt` | 488 | **split**: `AdminRepository`/`AdminCache` to `api`, the view and the server controls stay |
| `AdminApworlds.kt` | 488 | keep (one section); its pills move with the shared pills if they become one |
| `AdminUsers.kt` | 483 | keep; the pill and panel shared with `People.kt` |
| `People.kt` | 459 | keep; see `AdminUsers.kt` |
| `Settings.kt` | 527 | **split**: own account, bridge keys, two-factor login, appearance |
| `SpoilerView.kt` | 519 | keep (one view: playthrough, steps, full log) |
| `Connection.kt` | 326 | keep; its `Mono`, `Label`, `Field` merged into `ui` |
| `AdminCheats.kt` | 259 | keep |
| `TrackerRepository.kt` | 244 | keep (to `api`), with the request helper |
| `Theme.kt` | 234 | keep |
| `Platform.wasmJs.kt` | 214 | keep (the browser bridge); the interop opt-in for the module |
| `PersonDialog.kt` | 203 | keep; the dialog frame shared with `PlayerDialog.kt` |
| `Watch.kt` | 191 | **split**: `WatchRepository` to `api`; the page frame and clock shared with the dashboard |
| `PastSessions.kt` | 163 | keep |
| `PlayerDialog.kt` | 162 | keep; see `PersonDialog.kt` |
| `Fonts.kt` | 112 | keep |
| `Platform.kt` | 106 | keep |
| `Texts.kt` | 90 | keep |
| `Scrollbar.kt` | 87 | keep |
| `Router.kt` | 73 | keep |
| `Main.kt` | 29 | keep |
| `Format.kt` 22, `LoginWatch.kt` 21 | | **merge**: `Format.kt` into the dashboard's formatting, `LoginWatch.kt` into `api` (it belongs with the login's requests) |
| `page.js` 347, `theme.js` 24, `sw.js` 31 | | keep |

**shared** (`shared/src/commonMain/…/shared/`): done in 0.18.5, as below.

| File | Lines | Verdict |
|---|---|---|
| `Api.kt` | 928 | **split** by area: the dashboard's summary, the panel (sessions, generation, game server, APWorlds, cheats), the connection and downloads, the spoiler log; `ApiJson` and the IDs stay |
| `Themes.kt` | 629 | keep (the palettes are data) |
| `Accounts.kt` | 429 | **split**: the login's steps and requests, the account and its settings, the users' administration |
| `Fonts.kt` 113, `Bridge.kt` 56, `Problem.kt` 56 | | keep |
| `i18n/SessionAdminTexts.kt` | 650 | **split**: the session tips into their own file |
| `i18n/GeneralTexts.kt` | 479 | **rename and split**: it holds the APWorld and the user administration texts (`ApworldTexts.kt`, `UserAdminTexts.kt`) |
| the other `i18n/` files (Cheat, Dashboard, Dialog, Mail, Panel, People, Problem, Spoiler texts; `English`, `German`, `Texts`, `Language`, `Formatting`) | 33–397 | keep (one area each) |

**database** (`database/src/main/…/database/`): done in 0.18.6 (`AccountStores.kt` 664, `SessionStores.kt` 501,
`AppSettingsStore.kt` 24 lines); `StoresTest.kt` stays one file, as most of its tests cover accounts and sessions together.

| File | Lines | Verdict |
|---|---|---|
| `Stores.kt` | 1,166 | **split**: the accounts' stores (users, two-factor, invitations, address changes, logins, login links) and the sessions' (time, setup, sessions, slot passwords, finishing, cheat log, roles) |
| `Tables.kt` 199, `Database.kt` 81, `GatewayRole.kt` 38, `MigrationIndex.kt` 38 | | keep |

**server** (`server/src/main/…/server/`): done in 0.18.7, as below; the lenient JSON accessors went to `util/Json.kt` (the
login, the tracker, the archives and the room file use them too), not to the state.

| File | Lines | Verdict |
|---|---|---|
| `state/SessionState.kt` | 789 | **split**: the model (`ClientStatus`, `SlotType`, `NetworkItem`, `Player`, `Group`, `Hint`, `SavedState`) and the JSON helpers (`JsonObject.obj` …) out of the state |
| `config/LoginRoutes.kt` | 408 | **split**: the guards (`RequireLogin`, `RequireBridgeKey`, `RequireSessionRole`, `LoggedIn`) into their own file |
| `account/AccountService.kt` 442, `admin/SessionAdmin.kt` 430 | | keep (one service each) |
| `ap/TrackerService.kt` 310, `session/LiveSessions.kt` 308 | | keep |
| `config/AdminRoutes.kt` 277, `PastRoutes.kt` 226 | | keep; `follow`/`OpenStreams` from `PastRoutes.kt` to their own file (the streams of all routes use them) |
| `account/SingleSignOn.kt` | 265 | keep; `createLink` removed |
| the other 50 files (`Accounts`, `Oidc`, `Totp`, `TwoFactor`, `Mail`, `Cheats`, `SessionLifecycle`, `GameServerClient`, the other routes, `Installs`, `Hardening`, `TrackerConfig`, `Archives`, `Spoilers`, …) | 10–251 | keep (one subject each); `admin/Support.kt` 33 and `SessionLimit.kt` 38 small but on their own subject |

**gateway** (`gateway/src/main/…/gateway/`): all 12 files keep (one subject each, 58–395 lines; `Apworlds.kt` 395
is one subject).

**bridge** (`bridge/`): keep; `app.go` 567 (the page's handlers) could give `buildName`/`newer` (updates) a file of
their own. `ui/app.js` 281 keep.

**scripts, demo, Archipelago's helpers, the Locks world:** keep.

**Tests:** they follow the files they test; `StoresTest.kt` 540 splits with `Stores.kt`.
