"""logic_run.py without Archipelago: small stand-ins for the multiworld, its worlds, items, locations and states.

Run from the repository: python -m unittest discover -s archipelago/tests
"""
import enum
import io
import os
import sys
import types
import unittest
from collections import Counter
from contextlib import redirect_stdout

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import logic_run  # noqa: E402


class Flag(enum.IntFlag):
    filler = 0
    progression = 1
    useful = 2
    trap = 4
    skip_balancing = 8


class Item:
    def __init__(self, name, classification, code, player):
        self.name = name
        self.classification = classification
        self.code = code
        self.player = player
        self.location = None

    @property
    def advancement(self) -> bool:
        return Flag.progression in self.classification


class World:
    """A world whose items are [items] (ID -> name); those in [progression] are made progression, the rest filler."""

    def __init__(self, player, items, progression=()):
        self.player = player
        self.item_id_to_name = dict(items)
        self.ids = {name: code for code, name in items.items()}
        self.progression = set(progression)
        self.made = 0

    def create_item(self, name):
        self.made += 1
        if name == "Broken":
            raise RuntimeError("this world can't make it")
        kind = Flag.progression if name in self.progression else Flag.filler
        return Item(name, kind, self.ids[name], self.player)

    def collect(self, state, item) -> bool:
        if item.advancement:
            state.prog[item.name, self.player] += 1
            return True
        return False


class LocksWorld(World):
    """World Locks: a key collected makes the world it locks stale."""

    def __init__(self, player, locks):
        super().__init__(player, {7000 + i: key for i, key in enumerate(locks)}, progression=locks)
        self.locks = locks

    def collect(self, state, item) -> bool:
        changed = super().collect(state, item)
        if changed:
            state.stale[self.locks[item.name]] = True
        return changed


class Location:
    def __init__(self, name, player, address, rule=lambda state: True, item=None):
        self.name = name
        self.player = player
        self.address = address
        self.rule = rule
        self.item = item

    def can_reach(self, state) -> bool:
        return self.rule(state)


class State:
    def __init__(self, multiworld):
        self.multiworld = multiworld
        self.prog = Counter()
        self.stale = {player: True for player in multiworld.worlds}
        self.reachable_regions = {player: set() for player in multiworld.worlds}
        for items in multiworld.precollected_items.values():
            for item in items:
                self.collect(item, True)

    def collect(self, item, prevent_sweep=False, location=None) -> bool:
        changed = self.multiworld.worlds[item.player].collect(self, item)
        self.stale[item.player] = True
        return changed

    def has(self, name, player, count=1) -> bool:
        return self.prog[name, player] >= count

    def update_reachable_regions(self, player):
        self.stale[player] = False
        self.multiworld.grow(self, player)


class MultiWorld:
    def __init__(self, worlds, locations):
        self.worlds = worlds
        self.player_ids = tuple(sorted(p for p, w in worlds.items()))
        self.player_name = {p: f"P{p}" for p in worlds}
        self.game = {p: f"Game{p}" for p in worlds}
        self.precollected_items = {p: [] for p in worlds}
        self.locations = locations
        self.groups = {}

    def get_locations(self, player=None):
        return [location for location in self.locations if player is None or location.player == player]

    def push_precollected(self, item):
        self.precollected_items[item.player].append(item)

    def grow(self, state, player):
        pass


def setUpModule():
    stub = types.ModuleType("BaseClasses")
    stub.CollectionState = State
    sys.modules["BaseClasses"] = stub


def tearDownModule():
    sys.modules.pop("BaseClasses", None)


def seed(locations, precollected=None, spheres=None) -> logic_run.Seed:
    decoded = {"locations": locations, "precollected_items": precollected or {}}
    if spheres is not None:
        decoded["spheres"] = spheres
    return logic_run.Seed(decoded)


class KeysTest(unittest.TestCase):
    """A1: the keys found open a locked world's locations and regions."""

    def setUp(self):
        self.world = World(1, {100: "Sword"})
        self.locks = LocksWorld(7, {"World Key 1": 1})
        self.locked = Location("Behind the lock", 1, 10, lambda state: state.has("World Key 1", 7))
        self.multiworld = MultiWorld({1: self.world, 7: self.locks}, [self.locked])
        self.logic = logic_run.Logic(self.multiworld, {})

    def test_a_key_found_opens_its_world(self):
        locks = self.logic.locks({"locks": {"slot": 7, "items": [7000]}})
        state = self.logic.state(1, [], locks)
        self.assertEqual([10], self.logic.available(1, state, set()))

    def test_no_key_keeps_it_closed(self):
        for asked in ({}, {"locks": None}, {"locks": {"slot": 7, "items": []}}):
            state = self.logic.state(1, [], self.logic.locks(asked))
            self.assertEqual([], self.logic.available(1, state, set()), asked)

    def test_a_slot_not_in_the_multiworld_gives_no_keys(self):
        with redirect_stdout(io.StringIO()):
            self.assertIsNone(self.logic.locks({"locks": {"slot": 99, "items": [7000]}}))

    def test_the_key_comes_after_the_first_question(self):
        state = self.logic.state(1, [], self.logic.locks({"locks": {"slot": 7, "items": []}}))
        self.assertEqual([], self.logic.available(1, state, set()))
        state = self.logic.state(1, [], self.logic.locks({"locks": {"slot": 7, "items": [7000]}}))
        self.assertEqual([10], self.logic.available(1, state, set()))


class CollectTest(unittest.TestCase):
    """A5: the seed's classification, items a world can't make, regions worked out again, the kept state (A28)."""

    def test_the_seeds_classification_counts(self):
        world = World(1, {100: "Strawberry"})  # makes every further copy filler, as Celeste 64 does after its build
        location = Location("Summit", 1, 10, lambda state: state.has("Strawberry", 1, 2))
        multiworld = MultiWorld({1: world}, [location])
        without = logic_run.Logic(multiworld, {})
        self.assertEqual([], without.available(1, without.state(1, [100, 100]), set()))
        with_flags = logic_run.Logic(multiworld, {1: {100: int(Flag.progression)}})
        self.assertEqual([10], with_flags.available(1, with_flags.state(1, [100, 100]), set()))

    def test_flags_keep_the_other_bits(self):
        world = World(1, {100: "Thing"})
        world.create_item = lambda name: Item(name, Flag.skip_balancing, 100, 1)
        item = logic_run.make_item(world, 100, int(Flag.progression))
        self.assertEqual(Flag.progression | Flag.skip_balancing, item.classification)

    def test_the_strongest_copy_counts(self):
        multiworld = MultiWorld({1: World(1, {}), 2: World(2, {})}, [])
        found = logic_run.seed_flags(multiworld, seed({1: {10: (100, 2, 0), 11: (100, 2, 1)}, 2: {20: (101, 1, 2)}}))
        self.assertEqual({2: {100: 1}, 1: {101: 2}}, found)

    def test_an_item_its_world_cant_make_is_left_out(self):
        world = World(1, {100: "Broken", 101: "Sword"}, progression={"Sword"})
        location = Location("Cave", 1, 10, lambda state: state.has("Sword", 1))
        logic = logic_run.Logic(MultiWorld({1: world}, [location]), {})
        output = io.StringIO()
        with redirect_stdout(output):
            state = logic.state(1, [100, 101, 999])
        self.assertEqual([10], logic.available(1, state, set()))
        self.assertIn("can't make item 100", output.getvalue())
        self.assertIn("has no item 999", output.getvalue())

    def test_regions_are_worked_out_until_they_stop_growing(self):
        world = World(1, {})
        location = Location("Far", 1, 10, lambda state: len(state.reachable_regions[1]) >= 3)
        multiworld = MultiWorld({1: world}, [location])

        def grow(state, player):
            # One region more each time it is worked out, up to three (what one pass of Archipelago's misses)
            regions = state.reachable_regions[player]
            if len(regions) < 3:
                regions.add(len(regions))

        multiworld.grow = grow
        logic = logic_run.Logic(multiworld, {})
        self.assertEqual([10], logic.available(1, logic.state(1, []), set()))

    def test_events_open_the_next(self):
        world = World(1, {}, progression={"Door", "Lever"})
        world.ids = {"Door": None, "Lever": None}
        lever = Location("Lever", 1, None, item=Item("Lever", Flag.progression, None, 1))
        door = Location("Door", 1, None, lambda state: state.has("Lever", 1), item=Item("Door", Flag.progression, None, 1))
        room = Location("Room", 1, 10, lambda state: state.has("Door", 1))
        logic = logic_run.Logic(MultiWorld({1: world}, [door, lever, room]), {})
        self.assertEqual([10], logic.available(1, logic.state(1, []), set()))

    def test_a_kept_state_collects_only_what_is_new(self):
        world = World(1, {100: "A", 101: "B"}, progression={"A", "B"})
        logic = logic_run.Logic(MultiWorld({1: world}, []), {})
        first = logic.state(1, [100])
        self.assertEqual(1, world.made)
        second = logic.state(1, [100, 101])
        self.assertIs(first, second)
        self.assertEqual(2, world.made)
        third = logic.state(1, [101])  # fewer: made anew
        self.assertIsNot(second, third)
        self.assertEqual(3, world.made)
        self.assertFalse(third.has("A", 1))

    def test_a_failed_question_isnt_kept(self):
        world = World(1, {100: "A"}, progression={"A"})
        multiworld = MultiWorld({1: world}, [Location("Bad", 1, None, lambda state: 1 / 0, item=Item("E", 1, None, 1))])
        logic = logic_run.Logic(multiworld, {})
        with self.assertRaises(ZeroDivisionError):
            logic.state(1, [100])
        self.assertNotIn(1, logic.kept)


class SelfCheckTest(unittest.TestCase):
    """A6: the seed's spheres replayed against the rebuilt rules; A5: the seed's items and starting inventory."""

    def build(self, rule_12):
        self.world = World(1, {100: "Key", 101: "Other", 102: "Gem"}, progression={"Key", "Other"})
        self.locations = [
            Location("L10", 1, 10),
            Location("L11", 1, 11, lambda state: state.has("Key", 1)),
            Location("L12", 1, 12, rule_12),
        ]
        self.multiworld = MultiWorld({1: self.world}, self.locations)

    def prepare(self, spheres, precollected=None):
        placed = {1: {10: (100, 1, 1), 11: (101, 1, 1), 12: (102, 1, 0)}}
        with redirect_stdout(io.StringIO()) as output:
            result = logic_run.prepare(self.multiworld, seed(placed, precollected, spheres))
        return result, output.getvalue()

    def test_a_rebuild_that_fits(self):
        self.build(lambda state: state.has("Other", 1))
        (answered, mismatches, flags), _ = self.prepare([{1: {10}}, {1: {11}}, {1: {12}}])
        self.assertEqual({1}, answered)
        self.assertEqual({}, mismatches)
        self.assertEqual({1: {100: 1, 101: 1, 102: 0}}, flags)
        # The seed's items are on the rebuilt locations
        self.assertEqual("Key", self.locations[0].item.name)

    def test_a_rebuild_that_doesnt_fit_is_named_and_still_answered(self):
        self.build(lambda state: state.has("Missing", 1))
        (answered, mismatches, _), log = self.prepare([{1: {10}}, {1: {12}}, {1: {11}}])
        self.assertEqual({1}, answered)
        line = mismatches[1]
        self.assertTrue(line.startswith("sphere 2 of 3: L12 isn't reachable"), line)
        self.assertNotIn("\n", line)
        self.assertLessEqual(len(line), 300)
        self.assertIn("its rebuild doesn't fit the seed", log)

    def test_the_last_sphere_may_be_what_nothing_reaches(self):
        self.build(lambda state: state.has("Missing", 1))
        (_, mismatches, _), _ = self.prepare([{1: {10}}, {1: {11}}, {1: {12}}])
        self.assertEqual({}, mismatches)

    def test_no_spheres_no_check(self):
        self.build(lambda state: state.has("Missing", 1))
        (_, mismatches, _), log = self.prepare(None)
        self.assertEqual({}, mismatches)
        self.assertIn("no spheres", log)

    def test_the_seeds_starting_items_are_added(self):
        self.build(lambda state: True)
        self.locations[1].rule = lambda state: state.has("Key", 1)
        self.prepare([], precollected={1: [100]})
        self.assertEqual(["Key"], [item.name for item in self.multiworld.precollected_items[1]])

    def test_a_rebuilt_starting_item_the_seed_hasnt_is_a_mismatch(self):
        self.build(lambda state: True)
        self.multiworld.precollected_items[1].append(Item("Other", Flag.progression, 101, 1))
        (_, mismatches, _), _ = self.prepare([])
        self.assertEqual("its starting inventory has Other, the seed's hasn't", mismatches[1])

    def test_a_slot_whose_locations_differ_isnt_answered(self):
        self.build(lambda state: True)
        self.locations.append(Location("L13", 1, 13))
        (answered, mismatches, _), _ = self.prepare([{1: {10}}])
        self.assertEqual(set(), answered)
        self.assertEqual({}, mismatches)


class SayTest(unittest.TestCase):
    def test_an_answer_is_a_line_of_its_own(self):
        output = io.StringIO()
        with redirect_stdout(output):
            sys.stdout.write("a world's dot without a line end")
            logic_run.say({"ready": True, "slots": [], "mismatches": {"3": "x"}})
        lines = output.getvalue().split("\n")
        self.assertIn('[randogeddon-logic] {"ready": true, "slots": [], "mismatches": {"3": "x"}}', lines)


if __name__ == "__main__":
    unittest.main()
