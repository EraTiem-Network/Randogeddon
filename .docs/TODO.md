# Randogeddon To-Do List

What's open and planned, phase by phase; items within a phase roughly in implementation order. The finished
phases (1 to 21) are in [DONE.md](DONE.md).
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

## Phase 22: Maps (planned)

*Asked for on 2026-11-21: maps of the slots' games with markers for their checks. Which games, where the maps
and the locations' places come from, and what a marker shows are to come from the user.*

- [ ] Maps with check markers for the slots
- [x] The README notes every tool and piece of software Randogeddon uses (asked for on 2026-11-21): *Built with*
- [x] `.docs/` down to the essentials, shorter, and restructured where that helps (asked for on 2026-11-21)
    - The finished phases moved unchanged into `DONE.md`; the rest rewritten as compact references (from about
      2,500 lines to 530); `MEASUREMENTS.md` only the latest numbers
- [x] *Your connection*: copy, show and hide as icons; the bridge's fold arrow left to collapse, right to expand
      (0.22.1)
- [x] Creating a session: its ID's field takes only what a subdomain allows, capitals as small letters (0.22.1)

## Phase 23: Full Code Review & Security Check (planned)

*Asked for on 2026-11-21: the whole code reviewed, and its security checked. How deep and in which order is to
come with it.*

- [ ] A review of the whole code: tracker, gateway, web app, bridge, database, Archipelago's wrappers, scripts
    - Besides the usual review: duplication, optimisation, and merging what belongs together, where it makes sense
    - How (the user's choice): Fable 5.2 scans and hands each finding on by itself to a subagent on Opus 5.5,
      which fixes it
- [ ] A security check: logins and roles, the API and its limits, the gateway and the tunnel, the bridge, secrets,
      the containers, the dependencies
