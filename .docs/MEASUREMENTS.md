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
| median of 4 | 108 ms | 243 ms |

Locally the download costs nothing: on a real connection the 4.5 MB above decide it (about 4 s at 10 Mbit/s).

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

**web app** (`webApp/src/…/webapp/`, 29 files in one package): rearranged into packages by area —
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

**shared** (`shared/src/commonMain/…/shared/`)

| File | Lines | Verdict |
|---|---|---|
| `Api.kt` | 928 | **split** by area: the dashboard's summary, the panel (sessions, generation, game server, APWorlds, cheats), the connection and downloads, the spoiler log; `ApiJson` and the IDs stay |
| `Themes.kt` | 629 | keep (the palettes are data) |
| `Accounts.kt` | 429 | **split**: the login's steps and requests, the account and its settings, the users' administration |
| `Fonts.kt` 113, `Bridge.kt` 56, `Problem.kt` 56 | | keep |
| `i18n/SessionAdminTexts.kt` | 650 | **split**: the session tips into their own file |
| `i18n/GeneralTexts.kt` | 479 | **rename and split**: it holds the APWorld and the user administration texts (`ApworldTexts.kt`, `UserAdminTexts.kt`) |
| the other `i18n/` files (Cheat, Dashboard, Dialog, Mail, Panel, People, Problem, Spoiler texts; `English`, `German`, `Texts`, `Language`, `Formatting`) | 33–397 | keep (one area each) |

**database** (`database/src/main/…/database/`)

| File | Lines | Verdict |
|---|---|---|
| `Stores.kt` | 1,166 | **split**: the accounts' stores (users, two-factor, invitations, address changes, logins, login links) and the sessions' (time, setup, sessions, slot passwords, finishing, cheat log, roles) |
| `Tables.kt` 199, `Database.kt` 81, `GatewayRole.kt` 38, `MigrationIndex.kt` 38 | | keep |

**server** (`server/src/main/…/server/`)

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
