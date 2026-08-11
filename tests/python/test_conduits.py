"""Conduits: reach without capacity, and the sprite mask that shows it.

Ported from `tests/scenarios/conduits.txt`. Two things are being pinned: a conduit passes membership
along without storing anything, and its frame index is the bitmask of its connected neighbours —
bit0 north, bit1 east, bit2 south, bit3 west — which is what makes sixteen shapes come from one
sheet with no per-shape logic.
"""

from __future__ import annotations

from conftest import Terminal


def net(storage, dx: int, dy: int) -> Terminal:
    storage.place("terminal", dx, dy)
    return Terminal(storage, dx, dy)


def test_a_conduit_run_reaches_distant_units(storage):
    terminal = net(storage, 0, 0)
    for x in range(1, 4):
        storage.place("conduit", x, 0)

    for x in (4, 5):
        storage.place("unit", x, 0)
        storage.fill(x, 0, "ironbar", 30)

    assert terminal.units() == 2, "the conduits carry membership"
    assert terminal.count("ironbar") == 60
    assert terminal.capacity() == (2, 80), "and add no capacity of their own"


def test_a_gap_in_the_run_breaks_it(storage):
    terminal = net(storage, 0, 6)
    storage.place("conduit", 1, 6)
    storage.place("conduit", 3, 6)
    storage.place("unit", 4, 6)

    assert terminal.units() == 0


def test_a_conduit_conducts_in_every_direction(storage):
    """Not just along a line: the unit below the terminal and the unit past it both join through one
    conduit."""
    terminal = net(storage, 1, 10)
    storage.place("conduit", 1, 11)
    storage.place("unit", 0, 11)
    storage.place("unit", 2, 11)

    assert terminal.units() == 2


def test_breaking_the_only_conduit_severs_the_network(storage):
    terminal = net(storage, 0, 14)
    storage.place("conduit", 1, 14)
    storage.place("unit", 2, 14)
    assert terminal.units() == 1

    storage.break_at(1, 14)

    assert terminal.units() == 0


def test_the_frame_mask_matches_the_neighbours(storage):
    """bit0=N, bit1=E, bit2=S, bit3=W, and the frame index *is* the mask.

    The numbers below are the ones a player sees as corners and tees, so a regression here is a
    cosmetic bug that no functional test would catch.
    """
    net(storage, 0, 20)
    storage.place("conduit", 1, 20)
    storage.place("conduit", 2, 20)
    storage.place("conduit", 2, 21)
    storage.place("unit", 3, 21)

    assert storage.query("mask", 1, 20)["mask"] == 10, "east and west: a straight run"
    assert storage.query("mask", 2, 20)["mask"] == 12, "south and west: a corner"
    assert storage.query("mask", 2, 21)["mask"] == 3, "north and east: the other corner"


def test_an_isolated_conduit_has_no_connections_until_one_arrives(storage):
    storage.place("conduit", 6, 20)
    assert storage.query("mask", 6, 20)["mask"] == 0

    storage.place("conduit", 6, 21)

    assert storage.query("mask", 6, 20)["mask"] == 4, "south"
    assert storage.query("mask", 6, 21)["mask"] == 1, "north"
