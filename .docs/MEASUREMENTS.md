# Measurements

`scripts/measure.sh` measures a local stack in WSL (`./scripts/demo.sh` running, both demo sessions playing). The
numbers below are the latest (0.18.4, 2026-11-18, 32 threads); Phase 18's steps, what they changed and its code audit
are in Git's history (`.docs/MEASUREMENTS.md` before Phase 22) and the CHANGELOG.

## Build

| What | Time |
|---|---|
| from nothing (BuildKit's cache cleared) | 446–647 s |
| after a change to the tracker's code / the web app's | 149 s / 193 s |
| after a comment only | 63 s |
| nothing changed | 3 s |

The image build keeps Gradle's task results, the native images and Binaryen's optimisation in its cache mount.

## The page's files

Compressed once in the image's build (`.br` and `.gz` beside each); the tracker sends them as they are (about 10 ms of
its time per page load).

| File | Plain | gzip | Brotli |
|---|---|---|---|
| `webApp.js` | 715 KB | 103 KB | 80 KB |
| Compose's Skia wasm | 7,241 KB | 2,967 KB | 2,348 KB |
| the app's wasm | 4,236 KB | 1,328 KB | 986 KB |
| **all** (with `index.html`, `page.js`, `theme.js`) | **12,221 KB** | **4,408 KB** | **3,422 KB** |

Browsers take Brotli over HTTPS. The fonts load only when chosen. The first screen (headless Chromium, local, until
the session's state shows): 1.2–1.7 s, mostly the wasm compiling; on a real connection the 3.4 MB decide it.

## Images

| Image | Size | Inside |
|---|---|---|
| tracker | 347 MB | the native tracker ~100 MB, the bridge's builds 46 MB, the web app 26 MB |
| gateway | 725 MB | the native gateway ~90 MB, Archipelago and its Python packages, `libtk8.6` |

The `minecraft` image (0.21.6) isn't measured yet: Java 17, Forge and the mod.

## Containers and the API

| Container | Idle (2 sessions playing) | With 60 viewers (15 logged in, 45 by share link) |
|---|---|---|
| tracker | 0.5 %, 103–173 MiB | 0.8 %, 130 MiB |
| gateway | 0.5 %, 459 MiB | the same |
| PostgreSQL | 0.0 %, 90 MiB | ~3 %, 91 MiB |

- Each session is looked at by one poller (every 750 ms while anyone follows it); its streams wait for that.
- Answers (median of 20): `GET /api/me`, `…/state`, a share link's state, a stream's first event 1.5–3.7 ms; the
  panel's overview 2.9 ms.
- One login holds at most 20 streams (`OpenStreams.MAX`); a share link allows 60 requests a minute per address.

**Looked into and left as they are:** the gateway's memory is mostly its running MultiServers (~330 MB each, the native
gateway ~90 MB); the tracker's heap grows before the native image's collector collects (up to a share of its 2 GB),
nothing kept; the bridge's builds in the tracker image (downloads; packing them would save 20 MB).
