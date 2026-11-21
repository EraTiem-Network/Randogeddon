"""A session's logic: its seed's worlds generated again up to the fill, then asked what each slot can reach.

Usage: python logic_run.py <the seed's .zip> <Generate.py's arguments, with the seed's number and inputs>

The worlds' rules exist only while Archipelago generates (the seed's multidata has the placements, not the rules).
Generate.py runs with the seed's number and inputs as for its spoiler log, but stops before the fill: the worlds, their
regions, rules, events and locked items are there then; where every other item lies comes from the seed itself. Each
slot's locations are compared with the seed's first: one whose locations differ (an APWorld that doesn't build the
same with the same seed) gets no answers.

Then it answers on stdin / stdout, one JSON object a line. Asked:
    {"slots": [{"slot": 3, "items": [<item IDs it received>], "checked": [<its checked location IDs>]}]}
it answers with a line that starts with PREFIX:
    {"available": {"3": 12}}
how many of the slot's unchecked locations its logic reaches with those items and its starting inventory (`null`: no
answer for that slot). Everything else it prints is its log.
"""
import json
import os
import sys
import time
import zipfile

PREFIX = "[randogeddon-logic] "


class Built(Exception):
    """Raised at the fill: the worlds are built."""


def say(message: dict) -> None:
    sys.stdout.write(PREFIX + json.dumps(message) + "\n")
    sys.stdout.flush()


def seed_locations(path: str) -> dict:
    """Each slot's location IDs in the seed, read as MultiServer reads its multidata."""
    from MultiServer import Context

    with zipfile.ZipFile(path) as archive:
        name = next(name for name in archive.namelist() if name.endswith(".archipelago"))
        decoded = Context.decompress(archive.read(name))
    return {int(slot): set(locations) for slot, locations in decoded["locations"].items()}


def build(arguments: list) -> object:
    """The multiworld up to the fill."""
    sys.argv = ["Generate.py"] + arguments
    from Generate import main as generate_arguments
    args, seed = generate_arguments()
    import Main
    built = {}

    def stop(multiworld, *_, **__):
        built["multiworld"] = multiworld
        raise Built()

    Main.distribute_items_restrictive = stop
    try:
        Main.main(args, seed)
    except Built:
        pass
    if "multiworld" not in built:
        raise RuntimeError("Generate.py ended before the fill")
    return built["multiworld"]


def available(multiworld, slot: int, items: list, checked: set) -> int:
    """How many of [slot]'s unchecked locations its logic reaches with [items] (and its starting inventory)."""
    from BaseClasses import CollectionState

    world = multiworld.worlds[slot]
    state = CollectionState(multiworld)
    for item_id in items:
        name = world.item_id_to_name.get(item_id)
        if name is not None:
            state.collect(world.create_item(name), True)
    # Its events (locked items without an address) as far as they are reached, again and again: one opens the next
    events = [location for location in multiworld.get_locations(slot) if location.address is None and location.item]
    reached = set()
    grew = True
    while grew:
        grew = False
        for location in events:
            if id(location) not in reached and location.can_reach(state):
                state.collect(location.item, True, location)
                reached.add(id(location))
                grew = True
    return sum(
        1 for location in multiworld.get_locations(slot)
        if location.address is not None and location.address not in checked and location.can_reach(state)
    )


def main() -> None:
    seed_file, arguments = sys.argv[1], sys.argv[2:]
    sys.path.insert(0, os.getcwd())
    started = time.time()
    multiworld = build(arguments)
    expected = seed_locations(seed_file)
    answered = set()
    for slot in multiworld.player_ids:
        built = {location.address for location in multiworld.get_locations(slot) if location.address is not None}
        if built == expected.get(slot, set()):
            answered.add(slot)
        else:
            print(f"Slot {slot} ({multiworld.player_name[slot]}): its locations differ from the seed's "
                  f"({len(built)} built, {len(expected.get(slot, ()))} in the seed): no answers for it", flush=True)
    print(f"Built in {time.time() - started:.1f} s: {len(answered)} of {len(multiworld.player_ids)} slots answered",
          flush=True)
    say({"ready": True, "slots": sorted(answered)})
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            asked = json.loads(line)
            answers = {}
            for entry in asked.get("slots", []):
                slot = int(entry["slot"])
                answers[str(slot)] = available(multiworld, slot, entry.get("items", []), set(entry.get("checked", []))) \
                    if slot in answered else None
            say({"available": answers})
        except Exception as error:  # an answer for every question, also when one fails
            print(f"Could not answer: {error!r}", flush=True)
            say({"error": str(error)[:300]})


if __name__ == "__main__":
    main()
