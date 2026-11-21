"""A session's logic: its seed's worlds generated again up to the fill, then asked what each slot can reach.

Usage: python logic_run.py <the seed's .zip> <Generate.py's arguments, with the seed's number and inputs>

The worlds' rules exist only while Archipelago generates (the seed's multidata has the placements, not the rules).
Generate.py runs with the seed's number and inputs as for its spoiler log, but stops before the fill: the worlds, their
regions, rules, events and locked items are there then; where every other item lies comes from the seed itself. Each
slot's locations are compared with the seed's first: one whose locations differ (an APWorld that doesn't build the
same with the same seed) gets no answers.

Then it answers on stdin / stdout, one JSON object a line. Asked:
    {"slots": [{"slot": 3, "items": [<item IDs it received>], "checked": [<its checked location IDs>]}], "maps": [3]}
it answers with a line that starts with PREFIX:
    {"available": {"3": [<location IDs>]}, "maps": {"3": {"regions": [...], "links": [[0, 1], ...]}}}
which of the slot's unchecked locations its logic reaches with those items and its starting inventory (`null`: no
answer for that slot), and for the slots in "maps" their map (see slot_map). Everything else it prints is its log.
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


def available(multiworld, slot: int, items: list, checked: set) -> list:
    """[slot]'s unchecked locations its logic reaches with [items] (and its starting inventory): their IDs."""
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
    return [
        location.address for location in multiworld.get_locations(slot)
        if location.address is not None and location.address not in checked and location.can_reach(state)
    ]


def slot_map(multiworld, slot: int) -> dict:
    """
    [slot]'s map as its logic has it: its regions, each with its locations' IDs (its start first, then as they are
    reached from it), and which of them connect (pairs of their indices, either way once). A region without a location
    that leads nowhere else (a dead end only rules pass through) is left out, again and again.
    """
    world = multiworld.worlds[slot]
    regions = [region for region in multiworld.get_regions(slot)]
    locations = {
        region.name: [location.address for location in region.locations if location.address is not None]
        for region in regions
    }
    neighbours = {region.name: set() for region in regions}
    for region in regions:
        for exit in region.exits:
            target = exit.connected_region
            if target is not None and target.player == slot and target.name != region.name and target.name in neighbours:
                neighbours[region.name].add(target.name)
                neighbours[target.name].add(region.name)
    start = getattr(world, "origin_region_name", "Menu")
    pruned = True
    while pruned:
        pruned = False
        for name in list(neighbours):
            if name != start and not locations[name] and len(neighbours[name]) <= 1:
                for other in neighbours.pop(name):
                    neighbours[other].discard(name)
                pruned = True
    # Its start first, then breadth first from it; what can't be reached from it after
    order = []
    seen = set()
    queue = [start] if start in neighbours else []
    while queue:
        name = queue.pop(0)
        if name in seen:
            continue
        seen.add(name)
        order.append(name)
        queue.extend(sorted(neighbours[name] - seen))
    order.extend(sorted(name for name in neighbours if name not in seen))
    index = {name: i for i, name in enumerate(order)}
    links = sorted({tuple(sorted((index[a], index[b]))) for a in order for b in neighbours[a]})
    return {
        "regions": [{"name": name, "locations": locations[name]} for name in order],
        "links": [list(link) for link in links],
    }


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
            maps = {str(slot): slot_map(multiworld, slot) for slot in map(int, asked.get("maps", [])) if slot in answered}
            say({"available": answers, "maps": maps})
        except Exception as error:  # an answer for every question, also when one fails
            print(f"Could not answer: {error!r}", flush=True)
            say({"error": str(error)[:300]})


if __name__ == "__main__":
    main()
