"""A session's logic: its seed's worlds generated again up to the fill, then asked what each slot can reach.

Usage: python logic_run.py <the seed's .zip> <Generate.py's arguments, with the seed's number and inputs>

The worlds' rules exist only while Archipelago generates (the seed's multidata has the placements, not the rules).
Generate.py runs with the seed's number and inputs as for its spoiler log, but stops before the fill: the worlds, their
regions, rules, events and locked items are there then. What the fill and the steps after it did comes from the seed
itself (its multidata, read once): every location gets the item the seed put there (rules may look at it), the
starting inventory gets what the seed's has beyond the rebuild's (items given in or after the fill), and a received
item is collected with the classification the seed gave it (the strongest of its copies for that slot), not the one
its world gives a new copy. Each slot's locations are compared with the seed's first: one whose locations differ (an
APWorld that doesn't build the same with the same seed) gets no answers.

Then, once, the seed's own order is replayed against the rebuilt rules (self_check: its spheres; every location of one
must be reachable with the items of the spheres before it). A slot that doesn't fit is still answered; the ready line
names it with the first thing that didn't fit, and the log says so.

Then it answers on stdin / stdout, one JSON object a line. Asked:
    {"slots": [{"slot": 3, "items": [<item IDs it received>], "checked": [<its checked location IDs>]}], "maps": [3],
     "locks": {"slot": 7, "items": [<item IDs the World Locks slot received: the keys found>]}}
it answers with a line of its own that starts with PREFIX:
    {"available": {"3": [<location IDs>]}, "maps": {"3": {"regions": [...], "links": [[0, 1], ...]}},
     "regions": {"3": [0, 1, 4]}}
which of the slot's unchecked locations its logic reaches with those items, the keys found and its starting inventory
(`null`: no answer for that slot), for the slots in "maps" their map (see slot_map), and which of its map's regions it
reaches (their places in the map; the others are blocked for now). Without "locks" (or `null`) no key is collected.
Ready: {"ready": true, "slots": [<the slots answered>], "mismatches": {"3": "<what didn't fit>"}}. Everything else it
prints is its log.
"""
import json
import os
import sys
import time
import traceback
from collections import Counter

PREFIX = "[randogeddon-logic] "
# The most the self-check may take (the review's estimate: about one playthrough's first step); then it stops there
CHECK_SECONDS = 300
# What a multidata's item flags hold of an item's classification (progression, useful, trap)
CLASSIFICATION_FLAGS = 0b111

# Each slot's map's regions in the map's order, once its map was made
MAP_REGIONS = {}
# What was said once in the log (keys)
SAID = set()


class Built(Exception):
    """Raised at the fill: the worlds are built."""


def say(message: dict) -> None:
    """[message] on a line of its own, whatever a world printed before without a line end (the gateway takes only lines
    that start with PREFIX; at worst the log gets an empty line)."""
    sys.stderr.flush()
    sys.stdout.write("\n" + PREFIX + json.dumps(message) + "\n")
    sys.stdout.flush()


def once(key, text: str) -> None:
    """[text] in the log, the first time for [key]."""
    if key not in SAID:
        SAID.add(key)
        print(text, flush=True)


class Seed:
    """What the logic takes from the seed's multidata, read once at build time."""

    def __init__(self, decoded: dict):
        # Each slot's locations: address -> (item ID, the item's slot, its flags)
        self.locations = {int(slot): dict(placed) for slot, placed in decoded["locations"].items()}
        # Each slot's starting inventory (item IDs), as the seed has it after its fill
        self.precollected = {int(slot): list(items) for slot, items in decoded.get("precollected_items", {}).items()}
        # Each sphere: slot -> its location IDs in it; None: a multidata of before Archipelago wrote them
        self.spheres = decoded.get("spheres")


def build(arguments: list) -> object:
    """The multiworld up to the fill."""
    sys.argv = ["Generate.py"] + arguments
    # Depends on (Archipelago 0.6.8): Generate.main() returning (args, seed) and Main.main(args, seed)
    from Generate import main as generate_arguments
    args, seed = generate_arguments()
    import Main
    built = {}

    def stop(multiworld, *_, **__):
        built["multiworld"] = multiworld
        raise Built()

    # Depends on (Archipelago 0.6.8): Main.main() calling the fill by the names it imported into Main, after
    # pre_fill (and the stage methods: World Locks' lock is on then): distribute_items_restrictive, or flood_items when
    # host.yaml's generator algorithm is "flood"
    Main.distribute_items_restrictive = stop
    Main.flood_items = stop
    try:
        Main.main(args, seed)
    except Built:
        pass
    if "multiworld" not in built:
        raise RuntimeError("Generate.py ended before the fill")
    return built["multiworld"]


def new_state(multiworld) -> object:
    """A state with every slot's starting inventory."""
    # Depends on (Archipelago 0.6.8): CollectionState(multiworld) collecting multiworld.precollected_items;
    # state.collect(item, prevent_sweep, location), state.stale, state.reachable_regions[player] (a set) and
    # state.update_reachable_regions(player); location.can_reach(state), region.can_reach(state)
    from BaseClasses import CollectionState
    return CollectionState(multiworld)


def make_item(world, code: int, flags: int = None):
    """
    [world]'s item [code] as its world makes it, with [flags] (the seed's classification: progression, useful, trap)
    in place of its own when given; `None`: its world has no item of that ID. A world that can't make it raises.
    """
    # Depends on (Archipelago 0.6.8): world.item_id_to_name, world.create_item(name), item.classification (an IntFlag
    # whose lowest three bits are a multidata's flags) deciding item.advancement
    name = world.item_id_to_name.get(code)
    if name is None:
        return None
    item = world.create_item(name)
    if flags is not None:
        try:
            item.classification = type(item.classification)(
                (int(item.classification) & ~CLASSIFICATION_FLAGS) | (flags & CLASSIFICATION_FLAGS))
        except (AttributeError, TypeError, ValueError):
            pass
    return item


def seed_flags(multiworld, seed: Seed) -> dict:
    """
    For each slot, the item IDs it receives in the seed with the strongest classification of their copies (a world
    may make some copies of an item progression and the rest filler: any of them counts as the progression one, as
    the game counts them; an item link's items count for each of its slots).
    """
    groups = getattr(multiworld, "groups", None) or {}
    found = {}
    for placed in seed.locations.values():
        for code, owner, flags in placed.values():
            receivers = groups[owner].get("players", ()) if owner in groups else (owner,)
            for receiver in receivers:
                items = found.setdefault(int(receiver), {})
                items[code] = items.get(code, 0) | int(flags)
    return found


def mismatch(mismatches: dict, slot: int, text: str) -> None:
    """[text] as [slot]'s mismatch, if it has none yet: one line."""
    if slot not in mismatches:
        mismatches[slot] = " ".join(text.split())[:300]


def from_seed(multiworld, seed: Seed, answered: set, mismatches: dict) -> tuple:
    """
    The rebuild completed from the seed: what the seed's starting inventories have beyond the rebuild's goes into
    them; every location gets the seed's item where the rebuild has none (only what was placed before the fill is
    there). A rebuilt starting item or pre-placed item the seed hasn't is a mismatch of its slot. The seed's items by
    (slot, address), also where the rebuild has no such location (for the self-check), and those that couldn't be made
    and are progression.
    """
    def made(owner: int, code: int, flags: int = None):
        try:
            item = make_item(multiworld.worlds[owner], code, flags)
        except Exception as error:
            once(("make", owner, code), f"Slot {owner}: its world can't make item {code} of the seed ({error!r})")
            return None
        if item is None:
            once(("make", owner, code), f"Slot {owner}: its world has no item {code} of the seed")
        return item

    for slot in multiworld.player_ids:
        rebuilt = Counter(item.code for item in multiworld.precollected_items.get(slot, []) if type(item.code) is int)
        seeds = Counter(seed.precollected.get(slot, []))
        for code in (rebuilt - seeds):
            if slot in answered:
                name = multiworld.worlds[slot].item_id_to_name.get(code, code)
                mismatch(mismatches, slot, f"its starting inventory has {name}, the seed's hasn't")
        for code in (seeds - rebuilt).elements():
            item = made(slot, code)
            if item is not None:
                multiworld.push_precollected(item)

    items = {}
    unmade = set()
    for slot, placed in seed.locations.items():
        rebuilt = {}
        if slot in multiworld.worlds:
            rebuilt = {location.address: location for location in multiworld.get_locations(slot)
                       if location.address is not None}
        for address, (code, owner, flags) in placed.items():
            location = rebuilt.get(address)
            there = location.item if location is not None else None
            if there is not None and (there.code, there.player) == (code, owner):
                items[slot, address] = there
                continue
            if there is not None and slot in answered:
                mismatch(mismatches, slot, f"{location.name} holds {there.name} in the rebuild, another item in the seed")
            item = made(owner, code, flags)
            if item is None:
                if flags & 1:
                    unmade.add((slot, address))
                continue
            items[slot, address] = item
            if location is not None and there is None:
                location.item = item
                item.location = location
    return items, unmade


def sweep(state, events: list, done: set) -> bool:
    """[events] (locked items without an address) collected into [state] as far as they are reached, again and again:
    one opens the next. Whether one was."""
    collected = False
    grew = True
    while grew:
        grew = False
        for location in events:
            if id(location) not in done and location.can_reach(state):
                state.collect(location.item, True, location)
                done.add(id(location))
                grew = collected = True
    return collected


def regions_grew(state, player: int) -> bool:
    """
    [player]'s reached regions in [state] worked out again: Archipelago tries an entrance that was closed again only
    when the player's items change, so one whose rule asks whether another region is reached (without telling
    Archipelago) stays closed when that region is reached later. Whether more are reached now.
    """
    regions = getattr(state, "reachable_regions", None)
    update = getattr(state, "update_reachable_regions", None)
    if regions is None or update is None or player not in regions:
        return False
    before = len(regions[player])
    state.stale[player] = True
    update(player)
    return len(regions[player]) > before


def settle(state, slot: int, events: list, done: set) -> None:
    """[state] with [slot]'s events as far as they are reached and its regions worked out until neither grows."""
    while True:
        swept = sweep(state, events, done)
        grew = regions_grew(state, slot)
        if not (swept or grew):
            return


def self_check(multiworld, seed: Seed, items: dict, unmade: set, answered: set, mismatches: dict,
               seconds: float = CHECK_SECONDS) -> None:
    """
    The seed's spheres replayed against the rebuilt rules, once: with one state for every slot, each location of a
    sphere must be reachable with the items of the spheres before it (and the events those reach); then its items are
    collected, as the seed has them (also where a location didn't fit, so one difference isn't counted again later).
    The last sphere may be what nothing reaches in the seed (its unreachable locations): it fits if none of it is
    reached. A slot of [answered] with a location that doesn't fit gets its first one in [mismatches]. It stops after
    [seconds] (the rest isn't checked), and where a progression item of the seed can't be made.
    """
    if seed.spheres is None:
        print("The seed's multidata has no spheres (made by an older Archipelago): the rebuild isn't checked against "
              "the seed's order", flush=True)
        return
    started = time.monotonic()
    state = new_state(multiworld)
    locations = {(location.player, location.address): location for location in multiworld.get_locations()
                 if location.address is not None}
    events = [location for location in multiworld.get_locations() if location.address is None and location.item]
    done = set()
    count = len(seed.spheres)
    for number, sphere in enumerate(seed.spheres, start=1):
        if time.monotonic() - started > seconds:
            print(f"The self-check stopped after {seconds} s at sphere {number} of {count}: the rest of the seed's order "
                  "isn't checked", flush=True)
            return
        keys = sorted((int(slot), int(address)) for slot, addresses in sphere.items() for address in addresses)
        there = [locations[key] for key in keys if key in locations]
        sweep(state, events, done)
        unreachable = [location for location in there if not location.can_reach(state)]
        if unreachable:
            # Not by the order things were collected in: every slot's regions worked out again first
            for player in multiworld.player_ids:
                while regions_grew(state, player):
                    pass
            sweep(state, events, done)
            unreachable = [location for location in unreachable if not location.can_reach(state)]
        if unreachable and not (number == count and len(unreachable) == len(there)):
            for location in unreachable:
                if location.player in answered:
                    mismatch(mismatches, location.player,
                             f"sphere {number} of {count}: {location.name} isn't reachable by the rebuilt rules with "
                             "the items of the spheres before it")
        if any(key in unmade for key in keys):
            print(f"The self-check stopped at sphere {number} of {count}: a progression item of it can't be made by "
                  "its world", flush=True)
            return
        for key in keys:
            item = items.get(key)
            if item is not None:
                state.collect(item, True, locations.get(key))
    print(f"Self-check: {count} spheres of the seed replayed in {time.monotonic() - started:.1f} s", flush=True)


class Kept:
    """An asked slot's state between its questions, with what it was made of."""
    __slots__ = ("state", "lock_slot", "items", "keys", "events")

    def __init__(self, state, lock_slot):
        self.state = state
        self.lock_slot = lock_slot
        self.items = Counter()
        self.keys = Counter()
        # Its events collected (their ids)
        self.events = set()


def covers(more: Counter, less: Counter) -> bool:
    return all(more[key] >= count for key, count in less.items())


class Logic:
    """The questions' side: each slot's state from what it received and the keys found."""

    def __init__(self, multiworld, flags: dict):
        self.multiworld = multiworld
        self.flags = flags
        self.events = {}
        self.addressed = {}
        self.kept = {}

    def events_of(self, slot: int) -> list:
        """[slot]'s events (locked items without an address), listed once."""
        if slot not in self.events:
            self.events[slot] = [location for location in self.multiworld.get_locations(slot)
                                 if location.address is None and location.item]
        return self.events[slot]

    def addressed_of(self, slot: int) -> list:
        """[slot]'s locations with an address, listed once."""
        if slot not in self.addressed:
            self.addressed[slot] = [location for location in self.multiworld.get_locations(slot)
                                    if location.address is not None]
        return self.addressed[slot]

    def collect(self, state, owner: int, codes, flags: dict) -> None:
        """Items [codes] of slot [owner]'s world into [state] (with the seed's [flags] where it has them); one its world
        can't make is left out (said once), the others are collected."""
        world = self.multiworld.worlds[owner]
        for code in codes:
            try:
                item = make_item(world, code, flags.get(code))
            except Exception as error:
                once(("collect", owner, code), f"Slot {owner} ({self.multiworld.player_name[owner]}): its world can't "
                                               f"make item {code} ({error!r}): it is left out of the logic")
                continue
            if item is None:
                once(("collect", owner, code), f"Slot {owner} ({self.multiworld.player_name[owner]}): its world has "
                                               f"no item {code}: it is left out of the logic")
                continue
            state.collect(item, True)

    def state(self, slot: int, items: list, locks: tuple = None):
        """
        [slot]'s state with the [items] it received, the keys found ([locks]: the World Locks slot and the item IDs it
        received, collected through its world, which opens the worlds they lock) and its starting inventory, and its
        events as far as that reaches. Kept for its next question: one with as many of each item and key or more
        collects only what is new.
        """
        lock_slot, keys = locks if locks else (None, ())
        items, keys = Counter(items), Counter(keys)
        kept = self.kept.pop(slot, None)
        if kept is None or kept.lock_slot != lock_slot or not (covers(items, kept.items) and covers(keys, kept.keys)):
            kept = Kept(new_state(self.multiworld), lock_slot)
        if lock_slot is not None:
            self.collect(kept.state, lock_slot, (keys - kept.keys).elements(), {})
        self.collect(kept.state, slot, (items - kept.items).elements(), self.flags.get(slot, {}))
        settle(kept.state, slot, self.events_of(slot), kept.events)
        kept.items, kept.keys = items, keys
        # Kept only once it is whole: a question that failed on the way makes the next one start anew
        self.kept[slot] = kept
        return kept.state

    def available(self, slot: int, state, checked: set) -> list:
        """[slot]'s unchecked locations [state] reaches: their IDs."""
        return [location.address for location in self.addressed_of(slot)
                if location.address not in checked and location.can_reach(state)]

    def locks(self, asked: dict):
        """The keys found of question [asked] (its "locks"): (the World Locks slot, its received item IDs); `None`:
        none asked, or a slot this multiworld hasn't."""
        locks = asked.get("locks")
        if not isinstance(locks, dict) or locks.get("slot") is None:
            return None
        lock_slot = int(locks["slot"])
        if lock_slot not in self.multiworld.worlds:
            once(("locks", lock_slot), f"The keys' slot {lock_slot} isn't in this multiworld: no keys are collected")
            return None
        return lock_slot, [int(item) for item in locks.get("items") or []]


def reached_regions(multiworld, slot: int, state) -> list:
    """Which of [slot]'s map's regions [state] reaches: their places in the map (see slot_map)."""
    if slot not in MAP_REGIONS:
        slot_map(multiworld, slot)
    return [at for at, region in enumerate(MAP_REGIONS[slot]) if region.can_reach(state)]


def slot_map(multiworld, slot: int) -> dict:
    """
    [slot]'s map as its logic has it: its regions, each with its locations' IDs (its start first, then as they are
    reached from it), and which of them connect (pairs of their indices, either way once). A region without a location
    that leads nowhere else (a dead end only rules pass through) is left out, again and again. The regions are told
    apart by themselves, not by their names: a world may name them with anything (a tuple, a number), and twice the
    same.
    """
    # Depends on (Archipelago 0.6.8): multiworld.get_regions(player), region.locations, region.exits,
    # exit.connected_region, world.origin_region_name
    world = multiworld.worlds[slot]
    regions = list(multiworld.get_regions(slot))
    number = {id(region): i for i, region in enumerate(regions)}
    names = [region.name if isinstance(region.name, str) else str(region.name) for region in regions]
    locations = [
        [location.address for location in region.locations if isinstance(location.address, int)]
        for region in regions
    ]
    neighbours = {i: set() for i in range(len(regions))}
    for i, region in enumerate(regions):
        for exit in region.exits:
            target = number.get(id(exit.connected_region))
            if target is not None and target != i:
                neighbours[i].add(target)
                neighbours[target].add(i)
    origin = getattr(world, "origin_region_name", "Menu")
    start = next((i for i, region in enumerate(regions) if region.name == origin), None)
    pruned = True
    while pruned:
        pruned = False
        for i in list(neighbours):
            if i != start and not locations[i] and len(neighbours[i]) <= 1:
                for other in neighbours.pop(i):
                    neighbours[other].discard(i)
                pruned = True

    def by_name(some) -> list:
        return sorted(some, key=lambda i: (names[i], i))

    # Its start first, then breadth first from it; what can't be reached from it after
    order = []
    seen = set()
    queue = [start] if start in neighbours else []
    while queue:
        i = queue.pop(0)
        if i in seen:
            continue
        seen.add(i)
        order.append(i)
        queue.extend(by_name(neighbours[i] - seen))
    order.extend(by_name(i for i in neighbours if i not in seen))
    index = {i: at for at, i in enumerate(order)}
    MAP_REGIONS[slot] = [regions[i] for i in order]
    links = sorted({tuple(sorted((index[a], index[b]))) for a in order for b in neighbours[a]})
    return {
        "regions": [{"name": names[i], "locations": locations[i]} for i in order],
        "links": [list(link) for link in links],
    }


def prepare(multiworld, seed: Seed) -> tuple:
    """The built [multiworld] checked against and completed from [seed]: the slots answered, their mismatches (see
    self_check) and the seed's classifications (seed_flags)."""
    answered = set()
    for slot in multiworld.player_ids:
        built = {location.address for location in multiworld.get_locations(slot) if location.address is not None}
        expected = set(seed.locations.get(slot, {}))
        if built == expected:
            answered.add(slot)
        else:
            print(f"Slot {slot} ({multiworld.player_name[slot]}): its locations differ from the seed's "
                  f"({len(built)} built, {len(expected)} in the seed): no answers for it", flush=True)
    mismatches = {}
    items, unmade = from_seed(multiworld, seed, answered, mismatches)
    self_check(multiworld, seed, items, unmade, answered, mismatches)
    for slot, line in sorted(mismatches.items()):
        print(f"Slot {slot} ({multiworld.player_name[slot]}): its rebuild doesn't fit the seed ({line}): its available "
              "checks and map may be wrong", flush=True)
    return answered, mismatches, seed_flags(multiworld, seed)


def main() -> None:
    seed_file, arguments = sys.argv[1], sys.argv[2:]
    sys.path.insert(0, os.getcwd())
    started = time.time()
    multiworld = build(arguments)
    from seed_info import loads, multidata_bytes, unpacked
    # Read as MultiServer reads it
    seed = Seed(loads(unpacked(multidata_bytes(seed_file))))
    answered, mismatches, flags = prepare(multiworld, seed)
    del seed
    logic = Logic(multiworld, flags)
    print(f"Built in {time.time() - started:.1f} s: {len(answered)} of {len(multiworld.player_ids)} slots answered",
          flush=True)
    say({"ready": True, "slots": sorted(answered),
         "mismatches": {str(slot): line for slot, line in sorted(mismatches.items())}})
    failing = set()
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            asked = json.loads(line)
            locks = logic.locks(asked)
            answers = {}
            reached = {}
            for entry in asked.get("slots", []):
                slot = int(entry["slot"])
                answers[str(slot)] = None
                if slot not in answered:
                    continue
                try:  # a world whose rules fail gets no answer; the other slots are answered either way
                    state = logic.state(slot, entry.get("items", []), locks)
                    answers[str(slot)] = logic.available(slot, state, set(entry.get("checked", [])))
                    try:  # its map's regions too; without them nothing on its map is marked
                        reached[str(slot)] = reached_regions(multiworld, slot, state)
                    except Exception as error:
                        if ("regions", slot) not in failing:
                            failing.add(("regions", slot))
                            print(f"Slot {slot} ({multiworld.player_name[slot]}): which regions it reaches can't be "
                                  f"told: {error!r}", flush=True)
                except Exception as error:
                    if slot not in failing:  # said once, with where it failed
                        failing.add(slot)
                        print(f"Slot {slot} ({multiworld.player_name[slot]}, {multiworld.game[slot]}): its logic can't be "
                              f"asked, no answers for it: {error!r}", flush=True)
                        print(traceback.format_exc(limit=-5), flush=True)
            maps = {}
            for slot in map(int, asked.get("maps", [])):
                if slot not in answered:
                    continue
                try:  # a map that can't be made is left out: the available checks are answered either way
                    maps[str(slot)] = slot_map(multiworld, slot)
                except Exception as error:
                    print(f"No map for slot {slot} ({multiworld.player_name[slot]}): {error!r}", flush=True)
            say({"available": answers, "maps": maps, "regions": reached})
        except Exception as error:  # an answer for every question, also when one fails
            print(f"Could not answer: {error!r}", flush=True)
            say({"error": str(error)[:300]})


if __name__ == "__main__":
    main()
