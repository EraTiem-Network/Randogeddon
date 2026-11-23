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
- [ ] Left from 0.23.4 (the database's review): the gateway warns at each start for every session folder whose
      session is gone from the database (its stop is refused now); the temporary-table right of every role and a
      connection limit on the tracker's role (S3, D23: not decided); Archipelago's Python beside the gateway can
      still reach the database's port (D23); the tracker's refresh still reads `sessions` four times (D19, in
      part); not tried: the update on the live server (the database's container recreated, V29 on its data), a
      mail's time in an account's zone as it arrives
- [ ] Left from 0.23.5 (the web app's review):
    - [ ] S2: no npm lock file is committed. Looked at: the project has no npm dependencies of its own (its lock
          file is empty); webpack and the rest come with the Kotlin plugin. Whether the plugin pins them wasn't
          confirmed: with 0.23.7 (the dependencies)
    - [ ] The content policy still allows inline styles: the login page has none any more; to drop it once a
          browser shows that Compose adds none of its own
    - [ ] W32: the web app has no tests (no test source set; its new pure functions are ready for them:
          `Summary.replaces`, `retryDelay`, `homeTarget`, `Route.parse`, `pathSegment`)
    - [ ] Not done: a tip doesn't open when its button gets the focus by keyboard; the in-place confirmations
          don't take Enter or Escape; polling doesn't pause while the tab is hidden; large uploads still go as
          base64 (W11); `AdminUsers.kt` and `AdminApworlds.kt` aren't split (W29); chat lines from before the
          update stay as they were; an old login cookie's login isn't ended on the server (it runs out)
    - [ ] Not seen: the file pickers (their folders, their messages), a person's panel on a share-link page,
          someone with one session, Enter in the forms, a text selection kept in its panel, the page on a phone,
          whether Space presses a focused button and a phone keyboard's Go key sends a form
- [ ] Left from 0.23.6 (the wrappers' review):
    - [ ] To ask the user: a slot's name with `%`, `{` or `}` is refused altogether (stricter than Archipelago's
          own placeholders); the warning's reason is shown on share links too (it may name a location); a slot
          named like the locks' slot ("Locks") isn't refused (A29)
    - [ ] Three narrow races left in the gateway's handling of links (S1): a file swapped for a pipe between
          check and open, a seed's zip opened by its path after the check, a folder made through a link put
          there in that moment
    - [ ] Not done: the lock as a region of its own (A17: it would change how existing seeds rebuild); a size
          limit for a hosted server's folder (A25); what Archipelago's user reaches on the network (S13);
          pinning by hash (S14: with 0.23.7); `host.yaml` stays writable by Archipelago's user (settings only)
    - [ ] Not tried: a hosted Minecraft server on the new image (its own user, the join and leave lines'
          pattern against a real server's log: if it didn't match, a server would sleep while people play);
          `!getitem` refused by a real client; the warning on screen (no demo slot's rebuild differs); a
          broken save through the panel (tried with the script in the image: it stops with its line)
- [ ] Left from 0.23.7 (the scripts, the containers and the dependencies):
    - [ ] Not done, by the user's decision: the server pulls images by tag, not by digest (S6, C19)
    - [ ] Not done: the hosted Minecraft servers' way out to the internet (C17: they check accounts with
          Mojang); the gateway's internal API bound to one address (C18's second half: in the gateway); read-only
          roots for the gateway and the minecraft service (they write into their image); pip, setuptools and
          cython left in the gateway's image (C30); `FAIL_ON_PROJECT_REPOS` (the Kotlin plugin adds its own);
          the Playwright image of `measure.sh` not pinned; a notice when the nightly dump fails beyond the
          deploy's line (C22); the bridge's signing key without a passphrase (S15); the build's file prompt
          switched off (S16); `release-signers` is read from the tagged commit itself (a trusted copy outside
          the tree wasn't decided); a failure between the minecraft folder's move and the new settings starts
          the version before without its folder in place
    - [ ] Found on the way: at its start the gateway writes a stop event for every session folder, also for
          one whose session is gone from the database (the foreign key of 0.23.4 refuses it: an error line each)
    - [ ] Not tried: a real deploy with the new script (the upload, `sudo` over SSH, the systemd units, the real
          images' checks: the switch itself was tried against a scratch folder with stub services); a release's
          build (the release cache, the audit gate inside `deploy.sh`, the signer check against a tag that
          carries `release-signers`); `publish-bridge.sh`'s comparison of an existing file (whether the
          registry's address answers a GET with the file); `measure.sh`; the Traefik overlay's rule on a real
          Traefik; the build's negative checks (a wrong hash in a lock, a wrong wrapper checksum)
    - [ ] Standing chores from the pinning: `scripts/base-images.sh` and the audit before a phase's release;
          `gradle/verification-metadata.xml` at each dependency change; the tooling lock at a Kotlin update; the
          Python lock at an Archipelago update; `osv-scanner.toml`'s entries run out on 2027-02-23
- [ ] After 0.23.7: every "Left from" point above gone through with the user (the user on 2026-11-23)

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
- [ ] Cheats, an item's hint: an option whether it names only the slot the item is on or its location too; only
      the slot unless chosen otherwise (asked for on 2026-11-23)
- [ ] Hosted Minecraft Dig (reported on 2026-11-23): a connected Minecraft Dig server doesn't seem to keep its
      session's game server awake (players had to reconnect so that it wakes up); and a hosted Minecraft server
      should shut down after a number of minutes without players that an Administrator sets, 5 by default
- [ ] The bridge's page: a copy option for the slot's name (asked for on 2026-11-23)
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
- [ ] One user per session for Archipelago's processes (left by the gateway's review, 0.23.2.1; the user on
      2026-11-23: later): they share one user, so a world's code can read and change another session's files and
      read another game server's memory. A redesign of the directories, with the existing sessions' moved
- [ ] Maps: the games' real map images, uploaded by an Administrator, under the generated map (from Phase 22; the
      user: maybe later). Never in the repository: they are the games' own images
