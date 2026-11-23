"""Write the games this Archipelago release loads to JSON, so the gateway knows what it can generate.

Usage: python list_games.py <out.json>
Loads the worlds as Generate.py does (bundled ones and the apworlds in custom_worlds/), and lists the ones that
failed with Archipelago's reason.
"""
import json
import os
import sys

import worlds
from worlds.AutoWorld import AutoWorldRegister


def main() -> None:
    # Depends on (Archipelago 0.6.8): importing worlds loading every world (bundled ones and custom_worlds/);
    # AutoWorldRegister.world_types (game -> world class), a world's zip_path (set for an .apworld), and
    # worlds.failed_world_loads mapping a failed world's name to its reason
    games = []
    for name, world in AutoWorldRegister.world_types.items():
        zip_path = getattr(world, "zip_path", None)
        games.append({
            "game": name,
            "apworld": os.path.basename(str(zip_path)) if zip_path else None,
            "module": world.__module__,
        })
    failed = [{"name": name, "reason": str(reason)} for name, reason in worlds.failed_world_loads.items()]
    with open(sys.argv[1], "w", encoding="utf-8") as f:
        json.dump({"games": games, "failed": failed}, f, indent=2)


if __name__ == "__main__":
    main()
