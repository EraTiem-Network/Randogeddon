"""
World Locks: some worlds of the multiworld start locked and open once their key is found in another world.

Add one slot of this game to the session (e.g. players/locks.yaml) and list the slot names of the worlds that start
locked. For every locked world, a "World Key N" item goes into the item pool, and the generator is told that nothing in
that world is reachable without its key: every location and exit of the world's starting region gets the key as an
access rule. So the fill places the key in a world that can be played before it, and the seed stays completable
(including chains: a key in a world that is itself locked by another key).

Nobody plays this slot. The keys are sent to it when found, which is how the tracker sees a world unlock; the mapping
key -> world is in its slot data. The game client of a locked world doesn't know about the lock itself; in Randogeddon
the gateway refuses its connection until the key is found.
"""
from dataclasses import dataclass
from typing import Any, ClassVar, Mapping

from BaseClasses import CollectionState, Item, ItemClassification, MultiWorld, Region, Tutorial
from Options import OptionError, OptionSet, PerGameCommonOptions, Toggle
from worlds.AutoWorld import WebWorld, World
from worlds.generic.Rules import add_rule

GAME = "World Locks"
MAX_KEYS = 64
BASE_ID = 7_400_000
KEY_NAMES = [f"World Key {n}" for n in range(1, MAX_KEYS + 1)]
FILLER = "Lock Dust"


class LockedWorlds(OptionSet):
    """Slot names (the `name:` of their YAML) of the worlds that start locked."""
    display_name = "Locked Worlds"


class EarlyKeys(Toggle):
    """Place the keys in the first sphere (somewhere reachable from the start), so locked worlds open early in the
    session instead of whenever the logic happens to need them."""
    display_name = "Early Keys"


@dataclass
class WorldLocksOptions(PerGameCommonOptions):
    locked_worlds: LockedWorlds
    early_keys: EarlyKeys


class WorldLocksItem(Item):
    game = GAME


class WorldLocksWeb(WebWorld):
    tutorials = [Tutorial(
        "Setup Guide", "How to lock worlds behind keys.", "English", "setup_en.md", "setup/en", ["Motzkiste"],
    )]


class WorldLocksWorld(World):
    """Worlds that start locked and open when their key is found somewhere in the multiworld."""
    game = GAME
    web = WorldLocksWeb()
    options_dataclass = WorldLocksOptions
    options: WorldLocksOptions
    topology_present = False

    item_name_to_id: ClassVar[dict[str, int]] = {name: BASE_ID + i for i, name in enumerate(KEY_NAMES)} | {
        FILLER: BASE_ID + MAX_KEYS
    }
    location_name_to_id: ClassVar[dict[str, int]] = {}

    def generate_early(self) -> None:
        names = {self.multiworld.player_name[p]: p for p in self.multiworld.player_ids}
        locked = sorted(self.options.locked_worlds.value)
        unknown = [name for name in locked if name not in names]
        if unknown:
            raise OptionError(f"{self.player_name}: locked_worlds contains unknown slots: {', '.join(unknown)}")
        if self.player_name in locked or any(self.multiworld.worlds[names[n]].game == GAME for n in locked):
            raise OptionError(f"{self.player_name}: a World Locks slot can't be locked itself")
        if len(locked) > MAX_KEYS:
            raise OptionError(f"{self.player_name}: at most {MAX_KEYS} worlds can be locked")
        # key name -> (slot name, player)
        self.locks: dict[str, tuple[str, int]] = {KEY_NAMES[i]: (name, names[name]) for i, name in enumerate(locked)}

    def create_regions(self) -> None:
        self.multiworld.regions.append(Region(self.origin_region_name, self.player, self.multiworld))

    def create_items(self) -> None:
        self.multiworld.itempool += [self.create_item(key) for key in self.locks]
        if self.options.early_keys:
            for key in self.locks:
                self.multiworld.early_items[self.player][key] = 1

    def create_item(self, name: str) -> Item:
        classification = ItemClassification.filler if name == FILLER else ItemClassification.progression
        return WorldLocksItem(name, classification, self.item_name_to_id[name], self.player)

    def get_filler_item_name(self) -> str:
        return FILLER

    def set_rules(self) -> None:
        self.multiworld.completion_condition[self.player] = lambda state: True

    @classmethod
    def stage_generate_basic(cls, multiworld: MultiWorld) -> None:
        """After every world set its rules: make room in the pool for the keys."""
        lock_worlds = [w for w in multiworld.get_game_worlds(GAME)]
        # Keys have no locations of their own: remove as many filler items as there are keys, spread over the worlds
        needed = sum(len(w.locks) for w in lock_worlds)
        if needed:
            fillers = [i for i in multiworld.itempool if i.classification == ItemClassification.filler and i.game != GAME]
            if len(fillers) < needed:
                raise OptionError(f"Not enough filler items in the multiworld to make room for {needed} world keys")
            multiworld.random.shuffle(fillers)
            for item in fillers[:needed]:
                multiworld.itempool.remove(item)

    @classmethod
    def stage_pre_fill(cls, multiworld: MultiWorld) -> None:
        """
        Lock the worlds: only now, after every world's own pre_fill. Some place their items inside their own world
        first (A Link Between Worlds its dungeon keys), checked with a state of their own items only, which never has
        the key: with the lock already on, nothing of their world would be reachable. Before the main fill, which
        starts with the early items (our keys among them), the lock is on. (Stage methods run sorted by class name: a
        world whose stage_pre_fill sorts after this one still sees the lock.)
        """
        for world in multiworld.get_game_worlds(GAME):
            for key, (_, player) in world.locks.items():
                origin = multiworld.get_region(multiworld.worlds[player].origin_region_name, player)
                rule = lambda state, item=key, owner=world.player: state.has(item, owner)
                for location in origin.locations:
                    add_rule(location, rule)
                for exit_ in origin.exits:
                    add_rule(exit_, rule)

    # The key rules sit on the locked world's entrances, but the generator only recomputes which regions a player can
    # reach when that player's own items change. A key belongs to this slot, so collecting it must also mark the locked
    # world as changed, or its regions would stay unreachable even with the key.
    def collect(self, state: CollectionState, item: Item) -> bool:
        changed = super().collect(state, item)
        if changed and item.name in self.locks:
            state.stale[self.locks[item.name][1]] = True
        return changed

    def remove(self, state: CollectionState, item: Item) -> bool:
        changed = super().remove(state, item)
        if changed and item.name in self.locks:
            state.stale[self.locks[item.name][1]] = True
        return changed

    def fill_slot_data(self) -> Mapping[str, Any]:
        return {"locks": {key: name for key, (name, _) in self.locks.items()}}
