# Randogeddon To-Do List

What's open and planned, phase by phase; items within a phase roughly in implementation order. The finished
phases (1 to 22) are in [DONE.md](DONE.md).
`Open:` marks decisions that are not made yet.

## Conventions

- **Addresses:** the app runs at `randogeddon.eratiem.net`; each session's game server at
  `<session-id>.randogeddon.eratiem.net:443` (TLS, `wss://`).
    - TLS only: current clients connect over `wss://` (Launcher-based clients retry with it, and
      Archipelago.MultiClient.Net, used by many modded games, tries it first since April 2023). Older or unmaintained
      mods may only speak `ws://`, so every game of a session is tested with its real client before session day; for
      those, the Randogeddon Bridge (Phase 12) gives them a `ws://localhost` address.
- **Backups:** of the whole VM/container through Proxmox Backup Server (database, saves, seeds, state).
- **Generated passwords** (logins, room and slot passwords, ...): at least 32 characters, with lower- and upper-case
  letters, digits and symbols, from a cryptographically secure random source.
    - Passwords players type into game clients use only symbols every client accepts and that don't break config
      files: printable ASCII, no spaces, quotes or backslashes. Some games only offer an in-game text field without
      copy and paste.

## Phase 23: Full Code Review & Security Check (in progress)

*Asked for on 2026-11-21: the whole code reviewed, and its security checked. Started on 2026-11-22. Done points move
to [DONE.md](DONE.md).*

How it runs (decided with the user, 2026-11-21 and 2026-11-22):

- Part by part in the order of risk, review and security check together, each part a version of its own with a PR
  that lists every finding and what was done (or why not)
- Besides the usual review: duplication, optimisation, and merging what belongs together, where it makes sense
- Two scanners per part, both read-only: Fable 5.2 reviews everything, Opus 5.5 checks the security independently;
  the fixes are made by Opus 5.5 subagents only
- A fix keeps what users see, the API and the database schema as they are; one that would change them is the user's
  decision

The parts:

- [ ] Left from 0.23.1 (the tracker's review):
    - [ ] `X-Forwarded-Proto` and `X-Forwarded-Host` are still taken from any peer (Ktor's handler can't be gated
          by peer); only the client address follows `TRUSTED_PROXIES`. Nothing in the tracker reads them
    - [ ] No test of the command line's `admins --grant` and `--reset-2fa` ending an account's logins (their
          functions aren't reachable from a test; the same ending is tested where two-factor login is turned on)
- [ ] Left from 0.23.2 (the gateway's review), none of it can be tried in the demo: hosted Minecraft with
      `MINECRAFT_TOKEN` and the deploy's setting of it (the demo has no hosted server); whether every real game
      client takes a `RoomUpdate` right before `Connected` (sent when a session's options changed while it slept)
- [ ] Left by the steps of 2026-11-22 (0.23.2.2 to 0.23.2.8):
    - [ ] Not seen: the browser's real full screen for the map (a headless browser has none); the spoiler page
          with only its first level, and an item hint while *next in playthrough* is hidden (the demo's seeds
          have all three levels within a minute); the views' page arrows and slot filters (the search was tried)
    - [ ] A game's part of the spoiler log that the views don't read is only in its text
    - [ ] The registry (not this repository's): its hourly warning that deleted image manifests can't be removed
          for good (scan results still point at them); `BLOB_GC_ENABLED` is set on its server only, not where
          its compose file is kept; on 2026-11-22 only 0.23.2.5 and `latest` were left in it (the user hasn't
          said whether they deleted the rest)
- [ ] Left from 0.23.3 (the bridge's review): the page's one-time code is on the browser opener's command line
      until the page takes it (S5: narrowed, not closed); two starts that both find a stale lock in the same
      instant can both run (B9); not tried by hand: the bridge against a live session with a real game, its page
      in a browser, a release's check for the signing key before it builds (first run: the next publish)
- [ ] 0.23.4: the database (the stores, the roles, the migrations)
    - Noted by the tracker's scan: `EmailChangeStore.take` selects and then deletes (not atomic; harmless so far)
- [ ] 0.23.5: the web app
- [ ] 0.23.6: Archipelago's wrappers and the World Locks APWorld
- [ ] 0.23.7: the scripts, the containers and the dependencies (the images, compose files, `audit.sh`)

## Later

*Points without a phase yet.*

- [ ] One user per session for Archipelago's processes (left by the gateway's review, 0.23.2.1; the user on
      2026-11-23: later): they share one user, so a world's code can read and change another session's files and
      read another game server's memory. A redesign of the directories, with the existing sessions' moved
- [ ] Maps: the games' real map images, uploaded by an Administrator, under the generated map (from Phase 22; the
      user: maybe later). Never in the repository: they are the games' own images
