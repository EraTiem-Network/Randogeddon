"""The World Locks APWorld (apworlds/world_locks/) without Archipelago: stand-ins for the few classes it imports.

Run from the repository: python -m unittest discover -s archipelago/tests
"""
import enum
import importlib.util
import os
import sys
import types
import unittest
from types import SimpleNamespace
from unittest import mock

WORLD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "apworlds", "world_locks", "__init__.py")


class ItemClassification(enum.IntFlag):
    filler = 0
    progression = 1
    useful = 2


class Item:
    """As Archipelago's: equal to any item of the same name and slot."""
    game = "Celeste 64"

    def __init__(self, name, classification, code, player):
        self.name = name
        self.classification = classification
        self.code = code
        self.player = player

    def __eq__(self, other):
        return self.name == other.name and self.player == other.player

    def __hash__(self):
        return hash((self.name, self.player))


class OptionError(ValueError):
    pass


class World:
    def __init__(self, multiworld, player):
        self.multiworld = multiworld
        self.player = player

    @property
    def player_name(self):
        return self.multiworld.player_name[self.player]

    def collect(self, state, item):
        return True

    def remove(self, state, item):
        return True


def stubs() -> dict:
    base = types.ModuleType("BaseClasses")
    base.CollectionState = object
    base.Item = Item
    base.ItemClassification = ItemClassification
    base.MultiWorld = object
    base.Region = lambda *args: SimpleNamespace(args=args)
    base.Tutorial = lambda *args: SimpleNamespace(args=args)
    options = types.ModuleType("Options")
    options.OptionError = OptionError
    options.OptionSet = type("OptionSet", (), {})
    options.PerGameCommonOptions = type("PerGameCommonOptions", (), {})
    options.Toggle = type("Toggle", (), {})
    worlds = types.ModuleType("worlds")
    auto = types.ModuleType("worlds.AutoWorld")
    auto.WebWorld = type("WebWorld", (), {})
    auto.World = World
    generic = types.ModuleType("worlds.generic")
    rules = types.ModuleType("worlds.generic.Rules")
    rules.add_rule = lambda spot, rule: None
    return {"BaseClasses": base, "Options": options, "worlds": worlds, "worlds.AutoWorld": auto,
            "worlds.generic": generic, "worlds.generic.Rules": rules}


def load():
    with mock.patch.dict(sys.modules, stubs()):
        spec = importlib.util.spec_from_file_location("world_locks_under_test", WORLD)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    return module


world_locks = load()
GAME = world_locks.GAME


class MultiWorld:
    def __init__(self, games: dict):
        """[games]: slot name -> game, numbered from 1."""
        self.player_ids = tuple(range(1, len(games) + 1))
        self.player_name = dict(zip(self.player_ids, games))
        self.worlds = {p: SimpleNamespace(game=game) for p, game in zip(self.player_ids, games.values())}
        self.itempool = []
        self.shuffled = []
        self.random = SimpleNamespace(shuffle=self.shuffled.append)

    def get_game_players(self, game):
        return tuple(p for p in self.player_ids if self.worlds[p].game == game)

    def get_game_worlds(self, game):
        return [w for w in self.worlds.values() if w.game == game]


def locks_world(multiworld, player, locked) -> object:
    world = world_locks.WorldLocksWorld(multiworld, player)
    world.options = SimpleNamespace(locked_worlds=SimpleNamespace(value=set(locked)),
                                    early_keys=SimpleNamespace(value=0))
    multiworld.worlds[player] = world
    return world


class FillersTest(unittest.TestCase):
    """A2: the filler items chosen are removed, not an equal progression copy; the draw is the same."""

    def setUp(self):
        self.multiworld = MultiWorld({"Alice-Celeste": "Celeste 64", "Bob-Yacht": "Yacht Dice", "Locks": GAME})
        world = locks_world(self.multiworld, 3, ["Alice-Celeste"])
        world.generate_early()

    def test_the_chosen_filler_goes(self):
        progression = Item("Strawberry", ItemClassification.progression, 1, 1)
        filler = Item("Strawberry", ItemClassification.filler, 1, 1)
        other = Item("Sword", ItemClassification.progression, 2, 2)
        self.multiworld.itempool[:] = [progression, other, filler]
        world_locks.WorldLocksWorld.stage_generate_basic(self.multiworld)
        self.assertEqual(1, len(self.multiworld.shuffled))
        self.assertEqual([filler], self.multiworld.shuffled[0])
        self.assertEqual([id(progression), id(other)], [id(item) for item in self.multiworld.itempool])

    def test_not_enough_fillers(self):
        self.multiworld.itempool[:] = [Item("Sword", ItemClassification.progression, 2, 2)]
        with self.assertRaises(OptionError) as refused:
            world_locks.WorldLocksWorld.stage_generate_basic(self.multiworld)
        text = str(refused.exception)
        self.assertIn("filler", text)
        self.assertIn("world keys", text)


class GenerateEarlyTest(unittest.TestCase):
    """A16 / S3: no world left open, and a second World Locks slot, are refused with a reason."""

    def test_every_world_locked(self):
        multiworld = MultiWorld({"Alice-Celeste": "Celeste 64", "Locks": GAME})
        with self.assertRaises(OptionError) as refused:
            locks_world(multiworld, 2, ["Alice-Celeste"]).generate_early()
        self.assertIn("at least one world must stay open", str(refused.exception))

    def test_a_second_world_locks_slot(self):
        multiworld = MultiWorld({"Alice-Celeste": "Celeste 64", "Locks": GAME, "Locks2": GAME})
        locks_world(multiworld, 3, [])
        with self.assertRaises(OptionError) as refused:
            locks_world(multiworld, 2, ["Alice-Celeste"]).generate_early()
        self.assertIn("only one World Locks slot", str(refused.exception))

    def test_one_world_open_is_enough(self):
        multiworld = MultiWorld({"Alice-Celeste": "Celeste 64", "Bob-Yacht": "Yacht Dice", "Locks": GAME})
        world = locks_world(multiworld, 3, ["Alice-Celeste"])
        world.generate_early()
        self.assertEqual({"World Key 1": ("Alice-Celeste", 1)}, world.locks)


class StateTest(unittest.TestCase):
    """A12: a key taken out of a state closes its world again; A29: a world without locks collects too."""

    def test_remove_closes_the_locked_world(self):
        multiworld = MultiWorld({"Alice-Celeste": "Celeste 64", "Bob-Yacht": "Yacht Dice", "Locks": GAME})
        world = locks_world(multiworld, 3, ["Alice-Celeste"])
        world.generate_early()
        state = SimpleNamespace(stale={1: False}, reachable_regions={1: {"Menu", "Summit"}},
                                blocked_connections={1: {"exit"}})
        world.remove(state, Item("World Key 1", ItemClassification.progression, 0, 3))
        self.assertEqual(set(), state.reachable_regions[1])
        self.assertEqual(set(), state.blocked_connections[1])
        self.assertTrue(state.stale[1])
        world.collect(state, Item("World Key 1", ItemClassification.progression, 0, 3))
        self.assertTrue(state.stale[1])

    def test_a_world_without_generate_early(self):
        world = world_locks.WorldLocksWorld(MultiWorld({"Locks": GAME}), 1)
        state = SimpleNamespace(stale={}, reachable_regions={}, blocked_connections={})
        self.assertTrue(world.collect(state, Item("World Key 1", ItemClassification.progression, 0, 1)))
        self.assertEqual({"locks": {}}, world.fill_slot_data())


if __name__ == "__main__":
    unittest.main()
