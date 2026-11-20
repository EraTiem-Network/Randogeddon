"""Generate.py for a spoiler log's later levels, with a playthrough that doesn't give up.

Usage: python spoiler_run.py <Generate.py's arguments>

Archipelago works the playthrough out in two steps: the spheres in which every progression item can be collected,
then those reduced to the items the goals need. The second step ends with "Not all required items reachable" when a
world's logic contradicts itself (what it says depends on what else was collected), and then no spoiler log is
written at all. Here that falls back to the first step's spheres: every progression item in the order it can be
collected, not reduced. The spoiler log says so in its last line (NOTE), which the tracker reads.

Nothing of Archipelago's own files is changed: its function is wrapped while this runs.
"""
import glob
import os
import sys

NOTE = "Randogeddon: the playthrough is not reduced to the required items"

# What Archipelago said when it couldn't reduce the playthrough; None: it could
gave_up = None


def unreduced(spoiler, create_paths: bool) -> None:
    """The playthrough as the spheres every progression item is collected in (the first step of Archipelago's)."""
    from itertools import chain

    from BaseClasses import CollectionState

    multiworld = spoiler.multiworld
    candidates = {location for location in multiworld.get_filled_locations() if location.item.advancement}
    state = CollectionState(multiworld)
    spheres = []
    while candidates:
        sphere = {location for location in candidates if state.can_reach(location)}
        if not sphere:
            # What nothing reaches (Archipelago lists them as unreachable when the goals are met without them)
            spoiler.unreachables = candidates
            break
        for location in sphere:
            state.collect(location.item, True, location)
        candidates -= sphere
        spheres.append(sphere)
    spoiler.playthrough = {"0": sorted(
        multiworld.get_name_string_for_object(item)
        for item in chain.from_iterable(multiworld.precollected_items.values()) if item.advancement
    )}
    for number, sphere in enumerate(spheres, start=1):
        spoiler.playthrough[str(number)] = {str(location): str(location.item) for location in sorted(sphere)}
    if create_paths:
        spoiler.create_paths(state, spheres)


def tolerant(reduced):
    """Archipelago's create_playthrough ([reduced]), falling back to [unreduced] when it can't reduce."""

    def create_playthrough(spoiler, create_paths: bool = True) -> None:
        global gave_up
        multiworld = spoiler.multiworld
        # It takes starting items out while it works and gives them back only at its end
        before = {player: list(items) for player, items in multiworld.precollected_items.items()}
        try:
            reduced(spoiler, create_paths)
            return
        except RuntimeError as error:
            if "Not all required items reachable" not in str(error):
                raise
            gave_up = str(error)
        for player, items in before.items():
            there = {id(item) for item in multiworld.precollected_items[player]}
            for item in items:
                if id(item) not in there:
                    multiworld.push_precollected(item)
        print(f"[randogeddon] The playthrough couldn't be reduced to the required items ({gave_up[:300]}): "
              "it lists every progression item in the order it can be collected", flush=True)
        unreduced(spoiler, create_paths)

    return create_playthrough


def main() -> None:
    from Generate import main as arguments
    args, seed = arguments()
    import BaseClasses
    BaseClasses.Spoiler.create_playthrough = tolerant(BaseClasses.Spoiler.create_playthrough)
    from Main import main as generate
    generate(args, seed)
    if gave_up is not None:
        for spoiler in glob.glob(os.path.join(args.outputpath, "*_Spoiler.txt")):
            with open(spoiler, "a", encoding="utf-8") as file:
                file.write(f"\n{NOTE}\n")


if __name__ == "__main__":
    sys.argv[0] = "Generate.py"
    main()
