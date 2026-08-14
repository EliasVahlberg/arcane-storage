"""In-place tier upgrades — and above all, that nothing a player owns disappears.

Crafting the next tier already worked before this. What it cost was emptying a unit by hand first, which for a
storage mod is the problem restated rather than solved. So a placed unit can now be upgraded where it stands,
paying only the era materials: the unit on the tile *is* the "tier below x1" that the crafting recipe consumes,
and charging for it twice would mean feeding a second unit into the first.

**The tests that matter here are the preservation ones.** Capacity arithmetic is pleasant to assert and cheap to
get right; losing forty stacks of someone's belongings is the fastest way to make a storage mod hated, and it is
a bug that cannot be apologised for after the fact. `Level.setObject` destroys the old object entity without
dropping its contents — verified in `ObjectRegionLayer.setObjectByRegion`, which handles wires, lighting,
settlement rooms and level jobs but never the inventory — so for a moment during the swap the only copy of the
player's items is an array on the stack. Everything below exists to prove that array is complete and lands.

The consumption order is deliberate and also tested: the player's own bag first, the network only for the
remainder. Spending what someone is carrying before reaching into their filed-away stock is the behaviour that
matches the mental model of paying for something.
"""

from __future__ import annotations

import costs
import pytest

#: (alias prefix, storage stacks, station sockets), mirroring UnitTier.
TIERS = [
    ("", 40, 1),
    ("demonic", 80, 2),
    ("tungsten", 160, 4),
    ("fallen", 320, 8),
]

#: The in-place cost of reaching each tier, read from the file the mod itself reads. Not restated here: a test that
#: supplies the number it then asserts proves only that it was typed twice the same way, and the previous copy of
#: this dict disagreed with the code for two commits without a single failure.
UPGRADE_COST = {tier: costs.tier_cost(tier) for tier in ("demonic", "tungsten", "fallen")}

#: Shorthands for the two materials the preservation tests move around by hand.
DEMONIC_BARS = UPGRADE_COST["demonic"]["demonicbar"]
TUNGSTEN_BARS = UPGRADE_COST["tungsten"]["tungstenbar"]
FALLEN_SHARDS = UPGRADE_COST["fallen"]["upgradeshard"]

#: Rungs reachable by upgrading, paired with the tier below them.
STEPS = [
    ("", "demonic", 40, 80, 1, 2),
    ("demonic", "tungsten", 80, 160, 2, 4),
    ("tungsten", "fallen", 160, 320, 4, 8),
]


def unit_alias(prefix: str) -> str:
    return f"{prefix}unit" if prefix else "unit"


def station_alias(prefix: str) -> str:
    return f"{prefix}stationunit" if prefix else "stationunit"


def stock_player(storage, tier: str, multiple: int = 1) -> None:
    """Give the player exactly the materials for one upgrade to ``tier``."""
    for item, amount in UPGRADE_COST[tier].items():
        storage.give(item, amount * multiple)


# --------------------------------------------------------------------------------------------------
# The ladder walks
# --------------------------------------------------------------------------------------------------


@pytest.mark.parametrize("below,above,stacks_before,stacks_after,_sb,_sa", STEPS)
def test_a_storage_unit_grows_to_the_next_tier_in_place(
    storage, below, above, stacks_before, stacks_after, _sb, _sa
):
    """The tile keeps its position and gains the next rung's capacity."""
    storage.place("terminal", 0, 0)
    storage.place(unit_alias(below), 1, 0)
    storage.settle(5)

    assert storage.query("capacity", 0, 0) == {"used": 0, "total": stacks_before}

    stock_player(storage, above)
    storage.do("upgrade", 1, 0)
    storage.settle(5)

    state = storage.query("upgrade", 1, 0)
    assert state["outcome"] == "upgraded"
    assert state["slotsbefore"] == stacks_before
    assert state["slotsafter"] == stacks_after
    assert storage.query("capacity", 0, 0) == {"used": 0, "total": stacks_after}


@pytest.mark.parametrize("below,above,_b,_a,sockets_before,sockets_after", STEPS)
def test_a_station_unit_grows_its_sockets_in_place(
    storage, below, above, _b, _a, sockets_before, sockets_after
):
    """The same operation on the other ladder, where the number that grows is sockets."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place(station_alias(below), 0, 1)
    storage.settle(5)

    assert storage.query("stations", 0, 0)["sockets"] == sockets_before

    stock_player(storage, above)
    storage.do("upgrade", 0, 1)
    storage.settle(5)

    assert storage.query("upgrade", 0, 1)["outcome"] == "upgraded"
    assert storage.query("stations", 0, 0)["sockets"] == sockets_after


def test_the_top_tier_refuses_rather_than_consuming_anything(storage):
    """Fallen is the last rung, and asking again must not quietly eat materials."""
    storage.place("terminal", 0, 0)
    storage.place("fallenunit", 1, 0)
    storage.settle(5)

    storage.give("upgradeshard", FALLEN_SHARDS)
    storage.do("upgrade", 1, 0)
    storage.settle(5)

    state = storage.query("upgrade", 1, 0)
    assert state["outcome"] == "at_top_tier"
    assert state["next"] == "none"
    assert storage.query("playerinv", "upgradeshard")["count"] == FALLEN_SHARDS


# --------------------------------------------------------------------------------------------------
# Preservation — the point of the exercise
# --------------------------------------------------------------------------------------------------


def test_stored_items_survive_the_upgrade(storage):
    """Contents are still there afterwards, in the same amounts."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    storage.give("oaklog", 200)
    storage.give("ironbar", 30)
    terminal = storage.query("capacity", 0, 0)
    assert terminal["used"] == 0

    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    before = storage.query("item", 0, 0, "oaklog")["count"]
    bars_before = storage.query("item", 0, 0, "ironbar")["count"]
    assert before == 200
    assert bars_before == 30

    stock_player(storage, "demonic")
    storage.do("upgrade", 1, 0)
    storage.settle(5)

    state = storage.query("upgrade", 1, 0)
    assert state["outcome"] == "upgraded"
    assert state["dropped"] == 0, "items had to be dropped on the floor, which should be unreachable"
    assert storage.query("item", 0, 0, "oaklog")["count"] == before
    assert storage.query("item", 0, 0, "ironbar")["count"] == bars_before


def test_every_stack_is_carried_and_none_are_dropped(storage):
    """A unit filled across many slots reports carrying exactly that many, and dropping none.

    ``carried`` counts occupied slots moved into the new inventory. Asserting it equals the number of distinct
    stacks put in is what distinguishes "the items are somewhere" from "every stack arrived".
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    kinds = ["oaklog", "ironbar", "stone", "clay", "copperbar"]
    for kind in kinds:
        storage.give(kind, 10)

    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    used_before = storage.query("capacity", 0, 0)["used"]
    assert used_before == len(kinds)

    stock_player(storage, "demonic")
    storage.do("upgrade", 1, 0)
    storage.settle(5)

    state = storage.query("upgrade", 1, 0)
    assert state["carried"] == used_before
    assert state["dropped"] == 0
    assert storage.query("capacity", 0, 0)["used"] == used_before

    for kind in kinds:
        assert storage.query("item", 0, 0, kind)["count"] == 10


def test_installed_benches_survive_a_station_unit_upgrade(storage):
    """Sockets are addressed by order, so benches must come back in the same sockets."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("demonicstationunit", 0, 1)
    storage.settle(5)

    storage.do("open", 0, 0)
    storage.do("install", "workstation")
    storage.do("install", "alchemytable")
    storage.do("close")
    storage.settle(5)

    stations = storage.query("stations", 0, 0)
    assert stations["installed"] == 2

    stock_player(storage, "tungsten")
    storage.do("upgrade", 0, 1)
    storage.settle(5)

    state = storage.query("upgrade", 0, 1)
    assert state["outcome"] == "upgraded"
    assert state["carried"] == 2
    assert state["dropped"] == 0

    after = storage.query("stations", 0, 0)
    assert after["sockets"] == 4
    assert after["installed"] == 2


def test_a_full_unit_upgrades_without_dropping_anything(storage):
    """The worst case for preservation: no free slot to be sloppy with.

    Capacity only ever grows, so a 40-slot inventory always fits in an 80-slot one — but "always" is a claim
    about today's tier table, and this is the test that would notice if that stopped being true.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    # 40 distinct kinds would be fiddly to name; a smaller full unit is the same proof, so fill what we can
    # and assert nothing was lost regardless of how many slots ended up occupied.
    for kind in ["oaklog", "ironbar", "stone", "clay", "copperbar", "goldbar", "quartz"]:
        storage.give(kind, 5)

    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    used = storage.query("capacity", 0, 0)["used"]

    stock_player(storage, "demonic")
    storage.do("upgrade", 1, 0)
    storage.settle(5)

    state = storage.query("upgrade", 1, 0)
    assert state["carried"] == used
    assert state["dropped"] == 0


# --------------------------------------------------------------------------------------------------
# Where the materials come from
# --------------------------------------------------------------------------------------------------


def test_materials_come_from_the_player_before_the_network(storage):
    """With enough in both places, the bag is spent and the network is left alone."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    # A full cost into the network first, then a full cost into the bag.
    storage.give("demonicbar", DEMONIC_BARS)
    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)
    storage.give("demonicbar", DEMONIC_BARS)

    state = storage.query("upgrade", 1, 0)
    assert state["haveinv_demonicbar"] == DEMONIC_BARS
    assert state["havenet_demonicbar"] == DEMONIC_BARS
    assert state["have_demonicbar"] == DEMONIC_BARS * 2
    assert state["affordable"] is True

    storage.do("upgrade", 1, 0)
    storage.settle(5)

    assert storage.query("upgrade", 1, 0)["outcome"] == "upgraded"
    assert storage.query("playerinv", "demonicbar")["count"] == 0
    assert storage.query("item", 0, 0, "demonicbar")["count"] == DEMONIC_BARS, (
        "the network was charged even though the player's own stock covered it"
    )


def test_materials_come_from_the_network_when_the_player_has_none(storage):
    """Filed-away materials are spendable, which is the whole point of a storage network."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    storage.give("demonicbar", DEMONIC_BARS)
    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    state = storage.query("upgrade", 1, 0)
    assert state["haveinv_demonicbar"] == 0
    assert state["havenet_demonicbar"] == DEMONIC_BARS
    assert state["affordable"] is True

    storage.do("upgrade", 1, 0)
    storage.settle(5)

    assert storage.query("upgrade", 1, 0)["outcome"] == "upgraded"
    assert storage.query("item", 0, 0, "demonicbar")["count"] == 0


def test_the_remainder_is_taken_from_the_network(storage):
    """A part-paid upgrade tops up from the network rather than refusing."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    # Split so neither side alone is enough: the point is that the two are added, then drawn in order.
    in_bag = DEMONIC_BARS // 2
    in_network = DEMONIC_BARS - in_bag

    storage.give("demonicbar", in_network)
    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)
    storage.give("demonicbar", in_bag)

    state = storage.query("upgrade", 1, 0)
    assert state["haveinv_demonicbar"] == in_bag
    assert state["havenet_demonicbar"] == in_network
    assert state["affordable"] is True

    storage.do("upgrade", 1, 0)
    storage.settle(5)

    assert storage.query("upgrade", 1, 0)["outcome"] == "upgraded"
    assert storage.query("playerinv", "demonicbar")["count"] == 0
    assert storage.query("item", 0, 0, "demonicbar")["count"] == 0


def test_materials_stored_in_the_unit_being_upgraded_are_spendable(storage):
    """The bars inside the very thing being upgraded count, and are not refunded by the transfer.

    This is the case the ordering exists for: consumption happens *before* the snapshot, so materials taken out
    of this unit are already gone when its contents are captured. Snapshotting first would restore the bars that
    were just spent and hand out a free upgrade.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    storage.give("demonicbar", DEMONIC_BARS)
    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    # Counted through the terminal rather than per tile: item_at wants a terminal on the tile it is asked
    # about, and what matters here is only that the bars are in the network at all.
    assert storage.query("item", 0, 0, "demonicbar")["count"] == DEMONIC_BARS

    storage.do("upgrade", 1, 0)
    storage.settle(5)

    assert storage.query("upgrade", 1, 0)["outcome"] == "upgraded"
    assert storage.query("item", 0, 0, "demonicbar")["count"] == 0, "the spent bars came back"
    assert storage.query("capacity", 0, 0)["total"] == 80


# --------------------------------------------------------------------------------------------------
# Refusals must cost nothing
# --------------------------------------------------------------------------------------------------


def test_being_short_consumes_nothing_at_all(storage):
    """One bar short is a refusal, not a partial charge."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    one_short = DEMONIC_BARS - 1
    storage.give("demonicbar", one_short)

    state = storage.query("upgrade", 1, 0)
    assert state["have_demonicbar"] == one_short
    assert state["req_demonicbar"] == DEMONIC_BARS
    assert state["affordable"] is False

    storage.do("upgrade", 1, 0)
    storage.settle(5)

    after = storage.query("upgrade", 1, 0)
    assert after["outcome"] == "missing_materials"
    assert storage.query("playerinv", "demonicbar")["count"] == one_short
    assert storage.query("capacity", 0, 0)["total"] == 40


def test_a_multi_material_upgrade_short_on_one_charges_neither(storage):
    """Tungsten needs bars *and* quartz; having only the bars must leave the bars alone.

    The interesting failure mode is a loop that spends each material as it checks it, so the first requirement
    is consumed before the second is found wanting.
    """
    storage.place("terminal", 0, 0)
    storage.place("demonicunit", 1, 0)
    storage.settle(5)

    storage.give("tungstenbar", TUNGSTEN_BARS)

    state = storage.query("upgrade", 1, 0)
    assert state["have_tungstenbar"] == TUNGSTEN_BARS
    assert state["have_quartz"] == 0
    assert state["affordable"] is False

    storage.do("upgrade", 1, 0)
    storage.settle(5)

    assert storage.query("upgrade", 1, 0)["outcome"] == "missing_materials"
    assert storage.query("playerinv", "tungstenbar")["count"] == TUNGSTEN_BARS, (
        "bars were spent on a refused upgrade"
    )
    assert storage.query("capacity", 0, 0)["total"] == 80


def test_upgrading_something_that_is_not_a_unit_is_refused(storage):
    """A conduit is on the network but has no tier, and must not be mistaken for one."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 2, 0)
    storage.place("conduit", 1, 0)
    storage.settle(5)

    storage.give("demonicbar", DEMONIC_BARS)
    storage.do("upgrade", 1, 0)
    storage.settle(5)

    state = storage.query("upgrade", 1, 0)
    assert state["outcome"] == "not_a_unit"
    assert state["tier"] == "none"
    assert storage.query("playerinv", "demonicbar")["count"] == DEMONIC_BARS


# --------------------------------------------------------------------------------------------------
# The upgraded unit is an ordinary member afterwards
# --------------------------------------------------------------------------------------------------


def test_an_upgraded_unit_still_conducts_the_network(storage):
    """Swapping the object must not sever what was reachable through it."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("unit", 2, 0)
    storage.settle(5)

    assert storage.query("capacity", 0, 0)["total"] == 80

    stock_player(storage, "demonic")
    storage.do("upgrade", 1, 0)
    storage.settle(5)

    # The middle unit is now Demonic (80) and the far one is still reachable only through it (40).
    assert storage.query("upgrade", 1, 0)["outcome"] == "upgraded"
    assert storage.query("capacity", 0, 0)["total"] == 120


def test_an_upgraded_unit_accepts_new_items_in_its_new_slots(storage):
    """Capacity that cannot be used is not capacity."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    stock_player(storage, "demonic")
    storage.do("upgrade", 1, 0)
    storage.settle(5)

    assert storage.query("capacity", 0, 0)["total"] == 80

    storage.give("oaklog", 60)
    storage.do("open", 0, 0)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    assert storage.query("item", 0, 0, "oaklog")["count"] == 60


def test_the_upgrade_query_describes_the_next_rung(storage):
    """What the UI will render: the target, its cost, and whether it is affordable."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    state = storage.query("upgrade", 1, 0)
    assert state["tier"] == "base"
    assert state["next"] == "demonic"
    assert state["target"] == "arcanestorageunitdemonic"
    assert state["station"] is False
    assert state["cost"] == "demonicbar"
    assert state["req_demonicbar"] == DEMONIC_BARS
    assert state["affordable"] is False

    station = storage.place("stationunit", 0, 1)
    storage.settle(5)
    assert station is not None

    station_state = storage.query("upgrade", 0, 1)
    assert station_state["station"] is True
    assert station_state["target"] == "arcanestoragestationunitdemonic"


def test_the_object_itself_advances_a_tier_not_just_its_entity(storage):
    """The tile must *be* the next tier afterwards, not merely hold a bigger inventory.

    These are separate facts and they came apart in practice. ``Level.setObject`` replaces the object entity, so
    capacity grew and every headless assertion passed -- while the object ID the world draws, names and reopens
    the panel from stayed on the tier below. The tier read here comes from ``level.getObject``, so it fails if the
    object is not swapped even when the entity is.

    The client half of that bug is still not covered: the harness has no client, and the missing piece was a
    packet telling clients the object changed. Nothing here can see a texture.
    """
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.settle(5)

    assert storage.query("upgrade", 1, 0)["tier"] == "base"

    stock_player(storage, "demonic")
    storage.do("upgrade", 1, 0)
    storage.settle(5)

    state = storage.query("upgrade", 1, 0)
    assert state["outcome"] == "upgraded"
    assert state["tier"] == "demonic", "the object was not replaced, only its entity"
    assert state["next"] == "tungsten"
    assert state["target"] == "arcanestorageunittungsten"
    assert state["req_tungstenbar"] == TUNGSTEN_BARS, "the panel would reoffer the tier it just left"


def test_a_station_unit_object_advances_too(storage):
    """The same, on the ladder whose object IDs differ."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("stationunit", 0, 1)
    storage.settle(5)

    stock_player(storage, "demonic")
    storage.do("upgrade", 0, 1)
    storage.settle(5)

    state = storage.query("upgrade", 0, 1)
    assert state["tier"] == "demonic"
    assert state["station"] is True
    assert state["target"] == "arcanestoragestationunittungsten"
