"""A settler's "craft until N" order, and the Storage Units inside its settlement.

The reported loop, in one shape: a settlement chest is assigned as settlement storage, an export bus
beside it sends the order's output into Storage Units, and the engine's count of "what we already
have" never rises -- so the order crafts forever. These tests drive the count itself, because that is
the whole decision: `HasStorageLevelJob.getItemCount` is the one method an order is answered with, and
every test here reaches it through the mod's own patch with a real settler, a real settlement and the
real filter the order builds.

What this cannot reach is a settler actually walking to a bench and crafting. Job selection runs on the
engine's own executor, competing with eating, wandering and night, on a schedule no test can pin -- so
the arithmetic is asserted and the walk is a numbered check in `docs/QA_BACKLOG.md`.

Two things are deliberately asserted on every count: `vanilla`, the same call made with the setting
off, and `units`, how many Storage Units were gathered. A total that is right for the wrong reason is
the failure mode this feature invites -- one unit wrongly included and another wrongly excluded -- and
neither shows up in a single number.
"""

from __future__ import annotations

import itertools

import pytest

#: How far from spawn the first settlement is founded, in regions. Far enough that the fixture's clearing
#: and the player's own loaded regions cannot reach it.
REGIONS_AWAY = 8

#: Each test gets its own settlement, this far from the last one. A settlement's first bounds are five
#: regions across, so eight regions apart cannot overlap -- and a settlement never un-founds itself, so
#: sharing one would let one test's assigned storage answer for another's tile.
SETTLEMENT_SPACING = 8

#: Handed out in order, so every test founds its own camp.
_camps = itertools.count()

#: Item the order is about. Any stackable item works; the order's own filter is built from it.
ITEM = "ironbar"


def _region_center(harness, regions: int) -> tuple[int, int]:
    """Spawn-relative offset of the middle tile of a far region.

    A settlement's first bounds are a 5x5 block of regions centred on the flag's, so founding at a
    region's middle keeps every tile this file uses well inside them and leaves a clean edge to place an
    out-of-bounds unit past.
    """
    level = harness.query("level")
    ox, oy = harness.distant_offset(regions)
    wx, wy = level["spawnx"] + ox, level["spawny"] + oy
    return (wx // 16) * 16 + 8 - level["spawnx"], (wy // 16) * 16 + 8 - level["spawny"]


@pytest.fixture()
def camp(storage):
    """A founded settlement with a chest assigned as settlement storage and a farmer in it.

        y=0:   F  c        F flag, c assigned chest

    The chest is the reported loop's other half: the engine counts it, so an order's number is compared
    against what lands there and nothing else. No workstation is placed, because nothing here drives a
    craft -- the count is asked directly, the way an order asks it.

    Each test gets its own settlement, a long way from every other one. A settlement cannot be un-founded,
    and its storage assignments outlive the container that justified them until the engine next sweeps, so
    two tests sharing one would answer for each other's tiles.
    """
    cx, cy = _region_center(storage, REGIONS_AWAY + SETTLEMENT_SPACING * next(_camps))
    storage.place("storagebox", cx + 2, cy)
    storage.settle(1)

    assert storage.call("orderfound", cx, cy).ok, "the settlement should found on an empty region"
    assert storage.call("orderassign", cx + 2, cy).ok, "the chest should become settlement storage"
    assert storage.call("ordersettler", cx, cy).ok, "a farmer should move in"

    storage.settle(2)
    try:
        yield storage, (cx, cy)
    finally:
        # A settlement with an owner holds its level's unload buffer at zero, which would fail every later
        # test in this suite that expects a region to unload. The engine drops it on its next tick.
        storage.call("orderdisband", cx, cy)
        storage.settle(2)


def _have(harness, camp_tile, max_count: int) -> dict:
    """The count an order of `max_count` ITEMs is answered with, at this settlement."""
    cx, cy = camp_tile
    return harness.query("orderhave", cx, cy, ITEM, max_count)


def test_the_patch_is_woven_into_the_engines_count(camp):
    """The one assertion the whole feature rests on.

    A method patch binds to an exact signature, so a game update that changes
    `HasStorageLevelJob.getItemCount` leaves the advice unwoven -- and then this mod does nothing at all
    while every other test in this file still passes, because the count is simply the engine's own. The
    check runs the real engine method and watches this mod be consulted.
    """
    answer = camp[0].query("ordercount")

    assert answer["applied"] is True, "the engine's craft-until count does not call Arcane Storage at all"
    assert answer["enabled"] is True, "the setting ships on"
    assert answer["failures"] == 0, "the advice ran and threw; whatever it threw is in the server log"


def test_a_unit_inside_the_settlement_counts(camp):
    """The report, answered: stock kept in a unit inside the settlement is stock the order can see."""
    harness, tile = camp
    cx, cy = tile
    harness.place("unit", cx + 4, cy)
    harness.fill(cx + 4, cy, ITEM, 30)
    harness.settle(1)

    answered = _have(harness, tile, 50)

    assert answered["inbounds"] is True, "the farmer should be standing inside its settlement"
    assert answered["units"] == 1, "the one unit inside the bounds should be gathered"
    assert answered["vanilla"] == 0, "the engine counts only assigned storage, which holds none of this"
    assert answered["count"] == 30, "the order should be told it already has thirty"


def test_two_units_add_up_and_one_outside_the_bounds_adds_nothing(camp):
    """Bounds are the settlement's own rectangle, and nothing else is anybody's stock.

    Asserted as a pair on purpose: the gathered count says which units were included, and the total says
    what they were worth, so a unit included by mistake cannot hide behind another one left out.
    """
    harness, tile = camp
    cx, cy = tile
    harness.place("unit", cx + 4, cy)
    harness.fill(cx + 4, cy, ITEM, 30)
    harness.place("unit", cx + 6, cy)
    harness.fill(cx + 6, cy, ITEM, 20)
    # A settlement's first bounds are five regions wide, so "outside" is 44 tiles out -- far enough to be
    # past the edge, and still inside the range a settler searches for storage.
    harness.place("unit", cx + 44, cy)
    harness.fill(cx + 44, cy, ITEM, 40)
    harness.settle(1)

    answered = _have(harness, tile, 100)

    assert answered["units"] == 2, "only the two units inside the bounds belong to this settlement"
    assert answered["count"] == 50, "30 + 20, and not the 40 outside the settlement"


def test_a_unit_the_engine_already_counts_is_not_counted_twice(camp):
    """A unit assigned to settlement storage is already in the engine's number.

    The double count this rules out would be spectacular rather than subtle: an order of sixty would stop
    at thirty, which a player reads as the order never finishing its work.
    """
    harness, tile = camp
    cx, cy = tile
    harness.place("unit", cx + 4, cy)
    harness.fill(cx + 4, cy, ITEM, 30)
    assert harness.call("orderassign", cx + 4, cy).ok, "a Storage Unit keeps the engine's default, so it can be assigned"
    # The engine builds its storage jobs on the settlement's own ten-second cadence, so the assigned unit is
    # only in the engine's number once that has run.
    harness.settle(220)

    answered = _have(harness, tile, 60)

    assert answered["units"] == 0, "the engine counts this unit itself, so adding it here would double it"
    assert answered["vanilla"] == 30, "the engine's own count is the unit's contents once its jobs exist"
    assert answered["count"] == answered["vanilla"], "the answer should be exactly the engine's own"


def test_a_broken_unit_stops_counting(camp):
    """A removed unit's inventory is not stock. Its entity can outlive the break by a tick."""
    harness, tile = camp
    cx, cy = tile
    harness.place("unit", cx + 4, cy)
    harness.fill(cx + 4, cy, ITEM, 30)
    harness.settle(1)
    assert _have(harness, tile, 50)["count"] == 30, "the unit counts before it is broken"

    harness.break_at(cx + 4, cy)
    harness.settle(2)

    answered = _have(harness, tile, 50)
    assert answered["units"] == 0, "a broken unit is not the settlement's stock"
    assert answered["count"] == 0


def test_an_order_stops_at_its_number_however_much_the_network_holds(camp):
    """The cap is what makes this safe: over-reporting would stop an order early, and it cannot.

    It is also the affirmative half of the report -- an order of ten with a thousand bars in a unit must be
    finished, not asked to keep crafting.
    """
    harness, tile = camp
    cx, cy = tile
    harness.place("unit", cx + 4, cy)
    harness.fill(cx + 4, cy, ITEM, 500)
    harness.settle(1)

    answered = _have(harness, tile, 10)

    assert answered["count"] == 10, "an order of ten is satisfied by far more than ten"
    assert answered["vanilla"] == 0


def test_turning_the_setting_off_returns_the_engines_own_answer(camp):
    """Off is vanilla, exactly: not "counts less", but the same number the engine produced alone."""
    harness, tile = camp
    cx, cy = tile
    harness.place("unit", cx + 4, cy)
    harness.fill(cx + 4, cy, ITEM, 30)
    harness.settle(1)
    assert _have(harness, tile, 50)["count"] == 30, "the unit counts while the setting is on"

    try:
        assert harness.call("orderconfig", "off").ok
        answered = _have(harness, tile, 50)
        assert answered["count"] == 0, "with the setting off nothing outside settlement storage is counted"
        assert answered["count"] == answered["vanilla"]
    finally:
        harness.call("orderconfig", "on")
