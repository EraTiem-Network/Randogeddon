"""Write the slot list of a multidata file to JSON so the tracker knows which slots exist.

Usage: python room_info.py <AP_xxx.archipelago|AP_xxx.zip> <out.json>
Uses Archipelago's own (restricted) loader, so it reads exactly what MultiServer reads.
"""
import json
import sys
import zipfile

from MultiServer import Context


def read_multidata(path: str) -> bytes:
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as zf:
            for name in zf.namelist():
                if name.endswith(".archipelago"):
                    return zf.read(name)
        raise SystemExit(f"No .archipelago inside {path}")
    with open(path, "rb") as f:
        return f.read()


def main() -> None:
    src, dst = sys.argv[1], sys.argv[2]
    decoded = Context.decompress(read_multidata(src))
    slots = [
        {"slot": int(slot), "name": info.name, "game": info.game, "type": int(info.type)}
        for slot, info in decoded["slot_info"].items()
    ]
    with open(dst, "w", encoding="utf-8") as f:
        json.dump({"seed_name": decoded.get("seed_name"), "multidata": src, "slots": slots}, f, indent=2)


if __name__ == "__main__":
    main()
