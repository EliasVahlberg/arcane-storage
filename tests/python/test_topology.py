"""Connectivity: what counts as one network.

Ported from `tests/scenarios/topology.txt` and `multipath.txt`, which this replaces. The rules being
pinned down are the ones a player has to be able to predict by looking at their base: units and
conduits conduct, contact must be orthogonal, and a terminal never bridges two groups.
"""

from __future__ import annotations

import pytest

from conftest import Terminal


def net(storage, dx: int, dy: int) -> Terminal:
    """A terminal at an offset, so one test can hold several independent networks."""
    storage.place("terminal", dx, dy)
    return Terminal(storage, dx, dy)


def test_a_row_of_units_is_one_network(storage):
    terminal = net(storage, 0, 0)
    for x in range(1, 6):
        storage.place("unit", x, 0)

    assert terminal.units() == 5


def test_diagonal_contact_does_not_connect(storage):
    """Orthogonal only. A diagonal touch reads as connected on screen, which is exactly why this is
    worth a test rather than an assumption."""
    terminal = net(storage, 0, 4)
    storage.place("unit", 1, 5)
    assert terminal.units() == 0

    storage.place("unit", -1, 4)
    assert terminal.units() == 1, "and an orthogonal neighbour on the other side does connect"


def test_a_gap_does_not_connect(storage):
    terminal = net(storage, 0, 8)
    storage.place("unit", 2, 8)

    assert terminal.units() == 0


def test_breaking_a_unit_severs_what_was_behind_it(storage):
    """Membership is recomputed from the layout on every call, which is what makes this work with no
    cleanup: nothing persisted has to be told that a unit is gone."""
    terminal = net(storage, 0, 12)
    for x in range(1, 4):
        storage.place("unit", x, 12)

    assert terminal.units() == 3

    storage.break_at(2, 12)

    assert terminal.units() == 1


def test_two_terminals_share_one_network(storage):
    """Both ends see the same units, and neither is the owner. A terminal is a window, not a hub."""
    left = net(storage, 0, 16)
    for x in range(1, 4):
        storage.place("unit", x, 16)

    right = net(storage, 4, 16)

    assert left.units() == 3
    assert right.units() == 3


def test_a_block_of_units_is_not_double_counted(storage):
    """The case that gated Phase 2.

    In a 2x2 block every unit is reachable by more than one path, so a traversal that does not track
    what it has already seen counts some of them twice -- and the capacity and item totals inherit the
    error. Sixty is the honest answer; a double count would say more.
    """
    terminal = net(storage, 0, 0)
    for x, y in [(1, 0), (2, 0), (1, 1), (2, 1)]:
        storage.place("unit", x, y)
        storage.fill(x, y, "ironbar", 15)

    assert terminal.units() == 4
    assert terminal.count("ironbar") == 60
    assert storage.query("total", "ironbar")["count"] == 60, "and the level-wide total agrees"


@pytest.mark.parametrize("dy", [0, 4, 8])
def test_networks_at_different_offsets_stay_independent(storage, dy):
    """Several networks in one world must not see each other, which is what lets the tests above
    share a level."""
    terminal = net(storage, 0, dy)
    storage.place("unit", 1, dy)

    assert terminal.units() == 1
