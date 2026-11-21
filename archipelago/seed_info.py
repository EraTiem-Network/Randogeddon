"""What is in a seed generated elsewhere (the Launcher's AP_<seed>.zip, uploaded for a new session), as JSON.

Usage: python seed_info.py <AP_xxx.zip> <out.json>

The multidata comes from outside, so it is read with Archipelago's own restricted loader (as MultiServer reads it:
only Archipelago's classes, no code of the file's), and only after its packed size is known to stay within LIMIT:
MultiServer unpacks it without one. Writes {"problem": ...} for a seed that can't be served here.
"""
import json
import sys
import zipfile
import zlib

# The most a multidata may unpack to (the largest real ones are a few dozen MB)
LIMIT = 512 * 1024 * 1024


def read(path: str) -> dict:
    with zipfile.ZipFile(path) as archive:
        names = [name for name in archive.namelist() if name.endswith(".archipelago")]
        if len(names) != 1:
            return {"problem": "it has no .archipelago file (or more than one)"}
        data = archive.read(names[0])
    if not data or data[0] > 3:
        return {"problem": "its .archipelago file is of a format this Archipelago doesn't read"}
    unpacker = zlib.decompressobj()
    try:
        unpacked = unpacker.decompress(data[1:], LIMIT + 1)
    except zlib.error:
        return {"problem": "its .archipelago file isn't a multidata file"}
    if len(unpacked) > LIMIT or unpacker.unconsumed_tail:
        return {"problem": f"its .archipelago file unpacks to more than {LIMIT // 1024 // 1024} MB"}
    del unpacked

    import Utils
    from MultiServer import Context

    decoded = Context.decompress(data)
    needs = tuple(decoded.get("minimum_versions", {}).get("server", (0, 0, 0)))
    if needs > tuple(Utils.version_tuple):
        return {"problem": f"it needs Archipelago {'.'.join(str(part) for part in needs)} or newer to be served"}
    return {
        "seed_name": str(decoded.get("seed_name", "")),
        "version": ".".join(str(part) for part in decoded.get("version", ())),
        # Players only: item links and other groups are slots too, but nobody connects as one
        "slots": [
            {"slot": int(slot), "name": info.name, "game": info.game}
            for slot, info in sorted(decoded["slot_info"].items()) if int(info.type) == 1
        ],
    }


def main() -> None:
    src, dst = sys.argv[1], sys.argv[2]
    with open(dst, "w", encoding="utf-8") as out:
        json.dump(read(src), out)


if __name__ == "__main__":
    main()
