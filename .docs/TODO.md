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

- [ ] 0.23.1: the tracker (logins, roles, the API, its limits, share links, bridge keys, mails, the CLI, the session
      state). Scanned: no high finding, 11 medium, 41 low (52 in all, some found by both scanners)
    - Decided by the user (2026-11-22), each a change of behaviour:
        - Whoever loses their last slot in a session loses the open-to-all slots' passwords too (renewed); timing
          out, suspending or deleting an account renews the passwords of the slots only they played
        - Forwarded headers are believed only from configured proxies (a new setting, `TRUSTED_PROXIES`; unset: as
          before)
        - No e-mail addresses in the tracker's log: accounts by ID or name
        - Support can no longer restart a stopped spoiler-log run (Session-Managers and Administrators can); the
          panel never offered it to support, only the route did
        - A Session-Manager's invitation whose mail fails leaves no half-made account: the retry works
        - The new-session routes (overview, ID check, YAML inspection) paced per user
        - Administrators only through Authentik ("never allow password logins"): an Administrator by password exists
          only with an opt-in setting, which the demo and local testing set; without Authentik and without it,
          nobody is an Administrator and `admins --create` refuses. Where it's on: granting the flag or resetting
          two-factor ends the account's other logins
- [ ] 0.23.2: the gateway and its tunnel (the relay, the slot passwords' check, the Minecraft tunnel, generating,
      the internal API, Archipelago's processes beside it)
    - Noted by the tracker's scan: the gateway's database role may insert `cheat` events, which become feed lines
      under any name
- [ ] 0.23.3: Randogeddon Bridge (the key, the relays, the tunnel, its page, the downloads' signing)
- [ ] 0.23.4: the database (the stores, the roles, the migrations)
    - Noted by the tracker's scan: `EmailChangeStore.take` selects and then deletes (not atomic; harmless so far)
- [ ] 0.23.5: the web app
- [ ] 0.23.6: Archipelago's wrappers and the World Locks APWorld
- [ ] 0.23.7: the scripts, the containers and the dependencies (the images, compose files, `audit.sh`)

## Later

*Points without a phase yet.*

- [ ] Maps: the games' real map images, uploaded by an Administrator, under the generated map (from Phase 22; the
      user: maybe later). Never in the repository: they are the games' own images
