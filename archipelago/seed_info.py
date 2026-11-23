"""What is in a seed generated elsewhere (the Launcher's AP_<seed>.zip, uploaded for a new session), as JSON.

Usage: python seed_info.py <AP_xxx.zip> <out.json>

The multidata comes from outside, so it is read with Archipelago's own restricted loader (as MultiServer reads it:
only Archipelago's classes, no code of the file's), and only after its packed size is known to stay within LIMIT:
MultiServer unpacks it without one. It is unpacked once and held once; the objects it is read into may take at most
OBJECTS more memory than the script has before, and a module the file names isn't imported (Archipelago's loader would
import any module whose name ends in "Options": a multidata has no such object). Writes {"problem": ...} for a seed
that can't be served here.

Also the one way the other scripts find and read a seed's multidata (room_info.py, logic_run.py): multidata_bytes(),
unpacked(), loads(), slot_list(). Nothing here imports Archipelago before it is needed, so a file that is refused never
loads it.
"""
import io
import json
import pickle
import sys
import zipfile
import zlib

# The most a multidata may unpack to (the largest real ones are a few dozen MB)
LIMIT = 128 * 1024 * 1024
# How much more memory (address space) than it has after its imports the script may take while it reads the
# multidata's objects: a small pickle can make many large objects
OBJECTS = 2 * 1024 * 1024 * 1024


class Problem(Exception):
    """Why a seed's multidata can't be read, as a sentence's end ("it has ...")."""


def multidata_bytes(path: str, only: bool = False, limit: int = None) -> bytes:
    """
    The packed multidata of [path]: a seed's zip (its .archipelago file: the first, as MultiServer takes it; [only]:
    there must be exactly one) or the .archipelago file itself. [limit]: the most it may unpack to, so a packed file
    much larger than that is refused before it is read.
    """
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            names = [name for name in archive.namelist() if name.endswith(".archipelago")]
            if not names or (only and len(names) != 1):
                raise Problem("it has no .archipelago file (or more than one)")
            # Packed data is never much larger than what it unpacks to
            if limit is not None and archive.getinfo(names[0]).file_size > limit + limit // 64:
                raise Problem(f"its .archipelago file unpacks to more than {limit // 1024 // 1024} MB")
            return archive.read(names[0])
    with open(path, "rb") as file:
        return file.read()


def unpacked(data: bytes, limit: int = None) -> bytes:
    """The pickle in multidata [data] (format byte, then zlib), unpacked once; at most [limit] bytes of it."""
    if not data or data[0] > 3:
        raise Problem("its .archipelago file is of a format this Archipelago doesn't read")
    unpacker = zlib.decompressobj()
    try:
        if limit is None:
            result = unpacker.decompress(data[1:])
        else:
            result = unpacker.decompress(data[1:], limit + 1)
            if len(result) > limit or unpacker.unconsumed_tail:
                raise Problem(f"its .archipelago file unpacks to more than {limit // 1024 // 1024} MB")
    except zlib.error:
        raise Problem("its .archipelago file isn't a multidata file") from None
    if not unpacker.eof:
        raise Problem("its .archipelago file is cut off")
    return result


def loads(raw: bytes, strict: bool = False) -> dict:
    """
    The multidata in [raw] (a pickle, see unpacked()), read with Archipelago's restricted loader, as MultiServer reads
    it. [strict] (a file from outside): no module the file names is imported, and the objects may take at most
    OBJECTS more memory.
    """
    import Utils

    if not strict:
        return Utils.restricted_loads(raw)
    restricted = getattr(Utils, "RestrictedUnpickler", None)
    if restricted is None:  # Depends on Utils.RestrictedUnpickler (Archipelago 0.6.8): without it, as MultiServer reads
        return Utils.restricted_loads(raw)

    class Unpickler(restricted):
        # Archipelago's find_class imports any module whose name ends in "Options" (its WebHost unpickles option
        # objects); a multidata holds none, so that is refused here before anything is imported
        def find_class(self, module: str, name: str):
            if module != "Options" and module.endswith("Options"):
                raise pickle.UnpicklingError(f"global '{module}.{name}' is forbidden")
            return super().find_class(module, name)

    # Made first: it imports what it needs (Options, NetUtils) before the memory is bounded
    unpickler = Unpickler(io.BytesIO(raw))
    bound_memory(OBJECTS)
    return unpickler.load()


def bound_memory(extra: int) -> None:
    """At most [extra] bytes more address space than the process has now (where the system tells it); else nothing."""
    try:
        import resource

        with open("/proc/self/status", encoding="ascii") as status:
            size = next(int(line.split()[1]) * 1024 for line in status if line.startswith("VmSize:"))
        soft, hard = resource.getrlimit(resource.RLIMIT_AS)
        wanted = size + extra if hard == resource.RLIM_INFINITY else min(size + extra, hard)
        if soft == resource.RLIM_INFINITY or wanted < soft:
            resource.setrlimit(resource.RLIMIT_AS, (wanted, hard))
    except (ImportError, OSError, StopIteration, ValueError):
        pass


def slot_list(decoded: dict) -> list:
    """The multidata's slots in its order: number, name, game and type (1: a player; groups such as item links are
    slots too, but nobody connects as one)."""
    # Depends on (Archipelago 0.6.8): the multidata's "slot_info" (slot -> NetUtils.NetworkSlot with name, game and
    # type), its format byte (at most 3) and its zlib-packed pickle; Utils.restricted_loads, Utils.RestrictedUnpickler
    # and Utils.version_tuple
    return [
        {"slot": int(slot), "name": info.name, "game": info.game, "type": int(info.type)}
        for slot, info in decoded["slot_info"].items()
    ]


def read(path: str) -> dict:
    try:
        raw = unpacked(multidata_bytes(path, only=True, limit=LIMIT), LIMIT)
        decoded = loads(raw, strict=True)
    except Problem as problem:
        return {"problem": str(problem)}
    except MemoryError:
        return {"problem": "its .archipelago file holds more than can be read here"}
    except (pickle.UnpicklingError, EOFError, ValueError) as error:
        return {"problem": f"its .archipelago file holds what Archipelago's server doesn't read ({str(error)[:200]})"}
    del raw

    import Utils

    needs = tuple(decoded.get("minimum_versions", {}).get("server", (0, 0, 0)))
    if needs > tuple(Utils.version_tuple):
        return {"problem": f"it needs Archipelago {'.'.join(str(part) for part in needs)} or newer to be served"}
    return {
        "seed_name": str(decoded.get("seed_name", "")),
        "version": ".".join(str(part) for part in decoded.get("version", ())),
        # Players only: item links and other groups are slots too, but nobody connects as one
        "slots": [
            {"slot": slot["slot"], "name": slot["name"], "game": slot["game"]}
            for slot in sorted(slot_list(decoded), key=lambda slot: slot["slot"]) if slot["type"] == 1
        ],
    }


def main() -> None:
    src, dst = sys.argv[1], sys.argv[2]
    with open(dst, "w", encoding="utf-8") as out:
        json.dump(read(src), out)


if __name__ == "__main__":
    main()
