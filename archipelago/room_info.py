"""Write the slot list of a multidata file to JSON so the tracker knows which slots exist.

Usage: python room_info.py <AP_xxx.archipelago|AP_xxx.zip> <out.json>
Uses Archipelago's own (restricted) loader, so it reads exactly what MultiServer reads (found and read as seed_info.py
does: the first .archipelago file of a zip, as MultiServer takes it).
"""
import json
import sys

from seed_info import Problem, loads, multidata_bytes, slot_list, unpacked


def main() -> None:
    src, dst = sys.argv[1], sys.argv[2]
    try:
        decoded = loads(unpacked(multidata_bytes(src)))
    except Problem as problem:
        raise SystemExit(f"{src}: {problem}") from None
    with open(dst, "w", encoding="utf-8") as f:
        json.dump({"seed_name": decoded.get("seed_name"), "multidata": src, "slots": slot_list(decoded)}, f, indent=2)


if __name__ == "__main__":
    main()
