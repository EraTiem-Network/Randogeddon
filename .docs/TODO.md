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

- [ ] Left from 0.23.9, not tried: the page with two-factor on (unit-tested), the reset link opened while logged
      in, German on the new pages (the mails through the live mail server went out: the user, 2026-11-24)
- [ ] Left from 0.23.8: not seen on screen (built and unit-tested; the demo has no slot whose logic doesn't
      fit and runs on plain http): Enter and Escape in a confirmation, a tip on keyboard focus, polling paused
      in a hidden tab, the warning without its reason for a player, the old cookie's login ended; the deploy
      putting back a folder it had already moved (only a move that fails outright could be forced)
- [ ] To try on the live server and with real clients (nothing to build; from the parts' "not tried" lists):
    - [x] The switch to 0.23.8 on 2026-11-23 (from 0.23.4): the rewritten deploy over SSH went through (the
          upload, `sudo`, the systemd units, the real images' checks); the database's container made anew on its
          data (18.7, 48 MB of its 1 GB); the minecraft folder moved; the roles' limits; no error line in any log
    - [x] Hosted Minecraft on live (2026-11-23, the user joined and left the session's Minecraft Dig): started by
          the join through the tunnel, its server as its own user (uid 1200), the join and the leave recognised
          (the player listed, then gone), checks sent while playing
    - [ ] With real game clients: `!getitem` refused; the bridge against a live session and its page in a
          browser; a client taking a `RoomUpdate` right before `Connected` (after a session's options changed
          while it slept); a broken save through the panel; a mail's time in the account's zone;
          the bridge of 0.23.10 offering the session to someone of its support who plays a slot
    - [ ] In a real browser: the map's full screen; the file pickers (their folders, their messages); a
          person's panel on a share-link page; someone with one session; Enter in the forms; a text selection
          kept in its panel; Space on a focused button; the page on a phone and its keyboard's Go key; the
          spoiler page with only its first level and an item hint while *next in playthrough* is hidden; the
          views' page arrows and slot filters; the logic's warning on screen
    - [ ] Scripts: `publish-bridge.sh`'s comparison of an existing file (a second `--publish` of one version);
          `measure.sh`; the Traefik overlay on a real Traefik; the build's refusals (a wrong hash in a lock, a
          wrong wrapper checksum)
- [ ] The registry (not this repository's): its hourly warning that deleted image manifests can't be removed for
      good; `BLOB_GC_ENABLED` is set on its server only, not where its compose file is kept
- [ ] Standing chores since 0.23.7: `scripts/base-images.sh` and the audit before a phase's release;
      `gradle/verification-metadata.xml` at each dependency change; the tooling lock at a Kotlin update; the
      Python lock at an Archipelago update; `osv-scanner.toml`'s entries run out on 2027-02-23

## Phase 24: what was noted for later (planned)

*The user on 2026-11-23: the new locks' APWorld "done in Phase 24 as all the currently Later will be as well".*

- [ ] **Lockageddon**, the locks' new APWorld (the name chosen by the user; World Locks stays as it is for the
      sessions made with it, a new session uses Lockageddon):
    - the keys take no other game's fillers: the Lockageddon slot has locations of its own that hold ordinary
      items of the pool, handed out by the game server when a key is found (its console's `/send_location`, sent
      by the gateway: no client that has to run; to try first against a slot nobody is connected to). What is
      handed out that way is no cheat: a line of its own kind in the feed
    - a key is `Key to {slot}`; a known game may have its own word (`Boat to {slot}` for The Wind Waker), which
      then needs its plural too: one key for worlds that open together is `Keys to {A} and {B}` (or, with the
      games' own words, `{X}, {Y} and {Z} for {slot names}`: each game's word, then the slots)
    - a YAML that could roll its game is refused, and a second slot of it (as for World Locks since 0.23.6)
    - the form's rework, the SyncLocks and several keys a world: the next point

- [ ] How the available checks are worked out, looked at again: they should be more precise than they are now
      (asked for on 2026-11-23)
- [ ] Sent and received as two values each, all and progression, as the checks have them, with matching symbols;
      in the Overview's panel of the items transported too (asked for on 2026-11-23; can be one pull request)
- [ ] The spoiler page's *Everything*: whether a check is checked (asked for on 2026-11-23; the placements carry a
      mark since 0.23.2.8: to look at with the user what is missing), and for a World Key which slot it opens
- [ ] Hosted Minecraft Dig (reported on 2026-11-23): a connected Minecraft Dig server doesn't seem to keep its
      session's game server awake (players had to reconnect so that it wakes up); and a hosted Minecraft server
      should shut down after a number of minutes without players that an Administrator sets, 5 by default
- [ ] The bridge's page: a copy option for the slot's name (asked for on 2026-11-23)
- [ ] The dashboard's players (asked for on 2026-11-23):
    - [ ] Sorting by: the player's name, the slot's name, progression of the player, progression of the slot,
          last activity
    - [ ] A button with which someone pins themselves to the top
- [ ] Avatars (asked for on 2026-11-23): for slots and games, and for users
- [ ] The locks in the new session's form, reworked for Lockageddon (asked for on 2026-11-23): the locks' drop-down goes, only the
      mixed mode stays:
    - a checkbox that turns locking on
    - the mixed mode as it is (each world set locked, unlocked or random)
    - a bar for the random ones: how many of them start unlocked at least. Its least: 1, or 0 if at least one world
      is set unlocked; its most: the number of random worlds minus 1 (all of them open would make random pointless)
    - an option to have that number itself picked by chance within the bar's range
    - settings for the locks' progression balancing and accessibility (shown when locks are used)
    - (added on 2026-11-23, after a look at SlotLock, which is not used:) the keys without removing other games'
      fillers: the Locks slot with locations of its own that hold ordinary pool items, handed out by the game
      server itself (its console's `/send_location`, which the gateway can send: no client that has to run); until
      then the form says how many fillers the keys take, and a generation without enough of them is refused with
      that reason
    - worlds that open together, a SyncLock: a drop-down *SyncLock 1*, *SyncLock 2*, … per slot, which grows by
      itself. Once *SyncLock 1* is set on two slots, *SyncLock 2* is offered, to the other slots only (not to the
      two that make up *SyncLock 1*). With a third slot in it, all of its slots are offered every other SyncLock
      there is (one of them switching still leaves two). A SyncLock with a single slot can't be generated: taking
      one of a SyncLock's last two slots out of it blocks *Generate* until it has two again or none. A SyncLock
      that loses all its slots is gone, and every higher one moves down a number (*SyncLock 2* becomes
      *SyncLock 1*): no gaps in the numbers (the user, 2026-11-23)
    - several keys a world: a fixed number or a range picked by chance, for all worlds or per slot
- [ ] What Archipelago's processes reach on the network, with the per-session users below (the user on
      2026-11-23): code of an uploaded APWorld can still open connections to the internet, the database's port
      and the tracker (0.23.4's D23, 0.23.6's S13, 0.23.7's C17 and C18); the gateway's internal API bound to
      one address
- [ ] Left by the review for later: a game's part of the spoiler log that the views don't read is only in its
      text; the content policy still allows inline styles (to drop once a browser shows Compose adds none);
      the tracker's refresh reads `sessions` four times (D19); a notice when the nightly dump fails beyond the
      deploy's line; read-only roots for the gateway and the minecraft service; `FAIL_ON_PROJECT_REPOS`; a test
      of the command line's `admins --grant` and `--reset-2fa` ending an account's logins
- [ ] One user per session for Archipelago's processes (left by the gateway's review, 0.23.2.1; the user on
      2026-11-23: later): they share one user, so a world's code can read and change another session's files and
      read another game server's memory. A redesign of the directories, with the existing sessions' moved
- [ ] Maps: the games' real map images, uploaded by an Administrator, under the generated map (from Phase 22; the
      user: maybe later). Never in the repository: they are the games' own images
