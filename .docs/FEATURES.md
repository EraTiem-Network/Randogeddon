# Randogeddon: what it does

Everything Randogeddon does, in detail. The README has the overview.

## Sessions and their game servers

A new session's seed isn't made by a game server: the gateway runs Archipelago's `Generate.py` itself, one generation
at a time, and puts the seed into `data/sessions/<id>/`. The tracker runs the creation in the background, so the page
can be closed meanwhile; only once the seed is there and the session is set up does its game server start.

```mermaid
sequenceDiagram
  actor Creator as Creator (panel)
  participant Tracker
  participant Gateway
  participant Server as Game server (MultiServer)
  Creator->>Tracker: Generate and start (the form)
  Tracker->>Tracker: checks the form, the limits, the ID
  Tracker-->>Creator: 202: it runs (or why not, at once)
  Tracker->>Gateway: generate (the YAMLs, the locks, the release)
  Note over Gateway: Generate.py, one at a time (others wait their turn), its log starts with the command that ran
  loop until it ends
    Creator->>Tracker: how far? the log since …
    Tracker->>Gateway: progress, log
  end
  opt Cancel (while it generates)
    Creator->>Tracker: cancel
    Tracker->>Gateway: end Generate.py
  end
  Gateway-->>Tracker: the seed in the session's directory, data/sessions/ID (or the failure, with the log to download)
  Tracker->>Tracker: the session, its slot passwords, its locks, its Session-Manager
  Tracker->>Gateway: start its game server
  Gateway->>Server: start (a free port)
  Creator->>Tracker: how far?
  Tracker-->>Creator: done: the logins
```

Each session has its game server: one MultiServer, run only while it is needed. One nobody plays on sleeps, and the
first player who connects starts it again (if fewer run than may at once; they wait a moment meanwhile).

```mermaid
stateDiagram-v2
  [*] --> Starting: created
  Asleep --> Starting: a player connects (if there is room)
  Starting --> Running: MultiServer listens
  Running --> Asleep: nobody played for "Sleep after" (unless kept running)
  Running --> Crashed: MultiServer ended on its own
  Crashed --> Starting: again after a pause (1 s, doubling up to 30 s)
  Crashed --> GaveUp: 5 times in a row, each within a minute of its start
  Running --> Stopped: Stop (in the panel)
  Asleep --> Stopped: Stop (in the panel)
  Stopped --> Starting: Start (in the panel)
  GaveUp --> Starting: Start (in the panel)
  Running --> Stopped: the session finished (until it is reopened)
  GaveUp: Gave up
```

- **At the gateway's start** (a deploy, a reboot) none starts: every session sleeps until its first player, a
  finished one stays stopped.
- **Game servers at once** (*Users*, default 8) counts *Starting*, *Running* and *Crashed*: those hold a MultiServer
  (a few hundred MB each). Asleep, stopped, given up and finished sessions don't; with all of them asleep the gateway
  itself needs some 35 MB.
- A player can't wake one that was stopped by hand, gave up or is finished; the panel can start it.
- *Keep running without players* (an Administrator's switch in a session's *State and Control*) keeps it from
  sleeping; *Sleep after* `0` keeps them all running.

## Session day

### In the Management panel

1. **Collect the YAMLs**, one per slot, each with a unique `name:` (e.g. `Alice-HK`, `Alice-Celeste`). Players export
   them from the game's options page on archipelago.gg or via the Launcher's *Generate Template Options*. A game that
   isn't bundled with Archipelago needs its `.apworld` (see *APWorlds* below).
2. **Create the session** (the gear button, the *Management panel*, then *New session*; every user can, as many running ones at
   once as the Administrators allow, and is then its Session-Manager): its *Session ID* (prefilled with a free suggestion like
   `brave-otter`; lowercase letters, digits and `-`, starting with a letter (capitals typed become small ones), and
   without unwanted words, as for names; it becomes the game server's host name, so it can't be changed later), *Add YAMLs…*, then for every slot who
   plays it (one or more players, picked from the accounts, invited ones too: a slot with several players is played
   together); the locks
   (*Choose per slot*: tick the slots that start locked; *Random*: a number of slots picked at random start open,
   all others start locked; *Mixed*: each slot's switch says open, locked or left to chance, and the slider how many
   start open in all; at least one slot starts open, and a single slot can't be locked); the hint and release options;
   the generation's settings: what the YAMLs may plan by hand (Archipelago's plando modules *bosses*, *connections*,
   *texts*, *items*; its own default, all but the items, is ticked), *No progression balancing*, and *Race mode* (a
   seed nobody can reproduce and no spoiler log, so nothing of it in the panel and no background runs);
   an Administrator can use a **seed generated elsewhere** instead of YAMLs (*Use a generated seed…*: the
   `AP_<seed>.zip` of Archipelago's Launcher, up to 64 MB). The gateway checks it before anything of it is used: only
   files named as a seed's (`AP_…`, no folders, each once), one `.archipelago`, at most 1,000 files and 512 MB
   unpacked; it writes the zip anew from what it read, and reads the multidata with Archipelago's own restricted
   loader (as MultiServer does: no code of the file runs), which also says whether the release can serve it. Its
   slots are then assigned as with YAMLs. Such a session has no locks (they are generated in; a seed with a World
   Locks slot is refused), nothing is generated, its spoiler log is the one the zip brings (none: no spoiler page),
   and its files for the players are the zip's, unchecked: they come from whoever generated it;
   *Generate and start* (once every slot has a player or is hosted; while the session is made, the form makes room
   for its ID, its four steps (*Check*, *Generate*, *Set up*, *Start*) and a bar with the stage and about how far it
   is; the gateway generates as many seeds at once as it has cores, less one (`GENERATIONS_AT_ONCE`), so a session
   beyond that waits and says so). It generates in the
   background: the page can be closed, and shows it again (also after a reload) until it is done; *Cancel* ends it
   while it generates, and its ID is free again. Under the bar, Generate.py's output, newest on top, a page at a
   time and searchable; if it fails, the whole output can be downloaded, with the command that ran first. Every option has an ⓘ that says what it does; buttons with a tip show it on hover, or on a
   long press on a phone; a tip stays while the pointer is on it, and its text can be selected and copied (a long
   message, such as an APWorld's error, has a *Copy* button). The gateway generates the seed with the chosen
   Archipelago release and starts the game server on it. A generation that takes longer than `GENERATION_MINUTES`
   (default 60) is stopped, and the panel says so; Archipelago fills on one core, so a big multiworld (thousands of
   progression items) takes long. A file that isn't a player YAML (not `.yaml`/`.yml`, no
   name or game, not readable) isn't taken, and the panel says why. A YAML whose game the release has no APWorld for gets no
   slot yet: one message names them (and tells someone who isn't an Administrator to ask one to upload it), and they
   become slots by themselves once the APWorld is there (uploaded by anyone, anywhere). If Generate.py fails, the panel names the games that may be the cause (one the error mentions,
   or one whose APWorld comes from another release) above its output.
3. **Players are added** per slot, from a dropdown with a search (a slot starts without players). **New players are
   invited** by e-mail right there (type their address: only one that has no account), or beforehand by an
   Administrator (*Users*): they get a link, valid for 7 days, and choose their name and password themselves. Until
   they sign up, the session shows them by the suggested name (without one: `Invited-` and the start of their
   account's ID; never anything of their address, which only Administrators see). When a session ends, its invitations nobody accepted
   go (unless another running session has them).
   **Hosted slots:** for a game that runs as a server of its own (its players join that server with their games, not
   Archipelago), tick the slot's *Hosted* (asked once more). The slot then has no players; the dashboard shows it on a
   card of its own, *Hosted*. It starts *Open to all*: under *Your connection*, every player of the session can pick it
   and see its patch files. Untick *Open to all* and name players to give it only to them (they join its server, they
   don't play it). Its Session-Managers see its whole connection (server, slot, password), to set the game's server
   up. No account can be named *Hosted*.
   **Open to all:** a slot that isn't hosted can be ticked *Open to all* too: every player of the session (everyone
   named for any of its slots) plays it, with its password, its files and the bridge, and the dashboard shows it on a
   card of its own, *Everyone*. A session needs someone who plays for that. Randogeddon Bridge offers a hosted slot to its Session-Managers and
   Administrators (for a hosted game that can't do TLS), not to its players. A hosted *Minecraft Dig* slot can have
   its server run by Randogeddon itself instead: an Administrator ticks *its server here (through the bridge)* (or
   switches it on later in the panel's *Minecraft* section; Session-Managers can switch it off). Then the bridge
   offers it to the session's players too, as a tunnel to that server (below).
4. **Players log in** (with their name or their e-mail address) at `https://randogeddon.eratiem.net`. The dashboard opens their running session; with several
   (or none running), they pick one from their sessions, and switch between them on top of the dashboard. Each view
   has its own link: `#/<session-id>` a session's dashboard, `#/<session-id>/spoiler` its spoiler log, `#/manage` and
   `#/manage/<session-id>` the panel (`#/admin` still works), `#/watch/<token>` a share link. Under *Your connection*
   they find the server
   (`<session-id>.randogeddon.eratiem.net:443`; clients try `wss://` first, so TLS through Traefik just works; for a game that can't, see *Randogeddon Bridge*), their
   slot names and each slot's password, and for a ROM-based game (or one that needs a mod) the slot's file from the
   seed to download: a patch is opened with the Archipelago Launcher (*Open Patch*) and their own ROM. Session-Managers
   and Administrators download every slot's files in the panel (*Patch files*). Phones can install the dashboard as an
   app (see below).

Patch files for ROM-based games (and mods some games need) are in the seed, `data/sessions/<id>/AP_<seed>.zip` on the
server; players download their own from the dashboard (step 4), as long as the session exists. **Deleting** a
running session (the panel) stops its game server and moves its files to `data/old/<id>-<time>/`; deleting a
finished one deletes them for good. The accounts stay. As many game servers run at once as the Administrators allow
(*Game servers at once*, under *Users*; empty: no limit); a finished session doesn't run one. A game server that ends
with an error is started again after a pause; after 5 times in a row, each within a minute of its start, it gives up
(*gave up* in the panel, with its log) and stays stopped until it is started again.

A game server without players for *Sleep after (minutes)* (under *Users*; default 30, 0: never) saves and stops:
*asleep* in the panel, and the dashboard says so. Players are game clients in a slot through the gateway; the
tracker and hosted slots don't count. The first player who connects starts it again, if fewer run than may at once
(else they are told every game server is in use), and waits meanwhile. One stopped by hand, one that gave up and a
finished session's don't start this way. An Administrator can keep a session's game server running without players
(*Keep running without players*, in its *State and Control*).

### Finishing, past sessions and the spoiler log

- **Ending and pausing:** 2 minutes after every slot has reached its goal or was released, the session ends by
  itself. Meanwhile the dashboard shows a countdown; its Session-Managers can *End now* or *Cancel* (it then doesn't
  start again until a slot is no longer done). A Session-Manager can also end it: *Pause session* while not every
  check is done
  (e.g. when someone drops out), *End session* once it is at 100 %. Either way the game server stops (the gateway
  refuses to start that seed, also after a restart), and the dashboard keeps showing how far it got: the session is
  read-only from then on.
- **Reopening:** within 14 days of the finish (*Reopen*), unless the session was completed (100 %), and while a game
  server is free. Its history goes on from where it was.
- **Finished sessions** keep their files (`data/sessions/<id>/`) and what the dashboard showed when they finished
  (their archive, in the database's `session_snapshots`, so in the daily dump; until 0.19.10 a file in the tracker's
  volume, imported once). Their players see them under *Other sessions* on the
  dashboard, Administrators see all; a finished session is kept until it is deleted in the panel, or
  automatically `SESSION_RETENTION_DAYS` after it finished (90 by default, `0`: never; the date shows in the lists).
  *Disable auto-deletion* (asked once more) keeps it until it is deleted by hand; enabled again, the days count from
  then. Someone who plays only in finished sessions can still log in and see those.
- **Spoiler log, in stages:** a seed is generated with a spoiler log without its playthrough (Archipelago's level 1),
  so the session starts at once. The gateway then generates it once more in the background, with the same seed number
  and inputs (its YAMLs and APWorlds are kept for that in the session's `generation/`), only its spoiler log
  (`--spoiler_only`): level 2 with the playthrough, then level 3 with the paths to every item. A later level replaces
  the earlier one only when it places every item the same as the seed (else it stays, and the panel says why). The
  spoiler log, the hints and the cheats wait for level 2; meanwhile the session's top panel shows *spoiler log being
  completed* (for everyone), and the panel's *Spoiler log* (Session-Managers, Administrators, support) how far it is,
  the run going now and its log. These runs share the generations' places (`GENERATIONS_AT_ONCE`) with the new
  sessions' seeds and leave one of them free (where there is more than one). None starts while a new session waits
  for a place; if none is free, a level-3 run is paused for the new session (the panel says so) and goes on after,
  where it was. A level-2 run is never paused: the new session waits for it. A change of the APWorlds waits for the
  seeds and level-2 runs that are going; level-3 runs end for it and start over after. Until its spoiler log has its
  playthrough, a session shows no *Spoiler log* button and no *Cheats* (or support) section. Sessions from before
  0.19.3 have their complete spoiler log already.
  When Archipelago can't reduce a playthrough to the items the goals need (a world's logic contradicts itself; it
  would end without any spoiler log), the playthrough lists every progression item in the order it can be collected
  instead, and the spoiler page says so (`archipelago/spoiler_run.py` wraps that one step; the seed is the same). A
  run that stopped (it failed, or took too long) is tried again with *Try again* in the panel, after a fix.
- **Logic, the available checks:** how many of a slot's locations its game's logic reaches now with the items it has
  (as a Universal Tracker shows them). The rules exist only while Archipelago generates (the seed's multidata has the
  placements, not the rules), so the gateway generates the seed once more with its number and inputs, as for the
  spoiler log, but only up to the fill (`archipelago/logic_run.py`: the worlds, their rules, events and locked items;
  a few seconds, for a big seed a minute), and keeps that process to answer. It starts with the session's game server
  and ends as long after that one went to sleep as the game servers wait before they sleep (at least 10 min). The
  tracker asks it whenever a slot's items or checks change; each count goes into the slot's state, and with it into
  the kept dashboard (the database), so the last known counts show while it doesn't run. A slot whose locations
  differ from the seed's (an APWorld that doesn't build the same with the same seed) gets no count. Not for seeds from
  elsewhere, races and sessions from before 0.19.3 (no inputs kept), nor with an APWorld changed since the seed. The
  top panel shows its state beside the game server's (*Logic*: a working symbol while it is built, a dot when it
  runs, grey when it doesn't, red when its build failed; what that means as the tooltip); the panel's *Logic* section
  (Session-Managers, Administrators) shows its state and *Build again*, and to Administrators its log (pages, search).
- **Spoiler log:** the seed's spoiler log, with the playthrough sphere by sphere and the whole log
  (the search marks and counts the findings; the arrows, or Enter and Shift+Enter, jump between them). The full
  log shows 300 lines at a time, with arrows to the lines before and after: a big seed's log is too long for a
  browser to show at once. Its search runs on the server (a moment after the last key; per user one search at a
  time, half a second apart) and jumps to whichever page a finding is on. Both searches have an × to empty them. The
  playthrough reads *Item @ Check*, marks the steps already checked with a check mark, and each sphere says how many of its
  checks are done; its search keeps only the steps whose item, check or slot contains the text (and unfolds them).
  Administrators, its Session-Managers and support can open it at any time (behind a warning: it is a full spoiler);
  its players and watchers see it once it is finished.

### Names nobody should have

User names and session IDs are checked against a list of swear words and slurs (about 9,600, of the 17 languages
written in Latin letters: `unwanted-words.txt`, made by `scripts/unwanted_words.py` from three lists:
[LDNOOBW](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words) (CC BY 4.0),
[BadWords](https://github.com/FlacSy/badwords) (MIT) and
[Google Profanity Words](https://github.com/coffee-and-fun/google-profanity-words) (MIT)). Ordinary words in them are
taken out by checking against the 50,000 commonest words of each language
([FrequencyWords](https://github.com/hermitdave/FrequencyWords)) and 4,900 first names, and by hand; words are added
there too. The script downloads what it needs into `output/unwanted-words/` and runs in seconds. A name is read part by part (`Dark_Lord`, `darkLord`), written
together, and with digits as the letters they stand in for (`sh1t`); a word of 7 letters or more, and a few
marked ones, are also found inside a longer word. Short ones aren't (they are inside too many ordinary words and
names), so the filter keeps the obvious out and can be got around by someone who tries. Who is refused isn't told
which word it was. Names that exist already stay as they are; a name that comes from Authentik is made to fit
(`Ada Lovelace`: `Ada_Lovelace`). To change the list: `OURS` and `ALLOWED` in the script, then run it; to take
in what the lists have learned since, delete `output/unwanted-words/` first.

### APWorlds

The panel's *Archipelago* (Administrators) shows the gateway's releases under *Supported versions* (as pills; with
several, a click shows that release's games) and, under *Supported games*, every game the release can generate and
serve: the bundled ones (grey) and the custom ones, each APWorld as its chip (see below), together by name, with
*bundled* / *custom games* to show only those, and a search. Beside the heading:

- **upload apworld** adds `.apworld` files to that release's library (kept in `data/apworlds/<release>/`), several
  at once, together (the release lists its games once for all of them). The gateway checks that each is an APWorld (a zip with one world folder), names it
  after that folder (Archipelago imports it by its file name: `Pokemon Emerald (1).apworld` with `pokemon_emerald/`
  inside becomes `pokemon_emerald.apworld`), loads the release's worlds again and says which game it brought. Each file
  shows as a pill that fills yellow while it is checked and turns green (added), red (refused) or grey (skipped); its
  tip says what it brought or why not;
  *Clear* takes them away, and after an upload without trouble they go by themselves. One that
  doesn't load (e.g. it needs a newer Archipelago) is refused: it isn't kept, a file it would have replaced stays, and
  the panel says why. A game the release brings itself is refused too (its own world always wins). An upload that
  would replace something asks first (*Replace* / *Skip*): a file of the same name, or another uploaded APWorld of the
  same game (by its manifest, `archipelago.json`), which goes then. *remove* takes files out again: pick them (the
  uploaded ones; *Select all*), *Remove (n)*, confirm. An APWorld may import `tkinter` (for its client window): the
  image has the Tk library it needs.
- **The custom games:** each file as a chip with its game and its version (from its `archipelago.json`, e.g. *Anno 1800
  – 1.5.1*; an APWorld without one shows no version), coloured by where it comes from (see its ⓘ); its tip says its
  file name and size, and the oldest Archipelago release it needs, if it says so.
- **From other releases:** an APWorld uploaded for another release can be tried with this one (*Try with …*); it is
  copied and marked "untested here" (or refused, like an upload, if it doesn't load). If generating fails, such APWorlds are among the suspects.
- APWorlds put into the server's `custom_worlds/` by hand count for every release (they can't be removed in the
  panel).

The gateway puts exactly these files into each release's `custom_worlds/` before it lists the games or generates.

### The panel: the game server and its console

Everyone gets a gear button in the dashboard's header: the *Management panel*. It lists the sessions the user manages
(an Administrator's: every session; how far each is, its game server); a click picks one. For that session it shows
its actions (pause or end, reopen, auto-deletion, delete, spoiler log), its **People** (everyone of the session as a
pill with their part; a click shows their slots and how far they are, gives them another part, e.g. a player becomes
Session-Manager, or takes theirs; add a Session-Manager, support or a watcher by name, or invite a new address; the
share link), its **Slots** (who plays each slot, changed while the session runs: players added or taken off,
*Open to all* switched, someone new invited by e-mail right there; a hosted slot stays hosted; whoever loses a slot
loses its connection: it gets a new password, the gateway reads it at once and closes the slot's connections, and
its players connect again with the new one; asked once more; not for a hosted slot whose server runs elsewhere,
whose players never had its password), its **Patch files** (every slot's files from the seed), its **Minecraft** servers (a Minecraft Dig slot's:
asleep, starting, running with who is on it, or ended on its own; *Start* and *Stop* by hand), the cheats, its game
server's state (running, starting, stopped, crashed), lets them start, stop and restart it (stop and restart ask once
more: every player is disconnected), has the server console and, at the bottom, its **Logs** as tabs: the game
server's (live, the last 10,000 lines), the spoiler log's run, and for Administrators the logic's and each Minecraft
server's; each newest on top, a page at a time and searchable. Nobody gets a tab of a log they may not read. The
console:

```
/players              # who's connected
/send <slot> <item>   # give an item ("Mantis Claw" in quotes if it has spaces)
/hint <slot> <item>   # free hint
/release <slot>       # release a slot's items (e.g. someone has to drop out)
/collect <slot>
```

Archipelago's commands say `<player>` for a slot.

Up and down in the console bring back earlier commands. Every action is logged with the name of who did it in the tracker's
log.

**Cheats** (the panel, *Cheats*): give a slot an item, check a location, release or collect for it, pick
the slot and the item or location from searchable lists. *Check a location* goes either way: *from the slot* (one of
its own open locations: its item goes to whoever owns it) or *to the slot* (an open location of another world that
holds one of the slot's items, listed with the item: checked there, the slot gets it; from the spoiler log's
placements, so only for a seed whose spoiler log has them). Free hints (`/hint_location`) only if the session
was created with *Cheat hints*. A hint names one place: a location only if it isn't hinted yet, and for an item one
copy of it that is neither checked nor hinted, at random or the next one the playthrough needs (a switch beside it) (Archipelago's `/hint` would name every copy; it is only used
for a seed whose spoiler log has no placements). The hints' table explains its states under the ⓘ in its last
column. While a slot is locked (World Locks), two more: *Check its key's location* (the location that holds its key,
from the spoiler log's placements and shown beside it, is checked as if its player found it: the key opens the slot,
the check counts for the world it is in) and *Unlock* (its key is given to the World Locks slot with `/send`; no
location is checked). Support has these two as well. Every cheat is stored with who did it (and as what) and shown in the live feed ("Cheat: Alice
gave Mantis_Claw to Alice-HK"). Items a player got with the client's `!getitem` (if the server allows it) are
marked as cheats there too. A crashed MultiServer is started again by the gateway after a pause (1 s, doubling up to 30 s); one stopped by
hand stays stopped until started again.

Below the finished sessions, Administrators find *General*: *Users* (every account as a pill, marked if invited or an
Administrator; a click shows its information panel: name, address, ID, state, group, how it logs in, two-factor,
language, when it was made, the invitation, the deletion, *n/a* where there is none, its sessions as pills that open
them, for an invited one *Send again* and *Other address*, and what an Administrator does to it: see below),
*Archipelago* and *Versions* (what runs now: the tracker's, the gateway's and this page's version, the
gateway's Archipelago releases; to check a deploy). With no session running, the panel says so; *New session* opens the
form.

**Support** helps in the Management panel: the sessions someone supports are in its switch, each with how it
stands, its dashboard, the *Support* section (give an item, check a location, a free hint; the spoiler log) and
*Watchers* (adding and removing them, the share link), not what only a Session-Manager does. What support does is logged and in the feed as "Erin (support) gave …".

**On the dashboard, by role:** Players get *Watchers*: add someone by name or a new
address, remove watchers, and the share link. Watchers see a *Watching* pill in place of their slots. A **share
link** (`#/watch/<token>`) opens the session's dashboard without a login, read-only: progress, tiles, players, hints
and the live feed, no passwords and no spoiler; turned off or replaced, it says so.

Players can also use `!hint <item>`, `!remaining`, `!release`, `!collect` and `!countdown 10` from their
client, depending on the session's options. `AP_ADMIN_PASSWORD` enables `!admin` for remote server commands.

### Progress and restarts

Each game server saves to `data/sessions/<id>/AP_<seed>.apsave` continuously, and `restart: unless-stopped` brings
the gateway back after crashes or reboots; every session that isn't finished sleeps then, and starts with its first
player. Stopping
with `docker compose down` and starting with `up -d` keeps the progress.

## Logins and passwords

**Sessions per user:** how many running sessions of their own a user may have at once (0 to 20; 0: only
Administrators create sessions). *Sessions per user (default)* in the users' card is for every account without a
number of its own; an account's panel shows its running sessions against its limit and takes its *Self-created sessions limit* (the
empty field shows the default; *Use default* brings it back). It counts the sessions someone **created**, while they run: being made Session-Manager of
someone else's session doesn't count. It is asked when a session is created and when a finished one is reopened (for
its creator; an Administrator reopens regardless). Administrators have no limit, and the number of game servers
(*Game servers at once*) applies to everyone.

**Keeping an account out, deleting it** (in its panel under *Users*; never an Administrator's account, which is
managed where Administrators log in, so never one's own):

- **Time out** for 1, 3, 6 or 12 hours, 1, 2 or 3 days, 1 or 2 weeks or 30 days, or **Suspend** until it is taken back, with a reason if wanted
  (at most 200 characters; the person reads it). Their logins end at once (Randogeddon Bridge's too) and they can't
  log in: after the right password the login form says so, until when and why, and a mail tells them. A time-out ends
  by itself; *Let in again* ends either before (a mail again). The list marks such accounts.
- Their **sessions go on**, and their games stay connected (a slot's password isn't the account's). If they are a
  Session-Manager of a running session, its dashboard tells its people to turn to an Administrator for what a
  Session-Manager does.
- **Delete account** asks once more, then deletes it as it would delete itself: login, address and settings go, the
  sessions keep the name. A mail tells them (with the reason, if one is given). For an invited one it takes the
  invitation back, without a mail.

Nobody sees anything of the session without logging in, and nobody joins the game without their own password.

- **Accounts** belong to people, not to a session. An Administrator invites someone by e-mail (*Users*), or anyone right
  in a new session's player field); the link (single use, 7 days, a new one replaces it) leads to the sign-up page,
  where they choose their name (3–32 characters: letters without accents, digits and `_`; not one that passes for
  Randogeddon or someone with a say here, like *Admin* or *Support*; without unwanted words, see below; unique
  without regard to case), a password (at least 12 characters, stored only as an Argon2id hash) and when their
  account deletes itself (2 or 4 weeks, 3 or 6 months, 1 year after the end of their last session, or never; a mail
  warns 14 days before). They log in with their name or their e-mail address. `randogeddon logins --create <player>`
  makes a login without an invitation (local use, the demo), `randogeddon logins --reset <player>` a new password
  (ending that player's logins). A login lasts 30 days (HttpOnly, Secure, SameSite=Strict cookie). Failed logins and
  sign-ups are slowed down and limited to 5 per minute per address (an IPv6 /64 as one), and after 10 wrong passwords
  or two-factor codes within 10 minutes that account's login waits until the oldest is 10 minutes old. An account
  sends at most 10 mails in 10 minutes and 40 a day (invitations, address confirmations). A player sees the sessions they play in, and no
  other; Administrators see every session. Each session keeps the names its players had in it: a rename shows in the running
  ones, a finished one keeps the old name, also of a deleted account.
- **Two-factor login** (TOTP, any authenticator app): after the password, a code, or one of 10 recovery codes (each
  works once). **Administrators need it** unless they log in through Authentik (above): an Administrator without it
  sets it up right after the password (QR code or the key, then a first code); everyone else can turn it on in
  *Your account*. The secrets are stored encrypted with `TOTP_KEY`
  (`openssl rand -base64 32` in `.env`) or, without it, with a key file the tracker makes in its state volume; keep
  either with the backups. `randogeddon admins --reset-2fa <person>` turns it off for someone who lost both the
  device and the recovery codes (an Administrator sets it up again at the next login).
- **Roles:** an **Administrator** (Authentik's group; without single sign-on `randogeddon admins --grant`) reaches
  everything: every session, the APWorlds, the
  users and the settings. **Every user can create a session** (one at a time, of at most 100 YAML files and 250
  slots) and is its **Session-Manager**: they manage it (its
  state, finishing and reopening, deleting it, its game server, console and log, the cheats, its spoiler log while it
  runs) and give its people a part: Session-Managers (co-managers), support and watchers; a player can get one besides
  their slots. A session keeps its last Session-Manager, for Administrators too: someone else becomes one first. How many running sessions one
  user may manage at once is the Administrators' setting (default 1); the game servers' number still applies. Every
  cheat is logged and shown in the feed with who did it, and as what ("Bob (Session-Manager) gave …").
- **Support** helps the players of a session: gives items, checks locations and gives free hints (also in a session
  without cheat hints), and sees its spoiler log while it runs; no release or collect, game server or console. **Watchers**
  follow a session (dashboard, feed, hints; no slots, the spoiler log once it is finished). Its players and support add watchers too
  (by name, or invite an address that has no account), and take them away again. A **share link** (players, support
  and Session-Managers) shows the session to anyone who has it, without a login and read-only (for a stream): no
  passwords, no spoiler, no accounts. It is shown once; a new link replaces the old one, and it can be turned off.
- **Your account** (the person button on the top card): change the name, the password (the other logins end), the
  e-mail address (a link to the new address confirms it; until then the old one stays), two-factor login and the
  deletion period. A
  name's tip shows its account's ID, which tells a deleted account's name in an old session from a new account with
  the same name.
- **Slot passwords:** every slot of a seed gets its own generated password when the tracker first sees the
  seed; they stay the same across restarts, a new seed gets new ones. The gateway compares the password of every
  client's `Connect` with the slot's, the same field clients already send the room password in, so no client needs
  changes. Some clients send it only when the room asks for one, so the gateway says so in the `RoomInfo` it passes
  on (`password: true`; MultiServer itself has none here). A wrong one gets `InvalidPassword`, and a name that is no slot `InvalidSlot` from the gateway itself: only
  the slots with a password get to MultiServer at all (it would take a client in under an item link's group name
  without any password). There is no room password (`AP_PASSWORD` is gone).
- **Before a client is in a slot** it gets little of the gateway: frames of at most 1 MiB (unpacked too; a client's
  packets are small), 5 minutes and 32 frames until its `Connect` passes, and at most 1024 connections of everyone
  wait for theirs at once. Past a limit the connection is closed. How many connections an address holds isn't
  limited (a VPN's, a carrier's or a shared gateway's address is many households), nor are clients in a slot; Traefik
  only limits how fast one address opens new ones (100 at once, then 2 a second; 429 past it, clients try again).
- Slot passwords use only characters every client accepts and that don't break config files (printable ASCII without
  spaces, quotes or backslashes). Some games only have an in-game text field without paste, so the dashboard shows
  them in a monospaced font.
- `docker compose exec tracker randogeddon slots <session-id>` prints a session's slot passwords for the host, e.g.
  for the Locks slot (`!hint World Key 2`) or slots nobody plays; `randogeddon sessions` lists the sessions.

The data lives in the `postgres-data` volume (and in the backup of the whole VM, if it has one).

## Hints

A hint tells a player where one of their items is ("Alice-HK's *Mantis Claw* is at *Sky Meadow: Chest 4* in
Bob-RoR2"). There are two systems, each switched per session when it is created:

- **Point hints:** checking a location earns *Points per check* (default 1); `!hint <item>` or
  `!hint_location <location>` costs *Hint cost* % of the slot's locations (default 10 %, `0` = free).
  Players can mark hints *priority*, *avoid* or *no priority* in their client; the dashboard shows that.
- **Automatic hints:** some games create hints by themselves, e.g. a shop that hints what it sells.

MultiServer has no switches for this, so the server runs through `archipelago/serve.py`, a thin wrapper: with
point hints off, `!hint`/`!hint_location` answer "Hints are turned off"; with automatic hints off, games can still
scout locations but no hints are created. The host can always give hints from the server console (`/hint`).
With both off, the dashboard hides hints entirely (unless the host handed some out). With point hints on, every
slot shows its points and how many hints they buy.

## Locked slots

Some slots can start locked and open only when their key is found in another slot. This is part of the generated
seed, not just of the dashboard: the `World Locks` APWorld (`apworlds/world_locks/`, installed in the gateway image)
adds one key item per locked slot and tells the generator that nothing in that slot is reachable without its key. So
every seed stays completable, chains included (a key inside a slot that is itself locked).

In the panel, tick *starts locked* per slot, choose *Random* locks with the number of slots that start open
(at least one; the others are locked), or *Mixed*: per slot open, locked or left to chance, and how many start open in
all. The gateway adds the World Locks slot (`Locks`, nobody plays it) to the seed.

A locked slot can't connect until its key is found: the gateway refuses its game client (`SlotLocked`), hosted slots
too, and *Your connection* and the bridge show it as locked, without a password (*Your connection*
without its patch files too). The tracker opens it once the key
has turned up (the gateway sees that within about 10 seconds). Keys show up as "World Key 1, 2, …" in game clients (`!hint World Key 2` works from the
Locks slot), and as "Key to Alice-Celeste" on the dashboard, which also shows who found it or where it was hinted.

## Randogeddon Bridge

Game clients reach their session only over TLS (`wss://<session-id>.randogeddon.eratiem.net:443`); there is no plain
port. For a game whose client can't do TLS (older or unmaintained mods that only speak `ws://`), Randogeddon Bridge
runs on the player's PC: the game connects to `localhost:<port>`, the bridge takes it to the session over `wss://`.

- **Download:** under *Your connection*, for Windows and Linux (x86_64, ARM64) and macOS (Intel, Apple Silicon); one
  file, nothing to install. It isn't signed, so the system asks first: Windows *More info* → *Run anyway*; macOS
  right-click → *Open* (or `xattr -d com.apple.quarantine <file>`); Linux `chmod +x <file>`.
- **Login:** with a key, made under *Your connection* (*Create key*, shown once) and pasted into the bridge's page. The
  bridge never sees a password, so Administrators who log in through Authentik use it too. A key is a login for 30
  days; *Your account* lists them (made, last used, until when) with *End*, and a new password ends them all.
- **Use:** the bridge opens its page in the default browser, in the account's theme, mode, font and language. Pick the
  session and the slot, *Open*: a relay on a free local port, `localhost:<port>` to copy into the game. Several at
  once (each with its state: waiting for the game, connecting, connected, the server's error). The game's name and
  password don't matter: the bridge fills in the slot's. It offers the user's own slots, and to Session-Managers and
  Administrators the session's hosted slots too; never a locked one. Starting it again opens the running one's page;
  *Quit* ends it (on Windows it has no console window), and so does closing the page or the browser (unless it is
  back within 10 seconds, e.g. a reload). A newer bridge on the dashboard shows a notice with its download.
- **Minecraft Dig:** a hosted Minecraft Dig slot whose server Randogeddon runs (an Administrator's switch, see
  hosted slots) is reached only through the bridge: a tunnel instead of a relay, `localhost:<port>` for Minecraft
  1.19.4's *Direct Connection*, offered to the session's players and its Session-Managers. Each Minecraft connection
  goes over `wss://<session-id>.randogeddon.eratiem.net/minecraft/<slot>` with the slot's tunnel key in a header
  (`X-Randogeddon-Tunnel-Key`: made from the slot's password, so the players get it without the password, which
  would let them connect to Archipelago as the slot; a wrong key, a locked slot or one not hosted here is refused),
  and the gateway passes it on to the server; no Minecraft port is open to the internet. A Minecraft Dig slot that
  isn't hosted here (an account plays it, or it is hosted without the switch) runs its own server, and the bridge
  relays its mod like any game. A look at the server list while it sleeps is answered by the
  gateway ("asleep, join to wake it") and starts nothing; a join starts it and is held ("Logging in…", about 15 s
  to a minute) until it is ready, then every byte passes: Mojang's account check and the encryption are the
  server's own. Once a player is on it, it connects to the session with the slot's password, and it sleeps when
  nobody has been on it for as long as the game servers wait. None runs until the operator accepts Mojang's EULA
  (https://aka.ms/MinecraftEULA) with `MINECRAFT_EULA=true` in `.env`, once for all of them; at most
  `MINECRAFT_SERVERS` at once (1 by default, each up to 3 GB).
- **Command line** (a computer without a browser, e.g. the one a hosted game runs on):
  `randogeddon-bridge login` asks for the key (typed or pasted at a terminal it isn't shown; or from a file:
  `randogeddon-bridge login < keyfile`; never an argument or an `echo`, which the process list and the shell's
  history would keep), `slots` lists what it can open (session, slot, game, hosted),
  `relay -session <id> -slot <name> [-port <port>]` runs one relay until Ctrl+C (a fixed port keeps the game's
  setting; its state as it changes; it ends once the key is ended), `logout` ends the key. `-server <url>` goes
  before the command; the command line logs only to the terminal.
- **On a shared computer:** a relay takes whatever connects to its port on that computer and fills in the slot's
  password for it (a game has nowhere to put a token), so the bridge belongs on a computer that is yours alone.
- **Checking a download:** the systems don't know the builds (no bought certificate), so they warn as above. What
  can be checked for free: *Checksums*, *Signature* and *Key* beside the downloads give `SHA256SUMS`, its signature
  and the key it was made with (`bridge/signing-key.pub` in this repository, `SHA256:Y/hZRRTeTgoizXJ9CVzWCpIcr3RISfCy+STg3hp+v6w`).
  In the folder with the download, with OpenSSH (Windows, macOS and Linux have it):

  ```sh
  ssh-keygen -Y verify -f signing-key.pub -I bridge@randogeddon -n randogeddon-bridge -s SHA256SUMS.sig < SHA256SUMS
  sha256sum --ignore-missing -c SHA256SUMS     # macOS: shasum -a 256 --ignore-missing -c SHA256SUMS
  ```

  The first says the checksums are Randogeddon's (*Good "randogeddon-bridge" signature*), the second that the file
  is the one they name. The key itself should come from somewhere you trust, e.g. this repository, not only from
  the same page as the download. The builds are signed where they are built (`scripts/deploy.sh`, with
  `~/.config/randogeddon/bridge-signing-key` on that machine: keep a copy of it somewhere safe; without it the next
  builds are unsigned, and a new key means a new `signing-key.pub`).
- **Kept:** the dashboard's address and the key in the user's config directory, `Randogeddon/bridge.json` (readable
  only by them; removed at *Log out*), and `bridge.log` of the last run. `-server <url>` points it at another dashboard
  (the local demo: `-server http://localhost:8000`), `-no-browser` only writes the page's link to the log.

How it stays safe:

- A key reaches only the bridge's routes (`/bridge/…`: who, the sessions, a session's connection, the bridge offered,
  its own logout), sent as `Authorization: Bearer`; never `/api`, not even as the cookie. It is stored as its SHA-256
  like every login; the bridge checks it every 15 seconds (and fetches the account's theme, mode, font and language again,
  right away when its page is back in view) and logs out once it is ended.
- The relays listen on `127.0.0.1` only and refuse connections from a web page (an `Origin` header), so no site in the
  browser can play a slot through them. A Minecraft tunnel takes raw TCP, but the gateway reads a Minecraft handshake
  first and ends anything else (an HTTP request a page could send is none).
- The page answers only on its own address (`127.0.0.1:<port>`/`localhost:<port>`, against DNS rebinding), with the
  token of the link the bridge opened (a SameSite=Strict cookie); changes need its own header too. The dashboard is
  reached only over HTTPS (plain HTTP only on the same computer).

The Docker build tests it and builds the six binaries (`kotlin.Dockerfile`, the `bridge` stage: plain Go, no CGO);
the tracker serves them at `/downloads/bridge/<file>` (`BRIDGE_DIR`, `/app/bridge` in the image), with the version
the dashboard offers. `go test -race ./...` in `bridge/` runs its tests.

## The dashboard

For every running session, the tracker connects to its room like any other Archipelago tracker client (tag
`Tracker`). It doesn't read server internals. It opens one connection per slot, because the server only tells each
slot about its own checked locations and received items. The slot names come from `data/sessions/<id>/room.json`,
which the gateway writes before it starts the session's game server.

- **Your connection:** the server address, your slot names and their passwords (shown on request, copyable), the
  slot's patch files (or that it has none); beside them on a wide screen Randogeddon Bridge (its ⓘ, *Create key*, its
  downloads). The bridge folds away (*Collapse*, remembered in the browser): then the card is one narrow column and
  the tiles beside it get the room. A new key shows like a password (*Show*, *Copy*) until its line is closed.
- **Top card:** overall completion with "x of y checks done"; beside the game server's state the logic's (see the
  available checks above).
- **Overview:** each tile with its icon and its parts as pills, each an icon and a number, what it is as its tooltip
  (one word: *Complete*, *Goal*, *Released*, *Idle*, *Not started*, *Open*, *Progression*, *Traps*, *Deaths*, *Uptime*). *Finished* (slots done of all; below how many are
  complete, at their goal (not shown with release mode `auto`, where a goal becomes complete) and released), *Slots* (online of all; below idle and not started),
  *Unlocked* (locked slots opened), *Hints*, *Items* (sent so far; below progression and traps), *Links* (deaths and
  traps shared by DeathLink and TrapLink, from the first one on), *Play time*, *Session* (its age, the date it was
  made as a tooltip; below a power sign, green while the game server runs, and its *Uptime* for this seed).
- **Players:** one card per player of the session with their slots (a slot played together
  shows on each of its players' cards): game, state, online dot, the time beside its state, pills of an icon and a
  number, what they are as their tooltip (*Received*: the progression items it got; *Sent*: the progression items
  found in its world for other slots; counts only, so its players don't learn how much is still missing;
  *Available*: the checks it can make now, by its game's logic, grey with the last known count while the logic doesn't
  run; *Deaths* and *Traps*: what it passed on by DeathLink and TrapLink, from the first one; *Played*), two bars (a check mark: the locations checked in its own world; a
  star: those of them that hold a progression item, the progression checks). The person card has the same, summed over their
  slots, with *Done* (x / y), *Locked* and *Played* as pills under the name. How many game clients
  are connected if more than one, and for locked slots the key: who found it, or where it was hinted. *Sort* orders
  the cards and each card's slots alike (by name, progress or last activity). Without any players, every slot is its
  own card. Click a
  player's name for their overview (totals, slots, open hints, combined connection log, recent activity). Click a slot
  for its received items (filterable), remaining locations, play time and connection log.
- **A slot's state**, the first that applies: *released* (given up before its goal, or released by hand after it),
  *complete* (every location checked, also by the automatic release at the goal: a release less than 5 s after the
  slot's goal is that one), *goal* (locations left), *locked* (the key isn't found yet), *online* (a game client is
  connected now; whether someone really plays can't be known), *idle* (connected before and has checks), *not
  started*. Beside it: how long ago it was complete or released; for a goal, its goal and its last check; for idle and
  online, its last check.
- **Progression counts:** each of the tracker's slot connections asks what lies at that slot's own locations
  (`LocationScouts`, without making hints); together that is every placement, of which only the counts per slot
  reach the dashboard (never which item is where), and of those only a slot's own world's (its progression checks,
  what it sent): how many progression items are meant for a slot isn't said, its players would learn how much is
  still missing. They are there once the slot is scouted. The starting inventory isn't counted.
- **Play time:** time a game client was connected to a slot, per slot and per player, kept across restarts.
- **Live feed:** item sends colour-coded by classification (purple = progression, blue = useful,
  red = trap), hints, goals, releases, chat and joins, and the links between games (a DeathLink: who died and of
  what; a TrapLink: who set off which trap). You can filter by player and kind and search it; it shows 50 events at
  a time, with arrows to older and newer ones.
- **Hints:** every hint in the room, filterable by player, slot and state (not found, found, priority,
  avoid, no priority, unmarked).
- **Layout:** the live feed can be hidden (list button in the header) and placed right, left or below; each viewer's
  choice is remembered in their browser. On narrow screens it always sits below the players. The page takes the
  window's width less 2 % at each side, so a 1440p or 2160p screen is used: the feed grows to a quarter of it (440 to
  640 dp), and the tiles and the players' cards fill as many columns as fit (a card at least 330 dp).
- **Scroll bars:** the page, the dialogs and the lists show a thin bar on the right edge while there is more than fits
  (the page is drawn on a canvas, so the browser has none of its own); the mouse drags it, a click beside it jumps
  there, touch scrolls the content.
- **Other sessions:** the viewer's other sessions (all for Administrators), running or finished, below; a click shows one.

Known limitations:

- Each tracker connection makes the server post "X tracking … has joined" to the chat. That happens
  once per slot whenever the tracker (re)connects.
- What is said to everyone on a game server (item sends, hints, goals, releases, who joins and leaves, chat, death
  and trap links) is heard by the gateway, which starts every game server: one connection each from the moment the
  server accepts clients, written into the database (`session_events`) with the server's starts and ends. A player
  is let in once the gateway listens (it waits 3 s at most). The tracker reads the events from there about once a
  second, in their order. They are its history: at its start it sets a session's slots up from `room.json` and
  replays every one of its events (the feed, the connection logs, first check, goal and last activity come back);
  a cheat's feed line is written there by the tracker itself. So nothing is lost while it isn't running or isn't
  connected (a crash, a restart, a player waking a sleeping session before it), and nothing waits for a save. A
  session's events go when it is deleted. What a slot has checked and received still comes from the tracker's own
  connections, which bring all of it at every connect.
- **What a session's dashboard shows is kept** in the database (`session_snapshots`: the summary and every slot's
  details) when the tracker's connection to its game server ends (it sleeps or stops), when the tracker stops, and
  while connected at most every 5 minutes. Until the tracker has been connected since its start, the dashboard
  shows that (with who is connected from the replayed events): after a deploy every game server sleeps until a player
  comes, and the dashboards aren't empty meanwhile. Nothing in the game changes while its game server doesn't run.
- A slot can also count as online when it does something although no join was heard (it finds its own item or
  chats; only events from before 0.19.9, when not every join was heard): such sessions are marked "seen playing" in
  the slot's connection log.
- The feed shows the last 500 events of a session; all of them stay in the database until it is deleted.
- Until 0.19.10 the tracker kept each session's history in a file (`/state/sessions/<id>/tracker.json`, written
  every 15 s) and finished sessions' archives as files: both are brought into the database once (the history as the
  base its newer events are replayed onto) and then not read any more; the files are left where they are.


### Install on your phone

The dashboard is a web app (PWA), so no app store or Apple account is needed:

- **Android (Chrome):** open `https://randogeddon.eratiem.net` → menu ⋮ → *Install app* (or *Add to Home screen*).
- **iPhone / iPad (Safari):** open the page → Share → *Add to Home Screen*. It opens full screen with its own icon.

It needs a browser with WebAssembly GC: iOS/Safari 18.2+, Chrome/Edge 119+, Firefox 120+. Older browsers get a
message asking for an update. The first load is about 3.4 MB (Brotli, compressed when the image is built); after
that the browser has it cached.

### Appearance

Each user picks a theme and a mode under *Your account*, *Appearance*, each theme light or dark; *Automatic* follows
the device and switches with it. The themes are cards under a heading per group, each a small page in its colours in
the mode that shows now:

- **Randogeddon's:** Randogeddon (the default), Ocean, Forest, Ember, High contrast, Midnight, Sakura, Terminal,
  Sepia.
- **Inspired by Nintendo's consoles:** NES, SNES, N64, GameCube, Wii, Wii U, Switch, Switch 2.
- **Inspired by the PlayStation consoles:** PlayStation, PlayStation 2, 3, 4 and 5.
- **Inspired by the Xbox consoles:** Xbox, Xbox 360, Xbox Series X|S.
- **Inspired by SteamOS:** SteamOS.

The console and SteamOS themes are only inspired by those systems' colours (no logos, no artwork). Their names are
trademarks of their owners; Randogeddon is not affiliated with or endorsed by Nintendo, Sony, Microsoft or Valve. The
page says so under the cards.

The button on the top card cycles light, dark and automatic, and shows the mode it is in
(a sun, a moon, a half-filled circle). A new theme or mode fades in. It is stored with the account, so it follows them to every device; before the login, and on a share
link's page, the browser's last choice applies (the login page takes its colours too).

**Fonts:** every theme but the default brings a font that goes with it, and beside the mode there is a font choice:
*the theme's* (the default), *Randogeddon's original*, or another theme's font with whatever theme is chosen. A
third choice (*Font mode*) says where the font shows: *Font's default* (as the font is made: a display font, i.e. the pixel fonts of
NES and SNES and PlayStation 2's wide one, in the headings only, every other font in all text), in all text, or in
the headings only (the rest stays the original). The choice is stored with the account like the theme. A font's files are only loaded once it is
used (20 to 130 KB each); until then, and without them, the original shows. A change of the font by hand (another theme, the
font settings) is animated: the letters take the new one one by one, in no order; on page load it is just there. The login page keeps the system's font.

| Theme | Font | Theme | Font |
|---|---|---|---|
| Ocean | Mulish | Wii U | Fredoka |
| Forest | Roboto Slab | Switch | Rubik |
| Ember | Alegreya Sans | Switch 2 | Figtree |
| High contrast | Atkinson Hyperlegible | PlayStation | Chakra Petch |
| Midnight | Space Grotesk | PlayStation 2 | Orbitron |
| Sakura | Varela Round | PlayStation 3 | Outfit |
| Terminal | JetBrains Mono | PlayStation 4 | Public Sans |
| Sepia | Literata | PlayStation 5 | Montserrat |
| NES | Press Start 2P | Xbox | Oxanium |
| SNES | Pixelify Sans | Xbox 360 | Exo 2 |
| N64 | Baloo 2 | Xbox Series X\|S | Selawik |
| GameCube | Jost | SteamOS | Inter |
| Wii | Nunito | | |

None of them is a console's own font. All are free to ship: the SIL Open Font License 1.1, Roboto Slab the Apache
License 2.0; each licence's text is served beside the files (`/fonts/licences/`). `scripts/fonts.py` fetches them and
makes the files in `webApp/src/wasmJsMain/resources/fonts/` (a regular and a bold one per font, cut down to the Latin
letters the app writes; a font with a reserved name is shipped as it is). A new font: a line there, one in
`shared/…/shared/Fonts.kt`, and `FontsTest` checks that the files and the licence are there.

A new theme: an entry in `Theme` (with its group) and its two palettes in `shared/…/shared/Themes.kt`, its name in the texts
([Texts and languages](DEVELOPMENT.md#texts-and-languages)). `ThemesTest` checks that it is at least as readable as the default (text, muted text and the
text on the brand, the accent and the item and status colours on the panel) and that its item and status colours stay apart.

### Language

Randogeddon speaks English and German. Each user picks theirs under *Your account* (*Automatic* follows the
browser); it is stored with the account, so it follows them to every device, and their mails go out in it too.
Before the login the page takes the language last used in this browser, else the browser's; the login form has an
English · Deutsch switch. Dates, times and numbers are written as the language writes them. Names from Archipelago
(games, items, locations, slots) and its option values stay as they are.
