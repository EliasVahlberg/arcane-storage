"""The tier ladders — capacity and sockets growing across Necesse's four station eras.

Both unit types climb the same four rungs (base, Demonic, Tungsten, Fallen) because Necesse's own crafting
stations do, and both double per rung: 40/80/160/320 stacks and 1/2/4/8 sockets. These tests assert the
numbers against the ladder rather than against literals, so a change to the curve moves the tests with it
instead of leaving them contradicting the code.

Two things here are worth more than the arithmetic. A network must be able to mix tiers, because a player
upgrades one unit at a time and a network that only worked when every unit matched would be unplayable. And
the higher tiers must be ordinary members of the network in every other respect — conducting, being
discovered, contributing to one aggregated total — since the tier is meant to change one number and nothing
else about what a unit is.
"""

from __future__ import annotations

import pytest

#: The ladder, mirroring UnitTier. Kept as one table so a test can never assert a capacity against the
#: wrong tier's alias.
TIERS = [
    # alias prefix, storage stacks, station sockets
    ("", 40, 1),
    ("demonic", 80, 2),
    ("tungsten", 160, 4),
    ("fallen", 320, 8),
]

UPPER_TIERS = [t for t in TIERS if t[0]]


def unit_alias(prefix: str) -> str:
    return f"{prefix}unit" if prefix else "unit"


def station_alias(prefix: str) -> str:
    return f"{prefix}stationunit" if prefix else "stationunit"


@pytest.mark.parametrize("prefix,stacks,_sockets", TIERS)
def test_a_storage_unit_holds_its_tier_s_capacity(storage, prefix, stacks, _sockets):
    """One unit of each tier, on its own, reports exactly that tier's capacity."""
    storage.place("terminal", 0, 0)
    storage.place(unit_alias(prefix), 1, 0)
    storage.settle(5)

    assert storage.query("capacity", 0, 0) == {"used": 0, "total": stacks}


@pytest.mark.parametrize("prefix,_stacks,sockets", TIERS)
def test_a_station_unit_offers_its_tier_s_sockets(storage, prefix, _stacks, sockets):
    """Sockets double per rung, and the count is the unit's, not the terminal's."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place(station_alias(prefix), 0, 1)
    storage.settle(5)

    stations = storage.query("stations", 0, 0)

    assert stations["units"] == 1
    assert stations["sockets"] == sockets


def test_capacity_is_the_sum_across_mixed_tiers(storage):
    """A player upgrades one unit at a time, so mixing tiers is the normal case, not an edge one."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("demonicunit", 2, 0)
    storage.place("tungstenunit", 3, 0)
    storage.settle(5)

    assert storage.query("capacity", 0, 0) == {"used": 0, "total": 40 + 80 + 160}


def test_sockets_are_the_sum_across_mixed_tiers(storage):
    """Same argument for the other ladder."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("stationunit", 0, 1)
    storage.place("demonicstationunit", 0, 2)
    storage.settle(5)

    stations = storage.query("stations", 0, 0)

    assert stations["units"] == 2
    assert stations["sockets"] == 1 + 2


@pytest.mark.parametrize("prefix,stacks,_sockets", UPPER_TIERS)
def test_an_upper_tier_unit_conducts_the_network_like_any_other(storage, prefix, stacks, _sockets):
    """The tier changes a capacity, not what a unit is. A higher unit must still carry the network onward."""
    storage.place("terminal", 0, 0)
    storage.place(unit_alias(prefix), 1, 0)
    # Reachable only through the tiered unit, so it joins if and only if that unit conducts.
    storage.place("unit", 2, 0)
    storage.settle(5)

    assert storage.query("capacity", 0, 0) == {"used": 0, "total": stacks + 40}


@pytest.mark.parametrize("prefix,stacks,_sockets", UPPER_TIERS)
def test_an_upper_tier_unit_stores_and_withdraws(storage, prefix, stacks, _sockets):
    """Capacity that cannot be used is not capacity. Exercises the slots, not just the number."""
    storage.place("terminal", 0, 0)
    storage.place(unit_alias(prefix), 1, 0)
    storage.settle(5)

    storage.fill(1, 0, "oaklog", 30)
    storage.open(0, 0)

    assert storage.query("item", 0, 0, "oaklog")["count"] == 30

    reply = storage.call("withdraw", "oaklog", 10)

    assert reply.ok is True
    assert storage.query("item", 0, 0, "oaklog")["count"] == 20


def test_breaking_a_tiered_unit_takes_its_whole_capacity_with_it(storage):
    """Membership is derived from the layout, so removal needs no bookkeeping — including at higher tiers."""
    storage.place("terminal", 0, 0)
    storage.place("unit", 1, 0)
    storage.place("demonicunit", 2, 0)
    storage.settle(5)

    assert storage.query("capacity", 0, 0)["total"] == 40 + 80

    storage.do("break", 2, 0)
    storage.settle(5)

    assert storage.query("capacity", 0, 0)["total"] == 40
