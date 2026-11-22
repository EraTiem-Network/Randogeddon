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
    - [ ] Tests that need a database in the tracker's module (it has no harness for one): the mail limit's probes,
          the failed invitation, the provider's sync, the logins ended by a grant, a creation's reserved ID
    - [ ] F37: four hand-made expiring maps as one (optional; left)
- [ ] 0.23.2: the gateway and its tunnel (the relay, the slot passwords' check, the Minecraft tunnel, generating,
      the internal API, Archipelago's processes beside it). Scanned on 2026-11-22: 5 high (3 of them found by both
      scanners), 16 medium, 26 low (47 in all; G: the review's, S: the security scan's)
    - **Waiting for the user's decision** (each changes the deployment, the schema or what someone sees):
        - [ ] S1/G8 (high): Archipelago's Python runs as the gateway's own user, so an APWorld can read the gateway's
              secrets (`/proc/1/environ`: the database login, the internal token) and every session's files;
              taking them out of its environment protects nothing. Ways: Python as a user of its own in the image
              (the gateway starts it with `setpriv`/`su-exec`, the session directories group-readable), or the
              gateway's secrets read from files only root and the gateway can read
        - [ ] S2/G10 (high): the minecraft service holds the internal token and mounts all of `data/` writable as the
              gateway's user: whoever gets code to run in it (Forge, the mod) can take the gateway over. Ways: a
              token of its own that only its two routes accept; only `data/minecraft/` mounted
        - [ ] G9/S6: every MultiServer listens on all interfaces without a password: anything on the container's
              networks (with the Traefik overlay, Traefik's shared one) joins any slot past the gateway. Ways:
              bound to localhost, or a room password only the gateway knows
        - [ ] G11/S4/S5: one or two addresses can hold all 1,024 waiting places, and anyone can wake every sleeping
              game server (and its logic build) and so keep every place taken. Ways: a cap per address on waiting
              connections; a wake only after a `Connect` with a slot that exists (or with its right password)
        - [ ] G18/S8: the gateway's database role may insert any event (`cheat` too) for any session: forged feed
              lines under any name, if Archipelago's Python got its login. Restricting it is a schema change (a
              trigger or a check on `kind`)
        - [ ] G2 (the rate part): a cap on how many chat lines and link events of one slot are kept per minute
        - [ ] G24 (part): `init: true` for the gateway's and the minecraft service's containers (orphaned
              grandchildren reaped)
        - [ ] S9: refusals tell an anonymous client which sessions and which slot names exist (`InvalidSlot` or
              `InvalidPassword`, as Archipelago's own server says them; the tunnel's messages)
        - [ ] S11: one `!admin` password (`AP_ADMIN_PASSWORD`) for every session, guessed at without a limit by any
              player
    - [ ] Not checked end to end yet: 0.23.2 passed the gateway's, the tracker's and the shared tests, but the demo
          on it (real clients' openings through the new rules before a `Connect`) was stopped by the system for low
          memory while it built (2026-11-22). To be run before it is merged
    - [ ] Left by its fixes: a patch for Minecraft Dig is still read whole when its slots are looked up (only its
          `data.json` has a limit); the question whether a server is finished asks the database every 3 s per
          running Minecraft server; raising the game servers' `oom_score_adj` so the kernel ends one of them before
          the gateway (it changes which process is killed: the user's decision)
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
