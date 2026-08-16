"""Connectivity: what counts as one network.

Ported from `tests/scenarios/topology.txt` and `multipath.txt`, which this replaces. The rules being
pinned down are the ones a player has to be able to predict by looking at their base: units and
conduits conduct, contact must be orthogonal, and a terminal conducts too -- so what meets at a terminal is one
network, which it was not until the disagreement described in test_two_conduit_runs_meeting_at_a_terminal.
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


def test_two_conduit_runs_meeting_at_a_terminal_are_one_network(storage):
    """A terminal conducts, so what meets at it is joined rather than merely co-visible.

    This was a real disagreement rather than a missing feature. A terminal's own walk starts at its tile and
    expands, so it always aggregated the groups touching it -- but no other walk could cross the terminal, so a
    base station on the conduit run to its left could not see a transceiver on the run to its right, and a unit
    could not empty itself into a unit on the far side. The terminal showed one inventory while every other part
    of the mod saw two networks.
    """
    terminal = net(storage, 0, 30)

    # A run to the left and a run to the right, touching nothing but the terminal.
    storage.place("conduit", -1, 30)
    storage.place("unit", -2, 30)
    storage.place("conduit", 1, 30)
    storage.place("unit", 2, 30)
    storage.settle(5)

    assert terminal.units() == 2, "the terminal should see both runs, as it always did"

    # The new part: each unit's own walk reaches the other, which is what everything except the terminal uses.
    storage.give("oaklog", 90)
    storage.do("open", 0, 30)
    storage.do("depositall")
    storage.do("close")
    storage.settle(5)

    filled = -2 if storage.query("empty", -2, 30)["usedslots"] > 0 else 2
    other = -filled

    storage.do("empty", filled, 30)
    storage.settle(5)

    assert storage.query("empty", filled, 30)["outcome"] == "emptied", (
        "a unit could not reach across the terminal to the other run"
    )
    assert storage.query("empty", other, 30)["usedslots"] == 1
    assert storage.query("item", 0, 30, "oaklog")["count"] == 90


def test_a_terminal_alone_still_joins_nothing_it_does_not_touch(storage):
    """Conducting is not teleporting: the terminal joins only what is orthogonally against it or its runs."""
    terminal = net(storage, 0, 34)
    storage.place("unit", 2, 34)
    storage.settle(5)

    assert terminal.units() == 0


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
