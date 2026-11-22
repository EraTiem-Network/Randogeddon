"""Runs MultiServer with the session's hint settings, which MultiServer has no options for.

AP_POINT_HINTS=false  players can't buy hints: !hint and !hint_location are refused. The server console's /hint
                      (the admin panel's) still works, so the host can hand out hints.
AP_AUTO_HINTS=false   games can't create hints by themselves: LocationScouts with create_as_hint (e.g. shops that
                      hint what they sell) only scout, and CreateHints is ignored.

The room password is the first line on standard input (the gateway makes one at each start, never on the command
line, which every process can read): only the gateway and the tracker know it, every other client is refused. It is
set once the save is loaded (a save keeps the one of its last run), before the server listens. There is no server
password, so `!admin` stays off (a save of before may have one: it goes); the gateway's console is the only one.

Everything else is plain MultiServer: the arguments are passed through unchanged.
"""
import asyncio
import logging
import os
import sys

import MultiServer


def flag(name: str) -> bool:
    return os.environ.get(name, "true").strip().lower() not in ("0", "false", "no", "off")


POINT_HINTS = flag("AP_POINT_HINTS")
AUTO_HINTS = flag("AP_AUTO_HINTS")

# Read before MultiServer's console reads the rest of standard input (the same stream: what follows stays for it)
ROOM_PASSWORD = sys.stdin.readline().strip()
if not ROOM_PASSWORD:
    raise SystemExit("serve.py: no room password on standard input (the gateway gives one)")

load_save = MultiServer.Context.init_save
make_save = MultiServer.Context.get_save


def init_save(self, enabled: bool = True):
    load_save(self, enabled)
    self.password = ROOM_PASSWORD
    self.server_password = None


def get_save(self) -> dict:
    # Neither password goes into the save: the next start has its own
    save = make_save(self)
    save["game_options"]["password"] = None
    save["game_options"]["server_password"] = None
    return save


MultiServer.Context.init_save = init_save
MultiServer.Context.get_save = get_save

if not POINT_HINTS:
    def refuse(self, *args) -> bool:
        self.output("Hints are turned off for this session.")
        return False

    for command in ("hint", "hint_location"):
        MultiServer.ClientMessageProcessor.commands[command] = refuse

if not AUTO_HINTS:
    original = MultiServer.process_client_cmd

    async def process_client_cmd(ctx, client, args: dict):
        cmd = args.get("cmd")
        if cmd == "LocationScouts" and args.get("create_as_hint"):
            args = {**args, "create_as_hint": 0}
        elif cmd == "CreateHints":
            return
        await original(ctx, client, args)

    # server() looks the function up in the module on every packet
    MultiServer.process_client_cmd = process_client_cmd

if __name__ == "__main__":
    args = MultiServer.parse_args()
    try:
        asyncio.run(MultiServer.main(args))
    except asyncio.exceptions.CancelledError:
        pass
