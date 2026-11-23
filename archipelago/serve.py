"""Runs MultiServer with the session's hint settings, which MultiServer has no options for.

AP_POINT_HINTS=false  players can't buy hints: !hint and !hint_location are refused. The server console's /hint
                      (the admin panel's) still works, so the host can hand out hints.
AP_AUTO_HINTS=false   games can't create hints by themselves: LocationScouts with create_as_hint (e.g. shops that
                      hint what they sell) only scout, and CreateHints is ignored.

The room password is the first line on standard input (the gateway makes one at each start, never on the command
line, which every process can read): only the gateway and the tracker know it, every other client is refused. It is
set once the save is loaded (a save keeps the one of its last run), before the server listens, and taken out of every
line MultiServer logs (its log file under logs/ too). There is no server password, so `!admin` stays off (a save of
before may have one: it goes); the gateway's console is the only one. `!getitem` (the item cheat) is off, also for a
save that had it on.

The save (AP_<seed>.apsave beside the seed):
- one that is there and can't be read (cut off, not a save, not this seed's) is never started over: it stays as it is,
  a copy is kept beside it (AP_<seed>.apsave.broken-<UTC time as YYYYmmdd-HHMMSS>, at most BROKEN_KEPT, the oldest go;
  none again while the newest copy has the same bytes), and the server ends with exit code SAVE_PROBLEM_EXIT and one
  line on standard output that starts with SAVE_PROBLEM
- one that was read is copied to AP_<seed>.apsave.bak at each start (one, replaced each time)
- it is written to AP_<seed>.apsave.tmp and then moved over the save: a server ended while it saves leaves the last
  save whole

Everything else is plain MultiServer: the arguments are passed through unchanged.
"""
import asyncio
import logging
import os
import select
import shutil
import sys
import threading
import time
import zlib

import MultiServer

# How long the gateway's first line (the room password) is waited for
FIRST_LINE_SECONDS = 30
# How many copies of unreadable saves are kept
BROKEN_KEPT = 3
# The start of the line that says the save can't be read, and the exit code then
SAVE_PROBLEM = "[randogeddon-save] "
SAVE_PROBLEM_EXIT = 3


def flag(name: str) -> bool:
    return os.environ.get(name, "true").strip().lower() not in ("0", "false", "no", "off")


POINT_HINTS = flag("AP_POINT_HINTS")
AUTO_HINTS = flag("AP_AUTO_HINTS")

# This start's room password (main() reads it)
ROOM_PASSWORD = ""


def room_password(stream, seconds: float = FIRST_LINE_SECONDS) -> str:
    """The first line of [stream]: the room password, which the gateway writes at once; waited for [seconds] at most."""
    try:
        ready, _, _ = select.select([stream], [], [], seconds)
    except (OSError, ValueError):  # not something select() can wait on (io.UnsupportedOperation is both): read it
        ready = [stream]
    if not ready:
        raise SystemExit(f"serve.py: no room password on standard input within {seconds} s (the gateway gives one)")
    password = stream.readline().strip()
    if not password:
        raise SystemExit("serve.py: no room password on standard input (the gateway gives one)")
    return password


class WithoutSecret(logging.Filter):
    """Takes [secret] out of every record that passes (its message, its traceback): `***` in its place."""

    def __init__(self, secret: str):
        super().__init__()
        self.secret = secret

    def filter(self, record: logging.LogRecord) -> bool:
        if not self.secret:
            return True
        try:
            message = record.getMessage()
        except Exception:  # a record that can't be formatted: logging says so itself
            return True
        if self.secret in message:
            record.msg = message.replace(self.secret, "***")
            record.args = None
        if record.exc_info and not record.exc_text:
            text = logging.Formatter().formatException(record.exc_info)
            if self.secret in text:
                record.exc_text = text.replace(self.secret, "***")
        elif record.exc_text and self.secret in record.exc_text:
            record.exc_text = record.exc_text.replace(self.secret, "***")
        if record.stack_info and self.secret in record.stack_info:
            record.stack_info = record.stack_info.replace(self.secret, "***")
        return True


def mask_logs(secret: str) -> None:
    """[secret] never reaches a log: MultiServer names the room password as it starts ("Hosting game at … (Password:
    …)"), logs a refused `Connect` whole and every packet with --log_network, and its logging writes a file under
    logs/ as well as standard output (which the gateway masks itself)."""
    # Depends on (Archipelago 0.6.8): MultiServer.main() calling Utils.init_logging() first, which (re)makes the root
    # logger's handlers (the file under logs/, standard output); MultiServer's Context logging to the root logger.
    # The filter goes on each of those handlers (records of every logger pass them) and on the root logger.
    init_logging = MultiServer.Utils.init_logging
    without = WithoutSecret(secret)

    def masked_init_logging(*args, **kwargs):
        result = init_logging(*args, **kwargs)
        root = logging.getLogger()
        for handler in root.handlers:
            handler.addFilter(without)
        root.addFilter(without)
        return result

    MultiServer.Utils.init_logging = masked_init_logging
    for handler in logging.getLogger().handlers:
        handler.addFilter(without)
    logging.getLogger().addFilter(without)


def unread(ctx) -> bool:
    """Whether [ctx]'s save is there but wasn't read (see init_save)."""
    path = getattr(ctx, "save_filename", None)
    return bool(path) and os.path.exists(path) and not getattr(ctx, "randogeddon_loaded", False)


def keep_broken(path: str, now: float = None) -> str:
    """
    A copy of the unreadable save [path] beside it (`<save>.broken-<UTC time>`), unless the newest copy has the same
    bytes (each start that fails would make one); at most BROKEN_KEPT, the oldest go. The copy's path.
    """
    directory, base = os.path.split(path)
    prefix = base + ".broken-"

    def kept() -> list:
        return sorted(name for name in os.listdir(directory or ".") if name.startswith(prefix))

    with open(path, "rb") as file:
        data = file.read()
    before = kept()
    if before:
        newest = os.path.join(directory, before[-1])
        with open(newest, "rb") as file:
            if file.read() == data:
                return newest
    copy = f"{path}.broken-{time.strftime('%Y%m%d-%H%M%S', time.gmtime(now))}"
    with open(copy, "wb") as file:
        file.write(data)
    for old in kept()[:-BROKEN_KEPT]:
        try:
            os.remove(os.path.join(directory, old))
        except OSError:
            pass
    return copy


def why_unread(ctx) -> str:
    """Why [ctx]'s save couldn't be read, in a few words."""
    error = getattr(ctx, "randogeddon_save_error", None)
    if error is None:
        try:
            with open(ctx.save_filename, "rb") as file:
                MultiServer.restricted_loads(zlib.decompress(file.read()))
        except Exception as e:
            error = e
    if error is None:
        return "it was read but not taken"
    text = " ".join(f"{type(error).__name__}: {error}".split())
    return text[:200]


def refuse_save(ctx) -> None:
    """[ctx]'s save is there and can't be read: it stays, a copy is kept, and the server ends with one line."""
    path = ctx.save_filename
    name = os.path.basename(path)
    reason = why_unread(ctx)
    try:
        copy = f"kept as it is and as {os.path.basename(keep_broken(path))}"
    except OSError as error:
        copy = f"kept as it is (no copy could be made: {error})"
    backup = f"; the save of the last start is {name}.bak" if os.path.isfile(path + ".bak") else ""
    # The last line it prints: the panel shows it as the reason
    print(f"{SAVE_PROBLEM}The save {name} can't be read ({reason}): {copy}; not starting a new game over it{backup}",
          flush=True)
    raise SystemExit(SAVE_PROBLEM_EXIT)


def back_up(path: str) -> None:
    """The save that was just read, copied to `<save>.bak` (the one of the last start)."""
    try:
        shutil.copyfile(path, path + ".bak")
    except OSError as error:
        logging.warning(f"The save wasn't backed up: {error}")


# Archipelago's own, wrapped by install()
load_save = MultiServer.Context.init_save
make_save = MultiServer.Context.get_save
apply_save = MultiServer.Context.set_save
write_save = MultiServer.Context._save
# One save written at a time (the saving thread, a save on the console's /save, the one at exit)
saving = threading.Lock()


# Depends on (Archipelago 0.6.8): Context.init_save(self, enabled) naming self.save_filename, reading it, calling
# self.set_save(data) and then self._start_async_saving() (whatever happened: it logs a save it can't read and goes on
# with an empty game, which its saving thread and the save at exit would write over it); Context.password,
# server_password and item_cheat, set from the save by set_save.
def init_save(self, enabled: bool = True):
    self.randogeddon_loaded = False
    self.randogeddon_save_error = None
    start_saving = self._start_async_saving

    def start_unless_unread(*args, **kwargs):
        # Not over a save that is there and wasn't read: the server ends below instead
        if unread(self):
            return None
        return start_saving(*args, **kwargs)

    self._start_async_saving = start_unless_unread
    try:
        load_save(self, enabled)
    finally:
        del self._start_async_saving
    if enabled and unread(self):
        refuse_save(self)
    if enabled and self.randogeddon_loaded:
        back_up(self.save_filename)
    self.password = ROOM_PASSWORD
    self.server_password = None
    self.item_cheat = False


# Depends on (Archipelago 0.6.8): Context.set_save(self, savedata) raising when the save doesn't fit (another seed's,
# a newer server's) and init_save calling it only for a save it could unpack.
def set_save(self, savedata: dict):
    try:
        apply_save(self, savedata)
    except Exception as error:
        self.randogeddon_save_error = error
        raise
    self.randogeddon_loaded = True


# Depends on (Archipelago 0.6.8): Context.get_save() returning a dict with ["game_options"]["password"] and
# ["game_options"]["server_password"].
def get_save(self) -> dict:
    # Neither password goes into the save: the next start has its own
    save = make_save(self)
    save["game_options"]["password"] = None
    save["game_options"]["server_password"] = None
    return save


# Depends on (Archipelago 0.6.8): Context._save(self, exit_save) writing the whole save to self.save_filename (opened
# "wb") and returning whether it did; nothing else reading save_filename while it runs.
def _save(self, exit_save: bool = False) -> bool:
    with saving:
        target = self.save_filename
        if not target:
            return write_save(self, exit_save)
        temporary = target + ".tmp"
        self.save_filename = temporary
        try:
            saved = write_save(self, exit_save)
        finally:
            self.save_filename = target
        if not saved:
            try:
                os.remove(temporary)
            except OSError:
                pass
            return False
        try:
            with open(temporary, "rb") as file:
                os.fsync(file.fileno())
            os.replace(temporary, target)
        except OSError as error:
            self.logger.exception(error)
            return False
        return True


def install() -> None:
    """MultiServer with the save's handling, the passwords and the hint switches (see the module's description)."""
    MultiServer.Context.init_save = init_save
    MultiServer.Context.set_save = set_save
    MultiServer.Context.get_save = get_save
    MultiServer.Context._save = _save

    if not POINT_HINTS:
        def refuse(self, *args) -> bool:
            self.output("Hints are turned off for this session.")
            return False

        # Depends on (Archipelago 0.6.8): ClientMessageProcessor.commands, a dict per class that its CommandMeta
        # fills from the _cmd_<name> methods and that is looked up at each command; the commands' names hint and
        # hint_location; self.output(text). Another shape lets players buy hints again, without an error.
        for command in ("hint", "hint_location"):
            MultiServer.ClientMessageProcessor.commands[command] = refuse

    if not AUTO_HINTS:
        original = MultiServer.process_client_cmd

        async def process_client_cmd(ctx, client, args: dict):
            cmd = args.get("cmd") if isinstance(args, dict) else None
            if cmd == "LocationScouts" and args.get("create_as_hint"):
                args = {**args, "create_as_hint": 0}
            elif cmd == "CreateHints":
                return
            await original(ctx, client, args)

        # Depends on (Archipelago 0.6.8): server() looking process_client_cmd up in the module on every packet; the
        # packets LocationScouts (create_as_hint) and CreateHints. Another binding lets games hint again, without an
        # error. A packet that isn't an object goes on to Archipelago's own answer (InvalidPacket).
        MultiServer.process_client_cmd = process_client_cmd


def main() -> None:
    global ROOM_PASSWORD
    # Read before MultiServer's console reads the rest of standard input (the same stream: what follows stays for it).
    # Depends on (Archipelago 0.6.8): MultiServer's console reading sys.stdin
    ROOM_PASSWORD = room_password(sys.stdin)
    mask_logs(ROOM_PASSWORD)
    install()
    # Depends on (Archipelago 0.6.8): MultiServer.parse_args() and MultiServer.main(args)
    args = MultiServer.parse_args()
    try:
        asyncio.run(MultiServer.main(args))
    except asyncio.exceptions.CancelledError:
        pass


if __name__ == "__main__":
    main()
