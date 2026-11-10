"""Runs MultiServer with the session's hint settings, which MultiServer has no options for.

AP_POINT_HINTS=false  players can't buy hints: !hint and !hint_location are refused. The server console's /hint
                      (and !admin /hint) still work, so the host can hand out hints.
AP_AUTO_HINTS=false   games can't create hints by themselves: LocationScouts with create_as_hint (e.g. shops that
                      hint what they sell) only scout, and CreateHints is ignored.

Everything else is plain MultiServer: the arguments are passed through unchanged.
"""
import asyncio
import logging
import os

import MultiServer


def flag(name: str) -> bool:
    return os.environ.get(name, "true").strip().lower() not in ("0", "false", "no", "off")


POINT_HINTS = flag("AP_POINT_HINTS")
AUTO_HINTS = flag("AP_AUTO_HINTS")

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
